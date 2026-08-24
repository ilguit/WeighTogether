package com.example.huaweimisync.data

import com.example.huaweimisync.core.RawScaleMeasurement
import java.time.Instant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeasurementEnrichmentPolicyTest {
    @Test
    fun `complete packet enriches matching incomplete candidate after 24 seconds`() {
        assertTrue(canEnrich(delaySeconds = 24L))
    }

    @Test
    fun `strict thirty second window accepts zero through twenty nine seconds only`() {
        listOf(0L, 1L, 24L, 29L).forEach { delay ->
            assertTrue("delay=$delay", canEnrich(delaySeconds = delay))
        }
        listOf(30L, 31L, 60L).forEach { delay ->
            assertFalse("delay=$delay", canEnrich(delaySeconds = delay))
        }
    }

    @Test
    fun `candidate must precede incoming packet`() {
        assertFalse(canEnrich(delaySeconds = -1L))
        assertFalse(canEnrich(delaySeconds = -29L))
    }

    @Test
    fun `candidate bounds express strict preceding window and saturate`() {
        assertTrue(
            MeasurementEnrichmentPolicy.candidateEpochSecondBounds(BASE_SECOND) ==
                (BASE_SECOND - 29L)..BASE_SECOND,
        )
        assertTrue(
            MeasurementEnrichmentPolicy.candidateEpochSecondBounds(Long.MIN_VALUE) ==
                Long.MIN_VALUE..Long.MIN_VALUE,
        )
    }

    @Test
    fun `device address must match but comparison is case insensitive`() {
        assertTrue(
            canEnrich(
                delaySeconds = 24L,
                candidateDeviceAddress = DEVICE.lowercase(),
            ),
        )
        assertFalse(
            canEnrich(
                delaySeconds = 24L,
                candidateDeviceAddress = "11:22:33:44:55:66",
            ),
        )
    }

    @Test
    fun `raw weight must match exactly`() {
        assertFalse(canEnrich(delaySeconds = 24L, candidateRawWeight = RAW_WEIGHT - 1))
        assertFalse(canEnrich(delaySeconds = 24L, candidateRawWeight = RAW_WEIGHT + 1))
    }

    @Test
    fun `only incomplete to complete transition is eligible`() {
        assertFalse(
            MeasurementEnrichmentPolicy.canEnrich(
                candidate = completeMeasurement(BASE_SECOND - 24L),
                incoming = completeMeasurement(BASE_SECOND),
            ),
        )
        assertFalse(
            MeasurementEnrichmentPolicy.canEnrich(
                candidate = incompleteMeasurement(BASE_SECOND - 24L),
                incoming = incompleteMeasurement(BASE_SECOND),
            ),
        )
        assertFalse(
            MeasurementEnrichmentPolicy.canEnrich(
                candidate = incompleteMeasurement(BASE_SECOND - 24L),
                incoming = unstableMeasurement(BASE_SECOND),
            ),
        )
        assertFalse(
            MeasurementEnrichmentPolicy.canEnrich(
                candidate = unstableMeasurement(BASE_SECOND - 24L),
                incoming = completeMeasurement(BASE_SECOND),
            ),
        )
    }

    private fun canEnrich(
        delaySeconds: Long,
        candidateDeviceAddress: String = DEVICE,
        candidateRawWeight: Int = RAW_WEIGHT,
    ): Boolean = MeasurementEnrichmentPolicy.canEnrich(
        candidate = incompleteMeasurement(
            epochSecond = BASE_SECOND - delaySeconds,
            deviceAddress = candidateDeviceAddress,
            rawWeight = candidateRawWeight,
        ),
        incoming = completeMeasurement(BASE_SECOND),
    )

    private fun incompleteMeasurement(
        epochSecond: Long,
        deviceAddress: String = DEVICE,
        rawWeight: Int = RAW_WEIGHT,
    ) = measurement(
        epochSecond = epochSecond,
        deviceAddress = deviceAddress,
        rawWeight = rawWeight,
        impedanceOhm = 0,
        isStable = true,
        hasImpedance = false,
    )

    private fun completeMeasurement(epochSecond: Long) = measurement(
        epochSecond = epochSecond,
        impedanceOhm = 520,
        isStable = true,
        hasImpedance = true,
    )

    private fun unstableMeasurement(epochSecond: Long) = measurement(
        epochSecond = epochSecond,
        impedanceOhm = 0,
        isStable = false,
        hasImpedance = false,
    )

    private fun measurement(
        epochSecond: Long,
        deviceAddress: String = DEVICE,
        rawWeight: Int = RAW_WEIGHT,
        impedanceOhm: Int,
        isStable: Boolean,
        hasImpedance: Boolean,
    ) = RawScaleMeasurement(
        deviceAddress = deviceAddress,
        measuredAt = Instant.ofEpochSecond(epochSecond),
        weightKg = rawWeight * RawScaleMeasurement.WEIGHT_RESOLUTION_KG,
        impedanceOhm = impedanceOhm,
        isStable = isStable,
        hasImpedance = hasImpedance,
        rawPayload = byteArrayOf(),
        rawWeight = rawWeight,
    )

    private companion object {
        const val DEVICE = "AA:BB:CC:DD:EE:FF"
        const val RAW_WEIGHT = 14_000
        const val BASE_SECOND = 1_800_000_000L
    }
}
