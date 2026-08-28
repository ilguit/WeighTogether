package com.palixander.scalesync.data

import com.palixander.scalesync.core.Sex
import com.palixander.scalesync.domain.Account
import com.palixander.scalesync.domain.AccountId
import com.palixander.scalesync.domain.AccountProfile
import com.palixander.scalesync.domain.AccountUpdate
import com.palixander.scalesync.domain.ProfileHistoryUpdateMode
import com.palixander.scalesync.worker.ExternalSyncOperationSerializer
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SerializedAccountUpdaterTest {
    @Test
    fun workerHeldSerializerBlocksAccountWriteThenSweepsExactlyOnceAfterRelease() =
        runBlocking {
            val operations = ExternalSyncOperationSerializer()
            val workerEntered = CompletableDeferred<Unit>()
            val releaseWorker = CompletableDeferred<Unit>()
            val events = ConcurrentLinkedQueue<String>()
            val updater = SerializedAccountUpdater(
                updateDelegate = { _, mode ->
                    assertEquals(ProfileHistoryUpdateMode.RECALCULATE, mode)
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
                updater.update(nameOnlyUpdate, ProfileHistoryUpdateMode.RECALCULATE)
            }
            val wasBlocked = !accountUpdate.isCompleted
            val eventsWhileWorkerHeldLock = events.toList()

            releaseWorker.complete(Unit)
            worker.await()
            val result = accountUpdate.await()

            assertEquals(updatedAccount, result)
            assertTrue(wasBlocked)
            assertTrue(eventsWhileWorkerHeldLock.isEmpty())
            assertEquals(listOf("delegate", "sweep"), events.toList())
        }

    @Test
    fun accountWriteHeldSerializerBlocksWorkerUntilWriteCompletesAndReleasesBeforeSweep() =
        runBlocking {
            val operations = ExternalSyncOperationSerializer()
            val delegateEntered = CompletableDeferred<Unit>()
            val releaseDelegate = CompletableDeferred<Unit>()
            val sweepEntered = CompletableDeferred<Unit>()
            val releaseSweep = CompletableDeferred<Unit>()
            val workerEntered = CompletableDeferred<Unit>()
            val events = ConcurrentLinkedQueue<String>()
            val updater = SerializedAccountUpdater(
                updateDelegate = { _, mode ->
                    assertEquals(ProfileHistoryUpdateMode.KEEP_EXISTING, mode)
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
                updater.update(nameOnlyUpdate, ProfileHistoryUpdateMode.KEEP_EXISTING)
            }
            delegateEntered.await()

            val worker = async(start = CoroutineStart.UNDISPATCHED) {
                operations.runExclusive {
                    events += "worker-gateway"
                    workerEntered.complete(Unit)
                }
            }
            val workerWasBlocked = !workerEntered.isCompleted
            val accountWasInProgress = !accountUpdate.isCompleted

            releaseDelegate.complete(Unit)
            sweepEntered.await()
            workerEntered.await()
            val accountWasBlockedInSweep = !accountUpdate.isCompleted
            val eventsBeforeSweepRelease = events.toList()

            releaseSweep.complete(Unit)
            val result = accountUpdate.await()
            worker.await()

            assertEquals(updatedAccount, result)
            assertTrue(workerWasBlocked)
            assertTrue(accountWasInProgress)
            assertTrue(accountWasBlockedInSweep)
            assertEquals(
                listOf("delegate-start", "delegate-finish"),
                eventsBeforeSweepRelease.take(2),
            )
            assertEquals(
                setOf("sweep", "worker-gateway"),
                eventsBeforeSweepRelease.drop(2).toSet(),
            )
        }

    @Test
    fun failedAccountWriteReleasesSerializerAndSkipsRoutingSweep() = runBlocking {
        val operations = ExternalSyncOperationSerializer()
        val expected = IllegalStateException("write failed")
        var sweepCalls = 0
        val updater = SerializedAccountUpdater(
            updateDelegate = { _, _ -> throw expected },
            sweepPendingRouting = { sweepCalls += 1 },
            operations = operations,
        )

        val actual = runCatching {
            updater.update(nameOnlyUpdate, ProfileHistoryUpdateMode.RECALCULATE)
        }.exceptionOrNull()
        var subsequentOperationEntered = false
        operations.runExclusive { subsequentOperationEntered = true }

        assertSame(expected, actual)
        assertEquals(0, sweepCalls)
        assertTrue(subsequentOperationEntered)
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
