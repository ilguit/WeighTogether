package com.example.huaweimisync.worker

import androidx.work.ExistingWorkPolicy
import com.example.huaweimisync.core.RawScaleMeasurement
import com.example.huaweimisync.data.MeasurementIngestionResult
import com.example.huaweimisync.data.pendingReplayPlan
import com.example.huaweimisync.domain.PendingMeasurement
import com.example.huaweimisync.domain.PendingMeasurementId
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MeasurementFinalizationOrchestrationTest {
    @Test
    fun directSuccessDoesNotEnqueueFallback() = kotlinx.coroutines.runBlocking {
        val processed = mutableListOf<ScalePacket>()
        val fallback = mutableListOf<ScalePacket>()
        val packet = ScalePacket(byteArrayOf(1), "AA")

        val result = DirectPacketProcessingOrchestrator(
            process = { processed += it },
            enqueueFallback = { fallback += it },
        ).process(packet)

        assertEquals(DirectPacketProcessingResult.PROCESSED_DIRECTLY, result)
        assertEquals(listOf(packet), processed)
        assertTrue(fallback.isEmpty())
    }

    @Test
    fun directFailureEnqueuesExactlyOneDurableFallback() = kotlinx.coroutines.runBlocking {
        val fallback = mutableListOf<ScalePacket>()
        val packet = ScalePacket(byteArrayOf(1), "AA")

        val result = DirectPacketProcessingOrchestrator(
            process = { error("Room unavailable") },
            enqueueFallback = { fallback += it },
        ).process(packet)

        assertEquals(DirectPacketProcessingResult.FALLBACK_ENQUEUED, result)
        assertEquals(1, fallback.size)
        assertEquals(packet, fallback.single())
    }

    @Test
    fun exactFallbackReplayRepairsUniqueWatchdogWithoutSlidingDeadline() =
        kotlinx.coroutines.runBlocking {
            val original = pending()
            val pendingRows = mutableListOf<PendingMeasurement>()
            val uniqueWatchdogs = linkedSetOf<String>()
            val fallback = mutableListOf<ScalePacket>()
            var schedulerAttempts = 0
            val processor = ScalePacketProcessor(
                parse = { _, _ -> raw() },
                ingestion = MeasurementIngestionWorkOrchestrator(
                    ingest = {
                        val existing = pendingRows.singleOrNull()
                        if (existing == null) {
                            pendingRows += original
                            MeasurementIngestionResult.CreatedAggregate(original)
                        } else {
                            val replayPlan = pendingReplayPlan(
                                exactReplay = true,
                                isBeforeDeadline = true,
                            )
                            MeasurementIngestionResult.UpdatedAggregate(
                                pending = existing.copy(
                                    finalizeAfter = if (replayPlan.shouldSlideDeadline) {
                                        NOW.plusSeconds(20)
                                    } else {
                                        existing.finalizeAfter
                                    },
                                ),
                                wasEnriched = false,
                                shouldScheduleFinalization =
                                    replayPlan.shouldScheduleFinalization,
                            )
                        }
                    },
                    finalizationScheduler = object : PendingFinalizationScheduler {
                        override fun enqueue(pending: PendingMeasurement) = Unit

                        override fun enqueueIfAbsent(pending: PendingMeasurement) {
                            schedulerAttempts += 1
                            if (schedulerAttempts == 1) error("scheduler unavailable")
                            uniqueWatchdogs += pendingFinalizationWorkPlan(pending, NOW).uniqueName
                        }
                    },
                ),
            )
            val direct = DirectPacketProcessingOrchestrator(
                process = { processor.process(it) },
                enqueueFallback = { fallback += it },
            )
            val packet = ScalePacket(byteArrayOf(1), "AA")

            assertEquals(DirectPacketProcessingResult.FALLBACK_ENQUEUED, direct.process(packet))
            assertEquals(1, pendingRows.size)
            assertEquals(original.finalizeAfter, pendingRows.single().finalizeAfter)
            assertEquals(1, fallback.size)

            processor.process(fallback.single())

            assertEquals(2, schedulerAttempts)
            assertEquals(1, pendingRows.size)
            assertEquals(original.finalizeAfter, pendingRows.single().finalizeAfter)
            assertEquals(setOf("finalize-pending-1"), uniqueWatchdogs)
        }

    @Test
    fun packetProcessorParsesFiltersPersistsAndSchedulesOnce() = kotlinx.coroutines.runBlocking {
        var ingested = 0
        val ensured = mutableListOf<PendingMeasurement>()
        val created = pending()
        val processor = ScalePacketProcessor(
            parse = { _, _ -> raw() },
            ingestion = MeasurementIngestionWorkOrchestrator(
                ingest = {
                    ingested += 1
                    if (ingested == 1) MeasurementIngestionResult.CreatedAggregate(created)
                    else MeasurementIngestionResult.UpdatedAggregate(
                        pending = created,
                        wasEnriched = false,
                        shouldScheduleFinalization = false,
                    )
                },
                finalizationScheduler = object : PendingFinalizationScheduler {
                    override fun enqueue(pending: PendingMeasurement) = Unit
                    override fun enqueueIfAbsent(pending: PendingMeasurement) {
                        ensured += pending
                    }
                },
            ),
        )
        val packet = ScalePacket(byteArrayOf(1), "AA")

        processor.process(packet)
        processor.process(packet)

        assertEquals(2, ingested)
        assertEquals(listOf(created), ensured)
    }

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
        assertEquals(ExistingWorkPolicy.KEEP, FINALIZATION_ENSURE_POLICY)
        assertEquals(ExistingWorkPolicy.REPLACE, FINALIZATION_RESCHEDULE_POLICY)
    }

    @Test
    fun expiredDeadlineGetsZeroDelay() {
        assertEquals(
            0L,
            pendingFinalizationWorkPlan(pending(NOW.minusMillis(1)), NOW).delayMillis,
        )
    }

    @Test
    fun onlyCreatedAndNonDueUpdatedAggregatesEnsureFinalization() =
        kotlinx.coroutines.runBlocking {
            val scheduled = mutableListOf<PendingMeasurement>()
            val ensured = mutableListOf<PendingMeasurement>()
            val scheduler = object : PendingFinalizationScheduler {
                override fun enqueue(pending: PendingMeasurement) {
                    scheduled += pending
                }

                override fun enqueueIfAbsent(pending: PendingMeasurement) {
                    ensured += pending
                }
            }
            val outcomes = listOf<MeasurementIngestionResult>(
                MeasurementIngestionResult.CreatedAggregate(pending()),
                MeasurementIngestionResult.UpdatedAggregate(pending(), wasEnriched = true),
                MeasurementIngestionResult.UpdatedAggregate(
                    pending(),
                    wasEnriched = true,
                    shouldScheduleFinalization = false,
                ),
                MeasurementIngestionResult.SuppressedFinal,
                MeasurementIngestionResult.SuppressedTombstone,
            )

            outcomes.forEach { outcome ->
                MeasurementIngestionWorkOrchestrator({ outcome }, scheduler).process(raw())
            }

            assertTrue(scheduled.isEmpty())
            assertEquals(2, ensured.size)
            assertTrue(ensured.all { it.id == PendingMeasurementId("pending-1") })
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
