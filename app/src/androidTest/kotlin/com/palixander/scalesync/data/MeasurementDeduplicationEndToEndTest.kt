package com.palixander.scalesync.data

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.palixander.scalesync.core.BodyCompositionCalculator
import com.palixander.scalesync.core.MiScalePacketParser
import com.palixander.scalesync.core.Sex
import com.palixander.scalesync.domain.AccountProfile
import com.palixander.scalesync.domain.DiscardPendingResult
import com.palixander.scalesync.domain.NewAccount
import com.palixander.scalesync.domain.PendingMeasurement
import com.palixander.scalesync.domain.PendingMeasurementId
import com.palixander.scalesync.domain.isAwaitingDecisionAt
import com.palixander.scalesync.worker.MeasurementSyncScheduler
import com.palixander.scalesync.worker.PendingFinalizationScheduler
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Contract for the complete durable packet -> aggregate -> finalize -> sync orchestration. */
class MeasurementDeduplicationEndToEndTest {
    private lateinit var database: AppDatabase
    private var now = Instant.parse("2026-08-20T12:00:00Z")

    @Before
    fun createDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun closeDatabase() = database.close()

    @Test
    fun duplicatePacketsProduceOneFinalRecordAndAtMostOneEligibleSync() = runBlocking {
        val sync = RecordingSyncScheduler()
        val finalization = RecordingFinalizationScheduler()
        val notifications = RecordingPendingNotifier()
        val accounts = RoomAccountRepository(database, now = { now })
        val account = accounts.createAccount(
            NewAccount(
                displayName = "Alice",
                profile = AccountProfile.Complete(
                    heightCm = 175.0,
                    birthDate = LocalDate.of(1990, 1, 1),
                    sex = Sex.FEMALE,
                ),
            ),
        )
        val persistence = RoomMeasurementPersistence(
            database = database,
            calculator = BodyCompositionCalculator(ZoneId.of("UTC")),
            huaweiSyncEnabled = true,
            now = { now },
        )
        val repository = MeasurementRepository(
            dao = database.measurementDao(),
            profileProvider = { null },
            calculator = BodyCompositionCalculator(ZoneId.of("UTC")),
            syncScheduler = sync,
            huaweiSyncEnabled = true,
            multiAccountPersistence = persistence,
            accountRepository = accounts,
            pendingDecisionNotifier = notifications,
            pendingFinalizationScheduler = finalization,
        )

        val first = repository.ingestTestMeasurement(70.0, 0, MEASURED_AT)
            as MeasurementIngestionResult.CreatedAggregate
        val originalDeadline = first.pending.finalizeAfter
        assertEquals(listOf(first.pending.id), finalization.pendingIds)
        assertTrue(repository.observeAllEntities(account.id).first().isEmpty())
        assertTrue(sync.enqueued.isEmpty())
        assertTrue(notifications.counts.isEmpty())

        now = now.plusSeconds(8)
        val enriched = repository.ingestTestMeasurement(
            weightKg = 70.0,
            impedanceOhm = 500,
            measuredAt = MEASURED_AT.plusSeconds(8),
        ) as MeasurementIngestionResult.UpdatedAggregate
        assertTrue(enriched.wasEnriched)
        assertEquals(first.pending.id, enriched.pending.id)
        assertEquals(first.pending.measuredAt, enriched.pending.measuredAt)
        assertEquals(now.plusSeconds(10), enriched.pending.finalizeAfter)
        assertEquals(listOf(first.pending.id, first.pending.id), finalization.pendingIds)
        assertTrue(repository.observeAllEntities(account.id).first().isEmpty())
        assertTrue(sync.enqueued.isEmpty())

        val stale = repository.finalizeDue(first.pending.id, originalDeadline)
        assertTrue(stale is AggregateFinalizationResult.Reschedule)
        assertTrue(repository.observeAllEntities(account.id).first().isEmpty())
        assertTrue(sync.enqueued.isEmpty())

        now = enriched.pending.finalizeAfter
        val completed = repository.finalizeDue(first.pending.id, now)
            as AggregateFinalizationResult.Completed
        val assigned = completed.outcome as MeasurementIngestionResult.Assigned
        val records = repository.observeAllEntities(account.id).first()
        assertEquals(1, records.size)
        assertEquals(assigned.measurement.measurementId, records.single().id)
        assertEquals(MeasurementType.FULL, records.single().measurementType)
        assertEquals(MEASURED_AT.epochSecond, records.single().measuredAtEpochSecond)
        assertEquals(14_000, records.single().rawWeight)
        assertEquals(500, records.single().impedanceOhm)
        assertEquals(listOf(records.single().id), sync.enqueued)
        assertTrue(repository.observePending().first().isEmpty())
        assertEquals(listOf(0), notifications.counts)

        val postFinalDuplicate = repository.ingestTestMeasurement(
            70.0,
            500,
            MEASURED_AT.plusSeconds(9),
        )
        assertEquals(MeasurementIngestionResult.SuppressedFinal, postFinalDuplicate)
        val repeatedFinalize = repository.finalizeDue(first.pending.id, now)
            as AggregateFinalizationResult.Completed
        assertEquals(MeasurementIngestionResult.PendingMissing, repeatedFinalize.outcome)
        assertEquals(1, repository.observeAllEntities(account.id).first().size)
        assertEquals(1, sync.enqueued.size)
        assertEquals(2, finalization.pendingIds.size)

        now = now.plusSeconds(1)
        val exactBoundary = repository.ingestTestMeasurement(
            70.0,
            500,
            MEASURED_AT.plusSeconds(10),
        ) as MeasurementIngestionResult.CreatedAggregate
        assertTrue(exactBoundary.pending.id != first.pending.id)
        assertEquals(3, finalization.pendingIds.size)
        assertTrue(
            repository.discardPending(exactBoundary.pending.id) is DiscardPendingResult.Discarded,
        )
        val tombstonedDuplicate = repository.ingestTestMeasurement(
            70.0,
            500,
            MEASURED_AT.plusSeconds(19),
        )
        assertEquals(MeasurementIngestionResult.SuppressedTombstone, tombstonedDuplicate)
        assertEquals(1, repository.observeAllEntities(account.id).first().size)
        assertTrue(repository.observePending().first().isEmpty())
        assertEquals(1, sync.enqueued.size)
        assertEquals(3, finalization.pendingIds.size)
    }

