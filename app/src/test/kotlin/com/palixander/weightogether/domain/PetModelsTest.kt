package com.palixander.weightogether.domain

import com.palixander.weightogether.domain.reference.DogAdultWeightCategory
import java.time.Instant
import java.time.LocalDate
import java.time.Year
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class PetModelsTest {
    @Test
    fun breedIdRequiresAStableTrimmedNonBlankValue() {
        assertEquals("fci-195", BreedId("fci-195").value)
        assertThrows(IllegalArgumentException::class.java) { BreedId("") }
        assertThrows(IllegalArgumentException::class.java) { BreedId("   ") }
        assertThrows(IllegalArgumentException::class.java) { BreedId(" fci-195 ") }
    }

    @Test
    fun partialBirthDateKeepsItsDeclaredPrecision() {
        assertEquals(
            BirthDatePrecision.YEAR,
            PartialBirthDate.Year(Year.of(2020)).precision,
        )
        assertEquals(
            BirthDatePrecision.MONTH,
            PartialBirthDate.Month(YearMonth.of(2020, 2)).precision,
        )
        assertEquals(
            BirthDatePrecision.DAY,
            PartialBirthDate.Day(LocalDate.of(2020, 2, 29)).precision,
        )
    }

    @Test
    fun exactBirthDateProducesEqualAgeBounds() {
        val interval = PartialBirthDate.Day(LocalDate.of(2020, 2, 29))
            .ageAt(LocalDate.of(2024, 2, 29))

        assertEquals(LocalDate.of(2020, 2, 29), interval.earliestBirthDate)
        assertEquals(interval.earliestBirthDate, interval.latestBirthDate)
        assertEquals(1_461, interval.minimumDays)
        assertEquals(interval.minimumDays, interval.maximumDays)
        assertEquals(208, interval.minimumWeeks)
        assertEquals(48, interval.minimumMonths)
        assertEquals(4, interval.minimumYears)
    }

    @Test
    fun monthPrecisionProducesCalendarAgeBoundsAcrossDifferentMonthLengths() {
        val interval = PartialBirthDate.Month(YearMonth.of(2020, 2))
            .ageAt(LocalDate.of(2021, 3, 15))

        assertEquals(LocalDate.of(2020, 2, 1), interval.earliestBirthDate)
        assertEquals(LocalDate.of(2020, 2, 29), interval.latestBirthDate)
        assertEquals(380, interval.minimumDays)
        assertEquals(408, interval.maximumDays)
        assertEquals(54, interval.minimumWeeks)
        assertEquals(58, interval.maximumWeeks)
        assertEquals(12, interval.minimumMonths)
        assertEquals(13, interval.maximumMonths)
        assertEquals(1, interval.minimumYears)
        assertEquals(1, interval.maximumYears)
    }

    @Test
    fun yearPrecisionProducesCalendarAgeBounds() {
        val interval = PartialBirthDate.Year(Year.of(2020))
            .ageAt(LocalDate.of(2024, 6, 15))

        assertEquals(LocalDate.of(2020, 1, 1), interval.earliestBirthDate)
        assertEquals(LocalDate.of(2020, 12, 31), interval.latestBirthDate)
        assertEquals(41, interval.minimumMonths)
        assertEquals(53, interval.maximumMonths)
        assertEquals(3, interval.minimumYears)
        assertEquals(4, interval.maximumYears)
    }

    @Test
    fun currentPartialPeriodIsClampedToReferenceDate() {
        val referenceDate = LocalDate.of(2024, 6, 15)

        val year = PartialBirthDate.Year(Year.of(2024)).ageAt(referenceDate)
        val month = PartialBirthDate.Month(YearMonth.of(2024, 6)).ageAt(referenceDate)

        assertEquals(referenceDate, year.latestBirthDate)
        assertEquals(0, year.minimumDays)
        assertEquals(LocalDate.of(2024, 1, 1), year.earliestBirthDate)
        assertEquals(referenceDate, month.latestBirthDate)
        assertEquals(0, month.minimumDays)
        assertEquals(LocalDate.of(2024, 6, 1), month.earliestBirthDate)
    }

    @Test
    fun futurePartialAndExactBirthDatesAreRejectedAgainstExplicitReference() {
        val referenceDate = LocalDate.of(2024, 6, 15)

        assertThrows(IllegalArgumentException::class.java) {
            PartialBirthDate.Year(Year.of(2025)).validateAgainst(referenceDate)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PartialBirthDate.Month(YearMonth.of(2024, 7)).validateAgainst(referenceDate)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PartialBirthDate.Day(LocalDate.of(2024, 6, 16)).validateAgainst(referenceDate)
        }
    }

    @Test
    fun petContractsPreserveOptionalProfileAttributes() {
        val birthDate = PartialBirthDate.Month(YearMonth.of(2020, 2))
        val newPet = NewPet(
            displayName = "Барсик",
            species = PetSpecies.CAT,
            sex = PetSex.MALE,
            breedId = BreedId("wcf-sib"),
            birthDate = birthDate,
        )
        val update = PetUpdate(
            id = PetId("pet"),
            displayName = "Барсик",
            species = PetSpecies.CAT,
            sex = PetSex.MALE,
            breedId = BreedId("wcf-sib"),
            birthDate = birthDate,
        )

        assertEquals(PetSex.MALE, newPet.sex)
        assertEquals(BreedId("wcf-sib"), update.breedId)
        assertEquals(birthDate, newPet.birthDate)
        assertEquals(birthDate, update.birthDate)
    }

    @Test
    fun optionalProfileAttributesDefaultToAbsentForExistingCallers() {
        val pet = NewPet("Барсик", PetSpecies.CAT)

        assertNull(pet.sex)
        assertNull(pet.breedId)
        assertNull(pet.birthDate)
        assertNull(pet.dogAdultWeightCategory)
    }

    @Test
    fun petContractsPreserveDogAdultWeightCategoryAtTheEnd() {
        val newPet = NewPet("Луна", PetSpecies.DOG, dogAdultWeightCategory = DogAdultWeightCategory.III)
        val update = PetUpdate(
            PetId("pet"),
            "Луна",
            PetSpecies.DOG,
            dogAdultWeightCategory = DogAdultWeightCategory.IV,
        )

        assertEquals(DogAdultWeightCategory.III, newPet.dogAdultWeightCategory)
        assertEquals(DogAdultWeightCategory.IV, update.dogAdultWeightCategory)
    }

    @Test
    fun petPreservesTheLegacyFullPositionalConstructor() {
        val createdAt = Instant.ofEpochSecond(100)
        val updatedAt = Instant.ofEpochSecond(200)

        val pet = Pet(
            PetId("pet"),
            "Барсик",
            PetSpecies.CAT,
            "барсик",
            createdAt,
            updatedAt,
        )

        assertEquals(createdAt, pet.createdAt)
        assertEquals(updatedAt, pet.updatedAt)
        assertNull(pet.sex)
        assertNull(pet.breedId)
        assertNull(pet.birthDate)
    }

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
