package com.example.huaweimisync.data

import android.content.Context
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.example.huaweimisync.core.BodyCompositionCalculator
import com.example.huaweimisync.core.RawScaleMeasurement
import com.example.huaweimisync.core.Sex
import com.example.huaweimisync.domain.AccountId
import com.example.huaweimisync.domain.AccountProfile
import com.example.huaweimisync.domain.CreateAccountAndAssignResult
import com.example.huaweimisync.domain.ExternalSyncPolicy
import com.example.huaweimisync.domain.FinalizePendingResult
import com.example.huaweimisync.domain.NewAccount
import com.example.huaweimisync.domain.PendingMeasurementId
import com.example.huaweimisync.domain.PrimaryHistorySyncMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MultiAccountPersistenceTest {
    private lateinit var database: AppDatabase
    private var currentTime = Instant.parse("2026-08-15T12:00:00Z")
    private var nextId = 0

    @Before
    fun createDatabase() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun firstAccountIsPrimaryAndReplacementPromotesOnlyEligibleHistory() = runBlocking {
        val accounts = accountRepository()
        val persistence = persistence()
        val first = accounts.createAccount(NewAccount("Alice", completeProfile()))
        val second = accounts.createAccount(NewAccount("Bob", completeProfile()))

        assertEquals(first.id, accounts.observeSettings().first().primaryAccountId)

        val pending = persistence.enqueue(raw("2026-08-15T10:00:00Z", 82.0))
            as PendingPersistenceResult.Inserted
        val finalized = persistence.finalizePending(pending.pending.id, second.id)
            as FinalizePendingResult.Finalized
        assertEquals(ExternalSyncPolicy.ACCOUNT_LOCAL, finalized.measurement.externalSyncPolicy)
        var stored = database.multiAccountMeasurementDao().get(
            finalized.measurement.composition.measurementId,
        )!!
        assertEquals(SyncStatus.DISABLED.name, stored.huaweiStatus)
        assertEquals(SyncStatus.LOCAL_ONLY.name, stored.healthConnectStatus)

        val userLocalPending = persistence.enqueue(raw("2026-08-15T10:01:00Z", 83.0))
            as PendingPersistenceResult.Inserted
        val userLocalFinalized = persistence.finalizePending(userLocalPending.pending.id, second.id)
            as FinalizePendingResult.Finalized
        val userLocal = database.multiAccountMeasurementDao().get(
            userLocalFinalized.measurement.composition.measurementId,
        )!!.copy(
            externalSyncPolicy = ExternalSyncPolicy.USER_LOCAL.name,
            healthConnectStatus = SyncStatus.LOCAL_ONLY.name,
        )
        assertEquals(1, database.measurementDao().update(userLocal))

        accounts.setPrimaryAccount(second.id, PrimaryHistorySyncMode.FUTURE_ONLY)

        stored = database.multiAccountMeasurementDao().get(stored.id)!!
        assertEquals(ExternalSyncPolicy.ACCOUNT_LOCAL.name, stored.externalSyncPolicy)
        val userLocalAfterFutureOnly = database.multiAccountMeasurementDao().get(userLocal.id)!!
        assertEquals(ExternalSyncPolicy.USER_LOCAL.name, userLocalAfterFutureOnly.externalSyncPolicy)
        assertEquals(SyncStatus.LOCAL_ONLY.name, userLocalAfterFutureOnly.healthConnectStatus)
        accounts.setPrimaryAccount(first.id, PrimaryHistorySyncMode.FUTURE_ONLY)

        accounts.setPrimaryAccount(second.id, PrimaryHistorySyncMode.INCLUDE_ELIGIBLE_HISTORY)

        stored = database.multiAccountMeasurementDao().get(stored.id)!!
        assertEquals(ExternalSyncPolicy.AUTO.name, stored.externalSyncPolicy)
        assertEquals(SyncStatus.DISABLED.name, stored.huaweiStatus)
        assertEquals(SyncStatus.PENDING.name, stored.healthConnectStatus)
        val userLocalAfterPromotion = database.multiAccountMeasurementDao().get(userLocal.id)!!
        assertEquals(ExternalSyncPolicy.USER_LOCAL.name, userLocalAfterPromotion.externalSyncPolicy)
        assertEquals(SyncStatus.LOCAL_ONLY.name, userLocalAfterPromotion.healthConnectStatus)

        accounts.deletePrimaryWithReplacement(
            primaryAccountId = second.id,
            replacementAccountId = first.id,
            historySyncMode = PrimaryHistorySyncMode.FUTURE_ONLY,
        )

        assertEquals(first.id, accounts.observeSettings().first().primaryAccountId)
        assertNull(database.multiAccountMeasurementDao().get(stored.id))
        assertNull(database.multiAccountMeasurementDao().get(userLocal.id))
    }

    @Test
    fun primarySwitchLocalizesOnlyUnfinishedHistoryAndHonorsFutureOnlyOnReturn() = runBlocking {
        val accounts = accountRepository()
        val persistence = persistence()
        val first = accounts.createAccount(NewAccount("Alice", completeProfile()))
        val second = accounts.createAccount(NewAccount("Bob", completeProfile()))

        val unfinishedPending = persistence.enqueue(raw("2026-08-15T10:00:00Z", 70.0))
            as PendingPersistenceResult.Inserted
        val unfinished = persistence.finalizePending(unfinishedPending.pending.id, first.id)
            as FinalizePendingResult.Finalized
        val terminalPending = persistence.enqueue(raw("2026-08-15T10:01:00Z", 71.0))
            as PendingPersistenceResult.Inserted
        val terminal = persistence.finalizePending(terminalPending.pending.id, first.id)
            as FinalizePendingResult.Finalized
        val terminalEntity = database.multiAccountMeasurementDao().get(
            terminal.measurement.composition.measurementId,
        )!!.copy(healthConnectStatus = SyncStatus.SYNCED.name)
        assertEquals(1, database.measurementDao().update(terminalEntity))

        accounts.setPrimaryAccount(second.id, PrimaryHistorySyncMode.FUTURE_ONLY)

        var unfinishedEntity = database.multiAccountMeasurementDao().get(
            unfinished.measurement.composition.measurementId,
        )!!
        assertEquals(ExternalSyncPolicy.ACCOUNT_LOCAL.name, unfinishedEntity.externalSyncPolicy)
        assertEquals(SyncStatus.DISABLED.name, unfinishedEntity.huaweiStatus)
        assertEquals(SyncStatus.LOCAL_ONLY.name, unfinishedEntity.healthConnectStatus)
        assertEquals(
            ExternalSyncPolicy.AUTO.name,
            database.multiAccountMeasurementDao().get(terminalEntity.id)!!.externalSyncPolicy,
        )

        accounts.setPrimaryAccount(first.id, PrimaryHistorySyncMode.FUTURE_ONLY)
        unfinishedEntity = database.multiAccountMeasurementDao().get(unfinishedEntity.id)!!
        assertEquals(ExternalSyncPolicy.ACCOUNT_LOCAL.name, unfinishedEntity.externalSyncPolicy)
        assertEquals(SyncStatus.LOCAL_ONLY.name, unfinishedEntity.healthConnectStatus)

        accounts.setPrimaryAccount(second.id, PrimaryHistorySyncMode.FUTURE_ONLY)
        accounts.setPrimaryAccount(first.id, PrimaryHistorySyncMode.INCLUDE_ELIGIBLE_HISTORY)
        unfinishedEntity = database.multiAccountMeasurementDao().get(unfinishedEntity.id)!!
        assertEquals(ExternalSyncPolicy.AUTO.name, unfinishedEntity.externalSyncPolicy)
        assertEquals(SyncStatus.PENDING.name, unfinishedEntity.healthConnectStatus)
    }

    @Test
    fun latestWeightsUseOnlyStrictlyPriorMeasurements() = runBlocking {
        val account = accountRepository().createAccount(NewAccount("Alice", completeProfile()))
        val persistence = persistence()
        listOf(
            "2026-08-15T10:00:00Z" to 70.0,
            "2026-08-15T10:00:01Z" to 71.0,
            "2026-08-15T10:00:03.123Z" to 72.0,
        ).forEach { (timestamp, weight) ->
            val pending = persistence.enqueue(raw(timestamp, weight))
                as PendingPersistenceResult.Inserted
            assertTrue(
                persistence.finalizePending(pending.pending.id, account.id) is
                    FinalizePendingResult.Finalized,
            )
        }

        assertEquals(
            listOf(70.0),
            persistence.latestWeightsBefore(account.id, Instant.parse("2026-08-15T10:00:01Z")),
        )
        assertEquals(
            listOf(71.0, 70.0),
            persistence.latestWeightsBefore(account.id, Instant.parse("2026-08-15T10:00:02Z")),
        )
        assertEquals(
            listOf(72.0, 71.0, 70.0),
            persistence.latestWeightsBefore(
                account.id,
                Instant.parse("2026-08-15T10:00:03.123456789Z"),
            ),
        )
        assertEquals(
            listOf(71.0, 70.0),
            persistence.latestWeightsBefore(
                account.id,
                Instant.parse("2026-08-15T10:00:03.123Z"),
            ),
        )
        assertEquals(
            listOf(72.0),
            persistence.observeRange(
                account.id,
                Instant.parse("2026-08-15T10:00:01.000000001Z"),
                Instant.parse("2026-08-15T10:00:03.123456789Z"),
            ).first().map { it.composition.weightKg },
        )
    }

    @Test
    fun weightDeltaUpdateHonorsDomainBoundsAndPreservesValueAfterRejection() = runBlocking {
        val accounts = accountRepository()

        accounts.updateWeightDeltaKg(0.1)
        assertEquals(0.1, accounts.observeSettings().first().weightDeltaKg, 0.0)
        accounts.updateWeightDeltaKg(50.0)
        assertEquals(50.0, accounts.observeSettings().first().weightDeltaKg, 0.0)

        listOf(0.09, 50.01, Double.NaN).forEach { invalid ->
            assertTrue(runCatching { accounts.updateWeightDeltaKg(invalid) }.isFailure)
            assertEquals(50.0, accounts.observeSettings().first().weightDeltaKg, 0.0)
        }
    }

    @Test
    fun pendingIsFifoDeduplicatedAndRejectedHashExpiresAfterThirtyDays() = runBlocking {
        val persistence = persistence()
        val firstRaw = raw("2026-08-15T10:00:00Z", 70.0)
        val first = persistence.enqueue(firstRaw) as PendingPersistenceResult.Inserted
        currentTime = currentTime.plusSeconds(1)
        val second = persistence.enqueue(raw("2026-08-15T10:01:00Z", 71.0))
            as PendingPersistenceResult.Inserted

        val duplicate = persistence.enqueue(firstRaw.copy(rawPayload = byteArrayOf(9, 9)))
        assertTrue(duplicate is PendingPersistenceResult.AlreadyPending)
        assertEquals(
            listOf(first.pending.id, second.pending.id),
            persistence.observePending().first().map { it.id },
        )

        assertTrue(persistence.discardPending(first.pending.id))
        assertFalse(persistence.discardPending(first.pending.id))
        assertEquals(PendingPersistenceResult.Tombstoned, persistence.enqueue(firstRaw))
        assertEquals(1, database.pendingMeasurementDao().tombstoneCount())

        currentTime = currentTime.plus(RoomMeasurementPersistence.TOMBSTONE_TTL).plusSeconds(1)
        assertTrue(persistence.enqueue(firstRaw) is PendingPersistenceResult.Inserted)
        assertEquals(0, database.pendingMeasurementDao().tombstoneCount())
    }

    @Test
    fun concurrentDoubleFinalizeCreatesOneMeasurementAndIsDurablyIdempotent() = runBlocking {
        val account = accountRepository().createAccount(NewAccount("Alice", completeProfile()))
        val persistence = persistence()
        val pending = persistence.enqueue(raw("2026-08-15T10:00:00Z", 70.0))
            as PendingPersistenceResult.Inserted

        val results = listOf(1, 2).map {
            async(Dispatchers.Default) { persistence.finalizePending(pending.pending.id, account.id) }
        }.awaitAll()

        assertEquals(1, results.count { it is FinalizePendingResult.Finalized })
        assertEquals(1, results.count { it is FinalizePendingResult.AlreadyFinalized })
        assertEquals(1, persistence.observeAll(account.id).first().size)
        assertNull(persistence.getPending(pending.pending.id))
        assertTrue(
            persistence.enqueue(raw("2026-08-15T10:00:00Z", 70.0)) is
                PendingPersistenceResult.AlreadyFinalized,
        )
    }

    @Test
    fun createAccountAndAssignIsAtomicAndNameConflictLeavesPendingUntouched() = runBlocking {
        val persistence = persistence()
        val pending = persistence.enqueue(raw("2026-08-15T10:00:00Z", 70.0))
            as PendingPersistenceResult.Inserted
        val created = persistence.createAccountAndAssignPending(
            pending.pending.id,
            NewAccount("Alice", completeProfile()),
        ) as CreateAccountAndAssignResult.Created

        assertEquals(created.account.id, accountRepository().observeSettings().first().primaryAccountId)
        assertEquals(created.account.id, created.measurement.accountId)
        assertEquals(ExternalSyncPolicy.AUTO, created.measurement.externalSyncPolicy)
        assertNull(persistence.getPending(pending.pending.id))

        val conflictPending = persistence.enqueue(raw("2026-08-15T10:01:00Z", 71.0))
            as PendingPersistenceResult.Inserted
        val conflict = persistence.createAccountAndAssignPending(
            conflictPending.pending.id,
            NewAccount("ALICE", completeProfile()),
        )
        assertEquals(CreateAccountAndAssignResult.NameConflict("alice"), conflict)
        assertNotNull(persistence.getPending(conflictPending.pending.id))
        assertEquals(1, database.accountDao().count())
    }

    @Test
    fun incompleteRecoveryAccountCannotFinalize() = runBlocking {
        val accountId = UUID.randomUUID().toString()
        database.accountDao().insert(
            AccountEntity(
                id = accountId,
                displayName = "Recovery",
                normalizedName = "recovery",
                heightCm = 175.0,
                birthDateEpochDay = null,
                sex = Sex.MALE.name,
                isProfileComplete = false,
                createdAtEpochMillis = currentTime.toEpochMilli(),
                updatedAtEpochMillis = currentTime.toEpochMilli(),
            ),
        )
        database.appStateDao().insertDefault(AppStateEntity(primaryAccountId = accountId))
        val persistence = persistence()
        val pending = persistence.enqueue(raw("2026-08-15T10:00:00Z", 70.0))
            as PendingPersistenceResult.Inserted

        assertEquals(
            FinalizePendingResult.ProfileIncomplete,
            persistence.finalizePending(pending.pending.id, AccountId(accountId)),
        )
        assertNotNull(persistence.getPending(pending.pending.id))
        assertTrue(persistence.observeAll(AccountId(accountId)).first().isEmpty())
    }

    @Test
    fun failedCalculationRollsBackAccountCreationAndKeepsPending() = runBlocking {
        val persistence = persistence()
        val invalidRaw = raw("2026-08-15T10:00:00.123456789Z", 70.0).copy(isStable = false)
        val pending = persistence.enqueue(invalidRaw) as PendingPersistenceResult.Inserted
        assertEquals(invalidRaw.measuredAt, pending.pending.measuredAt)

        val failure = runCatching {
            persistence.createAccountAndAssignPending(
                pending.pending.id,
                NewAccount("Alice", completeProfile()),
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertEquals(0, database.accountDao().count())
        assertNotNull(persistence.getPending(pending.pending.id))
        assertNull(database.appStateDao().get())
    }

    private fun accountRepository() = RoomAccountRepository(
        database = database,
        now = { currentTime },
        newId = { durableId() },
    )

    private fun persistence() = RoomMeasurementPersistence(
        database = database,
        calculator = BodyCompositionCalculator(ZoneId.of("UTC")),
        huaweiSyncEnabled = false,
        now = { currentTime },
        newId = { durableId() },
    )

    private fun durableId(): String {
        nextId += 1
        return UUID.nameUUIDFromBytes("test-$nextId".toByteArray()).toString()
    }

    private fun completeProfile() = AccountProfile.Complete(
        heightCm = 175.0,
        birthDate = LocalDate.of(1990, 1, 1),
        sex = Sex.MALE,
    )

    private fun raw(timestamp: String, weightKg: Double) = RawScaleMeasurement(
        deviceAddress = "AA:BB:CC:DD:EE:FF",
        measuredAt = Instant.parse(timestamp),
        weightKg = weightKg,
        impedanceOhm = 500,
        isStable = true,
        hasImpedance = true,
        rawPayload = byteArrayOf(1, 2, 3),
    )
}
