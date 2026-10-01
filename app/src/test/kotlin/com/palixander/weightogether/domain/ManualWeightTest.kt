package com.palixander.weightogether.domain

import java.time.Instant
import java.util.Locale
import com.palixander.weightogether.measurements.formatWeight
import org.junit.Assert.*
import org.junit.Test

class ManualWeightTest {
    @Test fun acceptsThreeDecimalsWithoutHardwareRange() {
        listOf("0,001" to 0.001, "4.125" to 4.125, "400" to 400.0).forEach { (text, expected) ->
            assertEquals(expected, requireNotNull(parseManualWeight(text)), 0.0)
        }
    }

    @Test fun rejectsExcessPrecisionAndNonDecimalInput() {
        listOf("0", "-4", "NaN", "Infinity", "1e3", "1.0000", "1,2.3", "", "1.").forEach {
            assertNull(it, parseManualWeight(it))
        }
        assertFalse(isValidManualWeight(4.1251))
        assertFalse(isValidManualWeight(Double.POSITIVE_INFINITY))
    }

    @Test fun directPetWeightHasNoFabricatedReadings() {
        val direct = PetMeasurement("id", PetId("pet"), Instant.EPOCH, null, null, 4.125, MeasurementOrigin.MANUAL)
        assertEquals(4.125, direct.petWeightKg, 0.0)
        assertNull(direct.firstWeightKg)
        assertThrows(IllegalArgumentException::class.java) {
            direct.copy(firstWeightKg = 70.0, secondWeightKg = 74.125)
        }
        assertThrows(IllegalArgumentException::class.java) { direct.copy(origin = MeasurementOrigin.SCALE) }
        assertEquals(4.125, PetMeasurement("scale", PetId("pet"), Instant.EPOCH, 70.0, 74.125).petWeightKg, 0.0)
    }

    @Test fun duplicatePrecisionMatchesDisplayAcrossBinaryAndDecimalTies() {
        val values = listOf(4.0025, 4.0035, 4.0625, 4.0635, 4.0645, 4.0655, 10.040000000000001)
            .flatMap { listOf(Math.nextDown(it), it, Math.nextUp(it)) }
        for (value in values) {
            val displayed = formatWeight(value, Locale.US).toDouble()
            assertEquals("value=$value", displayed, canonicalManualWeight(value), 0.0)
            assertEquals(formatWeight(value, Locale.forLanguageTag("ru")),
                formatWeight(canonicalManualWeight(value), Locale.forLanguageTag("ru")))
        }
        assertEquals(4.062, canonicalManualWeight(Math.nextDown(4.0625)), 0.0)
        assertEquals(4.063, canonicalManualWeight(Math.nextUp(4.0625)), 0.0)
        assertNotEquals(canonicalManualWeight(4.062), canonicalManualWeight(4.063))
    }

    @Test fun canonicalManualWeightRoundsFloatArtifactsToDisplayedPrecision() {
        assertEquals(4.12, canonicalManualWeight(4.1200000000000045), 0.0)
        assertEquals(4.121, canonicalManualWeight(4.1209999999999996), 0.0)
        assertEquals(10.04, canonicalManualWeight(10.040000000000001), 0.0)
        assertEquals(4.062, canonicalManualWeight(4.0625), 0.0)
        assertEquals(2.5, canonicalManualWeight(2.5), 0.0)
    }
}
