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
import com.example.huaweimisync.domain.DiscardPendingResult
import com.example.huaweimisync.domain.DiscardPendingAndUpdateIgnorePolicyResult
import com.example.huaweimisync.domain.ExternalSyncPolicy
import com.example.huaweimisync.domain.FinalizePendingResult
import com.example.huaweimisync.domain.NewAccount
import com.example.huaweimisync.domain.PendingEnqueueResult
import com.example.huaweimisync.domain.PendingMeasurement
import com.example.huaweimisync.domain.PendingMeasurementId
import com.example.huaweimisync.domain.PrimaryHistorySyncMode
import com.example.huaweimisync.domain.RestorePendingResult
import com.example.huaweimisync.worker.MeasurementSyncScheduler
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
            finalized.measurement.measurementId,
        )!!
        assertEquals(SyncStatus.DISABLED.name, stored.huaweiStatus)
        assertEquals(SyncStatus.LOCAL_ONLY.name, stored.healthConnectStatus)

        val userLocalPending = persistence.enqueue(raw("2026-08-15T10:01:00Z", 83.0))
            as PendingPersistenceResult.Inserted
        val userLocalFinalized = persistence.finalizePending(userLocalPending.pending.id, second.id)
            as FinalizePendingResult.Finalized
        val userLocal = database.multiAccountMeasurementDao().get(
            userLocalFinalized.measurement.measurementId,
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
            terminal.measurement.measurementId,
        )!!.copy(healthConnectStatus = SyncStatus.SYNCED.name)
        assertEquals(1, database.measurementDao().update(terminalEntity))

        accounts.setPrimaryAccount(second.id, PrimaryHistorySyncMode.FUTURE_ONLY)

        var unfinishedEntity = database.multiAccountMeasurementDao().get(
            unfinished.measurement.measurementId,
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
            listOf(72.0, 71.0, 70.0),
            persistence.latestHistoryBefore(
                account.id,
                Instant.parse("2026-08-15T10:00:03.123456789Z"),
            ).map { it.weightKg },
        )
        assertEquals(
            listOf(71.0, 70.0),
            persistence.latestHistoryBefore(
                account.id,
                Instant.parse("2026-08-15T10:00:03.123Z"),
            ).map { it.weightKg },
        )
        assertEquals(
            listOf(72.0),
            persistence.observeRange(
                account.id,
                Instant.parse("2026-08-15T10:00:01.000000001Z"),
                Instant.parse("2026-08-15T10:00:03.123456789Z"),
            ).first().map { it.weightKg },
        )
    }

    @Test
    fun nanosecondTimestampSurvivesPendingAndFinalization() = runBlocking {
        val exact = Instant.parse("2026-08-15T10:00:00.123456789Z")
        val account = accountRepository().createAccount(NewAccount("Alice", completeProfile()))
        val persistence = persistence()

        val pending = persistence.enqueue(raw(exact.toString(), 70.0))
            as PendingPersistenceResult.Inserted
        assertEquals(exact, pending.pending.measuredAt)
        assertEquals(exact, persistence.getPending(pending.pending.id)?.measuredAt)

        val finalized = persistence.finalizePending(pending.pending.id, account.id)
            as FinalizePendingResult.Finalized
        val stored = requireNotNull(
            database.multiAccountMeasurementDao().get(finalized.measurement.measurementId),
        )
        assertEquals(exact.toEpochMilli(), stored.measuredAtEpochMillis)
        assertEquals(exact.epochSecond, stored.measuredAtEpochSecond)
        assertEquals(exact.nano, stored.measuredAtNano)
        assertEquals(exact, stored.measuredAt)
        assertEquals(exact, finalized.measurement.composition?.measuredAt)
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
    fun ignoreUnknownSettingDefaultsFalsePersistsAndDoesNotSweepExistingPending() = runBlocking {
        val accounts = accountRepository()
        val persistence = persistence()
        val existing = persistence.enqueue(raw("2026-08-15T10:00:00Z", 70.0))
            as PendingPersistenceResult.Inserted

        assertFalse(accounts.observeSettings().first().ignoreUnknownMeasurements)
        accounts.updateIgnoreUnknownMeasurements(true)

        assertTrue(accounts.observeSettings().first().ignoreUnknownMeasurements)
        assertEquals(existing.pending, persistence.getPending(existing.pending.id))
        accounts.updateIgnoreUnknownMeasurements(false)
        assertFalse(accounts.observeSettings().first().ignoreUnknownMeasurements)
    }

    @Test
    fun automaticIgnoreRechecksPersistedPolicyAndCreatesNormalTombstone() = runBlocking {
        val accounts = accountRepository()
        val persistence = persistence()
        val packet = raw("2026-08-15T10:00:00Z", 70.0)
        val pending = persistence.enqueue(packet) as PendingPersistenceResult.Inserted

        assertEquals(
            AutoIgnorePendingPersistenceResult.PolicyDisabled,
            persistence.discardUnknownPendingIfEnabled(pending.pending.id),
        )
        assertNotNull(persistence.getPending(pending.pending.id))
        accounts.updateIgnoreUnknownMeasurements(true)

        assertEquals(
            AutoIgnorePendingPersistenceResult.Discarded,
            persistence.discardUnknownPendingIfEnabled(pending.pending.id),
        )
        assertNull(persistence.getPending(pending.pending.id))
        assertEquals(1, database.pendingMeasurementDao().tombstoneCount())
        assertEquals(PendingPersistenceResult.Tombstoned, persistence.enqueue(packet))
    }

    @Test
    fun atomicPolicyDiscardReturnsUndoOnlyWhenDisablingAndChangesSettingOnlyOnSuccess() = runBlocking {
        val accounts = accountRepository()
        val persistence = persistence()
        val pending = persistence.enqueue(raw("2026-08-15T10:00:00Z", 70.0))
            as PendingPersistenceResult.Inserted

        assertEquals(
            DiscardPendingAndUpdateIgnorePolicyResult.Discarded(undoToken = null),
            persistence.discardPendingAndUpdateIgnorePolicy(pending.pending.id, true),
        )
        assertTrue(accounts.observeSettings().first().ignoreUnknownMeasurements)
        assertNull(persistence.getPending(pending.pending.id))
        assertEquals(1, database.pendingMeasurementDao().tombstoneCount())

        val undoablePending = persistence.enqueue(raw("2026-08-15T10:01:00Z", 71.0))
            as PendingPersistenceResult.Inserted
        val undoable = persistence.discardPendingAndUpdateIgnorePolicy(
            undoablePending.pending.id,
            false,
        ) as DiscardPendingAndUpdateIgnorePolicyResult.Discarded

        assertEquals(undoablePending.pending.id, requireNotNull(undoable.undoToken).pendingId)
        assertFalse(accounts.observeSettings().first().ignoreUnknownMeasurements)
        assertNull(persistence.getPending(undoablePending.pending.id))

        assertEquals(
            DiscardPendingAndUpdateIgnorePolicyResult.PendingNotFound,
            persistence.discardPendingAndUpdateIgnorePolicy(pending.pending.id, true),
        )
        assertFalse(accounts.observeSettings().first().ignoreUnknownMeasurements)

        val account = accounts.createAccount(NewAccount("Alice", completeProfile()))
        val finalizedPending = persistence.enqueue(raw("2026-08-15T10:02:00Z", 72.0))
            as PendingPersistenceResult.Inserted
        persistence.finalizePending(finalizedPending.pending.id, account.id)
        assertTrue(
            persistence.discardPendingAndUpdateIgnorePolicy(
                finalizedPending.pending.id,
                true,
            ) is DiscardPendingAndUpdateIgnorePolicyResult.AlreadyFinalized,
        )
        assertFalse(accounts.observeSettings().first().ignoreUnknownMeasurements)
    }

    @Test
    fun failedAtomicPolicyDiscardRollsBackPendingTombstoneAndSetting() = runBlocking {
        val accounts = accountRepository()
        val actualDao = database.pendingMeasurementDao()
        val normalPersistence = persistence()
        val pending = normalPersistence.enqueue(raw("2026-08-15T10:00:00Z", 70.0))
            as PendingPersistenceResult.Inserted
        val failingDao = object : PendingMeasurementDao by actualDao {
            override suspend fun delete(id: String): Int = 0
        }

        val failure = runCatching {
            persistence(failingDao).discardPendingAndUpdateIgnorePolicy(pending.pending.id, true)
        }

        assertTrue(failure.exceptionOrNull() is IllegalStateException)
        assertNotNull(normalPersistence.getPending(pending.pending.id))
        assertEquals(0, actualDao.tombstoneCount())
        assertFalse(accounts.observeSettings().first().ignoreUnknownMeasurements)
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

        val discarded = persistence.discardPending(first.pending.id)
            as DiscardPendingResult.Discarded
        assertEquals(first.pending.id, discarded.undoToken.pendingId)
        assertEquals(first.pending.deduplicationHash, discarded.undoToken.deduplicationHash)
        assertEquals(first.pending.enqueuedAt, discarded.undoToken.enqueuedAt)
        assertEquals(first.pending, discarded.undoToken.pending)
        assertEquals(
            DiscardPendingResult.PendingNotFound,
            persistence.discardPending(first.pending.id),
        )
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
        assertTrue(
            persistence.discardPending(pending.pending.id) is
                DiscardPendingResult.AlreadyFinalized,
        )
    }

    @Test
    fun restorePreservesIdentityFifoPositionAndConsumesTombstoneAfterInsert() = runBlocking {
        val persistence = persistence()
        val first = persistence.enqueue(raw("2026-08-15T10:00:00Z", 70.0))
            as PendingPersistenceResult.Inserted
        currentTime = currentTime.plusSeconds(1)
        val second = persistence.enqueue(raw("2026-08-15T10:01:00Z", 71.0))
            as PendingPersistenceResult.Inserted
        val token = (persistence.discardPending(first.pending.id) as DiscardPendingResult.Discarded)
            .undoToken

        val restored = persistence.restorePending(token) as RestorePendingResult.Restored

        assertEquals(first.pending, restored.pending)
        assertEquals(first.pending.id, restored.pending.id)
        assertEquals(first.pending.deduplicationHash, restored.pending.deduplicationHash)
        assertEquals(first.pending.enqueuedAt, restored.pending.enqueuedAt)
        assertEquals(
            listOf(first.pending.id, second.pending.id),
            persistence.observePending().first().map(PendingMeasurement::id),
        )
        assertEquals(0, database.pendingMeasurementDao().tombstoneCount())
    }

    @Test
    fun failedTombstoneDeletionRollsBackRestoreInsertion() = runBlocking {
        val normalPersistence = persistence()
        val inserted = normalPersistence.enqueue(raw("2026-08-15T10:00:00Z", 70.0))
            as PendingPersistenceResult.Inserted
        val token = (
            normalPersistence.discardPending(inserted.pending.id) as
                DiscardPendingResult.Discarded
            ).undoToken
        val actualDao = database.pendingMeasurementDao()
        val failingDao = object : PendingMeasurementDao by actualDao {
            override suspend fun deleteTombstone(deduplicationHash: String): Int {
                error("tombstone delete failed")
            }
        }

        val failure = runCatching {
            persistence(failingDao).restorePending(token)
        }

        assertTrue(failure.exceptionOrNull() is IllegalStateException)
        assertNull(normalPersistence.getPending(inserted.pending.id))
        assertNotNull(
            actualDao.getActiveTombstone(
                inserted.pending.deduplicationHash,
                currentTime.toEpochMilli(),
            ),
        )
        assertEquals(
            RestorePendingResult.Restored(inserted.pending),
            normalPersistence.restorePending(token),
        )
    }

    @Test
    fun restoreRejectsPendingIdentityAndHashConflictsWithoutDeletingTombstone() = runBlocking {
        val persistence = persistence()
        val inserted = persistence.enqueue(raw("2026-08-15T10:00:00Z", 70.0))
            as PendingPersistenceResult.Inserted
        val token = (persistence.discardPending(inserted.pending.id) as DiscardPendingResult.Discarded)
            .undoToken
        val dao = database.pendingMeasurementDao()
        val idConflict = inserted.pending.toEntityForTest(
            deduplicationHash = "different-hash",
        )
        assertTrue(dao.insert(idConflict) > 0L)

        assertEquals(
            RestorePendingResult.Conflict(idConflict.toDomain()),
            persistence.restorePending(token),
        )
        assertEquals(1, dao.tombstoneCount())

        assertEquals(1, dao.delete(idConflict.id))
        val hashConflict = inserted.pending.toEntityForTest(id = "different-id")
        assertTrue(dao.insert(hashConflict) > 0L)
        assertEquals(
            RestorePendingResult.Conflict(hashConflict.toDomain()),
            persistence.restorePending(token),
        )
        assertEquals(1, dao.tombstoneCount())
    }

    @Test
    fun concurrentAndRepeatedRestoreCreateOnePendingAndNeverReviveFinalizedReading() = runBlocking {
        val account = accountRepository().createAccount(NewAccount("Alice", completeProfile()))
        val persistence = persistence()
        val inserted = persistence.enqueue(raw("2026-08-15T10:00:00Z", 70.0))
            as PendingPersistenceResult.Inserted
        val token = (persistence.discardPending(inserted.pending.id) as DiscardPendingResult.Discarded)
            .undoToken

        val results = listOf(1, 2).map {
            async(Dispatchers.Default) { persistence.restorePending(token) }
        }.awaitAll()

        assertEquals(1, results.count { it is RestorePendingResult.Restored })
        assertEquals(1, results.count { it is RestorePendingResult.AlreadyRestored })
        assertEquals(listOf(inserted.pending), persistence.observePending().first())
        assertTrue(
            persistence.finalizePending(inserted.pending.id, account.id) is
                FinalizePendingResult.Finalized,
        )
        assertTrue(persistence.restorePending(token) is RestorePendingResult.AlreadyFinalized)
        assertNull(persistence.getPending(inserted.pending.id))
        assertEquals(1, persistence.observeAll(account.id).first().size)
    }

    @Test
    fun finalizedConflictIsNotRestoredAndKeepsDiscardTombstone() = runBlocking {
        val account = accountRepository().createAccount(NewAccount("Alice", completeProfile()))
        val persistence = persistence()
        val raw = raw("2026-08-15T10:00:00Z", 70.0)
        val inserted = persistence.enqueue(raw) as PendingPersistenceResult.Inserted
        val token = (persistence.discardPending(inserted.pending.id) as DiscardPendingResult.Discarded)
            .undoToken
        val finalized = raw.toWeightOnlyEntity(
            huaweiSyncEnabled = false,
            accountId = account.id,
            sourcePendingId = inserted.pending.id.value,
            deduplicationHash = inserted.pending.deduplicationHash,
        )
        assertTrue(database.multiAccountMeasurementDao().insert(finalized) > 0L)

        val result = persistence.restorePending(token)

        assertTrue(result is RestorePendingResult.AlreadyFinalized)
        assertNull(persistence.getPending(inserted.pending.id))
        assertEquals(1, database.pendingMeasurementDao().tombstoneCount())
    }

    @Test
    fun repositoryExposesRestoreWithoutNotificationCountSideEffect() = runBlocking {
        val counts = mutableListOf<Int>()
        val repository = repository(
            object : PendingDecisionNotifier {
                override fun updatePendingCount(count: Int) {
                    counts += count
                }
            },
        )
        val enqueued = repository.enqueuePending(raw("2026-08-15T10:00:00Z", 70.0))
            as PendingEnqueueResult.Enqueued
        val token = (
            repository.discardPending(enqueued.pending.id) as DiscardPendingResult.Discarded
            ).undoToken

        val result = repository.restorePending(token)

        assertEquals(RestorePendingResult.Restored(enqueued.pending), result)
        assertEquals(enqueued.pending, repository.getPending(enqueued.pending.id))
        assertEquals(listOf(0), counts)
    }

    @Test
    fun pendingDaoDeletesOnlyTheRequestedTombstone() = runBlocking {
        val dao = database.pendingMeasurementDao()
        val expiresAt = currentTime.plusSeconds(60).toEpochMilli()
        dao.upsertTombstone(MeasurementTombstoneEntity("hash-a", expiresAt))
        dao.upsertTombstone(MeasurementTombstoneEntity("hash-b", expiresAt))

        assertEquals(1, dao.deleteTombstone("hash-a"))

        assertNull(dao.getActiveTombstone("hash-a", currentTime.toEpochMilli()))
        assertNotNull(dao.getActiveTombstone("hash-b", currentTime.toEpochMilli()))
        assertEquals(1, dao.tombstoneCount())
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

    private fun persistence(
        pendingDao: PendingMeasurementDao = database.pendingMeasurementDao(),
    ) = RoomMeasurementPersistence(
        database = database,
        calculator = BodyCompositionCalculator(ZoneId.of("UTC")),
        huaweiSyncEnabled = false,
        pendingDao = pendingDao,
        now = { currentTime },
        newId = { durableId() },
    )

    private fun repository(notifier: PendingDecisionNotifier) = MeasurementRepository(
        dao = database.measurementDao(),
        profileProvider = { null },
        scaleAddressProvider = { null },
        calculator = BodyCompositionCalculator(ZoneId.of("UTC")),
        syncScheduler = NoOpSyncScheduler,
        huaweiSyncEnabled = false,
        multiAccountPersistence = persistence(),
        accountRepository = accountRepository(),
        pendingDecisionNotifier = notifier,
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

private object NoOpSyncScheduler : MeasurementSyncScheduler {
    override fun enqueue(measurementId: String) = Unit

    override fun cancel(measurementId: String) = Unit
}

private fun PendingMeasurement.toEntityForTest(
    id: String = this.id.value,
    deduplicationHash: String = this.deduplicationHash,
) = PendingMeasurementEntity(
    id = id,
    deviceAddress = deviceAddress,
    measuredAtEpochSecond = measuredAt.epochSecond,
    measuredAtNano = measuredAt.nano,
    weightKg = weightKg,
    impedanceOhm = impedanceOhm,
    isStable = isStable,
    hasImpedance = hasImpedance,
    rawPayload = rawPayload.copyOf(),
    deduplicationHash = deduplicationHash,
    enqueuedAtEpochMillis = enqueuedAt.toEpochMilli(),
    rawWeight = rawWeight,
)
