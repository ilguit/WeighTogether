package com.palixander.scalesync.data

import com.palixander.scalesync.domain.PartialBirthDate
import com.palixander.scalesync.domain.PetSpecies
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PetEntityTest {
    @Test
    fun mappingReconstructsStoredBirthDatePrecision() {
        val pet = entity(birthYear = 2020, birthMonth = 2).toDomain()

        assertEquals(PartialBirthDate.Month(YearMonth.of(2020, 2)), pet.birthDate)
    }

    @Test
    fun mappingTreatsIncompleteOrInvalidStoredBirthDatesAsAbsent() {
        assertNull(entity(birthMonth = 2, birthDay = 3).toDomain().birthDate)
        assertNull(entity(birthYear = 2021, birthMonth = 2, birthDay = 29).toDomain().birthDate)
        assertNull(entity(birthYear = 2021, birthDay = 3).toDomain().birthDate)
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
