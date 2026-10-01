package com.palixander.weightogether.core

import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MiScalePacketParserTest {
    private val parser = MiScalePacketParser(ZoneId.of("UTC"))

    @Test
    fun parsesStableMeasurementWithImpedanceAndUsesValidScaleTimestamp() {
        val parsed = parser.parse(validPayload(), "aa:bb:cc:dd:ee:ff")

        assertNotNull(parsed)
        assertTrue(parsed.isStableWeight)
        assertTrue(parsed.hasFullBodyComposition)
        assertEquals("AA:BB:CC:DD:EE:FF", parsed.deviceAddress)
        assertEquals(70.0, parsed.weightKg, 0.0001)
        assertEquals(500, parsed.impedanceOhm)
        assertEquals(Instant.parse("2026-08-11T12:34:56Z"), parsed.measuredAt)
    }

    @Test
    fun rejectsShortPayload() {
        assertNull(parser.parse(byteArrayOf(1, 2, 3), "AA:BB:CC:DD:EE:FF"))
    }

    @Test
    fun unstableMeasurementIsNotAccepted() {
        val payload = validPayload().also { it[1] = 0x02 }
        val parsed = parser.parse(payload, "AA:BB:CC:DD:EE:FF")

        assertNotNull(parsed)
        assertFalse(parsed.isStableWeight)
        assertFalse(parsed.hasFullBodyComposition)
    }

    @Test
    fun stableWeightWithoutImpedanceIsAcceptedWithoutComposition() {
        val payload = validPayload().also { it[1] = 0x20 }
        val parsed = parser.parse(payload, "AA:BB:CC:DD:EE:FF")

        assertNotNull(parsed)
        assertTrue(parsed.isStableWeight)
        assertFalse(parsed.hasFullBodyComposition)
    }

    @Test
    fun stableWeightWithOutOfRangeImpedanceIsAcceptedWithoutComposition() {
        val payload = validPayload().also {
            it[9] = 0x4f
            it[10] = 0x00
        }
        val parsed = parser.parse(payload, "AA:BB:CC:DD:EE:FF")

        assertNotNull(parsed)
        assertTrue(parsed.isStableWeight)
        assertTrue(parsed.hasImpedance)
        assertEquals(79, parsed.impedanceOhm)
        assertFalse(parsed.hasFullBodyComposition)
    }

    @Test
    fun stableOutOfRangeWeightIsNotAccepted() {
        val payload = validPayload().also {
            it[11] = 0x08
            it[12] = 0x07
        }
        val parsed = parser.parse(payload, "AA:BB:CC:DD:EE:FF")

        assertNotNull(parsed)
        assertEquals(9.0, parsed.weightKg, 0.0001)
        assertFalse(parsed.isStableWeight)
    }

    @Test
    fun acceptsStackPrefixAndUsesLastThirteenBytes() {
        val prefixed = byteArrayOf(0x1b, 0x18) + validPayload()
        val parsed = parser.parse(prefixed, "AA:BB:CC:DD:EE:FF")

        assertNotNull(parsed)
        assertEquals(70.0, parsed.weightKg, 0.0001)
    }

    @Test
    fun calendarAndClockErrorsFallBackToTheExactReceivedInstant() {
        val receivedAt = Instant.parse("2026-08-11T12:34:56.123456789Z")
        val invalidDates = listOf(
            validPayload().also {
                it[4] = 2
                it[5] = 29
            },
            validPayload().also {
                it[4] = 4
                it[5] = 31
            },
            validPayload().also { it[4] = 0 },
            validPayload().also { it[4] = 13 },
            validPayload().also { it[5] = 0 },
            validPayload().also { it[6] = 24 },
            validPayload().also { it[7] = 60 },
            validPayload().also { it[8] = 60 },
        )

        for (payload in invalidDates) {
            val parsed = assertNotNull(parser.parse(payload, "AA:BB:CC:DD:EE:FF", receivedAt))
            assertEquals(receivedAt, parsed.measuredAt, payload.contentToString())
            assertTrue(parsed.hasFullBodyComposition)
        }
    }

    @Test
    fun scaleTimeUsesTheExplicitTimeZoneAndAcceptsLeapDay() {
        val localParser = MiScalePacketParser(ZoneId.of("Asia/Yekaterinburg"))
        val payload = validPayload().also {
            it[2] = 0xe8.toByte() // 2024
            it[4] = 2
            it[5] = 29
        }

        val parsed = assertNotNull(localParser.parse(payload, "AA:BB:CC:DD:EE:FF"))

        assertEquals(Instant.parse("2024-02-29T07:34:56Z"), parsed.measuredAt)
    }

    @Test
    fun retainedPayloadIsAnIndependentCopyOfTheLastThirteenBytes() {
        val expected = validPayload()
        for (prefix in listOf(byteArrayOf(), byteArrayOf(0x1b, 0x18))) {
            val advertised = prefix + expected
            val parsed = assertNotNull(parser.parse(advertised, "AA:BB:CC:DD:EE:FF"))

            assertContentEquals(expected, parsed.rawPayload)
            advertised.fill(0)
            assertContentEquals(expected, parsed.rawPayload)
            parsed.rawPayload.fill(1)
            assertContentEquals(ByteArray(advertised.size), advertised)
        }
    }

    @Test
    fun stableWeightEligibilityIncludesBothEndpointsOnly() {
        val cases = listOf(
            1_999 to false,
            2_000 to true,
            60_000 to true,
            60_001 to false,
        )
        for ((rawWeight, accepted) in cases) {
            val payload = validPayload().also {
                it[11] = rawWeight.toByte()
                it[12] = (rawWeight ushr 8).toByte()
            }
            val parsed = assertNotNull(parser.parse(payload, "AA:BB:CC:DD:EE:FF"))

            assertEquals(rawWeight, parsed.rawWeight)
            assertEquals(accepted, parsed.isStableWeight, "rawWeight=$rawWeight")
            assertEquals(accepted, parsed.hasFullBodyComposition, "rawWeight=$rawWeight")
        }
    }

    @Test
    fun compositionEligibilityIncludesBothImpedanceEndpointsOnly() {
        val cases = listOf(0 to false, 79 to false, 80 to true, 3_000 to true, 3_001 to false)
        for ((impedance, accepted) in cases) {
            val payload = validPayload().also {
                it[9] = impedance.toByte()
                it[10] = (impedance ushr 8).toByte()
            }
            val parsed = assertNotNull(parser.parse(payload, "AA:BB:CC:DD:EE:FF"))

            assertEquals(impedance, parsed.impedanceOhm)
            assertTrue(parsed.isStableWeight)
            assertEquals(impedance != 0, parsed.hasImpedance, "impedance=$impedance")
            assertEquals(accepted, parsed.hasFullBodyComposition, "impedance=$impedance")
        }
    }

    @Test
    fun invalidScaleTimePreservesReceivedAtNanoseconds() {
        val receivedAt = Instant.parse("2026-08-11T12:34:56.123456789Z")
        val payload = validPayload().also {
            it[2] = 0
            it[3] = 0
        }

        val parsed = parser.parse(payload, "AA:BB:CC:DD:EE:FF", receivedAt)

        assertNotNull(parsed)
        assertEquals(receivedAt, parsed.measuredAt)
    }

    private fun validPayload(): ByteArray = byteArrayOf(
        0x00,
        0x22,
        0xea.toByte(), 0x07,
        0x08,
        0x0b,
        0x0c,
        0x22,
        0x38,
        0xf4.toByte(), 0x01,
        0xb0.toByte(), 0x36,
    )
}
