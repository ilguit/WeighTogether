package com.example.huaweimisync.data

import com.example.huaweimisync.core.Sex
import com.example.huaweimisync.domain.Account
import com.example.huaweimisync.domain.AccountId
import com.example.huaweimisync.domain.AccountProfile
import com.example.huaweimisync.domain.AccountUpdate
import com.example.huaweimisync.worker.ExternalSyncOperationSerializer
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SerializedAccountUpdaterTest {
    @Test
    fun workerHeldSerializerBlocksAccountWriteThenSweepsExactlyOnceAfterRelease() = runBlocking {
        val operations = ExternalSyncOperationSerializer()
        val workerEntered = CompletableDeferred<Unit>()
        val releaseWorker = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val updater = SerializedAccountUpdater(
            update = {
                events += "delegate"
                updatedAccount
            },
            sweepPendingRouting = { events += "sweep" },
            operations = operations,
        )
        val worker = async(start = CoroutineStart.UNDISPATCHED) {
            operations.runExclusive {
                workerEntered.complete(Unit)
                releaseWorker.await()
            }
        }
        workerEntered.await()

        val accountUpdate = async(start = CoroutineStart.UNDISPATCHED) {
            updater.update(nameOnlyUpdate)
        }

        assertFalse(accountUpdate.isCompleted)
        assertTrue(events.isEmpty())

        releaseWorker.complete(Unit)
        worker.await()

        assertEquals(updatedAccount, accountUpdate.await())
        assertEquals(listOf("delegate", "sweep"), events)
    }

    @Test
    fun accountWriteHeldSerializerBlocksWorkerUntilWriteCompletesAndReleasesBeforeSweep() = runBlocking {
        val operations = ExternalSyncOperationSerializer()
        val delegateEntered = CompletableDeferred<Unit>()
        val releaseDelegate = CompletableDeferred<Unit>()
        val sweepEntered = CompletableDeferred<Unit>()
        val releaseSweep = CompletableDeferred<Unit>()
        val workerEntered = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val updater = SerializedAccountUpdater(
            update = {
                events += "delegate-start"
                delegateEntered.complete(Unit)
                releaseDelegate.await()
                events += "delegate-finish"
                updatedAccount
            },
            sweepPendingRouting = {
                events += "sweep"
                sweepEntered.complete(Unit)
                releaseSweep.await()
            },
            operations = operations,
        )
        val accountUpdate = async(start = CoroutineStart.UNDISPATCHED) {
            updater.update(nameOnlyUpdate)
        }
        delegateEntered.await()

        val worker = async(start = CoroutineStart.UNDISPATCHED) {
            operations.runExclusive {
                events += "worker-gateway"
                workerEntered.complete(Unit)
            }
        }

        assertFalse(workerEntered.isCompleted)
        assertFalse(accountUpdate.isCompleted)

        releaseDelegate.complete(Unit)
        sweepEntered.await()
        workerEntered.await()

        assertFalse(accountUpdate.isCompleted)
        assertEquals(
            listOf("delegate-start", "delegate-finish", "sweep", "worker-gateway"),
            events,
        )

        releaseSweep.complete(Unit)
        assertEquals(updatedAccount, accountUpdate.await())
        worker.await()
        Unit
    }

    private companion object {
        val accountId = AccountId("account")
        val profile = AccountProfile.Complete(
            heightCm = 175.0,
            birthDate = LocalDate.of(1990, 1, 1),
            sex = Sex.MALE,
        )
        val nameOnlyUpdate = AccountUpdate(
            id = accountId,
            displayName = "Renamed",
            profile = profile,
        )
        val updatedAccount = Account(
            id = accountId,
            displayName = nameOnlyUpdate.displayName,
            profile = profile,
            createdAt = Instant.parse("2026-01-01T00:00:00Z"),
            updatedAt = Instant.parse("2026-01-02T00:00:00Z"),
        )
    }
}
