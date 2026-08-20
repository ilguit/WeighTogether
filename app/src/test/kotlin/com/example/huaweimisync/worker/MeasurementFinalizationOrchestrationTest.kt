package com.example.huaweimisync.worker

import androidx.work.ExistingWorkPolicy
import com.example.huaweimisync.core.RawScaleMeasurement
import com.example.huaweimisync.data.MeasurementIngestionResult
import com.example.huaweimisync.domain.PendingMeasurement
import com.example.huaweimisync.domain.PendingMeasurementId
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MeasurementFinalizationOrchestrationTest {
    @Test
    fun workPlanIsUniqueByPendingIdAndUsesRemainingSlidingDelay() {
        val pending = pending(finalizeAfter = NOW.plusSeconds(10))

        val first = pendingFinalizationWorkPlan(pending, NOW)
        val extended = pendingFinalizationWorkPlan(
            pending.copy(finalizeAfter = NOW.plusSeconds(18)),
            NOW.plusSeconds(3),
        )

        assertEquals("finalize-pending-1", first.uniqueName)
        assertEquals("finalize-pending-1", extended.uniqueName)
        assertEquals(10_000L, first.delayMillis)
        assertEquals(15_000L, extended.delayMillis)
        assertEquals(ExistingWorkPolicy.REPLACE, FINALIZATION_EXISTING_WORK_POLICY)
    }

    @Test
    fun expiredDeadlineGetsZeroDelay() {
        assertEquals(
            0L,
            pendingFinalizationWorkPlan(pending(NOW.minusMillis(1)), NOW).delayMillis,
        )
    }

    @Test
    fun onlyCreatedAndUpdatedAggregatesScheduleFinalization() = kotlinx.coroutines.runBlocking {
        val scheduled = mutableListOf<PendingMeasurement>()
        val scheduler = object : PendingFinalizationScheduler {
            override fun enqueue(pending: PendingMeasurement) {
                scheduled += pending
            }
        }
        val outcomes = listOf<MeasurementIngestionResult>(
            MeasurementIngestionResult.CreatedAggregate(pending()),
            MeasurementIngestionResult.UpdatedAggregate(pending(), wasEnriched = true),
            MeasurementIngestionResult.SuppressedFinal,
            MeasurementIngestionResult.SuppressedTombstone,
        )

        outcomes.forEach { outcome ->
            MeasurementIngestionWorkOrchestrator({ outcome }, scheduler).process(raw())
        }

        assertEquals(2, scheduled.size)
        assertTrue(scheduled.all { it.id == PendingMeasurementId("pending-1") })
    }
}

private fun pending(finalizeAfter: Instant = NOW.plusSeconds(10)) = PendingMeasurement(
    id = PendingMeasurementId("pending-1"),
    deviceAddress = "AA:BB:CC:DD:EE:FF",
    measuredAt = NOW,
    weightKg = 70.0,
    impedanceOhm = 500,
    isStable = true,
    hasImpedance = true,
    rawPayload = byteArrayOf(1, 2, 3),
    deduplicationHash = "hash",
    enqueuedAt = NOW,
    finalizeAfter = finalizeAfter,
)

private fun raw() = RawScaleMeasurement(
    deviceAddress = "AA:BB:CC:DD:EE:FF",
    measuredAt = NOW,
    weightKg = 70.0,
    impedanceOhm = 500,
    isStable = true,
    hasImpedance = true,
    rawPayload = byteArrayOf(1, 2, 3),
)

private val NOW = Instant.parse("2026-08-20T10:00:00Z")
