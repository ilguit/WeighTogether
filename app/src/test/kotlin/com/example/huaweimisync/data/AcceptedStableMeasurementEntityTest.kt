package com.example.huaweimisync.data

import com.example.huaweimisync.core.RawScaleMeasurement
import java.time.Instant
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class AcceptedStableMeasurementEntityTest {
    @Test
    fun latestRoundTripsEveryRawFieldWithoutLosingTimestampPrecision() {
        val payload = byteArrayOf(0x01, 0x80.toByte(), 0xff.toByte())
        val raw = RawScaleMeasurement(
            deviceAddress = "AA:BB:CC:DD:EE:FF",
            measuredAt = Instant.ofEpochSecond(1_789_012_345L, 987_654_321L),
            weightKg = 73.125,
            rawWeight = 14_625,
            impedanceOhm = 517,
            isStable = true,
            hasImpedance = true,
            rawPayload = payload,
        )

        val entity = AcceptedStableMeasurementEntity.latest(raw)
        payload[0] = 0x7f
        val restored = entity.toRawScaleMeasurement()

        assertEquals(AcceptedStableMeasurementEntity.LATEST_ID, entity.id)
        assertEquals(raw.copy(rawPayload = byteArrayOf(0x01, 0x80.toByte(), 0xff.toByte())), restored)
        assertEquals(987_654_321, restored.measuredAt.nano)
        assertArrayEquals(byteArrayOf(0x01, 0x80.toByte(), 0xff.toByte()), restored.rawPayload)
    }
}
