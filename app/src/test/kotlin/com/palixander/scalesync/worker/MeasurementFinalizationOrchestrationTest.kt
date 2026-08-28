package com.palixander.scalesync.worker

import androidx.work.ExistingWorkPolicy
import com.palixander.scalesync.core.RawScaleMeasurement
import com.palixander.scalesync.data.MeasurementIngestionResult
import com.palixander.scalesync.data.pendingReplayPlan
import com.palixander.scalesync.domain.PendingMeasurement
import com.palixander.scalesync.domain.PendingMeasurementId
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun activePetSessionAutomaticallyQuarantinesHumanPipelinePacketReplay() =
        kotlinx.coroutines.runBlocking {
            val gate = PetMeasurementIngestionGate()
            var ingested = 0
            var finalized = 0
            val processor = ScalePacketProcessor(
                parse = { _, _ -> raw() },
                ingestion = MeasurementIngestionWorkOrchestrator(
                    ingest = {
                        ingested += 1
                        MeasurementIngestionResult.CreatedAggregate(pending())
                    },
                    finalizationScheduler = object : PendingFinalizationScheduler {
                        override fun enqueue(pending: PendingMeasurement) = Unit
                        override fun enqueueIfAbsent(pending: PendingMeasurement) {
                            finalized += 1
                        }
                    },
                ),
                petMeasurementGate = gate,
            )
            val packet = ScalePacket(byteArrayOf(1), "AA")

            val lease = gate.activate()
            assertEquals(MeasurementIngestionResult.IgnoredNotFinal, processor.process(packet))
            assertEquals(0, ingested)

            lease.release()
            assertEquals(MeasurementIngestionResult.IgnoredNotFinal, processor.process(packet))
            assertEquals(0, ingested)
            assertEquals(0, finalized)

            assertTrue(
                processor.process(ScalePacket(byteArrayOf(2), "AA")) is
                    MeasurementIngestionResult.CreatedAggregate,
            )
            assertEquals(1, ingested)
            assertEquals(1, finalized)
        }

    @Test
    fun automaticPetQuarantineUsesParserNormalizedPayloadWithoutExternalSideEffects() =
        kotlinx.coroutines.runBlocking {
            val gate = PetMeasurementIngestionGate()
            var parsed = 0
            var ingested = 0
            var finalized = 0
            val processor = ScalePacketProcessor(
                parse = { _, _ ->
                    parsed += 1
                    raw()
                },
                ingestion = MeasurementIngestionWorkOrchestrator(
                    ingest = {
                        ingested += 1
                        MeasurementIngestionResult.CreatedAggregate(pending())
                    },
                    finalizationScheduler = object : PendingFinalizationScheduler {
                        override fun enqueue(pending: PendingMeasurement) = Unit
                        override fun enqueueIfAbsent(pending: PendingMeasurement) {
                            finalized += 1
                        }
                    },
                ),
                petMeasurementGate = gate,
            )
            val logicalPayload = ByteArray(13) { it.toByte() }
            val activePacket = ScalePacket(byteArrayOf(99) + logicalPayload, " aa:bb ")
            val replay = ScalePacket(byteArrayOf(42, 43) + logicalPayload, "AA:BB")

            val lease = gate.activate()
            assertEquals(MeasurementIngestionResult.IgnoredNotFinal, processor.process(activePacket))
            lease.release()
            assertEquals(MeasurementIngestionResult.IgnoredNotFinal, processor.process(replay))

            assertEquals(0, parsed)
            assertEquals(0, ingested)
            assertEquals(0, finalized)
        }

    @Test
    fun registeredPetPacketsRemainSuppressedAfterReleaseWithoutFinalizationSideEffects() =
        kotlinx.coroutines.runBlocking {
            var now = 1_000L
            val gate = PetMeasurementIngestionGate { now }
            var ingested = 0
            var finalized = 0
            val processor = ScalePacketProcessor(
                parse = { _, _ -> raw() },
                ingestion = MeasurementIngestionWorkOrchestrator(
                    ingest = {
                        ingested += 1
                        MeasurementIngestionResult.CreatedAggregate(pending())
                    },
                    finalizationScheduler = object : PendingFinalizationScheduler {
                        override fun enqueue(pending: PendingMeasurement) = Unit
                        override fun enqueueIfAbsent(pending: PendingMeasurement) {
                            finalized += 1
                        }
                    },
                ),
                petMeasurementGate = gate,
            )
            val first = ScalePacket(byteArrayOf(1, 2), "aa:bb")
            val second = ScalePacket(byteArrayOf(3, 4), "AA:BB")

            val lease = gate.activate()
            lease.registerPetPacket("AA:BB", first.payload)
            lease.registerPetPacket("aa:bb", second.payload)
            lease.release()

            assertEquals(MeasurementIngestionResult.IgnoredNotFinal, processor.process(first))
            assertEquals(MeasurementIngestionResult.IgnoredNotFinal, processor.process(second))
            assertEquals(0, ingested)
            assertEquals(0, finalized)

            assertTrue(
                processor.process(ScalePacket(byteArrayOf(5), "AA:BB")) is
                    MeasurementIngestionResult.CreatedAggregate,
            )
            assertEquals(1, ingested)
            assertEquals(1, finalized)
        }

    @Test
    fun petPacketQuarantineExpires() = kotlinx.coroutines.runBlocking {
        var now = 10L
        val gate = PetMeasurementIngestionGate { now }
        val packet = ScalePacket(byteArrayOf(1), "AA")
        val lease = gate.activate()
        lease.registerPetPacket(packet.deviceAddress, packet.payload)
        lease.release()

        assertEquals(null, gate.processPacketWhenInactive(packet) { "processed" })
        now += PET_PACKET_QUARANTINE_TTL_NANOS
        assertEquals("processed", gate.processPacketWhenInactive(packet) { "processed" })
    }

    @Test
    fun latePetPacketRegistrationAfterReleaseIsQuarantined() = kotlinx.coroutines.runBlocking {
        var now = 10L
        val gate = PetMeasurementIngestionGate { now }
        val packet = ScalePacket(byteArrayOf(1, 2), "AA")
        val lease = gate.activate()

        lease.release()
        now += 1L
        lease.registerPetPacket(packet.deviceAddress, packet.payload)

        assertEquals(null, gate.processPacketWhenInactive(packet) { "processed" })
        assertEquals(
            "distinct processed",
            gate.processPacketWhenInactive(ScalePacket(byteArrayOf(3), "AA")) {
                "distinct processed"
            },
        )
    }

    @Test
    fun expiredOrStalePetSessionRegistrarCannotQuarantinePackets() =
        kotlinx.coroutines.runBlocking {
            var now = 10L
            val gate = PetMeasurementIngestionGate { now }
            val expiredPacket = ScalePacket(byteArrayOf(1), "AA")
            val expiredLease = gate.activate()
            expiredLease.release()
            now += PET_PACKET_QUARANTINE_TTL_NANOS
            expiredLease.registerPetPacket(expiredPacket.deviceAddress, expiredPacket.payload)
            assertEquals(
                "expired processed",
                gate.processPacketWhenInactive(expiredPacket) { "expired processed" },
            )

            val stalePacket = ScalePacket(byteArrayOf(2), "AA")
            val staleLease = gate.activate()
            staleLease.release()
            val currentLease = gate.activate()
            staleLease.registerPetPacket(stalePacket.deviceAddress, stalePacket.payload)
            currentLease.release()
            assertEquals(
                "stale processed",
                gate.processPacketWhenInactive(stalePacket) { "stale processed" },
            )
        }

    @Test
    fun petPacketQuarantineDropsOldestIdentityAtCapacity() = kotlinx.coroutines.runBlocking {
        val gate = PetMeasurementIngestionGate { 10L }
        val lease = gate.activate()
        repeat(PET_PACKET_QUARANTINE_MAX_IDENTITIES + 1) { value ->
            lease.registerPetPacket("AA", byteArrayOf(value.toByte()))
        }
        lease.release()

        assertEquals(
            "oldest passed",
            gate.processPacketWhenInactive(ScalePacket(byteArrayOf(0), "AA")) {
                "oldest passed"
            },
        )
        assertEquals(
            null,
            gate.processPacketWhenInactive(
                ScalePacket(byteArrayOf(PET_PACKET_QUARANTINE_MAX_IDENTITIES.toByte()), "AA"),
            ) { "processed" },
        )
    }

    @Test
    fun acceptedStablePacketsSurviveActiveSessionNoiseOverflowWithoutSideEffects() =
        kotlinx.coroutines.runBlocking {
            val gate = PetMeasurementIngestionGate { 10L }
            var ingested = 0
            var finalized = 0
            val processor = ScalePacketProcessor(
                parse = { _, _ -> raw() },
                ingestion = MeasurementIngestionWorkOrchestrator(
                    ingest = {
                        ingested += 1
                        MeasurementIngestionResult.CreatedAggregate(pending())
                    },
                    finalizationScheduler = object : PendingFinalizationScheduler {
                        override fun enqueue(pending: PendingMeasurement) = Unit
                        override fun enqueueIfAbsent(pending: PendingMeasurement) {
                            finalized += 1
                        }
                    },
                ),
                petMeasurementGate = gate,
            )
            val first = ScalePacket(ByteArray(13) { (it + 40).toByte() }, "AA")
            val second = ScalePacket(ByteArray(13) { (it + 80).toByte() }, "AA")
            val lease = gate.activate()
            lease.registerPetPacket(first.deviceAddress, first.payload)
            lease.protectPetPacket(first.deviceAddress, first.payload.toHexIdentity())
            lease.registerPetPacket(second.deviceAddress, second.payload)
            lease.protectPetPacket(second.deviceAddress, second.payload.toHexIdentity())
            repeat(PET_PACKET_QUARANTINE_MAX_IDENTITIES + 8) { value ->
                lease.registerPetPacket("AA", ByteArray(13) { (value + it).toByte() })
            }
            lease.release()

            assertEquals(MeasurementIngestionResult.IgnoredNotFinal, processor.process(first))
            assertEquals(MeasurementIngestionResult.IgnoredNotFinal, processor.process(second))
            assertEquals(0, ingested)
            assertEquals(0, finalized)
        }

    @Test
    fun acceptedStableReadingPayloadAndTimestampVariantsAreSuppressedButDistinctMeasurementPasses() =
        kotlinx.coroutines.runBlocking {
            val gate = PetMeasurementIngestionGate { 10L }
            val ingested = mutableListOf<RawScaleMeasurement>()
            val first = raw(measuredAt = NOW, rawWeight = 14_000)
            val second = raw(measuredAt = NOW.plusSeconds(5), rawWeight = 15_000)
            val distinctTime = first.copy(measuredAt = NOW.plusSeconds(1))
            val distinctWeight = first.copy(rawWeight = 14_001)
            val distinctAddress = first.copy(deviceAddress = "AA:BB:CC:DD:EE:00")
            var finalized = 0
            val processor = ScalePacketProcessor(
                parse = { payload, _ ->
                    when (payload.first().toInt()) {
                        1, 2 -> first.copy(rawPayload = payload)
                        3, 4 -> second.copy(rawPayload = payload)
                        5 -> distinctTime.copy(rawPayload = payload)
                        6 -> distinctWeight.copy(rawPayload = payload)
                        else -> distinctAddress.copy(rawPayload = payload)
                    }
                },
                ingestion = MeasurementIngestionWorkOrchestrator(
                    ingest = {
                        ingested += it
                        MeasurementIngestionResult.CreatedAggregate(pending())
                    },
                    finalizationScheduler = object : PendingFinalizationScheduler {
                        override fun enqueue(pending: PendingMeasurement) = Unit
                        override fun enqueueIfAbsent(pending: PendingMeasurement) {
                            finalized += 1
                        }
                    },
                ),
                petMeasurementGate = gate,
            )
            val lease = gate.activate()
            lease.protectPetReading(
                first.deviceAddress,
                byteArrayOf(1).toHexIdentity(),
                first.measuredAt,
                first.rawWeight,
            )
            lease.protectPetReading(
                second.deviceAddress,
                byteArrayOf(3).toHexIdentity(),
                second.measuredAt,
                second.rawWeight,
            )
            lease.release()

            assertEquals(
                MeasurementIngestionResult.IgnoredNotFinal,
                processor.process(ScalePacket(byteArrayOf(2), first.deviceAddress)),
            )
            assertEquals(
                MeasurementIngestionResult.IgnoredNotFinal,
                processor.process(ScalePacket(byteArrayOf(4), second.deviceAddress)),
            )
            assertEquals(
                MeasurementIngestionResult.IgnoredNotFinal,
                processor.process(ScalePacket(byteArrayOf(5), distinctTime.deviceAddress)),
            )
            assertTrue(
                processor.process(ScalePacket(byteArrayOf(6), distinctWeight.deviceAddress)) is
                    MeasurementIngestionResult.CreatedAggregate,
            )
            assertTrue(
                processor.process(ScalePacket(byteArrayOf(7), distinctAddress.deviceAddress)) is
                    MeasurementIngestionResult.CreatedAggregate,
            )
            assertEquals(
                listOf(
                    distinctWeight.copy(rawPayload = byteArrayOf(6)),
                    distinctAddress.copy(rawPayload = byteArrayOf(7)),
                ),
                ingested,
            )
            assertEquals(2, finalized)
        }

    @Test
    fun acceptedStableReadingSemanticQuarantineHonorsTtlAndSessionOwnership() =
        kotlinx.coroutines.runBlocking {
            var now = 10L
            val gate = PetMeasurementIngestionGate { now }
            val reading = raw()
            val active = gate.activate()
            active.protectPetReading(
                reading.deviceAddress,
                byteArrayOf(1).toHexIdentity(),
                reading.measuredAt,
                reading.rawWeight,
            )
            active.release()

            assertTrue(gate.isQuarantinedPetReading(reading.copy(measuredAt = NOW.plusSeconds(30))))
            now += PET_PACKET_QUARANTINE_TTL_NANOS
            assertFalse(gate.isQuarantinedPetReading(reading.copy(measuredAt = NOW.plusSeconds(30))))

            val stale = gate.activate()
            stale.release()
            val current = gate.activate()
            stale.protectPetReading(
                reading.deviceAddress,
                byteArrayOf(2).toHexIdentity(),
                reading.measuredAt,
                reading.rawWeight,
            )
            current.release()
            assertFalse(gate.isQuarantinedPetReading(reading))
        }

    @Test
    fun staleSessionCannotProtectPacketInCurrentSession() = kotlinx.coroutines.runBlocking {
        val gate = PetMeasurementIngestionGate { 10L }
        val stale = gate.activate()
        stale.release()
        val current = gate.activate()
        val packet = ScalePacket(ByteArray(13) { it.toByte() }, "AA")
        stale.protectPetPacket(packet.deviceAddress, packet.payload.toHexIdentity())
        current.release()

        assertEquals("processed", gate.processPacketWhenInactive(packet) { "processed" })
    }

    @Test
    fun petSessionWaitsForInFlightIngestionAndThenExcludesPacketProcessing() =
        kotlinx.coroutines.runBlocking {
            val gate = PetMeasurementIngestionGate()
            val ingestionStarted = CompletableDeferred<Unit>()
            val finishIngestion = CompletableDeferred<Unit>()
            val processor = ScalePacketProcessor(
                parse = { _, _ -> raw() },
                ingestion = MeasurementIngestionWorkOrchestrator(
                    ingest = {
                        ingestionStarted.complete(Unit)
                        finishIngestion.await()
                        MeasurementIngestionResult.CreatedAggregate(pending())
                    },
                    finalizationScheduler = object : PendingFinalizationScheduler {
                        override fun enqueue(pending: PendingMeasurement) = Unit
                        override fun enqueueIfAbsent(pending: PendingMeasurement) = Unit
                    },
                ),
                petMeasurementGate = gate,
            )

            val processing = async { processor.process(ScalePacket(byteArrayOf(1), "AA")) }
            ingestionStarted.await()
            val lease = async { gate.activate() }
            assertFalse(lease.isCompleted)

            finishIngestion.complete(Unit)
            assertTrue(processing.await() is MeasurementIngestionResult.CreatedAggregate)
            val activeLease = lease.await()
            assertEquals(
                MeasurementIngestionResult.IgnoredNotFinal,
                processor.process(ScalePacket(byteArrayOf(2), "AA")),
            )

            activeLease.release()
            assertTrue(
                processor.process(ScalePacket(byteArrayOf(3), "AA")) is
                    MeasurementIngestionResult.CreatedAggregate,
            )
        }

    @Test
    fun concurrentPetSessionActivationWaitsForFirstLeaseAndThenSucceeds() =
        kotlinx.coroutines.runBlocking {
            val gate = PetMeasurementIngestionGate()
            val processingStarted = CompletableDeferred<Unit>()
            val finishProcessing = CompletableDeferred<Unit>()
            val processing = async {
                gate.processWhenInactive {
                    processingStarted.complete(Unit)
                    finishProcessing.await()
                }
            }
            processingStarted.await()

            val firstActivation = async(start = CoroutineStart.UNDISPATCHED) { gate.activate() }
            val secondActivation = async(start = CoroutineStart.UNDISPATCHED) { gate.activate() }
            assertFalse(firstActivation.isCompleted)
            assertFalse(secondActivation.isCompleted)

            finishProcessing.complete(Unit)
            processing.await()
            val firstLease = firstActivation.await()
            assertFalse(secondActivation.isCompleted)

            firstLease.release()
            secondActivation.await().release()
        }

    @Test
    fun cancelledPetSessionActivationFreesOwnershipForNextActivation() =
        kotlinx.coroutines.runBlocking {
            val gate = PetMeasurementIngestionGate()
            val processingStarted = CompletableDeferred<Unit>()
            val finishProcessing = CompletableDeferred<Unit>()
            val processing = async {
                gate.processWhenInactive {
                    processingStarted.complete(Unit)
                    finishProcessing.await()
                }
            }
            processingStarted.await()

            val cancelledActivation = async(start = CoroutineStart.UNDISPATCHED) { gate.activate() }
            assertFalse(cancelledActivation.isCompleted)
            cancelledActivation.cancelAndJoin()

            val nextActivation = async(start = CoroutineStart.UNDISPATCHED) { gate.activate() }
            assertFalse(nextActivation.isCompleted)
            finishProcessing.complete(Unit)
            processing.await()
            nextActivation.await().release()
        }

    @Test
    fun staleAndDoubleLeaseReleaseDoesNotDeactivateNewPetSession() =
        kotlinx.coroutines.runBlocking {
            val gate = PetMeasurementIngestionGate()
            val firstLease = gate.activate()
            firstLease.release()
            firstLease.release()

            val secondLease = gate.activate()
            firstLease.release()
            assertEquals(null, gate.processWhenInactive { "processed" })

            secondLease.release()
            assertEquals("processed", gate.processWhenInactive { "processed" })
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
    fun exactReplayDoesNotScheduleFinalization() = kotlinx.coroutines.runBlocking {
        val ensured = mutableListOf<PendingMeasurement>()
        val scheduler = object : PendingFinalizationScheduler {
            override fun enqueue(pending: PendingMeasurement) = Unit
            override fun enqueueIfAbsent(pending: PendingMeasurement) {
                ensured += pending
            }
        }

        assertEquals(
            MeasurementIngestionResult.ExactReplay,
            MeasurementIngestionWorkOrchestrator(
                ingest = { MeasurementIngestionResult.ExactReplay },
                finalizationScheduler = scheduler,
            ).process(raw()),
        )
        assertTrue(ensured.isEmpty())
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
                MeasurementIngestionResult.ExactReplay,
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

private fun raw(
    measuredAt: Instant = NOW,
    rawWeight: Int = 14_000,
) = RawScaleMeasurement(
    deviceAddress = "AA:BB:CC:DD:EE:FF",
    measuredAt = measuredAt,
    weightKg = rawWeight * RawScaleMeasurement.WEIGHT_RESOLUTION_KG,
    impedanceOhm = 500,
    isStable = true,
    hasImpedance = true,
    rawPayload = byteArrayOf(1, 2, 3),
    rawWeight = rawWeight,
)

private fun ByteArray.toHexIdentity(): String =
    joinToString("") { "%02x".format(it.toInt() and 0xff) }

private val NOW = Instant.parse("2026-08-20T10:00:00Z")
