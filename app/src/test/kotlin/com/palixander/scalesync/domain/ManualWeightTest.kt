package com.palixander.scalesync.domain

import java.time.Instant
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
}