    @Test
    fun deletingFinalRecordKeepsExactReplayBaselineAndAcceptsChangedPacket() = runBlocking {
        val accounts = RoomAccountRepository(database, now = { now })
        val account = accounts.createAccount(
            NewAccount(
                displayName = "Alice",
                profile = AccountProfile.Complete(
                    heightCm = 175.0,
                    birthDate = LocalDate.of(1990, 1, 1),
                    sex = Sex.FEMALE,
                ),
            ),
        )
        val calculator = BodyCompositionCalculator(ZoneId.of("UTC"))
        val repository = MeasurementRepository(
            dao = database.measurementDao(),
            profileProvider = { null },
            calculator = calculator,
            syncScheduler = RecordingSyncScheduler(),
            huaweiSyncEnabled = true,
            multiAccountPersistence = RoomMeasurementPersistence(
                database = database,
                calculator = calculator,
                huaweiSyncEnabled = true,
                now = { now },
            ),
            accountRepository = accounts,
        )

        val created = repository.ingestTestMeasurement(70.0, 500, MEASURED_AT)
            as MeasurementIngestionResult.CreatedAggregate
        now = created.pending.finalizeAfter
        val assigned = (
            repository.finalizeDue(created.pending.id, now) as AggregateFinalizationResult.Completed
        ).outcome as MeasurementIngestionResult.Assigned
        val baselineBeforeDelete = requireNotNull(database.acceptedStableMeasurementDao().getLatest())

        assertEquals(
            MeasurementMutationResult.Success,
            repository.delete(assigned.measurement.measurementId),
        )
        assertTrue(repository.observeAllEntities(account.id).first().isEmpty())
        assertTrue(
            requireNotNull(database.acceptedStableMeasurementDao().getLatest())
                .exactlyMatches(baselineBeforeDelete.toRawScaleMeasurement()),
        )

        val replay = repository.ingestTestMeasurement(70.0, 500, MEASURED_AT)
        assertEquals(MeasurementIngestionResult.ExactReplay, replay)
        assertTrue(repository.observePending().first().isEmpty())

        val changed = repository.ingestTestMeasurement(70.1, 500, MEASURED_AT)
        assertTrue(changed is MeasurementIngestionResult.CreatedAggregate)
        assertEquals(1, repository.observePending().first().size)
    }

    @Test
    fun lateDuplicateEnrichesAwaitingDecisionWithoutReturningItToAggregation() = runBlocking {
        val finalization = RecordingFinalizationScheduler()
        val notifications = RecordingPendingNotifier()
        val accounts = RoomAccountRepository(database, now = { now })
        val calculator = BodyCompositionCalculator(ZoneId.of("UTC"))
        val repository = MeasurementRepository(
            dao = database.measurementDao(),
            profileProvider = { null },
            calculator = calculator,
            syncScheduler = RecordingSyncScheduler(),
            huaweiSyncEnabled = true,
            multiAccountPersistence = RoomMeasurementPersistence(
                database = database,
                calculator = calculator,
                huaweiSyncEnabled = true,
                now = { now },
            ),
            accountRepository = accounts,
            pendingDecisionNotifier = notifications,
            pendingFinalizationScheduler = finalization,
        )

        val created = repository.ingestTestMeasurement(70.0, 0, MEASURED_AT)
            as MeasurementIngestionResult.CreatedAggregate
        now = created.pending.finalizeAfter
        val due = repository.finalizeDue(created.pending.id, now)
            as AggregateFinalizationResult.Completed

        assertTrue(due.outcome is MeasurementIngestionResult.AwaitingDecision)
        assertTrue(
            requireNotNull(repository.getPending(created.pending.id)).isAwaitingDecisionAt(now),
        )
        assertEquals(listOf(1), notifications.counts)

        now = now.plusSeconds(2)
        val repeated = repository.ingestTestMeasurement(
            weightKg = 70.0,
            impedanceOhm = 500,
            measuredAt = MEASURED_AT.plusSeconds(8),
        ) as MeasurementIngestionResult.UpdatedAggregate
        val persisted = requireNotNull(repository.getPending(created.pending.id))

        assertTrue(repeated.wasEnriched)
        assertTrue(!repeated.shouldScheduleFinalization)
        assertEquals(created.pending.id, repeated.pending.id)
        assertEquals(created.pending.finalizeAfter, repeated.pending.finalizeAfter)
        assertEquals(500, persisted.impedanceOhm)
        assertTrue(persisted.hasImpedance)
        assertTrue(persisted.isAwaitingDecisionAt(now))
        assertEquals(listOf(created.pending.id), finalization.pendingIds)
    }

