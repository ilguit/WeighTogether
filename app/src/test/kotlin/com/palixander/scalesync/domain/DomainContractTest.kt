package com.palixander.scalesync.domain

import com.palixander.scalesync.core.RawScaleMeasurement
import com.palixander.scalesync.core.Sex
import java.io.Serializable
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainContractTest {
    @Test
    fun namesAreNormalizedWithRootCaseAndTrimmedInputIsEnforced() {
        assertEquals("alice", normalizeAccountName("  ALICE  "))
        assertThrows(IllegalArgumentException::class.java) {
            NewAccount(" Alice ", completeProfile())
        }
    }

    @Test
    fun incompleteRecoveryProfileCannotBecomeCalculatorProfile() {
        val profile = AccountProfile.IncompleteRecovery(heightCm = 180.0)

        assertFalse(profile.isComplete)
        assertNull(profile.toUserProfileOrNull())
        assertTrue(completeProfile().isComplete)
    }

    @Test
    fun weightDeltaIncludesAcceptedLimits() {
        AccountSettings(weightDeltaKg = 0.1)
        AccountSettings(weightDeltaKg = 50.0)

        assertThrows(IllegalArgumentException::class.java) {
            AccountSettings(weightDeltaKg = 0.09)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AccountSettings(weightDeltaKg = Double.NaN)
        }
    }

    @Test
    fun routingCandidatesUseDifferenceThenStableOrder() {
        val first = candidate("first", difference = 1.0, order = 0)
        val second = candidate("second", difference = 0.5, order = 2)
        val third = candidate("third", difference = 0.5, order = 1)
        val sorted = listOf(first, second, third).sortedForRouting()

        assertEquals(listOf(third, second, first), sorted)
        RoutingDecision.ChooseAccount(sorted)
        assertThrows(IllegalArgumentException::class.java) {
            RoutingDecision.ChooseAccount(listOf(first, second))
        }
    }

    @Test
    fun pendingMappingRoundTripsRawAndCopiesPayload() {
        val payload = byteArrayOf(1, 2, 3)
        val raw = RawScaleMeasurement(
            deviceAddress = "AA:BB",
            measuredAt = Instant.parse("2025-01-02T03:04:05Z"),
            weightKg = 72.5,
            impedanceOhm = 500,
            isStable = true,
            hasImpedance = true,
            rawPayload = payload,
        )
        val pending = raw.toPendingMeasurement(
            id = PendingMeasurementId("pending-1"),
            deduplicationHash = "hash-1",
            enqueuedAt = Instant.parse("2025-01-02T03:04:06Z"),
        )
        payload[0] = 9

        assertArrayEquals(byteArrayOf(1, 2, 3), pending.rawPayload)
        assertEquals(
            raw.copy(
                measuredAt = Instant.ofEpochSecond(raw.measuredAt.epochSecond),
                rawPayload = byteArrayOf(1, 2, 3),
            ),
            pending.toRawScaleMeasurement(),
        )
    }

    @Test
    fun discardUndoTokenIsAnInMemoryFullPendingSnapshot() {
        val pending = RawScaleMeasurement(
            deviceAddress = "AA:BB",
            measuredAt = Instant.parse("2025-01-02T03:04:05.123456789Z"),
            weightKg = 72.5,
            impedanceOhm = 500,
            isStable = true,
            hasImpedance = true,
            rawPayload = byteArrayOf(1, 2, 3),
        ).toPendingMeasurement(
            id = PendingMeasurementId("pending-undo"),
            deduplicationHash = "hash-undo",
            enqueuedAt = Instant.parse("2025-01-02T03:04:06Z"),
        )
        val token = PendingDiscardUndoToken(pending)

        assertEquals(0, pending.measuredAt.nano)
        assertEquals(pending, token.pending)
        assertEquals(pending.id, token.pendingId)
        assertEquals(pending.deduplicationHash, token.deduplicationHash)
        assertEquals(pending.enqueuedAt, token.enqueuedAt)
        assertFalse(Serializable::class.java.isAssignableFrom(token.javaClass))
    }

    @Test
    fun aggregatingPendingIsHiddenUntilItsFinalizationDeadline() {
        val enqueuedAt = Instant.parse("2026-08-20T10:00:00Z")
        val pending = RawScaleMeasurement(
            deviceAddress = "AA:BB",
            measuredAt = enqueuedAt,
            weightKg = 72.5,
            impedanceOhm = 0,
            isStable = true,
            hasImpedance = false,
            rawPayload = byteArrayOf(1),
        ).toPendingMeasurement(
            id = PendingMeasurementId("pending-aggregate"),
            deduplicationHash = "hash-aggregate",
            enqueuedAt = enqueuedAt,
        )

        assertFalse(pending.isAwaitingDecisionAt(pending.finalizeAfter.minusMillis(1)))
        assertTrue(pending.isAwaitingDecisionAt(pending.finalizeAfter))
    }

    @Test
    fun preliminaryLifecycleKeepsIdentityAcrossEnrichmentAndTerminalOutcomes() {
        val now = Instant.parse("2026-08-20T10:00:00Z")
        val aggregating = RawScaleMeasurement(
            deviceAddress = "AA:BB",
            measuredAt = now,
            weightKg = 72.5,
            impedanceOhm = 0,
            isStable = true,
            hasImpedance = false,
            rawPayload = byteArrayOf(1),
        ).toPendingMeasurement(
            id = PendingMeasurementId("pending-lifecycle"),
            deduplicationHash = "hash-lifecycle",
            enqueuedAt = now,
        )
        val enriched = aggregating.copy(
            impedanceOhm = 512,
            hasImpedance = true,
        )

        val aggregatingLifecycle = aggregating.lifecycleAt(now)
        val enrichedLifecycle = enriched.lifecycleAt(enriched.finalizeAfter)
        val finalized = enriched.finalizedLifecycle("measurement-row")
        val suppressed = enriched.suppressedLifecycle()

        assertTrue(aggregating.isPreliminary)
        assertEquals(PreliminaryMeasurementStage.AGGREGATING, aggregatingLifecycle.stage)
        assertEquals(PreliminaryDecisionReadiness.AGGREGATING, aggregatingLifecycle.decisionReadiness)
        assertFalse(aggregatingLifecycle.isReadyForDecision)
        assertEquals(PreliminaryMeasurementStage.ENRICHED, enrichedLifecycle.stage)
        assertEquals(
            PreliminaryDecisionReadiness.READY_FOR_DECISION,
            enrichedLifecycle.decisionReadiness,
        )
        assertTrue(enrichedLifecycle.isReadyForDecision)
        assertEquals(aggregating.id, enrichedLifecycle.presentationKey)
        assertEquals(aggregating.id, finalized.presentationKey)
        assertEquals("measurement-row", finalized.finalMeasurementId)
        assertEquals(aggregating.id, suppressed.presentationKey)
        assertNull(suppressed.finalMeasurementId)
    }

    private fun completeProfile() = AccountProfile.Complete(
        heightCm = 180.0,
        birthDate = LocalDate.of(1990, 1, 1),
        sex = Sex.MALE,
    )

    private fun candidate(id: String, difference: Double, order: Int) = RoutingCandidate(
        accountId = AccountId(id),
        differenceKg = difference,
        medianWeightKg = 70.0,
        stableOrder = order,
    )
}
