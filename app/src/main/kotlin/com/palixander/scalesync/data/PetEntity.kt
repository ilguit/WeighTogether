package com.palixander.scalesync.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.palixander.scalesync.domain.Pet
import com.palixander.scalesync.domain.PetId
import com.palixander.scalesync.domain.PetSpecies
import com.palixander.scalesync.domain.PetSex
import com.palixander.scalesync.domain.BreedId
import com.palixander.scalesync.domain.PartialBirthDate
import com.palixander.scalesync.domain.reference.DogAdultWeightCategory
import com.palixander.scalesync.core.breed.canonicalBreedId
import java.time.Instant
import java.time.LocalDate
import java.time.Year
import java.time.YearMonth

@Entity(
    tableName = "pets",
    indices = [Index(value = ["normalizedName"], unique = true)],
)
data class PetEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    val normalizedName: String,
    val species: PetSpecies,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val sex: PetSex? = null,
    val breedId: String? = null,
    val birthYear: Int? = null,
    val birthMonth: Int? = null,
    val birthDay: Int? = null,
    val dogAdultWeightCategory: DogAdultWeightCategory? = null,
) {
    fun toDomain(): Pet = Pet(
        id = PetId(id),
        displayName = displayName,
        normalizedName = normalizedName,
        species = species,
        createdAt = Instant.ofEpochMilli(createdAtEpochMillis),
        updatedAt = Instant.ofEpochMilli(updatedAtEpochMillis),
        sex = sex,
        breedId = breedId?.let(::canonicalBreedId)?.let(::BreedId),
        birthDate = toPartialBirthDate(),
        dogAdultWeightCategory = dogAdultWeightCategory,
    )

    private fun toPartialBirthDate(): PartialBirthDate? =
        when {
            birthYear == null && birthMonth == null && birthDay == null -> null
            birthYear == null -> throw IllegalArgumentException("Birth month/day requires birth year")
            birthMonth == null && birthDay != null -> throw IllegalArgumentException("Birth day requires birth month")
            birthMonth == null -> PartialBirthDate.Year(Year.of(birthYear))
            birthDay == null -> PartialBirthDate.Month(YearMonth.of(birthYear, birthMonth))
            else -> PartialBirthDate.Day(LocalDate.of(birthYear, birthMonth, birthDay))
        }
}