    @Test
    fun parsedAdjacentRawWeightsProduceIndependentHistoryAndSync() = runBlocking {
        val sync = RecordingSyncScheduler()
        val accounts = RoomAccountRepository(database, now = { now })
        val account = accounts.createAccount(
            NewAccount(
                displayName = "Alice",
                profile = AccountProfile.Complete(
                    heightCm = 175.0,
                    birthDate = LocalDate.of(1990, 1, 1),
                    sex = Sex.FEMALE,
                ),
            ),
        )
        val calculator = BodyCompositionCalculator(ZoneId.of("UTC"))
        val repository = MeasurementRepository(
            dao = database.measurementDao(),
            profileProvider = { null },
            calculator = calculator,
            syncScheduler = sync,
            huaweiSyncEnabled = true,
            multiAccountPersistence = RoomMeasurementPersistence(
                database = database,
                calculator = calculator,
                huaweiSyncEnabled = true,
                now = { now },
            ),
            accountRepository = accounts,
        )
        val parser = MiScalePacketParser(ZoneId.of("UTC"))

        val first = repository.ingest(
            requireNotNull(
                parser.parse(scalePayload(rawWeight = 14_000, second = 0), DEVICE.lowercase()),
            ),
        ) as MeasurementIngestionResult.CreatedAggregate
        val repeated = repository.ingest(
            requireNotNull(parser.parse(scalePayload(rawWeight = 14_000, second = 8), DEVICE)),
        ) as MeasurementIngestionResult.UpdatedAggregate
        val adjacent = repository.ingest(
            requireNotNull(parser.parse(scalePayload(rawWeight = 14_001, second = 8), DEVICE)),
        ) as MeasurementIngestionResult.CreatedAggregate

        assertEquals(first.pending.id, repeated.pending.id)
        assertTrue(adjacent.pending.id != first.pending.id)
        assertEquals(2, repository.observePending().first().size)

        val firstAssigned = (
            repository.finalizeDue(first.pending.id, repeated.pending.finalizeAfter) as
                AggregateFinalizationResult.Completed
            ).outcome as MeasurementIngestionResult.Assigned
        val adjacentAssigned = (
            repository.finalizeDue(adjacent.pending.id, adjacent.pending.finalizeAfter) as
                AggregateFinalizationResult.Completed
            ).outcome as MeasurementIngestionResult.Assigned

        val history = repository.observeAllEntities(account.id).first()
        assertEquals(setOf(14_000, 14_001), history.map { it.rawWeight }.toSet())
        assertEquals(
            setOf(firstAssigned.measurement.measurementId, adjacentAssigned.measurement.measurementId),
            history.map { it.id }.toSet(),
        )
        assertEquals(history.map { it.id }.toSet(), sync.enqueued.toSet())
        assertEquals(2, sync.enqueued.size)
        assertTrue(repository.observePending().first().isEmpty())
    }

    private fun scalePayload(rawWeight: Int, second: Int): ByteArray = byteArrayOf(
        0x00,
        0x22,
        0xea.toByte(), 0x07,
        0x08,
        0x14,
        0x0a,
        0x00,
        second.toByte(),
        0xf4.toByte(), 0x01,
        rawWeight.toByte(), (rawWeight ushr 8).toByte(),
    )

    private class RecordingSyncScheduler : MeasurementSyncScheduler {
        val enqueued = mutableListOf<String>()

        override fun enqueue(measurementId: String) {
            enqueued += measurementId
        }

        override fun deferCurrent(measurementId: String, notBeforeEpochMillis: Long) = Unit

        override fun cancel(measurementId: String) = Unit
    }

    private class RecordingFinalizationScheduler : PendingFinalizationScheduler {
        val pendingIds = mutableListOf<com.palixander.scalesync.domain.PendingMeasurementId>()

        override fun enqueue(pending: PendingMeasurement) {
            pendingIds += pending.id
        }
    }

    private class RecordingPendingNotifier : PendingDecisionNotifier {
        val counts = mutableListOf<Int>()

        override fun updatePendingMeasurements(pendingIds: Set<PendingMeasurementId>) {
            counts += pendingIds.size
        }
    }

    private companion object {
        const val DEVICE = "AA:BB:CC:DD:EE:FF"
        val MEASURED_AT: Instant = Instant.parse("2026-08-20T10:00:00Z")
    }
}
