package com.palixander.scalesync.domain

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PetModelsTest {
    @Test
    fun nameNormalizationIsLocaleIndependentAndRejectsUntrimmedInput() {
        assertEquals("барсик", NewPet("Барсик", PetSpecies.CAT).normalizedName)
        assertThrows(IllegalArgumentException::class.java) {
            NewPet(" Барсик ", PetSpecies.CAT)
        }
    }

    @Test
    fun newAndUpdatedPetsRequireExplicitSupportedSpecies() {
        val id = PetId("pet")
        val update = PetUpdate(id, "Барсик", PetSpecies.DOG)

        assertEquals(PetSpecies.DOG, update.species)
        assertEquals("барсик", update.normalizedName)
        assertThrows(IllegalArgumentException::class.java) {
            NewPet("Барсик", PetSpecies.UNSPECIFIED)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PetUpdate(id, "Барсик", PetSpecies.UNSPECIFIED)
        }
    }

    @Test
    fun completedMeasurementDerivesPositivePetWeightFromEitherReadingOrder() {
        val lifted = measurement(first = 82.4, second = 78.9)
        val steppedOn = measurement(first = 78.9, second = 82.4)

        assertEquals(3.5, lifted.petWeightKg, 0.000_001)
        assertEquals(3.5, steppedOn.petWeightKg, 0.000_001)
    }

    @Test
    fun completedMeasurementRejectsNonFiniteNonPositiveAndZeroDeltaValues() {
        assertThrows(IllegalArgumentException::class.java) {
            measurement(first = Double.NaN, second = 4.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            measurement(first = 0.0, second = 4.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            measurement(first = 4.0, second = 4.0)
        }
    }

    private fun measurement(first: Double, second: Double) = PetMeasurement(
        id = "measurement",
        petId = PetId("pet"),
        measuredAt = Instant.ofEpochSecond(100),
        firstWeightKg = first,
        secondWeightKg = second,
    )
}
