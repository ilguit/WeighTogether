package com.palixander.weightogether.data

import com.palixander.weightogether.domain.PartialBirthDate
import com.palixander.weightogether.domain.PetSpecies
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PetEntityTest {
    @Test
    fun heightCanBeAddedChangedAndClearedThroughModelMappings() {
        val created = com.palixander.weightogether.domain.NewPet("Cat", PetSpecies.CAT, heightCm = 25.5)
            .toPetEntity("pet", java.time.Instant.ofEpochMilli(1))
        assertEquals(25.5, created.toDomain().heightCm!!, 0.0)
        val update = com.palixander.weightogether.domain.PetUpdate(
            created.toDomain().id, "Cat", PetSpecies.CAT, heightCm = 30.0,
        )
        val changed = created.withUpdate(update, java.time.Instant.ofEpochMilli(2))
        assertEquals(30.0, changed.toDomain().heightCm!!, 0.0)
        assertEquals(null, changed.withUpdate(update.copy(heightCm = null), java.time.Instant.ofEpochMilli(3)).toDomain().heightCm)
        for (height in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) { created.toDomain().copy(heightCm = height) }
            assertThrows(IllegalArgumentException::class.java) { update.copy(heightCm = height) }
            assertThrows(IllegalArgumentException::class.java) {
                com.palixander.weightogether.domain.NewPet("Cat", PetSpecies.CAT, heightCm = height)
            }
        }
    }

    @Test
    fun mappingReconstructsStoredBirthDatePrecision() {
        val pet = entity(birthYear = 2020, birthMonth = 2).toDomain()

        assertEquals(PartialBirthDate.Month(YearMonth.of(2020, 2)), pet.birthDate)
    }

    @Test
    fun mappingRejectsIncompleteOrInvalidStoredBirthDates() {
        assertThrows(RuntimeException::class.java) { entity(birthMonth = 2, birthDay = 3).toDomain() }
        assertThrows(RuntimeException::class.java) { entity(birthYear = 2021, birthMonth = 2, birthDay = 29).toDomain() }
        assertThrows(RuntimeException::class.java) { entity(birthYear = 2021, birthDay = 3).toDomain() }
    }

    private fun entity(
        birthYear: Int? = null,
        birthMonth: Int? = null,
        birthDay: Int? = null,
    ) = PetEntity(
        id = "pet",
        displayName = "Луна",
        normalizedName = "луна",
        species = PetSpecies.DOG,
        createdAtEpochMillis = 1,
        updatedAtEpochMillis = 2,
        birthYear = birthYear,
        birthMonth = birthMonth,
        birthDay = birthDay,
    )
}
