package com.example.huaweimisync.data

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.example.huaweimisync.core.BodyCompositionCalculator
import com.example.huaweimisync.core.Sex
import com.example.huaweimisync.domain.AccountProfile
import com.example.huaweimisync.domain.DiscardPendingResult
import com.example.huaweimisync.domain.NewAccount
import com.example.huaweimisync.domain.PendingMeasurement
import com.example.huaweimisync.worker.MeasurementSyncScheduler
import com.example.huaweimisync.worker.PendingFinalizationScheduler
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
            scaleAddressProvider = { null },
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

    private class RecordingSyncScheduler : MeasurementSyncScheduler {
        val enqueued = mutableListOf<String>()

        override fun enqueue(measurementId: String) {
            enqueued += measurementId
        }

        override fun deferCurrent(measurementId: String, notBeforeEpochMillis: Long) = Unit

        override fun cancel(measurementId: String) = Unit
    }

    private class RecordingFinalizationScheduler : PendingFinalizationScheduler {
        val pendingIds = mutableListOf<com.example.huaweimisync.domain.PendingMeasurementId>()

        override fun enqueue(pending: PendingMeasurement) {
            pendingIds += pending.id
        }
    }

    private class RecordingPendingNotifier : PendingDecisionNotifier {
        val counts = mutableListOf<Int>()

        override fun updatePendingCount(count: Int) {
            counts += count
        }
    }

    private companion object {
        val MEASURED_AT: Instant = Instant.parse("2026-08-20T10:00:00Z")
    }
}
