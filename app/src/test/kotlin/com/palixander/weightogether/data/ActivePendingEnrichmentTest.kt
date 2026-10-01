package com.palixander.weightogether.data

import com.palixander.weightogether.core.RawScaleMeasurement
import java.time.Instant
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivePendingEnrichmentTest {
    @Test
    fun `full packet after twenty four measurement seconds enriches active pending in place`() {
        val candidate = candidate()
        val receivedAt = ENQUEUED_AT.plusSeconds(3)

        val update = requireNotNull(
            activePendingEnrichment(candidate, incoming(second = 24), receivedAt),
        )

        assertEquals(candidate.id, update.entity.id)
        assertEquals(candidate.measuredAtEpochSecond, update.entity.measuredAtEpochSecond)
        assertEquals(candidate.enqueuedAtEpochMillis, update.entity.enqueuedAtEpochMillis)
        assertEquals(candidate.provisionalAccountId, update.entity.provisionalAccountId)
        assertEquals(candidate.deduplicationHash, update.entity.deduplicationHash)
        assertEquals(520, update.entity.impedanceOhm)
        assertTrue(update.entity.hasImpedance)
        assertTrue(update.entity.isStable)
        assertArrayEquals(byteArrayOf(9, 8, 7), update.entity.rawPayload)
        assertEquals(receivedAt.plusSeconds(10).toEpochMilli(), update.entity.finalizeAfterEpochMillis)
        assertTrue(update.shouldScheduleFinalization)
    }

    @Test
    fun `due pending cannot be enriched or returned to aggregation`() {
        val candidate = candidate()

        assertNull(
            activePendingEnrichment(
                candidate = candidate,
                incoming = incoming(second = 24),
                receivedAt = Instant.ofEpochMilli(candidate.finalizeAfterEpochMillis),
            ),
        )
    }

    @Test
    fun `extended enrichment rejects unrelated packet`() {
        val candidate = candidate()
        val receivedAt = ENQUEUED_AT.plusSeconds(3)

        assertNull(
            activePendingEnrichment(
                candidate,
                incoming(second = 24, device = "11:22:33:44:55:66"),
                receivedAt,
            ),
        )
        assertNull(
            activePendingEnrichment(
                candidate,
                incoming(second = 24, rawWeight = RAW_WEIGHT + 1),
                receivedAt,
            ),
        )
        assertNull(activePendingEnrichment(candidate, incoming(second = 30), receivedAt))
    }

    private fun candidate() = PendingMeasurementEntity(
        id = "pending-original",
        deviceAddress = DEVICE,
        measuredAtEpochSecond = BASE_SECOND,
        weightKg = RAW_WEIGHT * RawScaleMeasurement.WEIGHT_RESOLUTION_KG,
        impedanceOhm = 0,
        isStable = true,
        hasImpedance = false,
        rawPayload = byteArrayOf(1),
        deduplicationHash = "original-hash",
        enqueuedAtEpochMillis = ENQUEUED_AT.toEpochMilli(),
        rawWeight = RAW_WEIGHT,
        finalizeAfterEpochMillis = ENQUEUED_AT.plusSeconds(10).toEpochMilli(),
        provisionalAccountId = "account-original",
    )

    private fun incoming(
        second: Long,
        device: String = DEVICE,
        rawWeight: Int = RAW_WEIGHT,
    ) = RawScaleMeasurement(
        deviceAddress = device,
        measuredAt = Instant.ofEpochSecond(BASE_SECOND + second),
        weightKg = rawWeight * RawScaleMeasurement.WEIGHT_RESOLUTION_KG,
        impedanceOhm = 520,
        isStable = true,
        hasImpedance = true,
        rawPayload = byteArrayOf(9, 8, 7),
        rawWeight = rawWeight,
    )

    private companion object {
        const val DEVICE = "AA:BB:CC:DD:EE:FF"
        const val RAW_WEIGHT = 14_000
        const val BASE_SECOND = 1_800_000_000L
        val ENQUEUED_AT: Instant = Instant.parse("2026-08-20T12:00:00Z")
    }
}
