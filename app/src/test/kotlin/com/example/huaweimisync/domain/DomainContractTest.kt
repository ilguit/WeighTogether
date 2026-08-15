package com.example.huaweimisync.domain

import com.example.huaweimisync.core.RawScaleMeasurement
import com.example.huaweimisync.core.Sex
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
        assertEquals(raw.copy(rawPayload = byteArrayOf(1, 2, 3)), pending.toRawScaleMeasurement())
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
