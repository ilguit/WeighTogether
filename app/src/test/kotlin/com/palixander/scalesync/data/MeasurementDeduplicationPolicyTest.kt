package com.palixander.scalesync.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeasurementDeduplicationPolicyTest {
    @Test
    fun sameDeviceAndRawWeightMatchOnlyInsideStrictTenSecondWindow() {
        listOf(0L, 9L, -9L).forEach { offset ->
            assertTrue("offset=$offset", matches(offset = offset))
        }
        listOf(10L, -10L, 30L, -30L).forEach { offset ->
            assertFalse("offset=$offset", matches(offset = offset))
        }
    }

    @Test
    fun differentRawWeightNeverMatchesEvenAtSameSecond() {
        listOf(0L, 9L, 10L, 30L).forEach { offset ->
            assertFalse(
                "offset=$offset",
                matches(offset = offset, candidateRawWeight = RAW_WEIGHT + 1),
            )
        }
    }

    @Test
    fun addressComparisonIsCaseInsensitiveButDifferentDeviceNeverMatches() {
        assertTrue(matches(offset = 9, candidateDeviceAddress = DEVICE.lowercase()))
        assertFalse(matches(offset = 0, candidateDeviceAddress = "11:22:33:44:55:66"))
    }

    @Test
    fun boundsStayStrictAndSaturateAtEpochExtremes() {
        assertTrue(MeasurementDeduplicationPolicy.epochSecondBounds(100L) == 91L..109L)
        assertTrue(
            MeasurementDeduplicationPolicy.epochSecondBounds(Long.MIN_VALUE) ==
                Long.MIN_VALUE..(Long.MIN_VALUE + 9),
        )
        assertTrue(
            MeasurementDeduplicationPolicy.epochSecondBounds(Long.MAX_VALUE) ==
                (Long.MAX_VALUE - 9)..Long.MAX_VALUE,
        )
    }

    private fun matches(
        offset: Long,
        candidateRawWeight: Int = RAW_WEIGHT,
        candidateDeviceAddress: String = DEVICE,
    ): Boolean = MeasurementDeduplicationPolicy.isSameMeasurement(
        deviceAddress = DEVICE,
        rawWeight = RAW_WEIGHT,
        measuredAtEpochSecond = BASE_SECOND,
        candidateDeviceAddress = candidateDeviceAddress,
        candidateRawWeight = candidateRawWeight,
        candidateMeasuredAtEpochSecond = BASE_SECOND + offset,
    )

    private companion object {
        const val DEVICE = "AA:BB:CC:DD:EE:FF"
        const val RAW_WEIGHT = 14_000
        const val BASE_SECOND = 1_800_000_000L
    }
}
