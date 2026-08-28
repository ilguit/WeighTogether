package com.palixander.scalesync.data

import android.content.Context
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.palixander.scalesync.core.BodyCompositionCalculator
import com.palixander.scalesync.core.RawScaleMeasurement
import com.palixander.scalesync.core.Sex
import com.palixander.scalesync.domain.AccountId
import com.palixander.scalesync.domain.AccountProfile
import com.palixander.scalesync.domain.CreateAccountAndAssignResult
import com.palixander.scalesync.domain.DiscardPendingResult
import com.palixander.scalesync.domain.DiscardPendingAndUpdateIgnorePolicyResult
import com.palixander.scalesync.domain.ExternalSyncPolicy
import com.palixander.scalesync.domain.FinalizePendingResult
import com.palixander.scalesync.domain.NewAccount
import com.palixander.scalesync.domain.PendingEnqueueResult
import com.palixander.scalesync.domain.PendingMeasurement
import com.palixander.scalesync.domain.PendingMeasurementId
import com.palixander.scalesync.domain.PrimaryHistorySyncMode
import com.palixander.scalesync.domain.RestorePendingResult
import com.palixander.scalesync.domain.RoutingDecision
import com.palixander.scalesync.domain.routing.MatchingEngine
import com.palixander.scalesync.worker.MeasurementSyncScheduler
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
        val extendedFirst = (duplicate as PendingPersistenceResult.AlreadyPending).pending
        assertEquals(
            listOf(first.pending.id, second.pending.id),
            persistence.observePending().first().map { it.id },
        )

        val discarded = persistence.discardPending(first.pending.id)
            as DiscardPendingResult.Discarded
        assertEquals(first.pending.id, discarded.undoToken.pendingId)
        assertEquals(first.pending.deduplicationHash, discarded.undoToken.deduplicationHash)
        assertEquals(first.pending.enqueuedAt, discarded.undoToken.enqueuedAt)
        assertEquals(extendedFirst, discarded.undoToken.pending)
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
                override fun updatePendingMeasurements(pendingIds: Set<PendingMeasurementId>) {
                    counts += pendingIds.size
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

    @Test
    fun enqueueClassifiesPrimarySecondaryAmbiguousAndNoMatch() = runBlocking {
        val accounts = accountRepository()
        val persistence = persistence()
        val primary = accounts.createAccount(NewAccount("Primary", completeProfile()))
        val secondary = accounts.createAccount(NewAccount("Secondary", completeProfile()))
        val third = accounts.createAccount(NewAccount("Third", completeProfile()))
        seedHistory(persistence, primary.id, "2026-08-15T09:00:00Z", 70.0)
        seedHistory(persistence, secondary.id, "2026-08-15T09:01:00Z", 80.0)

        val primaryMatch = persistence.enqueue(raw("2026-08-15T10:00:00Z", 72.0))
            as PendingPersistenceResult.Inserted
        val secondaryMatch = persistence.enqueue(raw("2026-08-15T10:01:00Z", 78.0))
            as PendingPersistenceResult.Inserted
        seedHistory(persistence, third.id, "2026-08-15T09:02:00Z", 82.0)
        val ambiguous = persistence.enqueue(raw("2026-08-15T10:02:00Z", 81.0))
            as PendingPersistenceResult.Inserted
        val noMatch = persistence.enqueue(raw("2026-08-15T10:03:00Z", 110.0))
            as PendingPersistenceResult.Inserted

        assertEquals(primary.id, primaryMatch.pending.provisionalAccountId)
        assertEquals(secondary.id, secondaryMatch.pending.provisionalAccountId)
        assertNull(ambiguous.pending.provisionalAccountId)
        assertNull(noMatch.pending.provisionalAccountId)
    }

    @Test
    fun preliminaryFlowIsAccountScopedWhileUnassignedRemainsImmediatelyObservable() = runBlocking {
        val accounts = accountRepository()
        val persistence = persistence()
        val first = accounts.createAccount(NewAccount("First", completeProfile()))
        val second = accounts.createAccount(NewAccount("Second", completeProfile()))
        seedHistory(persistence, first.id, "2026-08-15T09:00:00Z", 70.0)
        seedHistory(persistence, second.id, "2026-08-15T09:01:00Z", 90.0)

        val firstMatch = persistence.enqueue(raw("2026-08-15T10:00:00Z", 71.0))
            as PendingPersistenceResult.Inserted
        val unassigned = persistence.enqueue(raw("2026-08-15T10:01:00Z", 120.0))
            as PendingPersistenceResult.Inserted

        assertEquals(
            listOf(unassigned.pending.id),
            persistence.observeUnassignedPending().first().map(PendingMeasurement::id),
        )

        currentTime = currentTime.plusMillis(1)
        val laterUnassigned = persistence.enqueue(raw("2026-08-15T10:02:00Z", 121.0))
            as PendingPersistenceResult.Inserted

        assertEquals(
            listOf(firstMatch.pending.id),
            persistence.observePreliminary(first.id).first().map(PendingMeasurement::id),
        )
        assertTrue(persistence.observePreliminary(second.id).first().isEmpty())
        assertEquals(
            setOf(firstMatch.pending.id, unassigned.pending.id, laterUnassigned.pending.id),
            persistence.observePending().first().map(PendingMeasurement::id).toSet(),
        )
        assertEquals(
            listOf(unassigned.pending.id, laterUnassigned.pending.id),
            persistence.observeUnassignedPending().first().map(PendingMeasurement::id),
        )
        assertEquals(
            listOf(unassigned.pending.id, laterUnassigned.pending.id),
            repository(NoOpPendingDecisionNotifier)
                .observeUnassignedPending()
                .first()
                .map(PendingMeasurement::id),
        )
        assertTrue(currentTime.isBefore(unassigned.pending.finalizeAfter))
    }

    @Test
    fun impedanceEnrichmentKeepsOnePreliminaryRowAndItsClassification() = runBlocking {
        val account = accountRepository().createAccount(NewAccount("Primary", completeProfile()))
        val persistence = persistence()
        val weightOnly = raw("2026-08-15T10:00:00Z", 72.0).copy(
            impedanceOhm = 0,
            hasImpedance = false,
            rawPayload = byteArrayOf(1),
        )
        val first = persistence.enqueue(weightOnly) as PendingPersistenceResult.Inserted

        val enriched = persistence.enqueue(
            weightOnly.copy(
                impedanceOhm = 500,
                hasImpedance = true,
                rawPayload = byteArrayOf(1, 2, 3),
            ),
        ) as PendingPersistenceResult.AlreadyPending

        assertTrue(enriched.wasEnriched)
        assertEquals(first.pending.id, enriched.pending.id)
        assertEquals(account.id, enriched.pending.provisionalAccountId)
        assertEquals(1, database.pendingMeasurementDao().getAll().size)
        assertTrue(database.pendingMeasurementDao().getAll().single().hasImpedance)
    }

    @Test
    fun fullPacketAtPlus24SecondsUpgradesFinalizedWeightOnlyInPlace() = runBlocking {
        val accounts = accountRepository()
        val account = accounts.createAccount(NewAccount("Primary", completeProfile()))
        val persistence = persistence()
        val weightOnly = raw("2026-08-15T10:00:00Z", 72.0).copy(
            impedanceOhm = 0,
            hasImpedance = false,
            rawPayload = byteArrayOf(1),
        )
        val pending = persistence.enqueue(weightOnly) as PendingPersistenceResult.Inserted
        val finalized = persistence.finalizePending(pending.pending.id, account.id)
            as FinalizePendingResult.Finalized
        val original = database.multiAccountMeasurementDao().get(
            finalized.measurement.measurementId,
        )!!.copy(
            huaweiStatus = SyncStatus.DISABLED.name,
            huaweiError = "adapter disabled",
            healthConnectStatus = SyncStatus.SYNCED.name,
            healthConnectWeightSynced = true,
            healthConnectSyncedCalculatedValues = "old-snapshot",
        )
        assertEquals(1, database.multiAccountMeasurementDao().update(original))

        val full = weightOnly.copy(
            measuredAt = weightOnly.measuredAt.plusSeconds(24),
            impedanceOhm = 500,
            hasImpedance = true,
            rawPayload = byteArrayOf(9, 8, 7),
        )
        val upgraded = persistence.enqueue(full) as PendingPersistenceResult.UpgradedFinalized
        val stored = database.multiAccountMeasurementDao().get(original.id)!!

        assertEquals(original.id, upgraded.measurement.measurementId)
        assertEquals(original.id, stored.id)
        assertEquals(original.accountId, stored.accountId)
        assertEquals(original.sourcePendingId, stored.sourcePendingId)
        assertEquals(original.fingerprint, stored.fingerprint)
        assertEquals(original.deduplicationHash, stored.deduplicationHash)
        assertEquals(original.measuredAtEpochSecond, stored.measuredAtEpochSecond)
        assertEquals(original.createdAtEpochMillis, stored.createdAtEpochMillis)
        assertEquals(MeasurementType.FULL, stored.measurementType)
        assertEquals(500, stored.impedanceOhm)
        assertEquals(SyncStatus.DISABLED.name, stored.huaweiStatus)
        assertEquals("adapter disabled", stored.huaweiError)
        assertEquals(SyncStatus.PENDING.name, stored.healthConnectStatus)
        assertNull(stored.healthConnectError)
        assertTrue(stored.healthConnectWeightSynced)
        assertEquals("old-snapshot", stored.healthConnectSyncedCalculatedValues)
        assertTrue(database.pendingMeasurementDao().getAll().isEmpty())
        assertEquals(
            1,
            database.multiAccountMeasurementDao().observeAll(account.id.value).first().size,
        )

        assertTrue(persistence.enqueue(full) is PendingPersistenceResult.AlreadyFinalized)
        assertEquals(1, database.multiAccountMeasurementDao().observeAll(account.id.value).first().size)
        assertTrue(database.pendingMeasurementDao().getAll().isEmpty())
    }

    @Test
    fun fullPacketAtSameSecondUpgradesFinalizedWeightOnlyInPlace() = runBlocking {
        assertFinalizedWeightOnlyUpgradeInsideDedupWindow(delaySeconds = 0)
    }

    @Test
    fun fullPacketAtPlus9SecondsUpgradesFinalizedWeightOnlyInPlace() = runBlocking {
        assertFinalizedWeightOnlyUpgradeInsideDedupWindow(delaySeconds = 9)
    }

    @Test
    fun nearerFullDoesNotHideEligibleFinalizedWeightOnly() = runBlocking {
        val account = accountRepository().createAccount(NewAccount("Primary", completeProfile()))
        val persistence = persistence()
        val nearerFullPending = persistence.enqueue(
            raw("2026-08-15T10:00:12Z", 72.0).copy(rawPayload = byteArrayOf(7, 8, 9)),
        ) as PendingPersistenceResult.Inserted
        val nearerFull = persistence.finalizePending(nearerFullPending.pending.id, account.id)
            as FinalizePendingResult.Finalized
        val weightOnly = raw("2026-08-15T10:00:00Z", 72.0).copy(
            impedanceOhm = 0,
            hasImpedance = false,
            rawPayload = byteArrayOf(1),
        )
        val weightOnlyPending = persistence.enqueue(weightOnly)
            as PendingPersistenceResult.Inserted
        val finalizedWeightOnly = persistence.finalizePending(
            weightOnlyPending.pending.id,
            account.id,
        ) as FinalizePendingResult.Finalized

        val result = persistence.enqueue(
            raw("2026-08-15T10:00:24Z", 72.0).copy(rawPayload = byteArrayOf(4, 5, 6)),
        ) as PendingPersistenceResult.UpgradedFinalized

        assertEquals(
            finalizedWeightOnly.measurement.measurementId,
            result.measurement.measurementId,
        )
        assertEquals(
            MeasurementType.FULL,
            database.multiAccountMeasurementDao().get(
                finalizedWeightOnly.measurement.measurementId,
            )!!.measurementType,
        )
        assertEquals(
            MeasurementType.FULL,
            database.multiAccountMeasurementDao().get(
                nearerFull.measurement.measurementId,
            )!!.measurementType,
        )
        assertEquals(
            2,
            database.multiAccountMeasurementDao().observeAll(account.id.value).first().size,
        )
        assertTrue(database.pendingMeasurementDao().getAll().isEmpty())
    }

    @Test
    fun exactFullReplayOutsideDeduplicationWindowRemainsIdempotent() = runBlocking {
        val account = accountRepository().createAccount(NewAccount("Primary", completeProfile()))
        val persistence = persistence()
        val full = raw("2026-08-15T10:00:00Z", 72.0).copy(
            rawPayload = byteArrayOf(7, 8, 9),
        )
        val pending = persistence.enqueue(full) as PendingPersistenceResult.Inserted
        val finalized = persistence.finalizePending(pending.pending.id, account.id)
            as FinalizePendingResult.Finalized

        val replay = persistence.enqueue(
            full.copy(measuredAt = full.measuredAt.plusSeconds(24)),
        ) as PendingPersistenceResult.AlreadyFinalized

        assertEquals(finalized.measurement.measurementId, replay.measurement.measurementId)
        assertEquals(
            1,
            database.multiAccountMeasurementDao().observeAll(account.id.value).first().size,
        )
        assertTrue(database.pendingMeasurementDao().getAll().isEmpty())
    }

    @Test
    fun incompleteProfileKeepsFinalizedWeightOnlyAndDoesNotCreatePendingDuplicate() = runBlocking {
        val accounts = accountRepository()
        val account = accounts.createAccount(NewAccount("Primary", completeProfile()))
        val persistence = persistence()
        val weightOnly = raw("2026-08-15T10:00:00Z", 72.0).copy(
            impedanceOhm = 0,
            hasImpedance = false,
            rawPayload = byteArrayOf(1),
        )
        val pending = persistence.enqueue(weightOnly) as PendingPersistenceResult.Inserted
        val finalized = persistence.finalizePending(pending.pending.id, account.id)
            as FinalizePendingResult.Finalized
        val accountEntity = database.accountDao().get(account.id.value)!!
        assertEquals(1, database.accountDao().update(accountEntity.copy(isProfileComplete = false)))

        val outcome = persistence.enqueue(
            weightOnly.copy(
                measuredAt = weightOnly.measuredAt.plusSeconds(24),
                impedanceOhm = 500,
                hasImpedance = true,
                rawPayload = byteArrayOf(9, 8, 7),
            ),
        ) as PendingPersistenceResult.AlreadyFinalized

        assertEquals(finalized.measurement.measurementId, outcome.measurement.measurementId)
        assertEquals(
            MeasurementType.WEIGHT_ONLY,
            database.multiAccountMeasurementDao().get(finalized.measurement.measurementId)!!
                .measurementType,
        )
        assertTrue(database.pendingMeasurementDao().getAll().isEmpty())
    }

    @Test
    fun extendedEnrichmentUsesNearestPredecessorAndDoesNotMergeOutsidePolicy() = runBlocking {
        val account = accountRepository().createAccount(NewAccount("Primary", completeProfile()))
        val persistence = persistence()
        suspend fun finalizeWeightOnly(timestamp: String): String {
            val pending = persistence.enqueue(
                raw(timestamp, 72.0).copy(impedanceOhm = 0, hasImpedance = false),
            ) as PendingPersistenceResult.Inserted
            return (persistence.finalizePending(pending.pending.id, account.id)
                as FinalizePendingResult.Finalized).measurement.measurementId
        }
        val olderId = finalizeWeightOnly("2026-08-15T10:00:00Z")
        val nearerId = finalizeWeightOnly("2026-08-15T10:00:12Z")

        val upgraded = persistence.enqueue(
            raw("2026-08-15T10:00:24Z", 72.0).copy(rawPayload = byteArrayOf(4, 5, 6)),
        ) as PendingPersistenceResult.UpgradedFinalized
        assertEquals(nearerId, upgraded.measurement.measurementId)
        assertEquals(
            MeasurementType.WEIGHT_ONLY,
            database.multiAccountMeasurementDao().get(olderId)!!.measurementType,
        )

        val outside = persistence.enqueue(raw("2026-08-15T10:01:00Z", 72.0))
        assertTrue(outside is PendingPersistenceResult.Inserted)
    }

    @Test
    fun sweepReclassifiesPreliminaryButFinalizationReevaluatesAuthoritatively() = runBlocking {
        val accounts = accountRepository()
        val persistence = persistence()
        val primary = accounts.createAccount(NewAccount("Primary", completeProfile()))
        seedHistory(persistence, primary.id, "2026-08-15T09:00:00Z", 70.0)
        currentTime = Instant.parse("2099-08-15T12:00:00Z")
        val preliminary = persistence.enqueue(raw("2026-08-15T10:00:00Z", 90.0))
            as PendingPersistenceResult.Inserted
        assertNull(preliminary.pending.provisionalAccountId)

        accounts.updateWeightDeltaKg(25.0)
        val coordinator = MeasurementIngestionCoordinator(
            persistence = persistence,
            accounts = accounts,
            calculator = BodyCompositionCalculator(ZoneId.of("UTC")),
            syncScheduler = NoOpSyncScheduler,
            matchingEngine = MatchingEngine(),
        )
        coordinator.sweepPendingRouting()
        assertEquals(primary.id, persistence.getPending(preliminary.pending.id)?.provisionalAccountId)

        accounts.updateWeightDeltaKg(5.0)
        currentTime = currentTime.plusSeconds(RoomMeasurementPersistence.DEBOUNCE_SECONDS + 1)
        val finalized = coordinator.finalizeDue(preliminary.pending.id, currentTime)
        val awaiting = finalized as AggregateFinalizationResult.Completed
        assertTrue(awaiting.outcome is MeasurementIngestionResult.AwaitingDecision)
        assertEquals(
            RoutingDecision.NoMatch,
            (awaiting.outcome as MeasurementIngestionResult.AwaitingDecision).decision,
        )
        assertNotNull(persistence.getPending(preliminary.pending.id))
    }

    private suspend fun seedHistory(
        persistence: RoomMeasurementPersistence,
        accountId: AccountId,
        measuredAt: String,
        weightKg: Double,
    ) {
        val pending = persistence.enqueue(raw(measuredAt, weightKg))
            as PendingPersistenceResult.Inserted
        assertTrue(
            persistence.finalizePending(pending.pending.id, accountId) is
                FinalizePendingResult.Finalized,
        )
    }

    private suspend fun assertFinalizedWeightOnlyUpgradeInsideDedupWindow(delaySeconds: Long) {
        val account = accountRepository().createAccount(NewAccount("Primary", completeProfile()))
        val persistence = persistence()
        val weightOnly = raw("2026-08-15T10:00:00Z", 72.0).copy(
            impedanceOhm = 0,
            hasImpedance = false,
            rawPayload = byteArrayOf(1),
        )
        val pending = persistence.enqueue(weightOnly) as PendingPersistenceResult.Inserted
        val finalized = persistence.finalizePending(pending.pending.id, account.id)
            as FinalizePendingResult.Finalized

        val upgraded = persistence.enqueue(
            weightOnly.copy(
                measuredAt = weightOnly.measuredAt.plusSeconds(delaySeconds),
                impedanceOhm = 500,
                hasImpedance = true,
                rawPayload = byteArrayOf(9, 8, 7),
            ),
        ) as PendingPersistenceResult.UpgradedFinalized
        val stored = database.multiAccountMeasurementDao().get(
            finalized.measurement.measurementId,
        )!!

        assertEquals(finalized.measurement.measurementId, upgraded.measurement.measurementId)
        assertEquals(MeasurementType.FULL, stored.measurementType)
        assertEquals(weightOnly.measuredAt.epochSecond, stored.measuredAtEpochSecond)
        assertEquals(pending.pending.id.value, stored.sourcePendingId)
        assertEquals(pending.pending.deduplicationHash, stored.deduplicationHash)
        assertEquals(1, database.multiAccountMeasurementDao().observeAll(account.id.value).first().size)
        assertTrue(database.pendingMeasurementDao().getAll().isEmpty())
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

    override fun deferCurrent(measurementId: String, notBeforeEpochMillis: Long) = Unit

    override fun cancel(measurementId: String) = Unit
}

private fun PendingMeasurement.toEntityForTest(
    id: String = this.id.value,
    deduplicationHash: String = this.deduplicationHash,
) = PendingMeasurementEntity(
    id = id,
    deviceAddress = deviceAddress,
    measuredAtEpochSecond = measuredAt.epochSecond,
    weightKg = weightKg,
    impedanceOhm = impedanceOhm,
    isStable = isStable,
    hasImpedance = hasImpedance,
    rawPayload = rawPayload.copyOf(),
    deduplicationHash = deduplicationHash,
    enqueuedAtEpochMillis = enqueuedAt.toEpochMilli(),
    rawWeight = rawWeight,
)
