package com.example.huaweimisync.core

import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MiScalePacketParserTest {
    private val parser = MiScalePacketParser(ZoneId.of("UTC"))

    @Test
    fun parsesStableMeasurementWithImpedance() {
        val parsed = parser.parse(validPayload(), "aa:bb:cc:dd:ee:ff")

        assertNotNull(parsed)
        assertTrue(parsed.isFinal)
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
    fun unstableMeasurementIsNotFinal() {
        val payload = validPayload().also { it[1] = 0x02 }
        val parsed = parser.parse(payload, "AA:BB:CC:DD:EE:FF")

        assertNotNull(parsed)
        assertFalse(parsed.isFinal)
    }

    @Test
    fun stableWeightWithoutImpedanceIsNotFinal() {
        val payload = validPayload().also { it[1] = 0x20 }
        val parsed = parser.parse(payload, "AA:BB:CC:DD:EE:FF")

        assertNotNull(parsed)
        assertFalse(parsed.isFinal)
    }

    @Test
    fun acceptsStackPrefixAndUsesLastThirteenBytes() {
        val prefixed = byteArrayOf(0x1b, 0x18) + validPayload()
        val parsed = parser.parse(prefixed, "AA:BB:CC:DD:EE:FF")

        assertNotNull(parsed)
        assertEquals(70.0, parsed.weightKg, 0.0001)
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

