package com.palixander.weightogether.domain

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.util.Locale
import com.palixander.weightogether.domain.reference.DogAdultWeightCategory
import kotlin.math.abs

@JvmInline
value class PetId(val value: String) {
    init {
        require(value.isNotBlank()) { "Pet id must not be blank" }
    }

    override fun toString(): String = value
}

enum class PetSpecies {
    CAT,
    DOG,
    UNSPECIFIED,
}

enum class PetSex {
    MALE,
    FEMALE,
}

@JvmInline
value class BreedId(val value: String) {
    init {
        require(value.isNotBlank()) { "Breed id must not be blank" }
        require(value == value.trim()) { "Breed id must be trimmed" }
    }

    override fun toString(): String = value
}

enum class BirthDatePrecision {
    YEAR,
    MONTH,
    DAY,
}

sealed interface PartialBirthDate {
    val precision: BirthDatePrecision

    data class Year(val value: java.time.Year) : PartialBirthDate {
        override val precision: BirthDatePrecision = BirthDatePrecision.YEAR
    }

    data class Month(val value: YearMonth) : PartialBirthDate {
        override val precision: BirthDatePrecision = BirthDatePrecision.MONTH
    }

    data class Day(val value: LocalDate) : PartialBirthDate {
        override val precision: BirthDatePrecision = BirthDatePrecision.DAY
    }
}

/** A deterministic range of possible completed ages on [referenceDate]. */
data class AgeInterval(
    val referenceDate: LocalDate,
    val earliestBirthDate: LocalDate,
    val latestBirthDate: LocalDate,
) {
    init {
        require(!latestBirthDate.isBefore(earliestBirthDate)) {
            "Latest birth date cannot precede earliest birth date"
        }
        require(!earliestBirthDate.isAfter(referenceDate)) {
            "Reference date cannot precede the earliest possible birth date"
        }
        require(!latestBirthDate.isAfter(referenceDate)) {
            "Latest birth date cannot be after the reference date"
        }
    }

    val minimumDays: Long = ChronoUnit.DAYS.between(latestBirthDate, referenceDate)
    val maximumDays: Long = ChronoUnit.DAYS.between(earliestBirthDate, referenceDate)
    val minimumWeeks: Long = minimumDays / 7
    val maximumWeeks: Long = maximumDays / 7
    val minimumMonths: Long = ChronoUnit.MONTHS.between(latestBirthDate, referenceDate)
    val maximumMonths: Long = ChronoUnit.MONTHS.between(earliestBirthDate, referenceDate)
    val minimumYears: Long = ChronoUnit.YEARS.between(latestBirthDate, referenceDate)
    val maximumYears: Long = ChronoUnit.YEARS.between(earliestBirthDate, referenceDate)
}

fun PartialBirthDate.ageAt(referenceDate: LocalDate): AgeInterval {
    val earliestBirthDate = when (this) {
        is PartialBirthDate.Year -> value.atDay(1)
        is PartialBirthDate.Month -> value.atDay(1)
        is PartialBirthDate.Day -> value
    }
    require(!earliestBirthDate.isAfter(referenceDate)) {
        "Reference date cannot precede the earliest possible birth date"
    }
    val latestBirthDate = when (this) {
        is PartialBirthDate.Year -> value.atMonth(12).atEndOfMonth()
        is PartialBirthDate.Month -> value.atEndOfMonth()
        is PartialBirthDate.Day -> value
    }.coerceAtMost(referenceDate)
    return AgeInterval(referenceDate, earliestBirthDate, latestBirthDate)
}

fun PartialBirthDate.validateAgainst(referenceDate: LocalDate) {
    ageAt(referenceDate)
}

private fun LocalDate.coerceAtMost(maximum: LocalDate): LocalDate =
    if (isAfter(maximum)) maximum else this

data class Pet(
    val id: PetId,
    val displayName: String,
    val species: PetSpecies = PetSpecies.UNSPECIFIED,
    val normalizedName: String = normalizePetName(displayName),
    val createdAt: Instant,
    val updatedAt: Instant,
    val sex: PetSex? = null,
    val breedId: BreedId? = null,
    val birthDate: PartialBirthDate? = null,
    val dogAdultWeightCategory: DogAdultWeightCategory? = null,
    val photoPath: String? = null,
    val heightCm: Double? = null,
) {
    init {
        validatePetName(displayName)
        require(normalizedName == normalizePetName(displayName)) {
            "Normalized name must match the display name"
        }
        require(!updatedAt.isBefore(createdAt)) { "Updated time cannot precede created time" }
        require(species == PetSpecies.DOG || dogAdultWeightCategory == null) { "Adult dog weight category requires DOG species" }
        validateManagedProfilePhotoPath(photoPath)
        require(heightCm == null || (heightCm.isFinite() && heightCm > 0.0)) {
            "Pet height must be finite and positive"
        }
    }
}

data class NewPet(
    val displayName: String,
    val species: PetSpecies,
    val sex: PetSex? = null,
    val breedId: BreedId? = null,
    val birthDate: PartialBirthDate? = null,
    val dogAdultWeightCategory: DogAdultWeightCategory? = null,
    val photoPath: String? = null,
    val heightCm: Double? = null,
) {
    init {
        validatePetName(displayName)
        require(species != PetSpecies.UNSPECIFIED) { "Pet species must be CAT or DOG" }
        require(species == PetSpecies.DOG || dogAdultWeightCategory == null) { "Adult dog weight category requires DOG species" }
        validateManagedProfilePhotoPath(photoPath)
        require(heightCm == null || (heightCm.isFinite() && heightCm > 0.0)) {
            "Pet height must be finite and positive"
        }
    }

    val normalizedName: String = normalizePetName(displayName)
}

data class PetUpdate(
    val id: PetId,
    val displayName: String,
    val species: PetSpecies,
    val sex: PetSex? = null,
    val breedId: BreedId? = null,
    val birthDate: PartialBirthDate? = null,
    val dogAdultWeightCategory: DogAdultWeightCategory? = null,
    val photoPath: String? = null,
    val heightCm: Double? = null,
) {
    init {
        validatePetName(displayName)
        require(species != PetSpecies.UNSPECIFIED) { "Pet species must be CAT or DOG" }
        require(species == PetSpecies.DOG || dogAdultWeightCategory == null) { "Adult dog weight category requires DOG species" }
        validateManagedProfilePhotoPath(photoPath)
        require(heightCm == null || (heightCm.isFinite() && heightCm > 0.0)) {
            "Pet height must be finite and positive"
        }
    }

    val normalizedName: String = normalizePetName(displayName)
}

data class PetMeasurement(
    val id: String,
    val petId: PetId,
    val measuredAt: Instant,
    val firstWeightKg: Double?,
    val secondWeightKg: Double?,
    val petWeightKg: Double = abs(requireNotNull(secondWeightKg) - requireNotNull(firstWeightKg)),
    val origin: MeasurementOrigin = MeasurementOrigin.SCALE,
    val isManuallyEdited: Boolean = false,
) {
    init {
        require(id.isNotBlank()) { "Pet measurement id must not be blank" }
        require(petWeightKg.isFinite() && petWeightKg > 0.0) { "Pet weight must be finite and positive" }
        if (origin == MeasurementOrigin.MANUAL) {
            require(firstWeightKg == null && secondWeightKg == null) { "Manual weight has no source readings" }
        } else {
            require(firstWeightKg != null && firstWeightKg.isFinite() && firstWeightKg > 0.0)
            require(secondWeightKg != null && secondWeightKg.isFinite() && secondWeightKg > 0.0)
            require(isManuallyEdited || abs(abs(secondWeightKg - firstWeightKg) - petWeightKg) < 0.000_001) {
                "Stored pet weight does not match source readings"
            }
        }
    }
}

data class PetWithLatestWeight(
    val pet: Pet,
    val latestMeasurement: PetMeasurement?,
) {
    val latestPetWeightKg: Double?
        get() = latestMeasurement?.petWeightKg

    val latestMeasuredAt: Instant?
        get() = latestMeasurement?.measuredAt
}

data class PetWithMeasurementCount(
    val pet: Pet,
    val measurementCount: Int,
) {
    init {
        require(measurementCount >= 0) { "Measurement count must not be negative" }
    }
}

data class PetDeletionPreview(
    val pet: Pet,
    val measurementCount: Int,
) {
    init {
        require(measurementCount >= 0) { "Measurement count must not be negative" }
    }
}

fun normalizePetName(displayName: String): String =
    displayName.trim().lowercase(Locale.ROOT)

val PET_NAME_LENGTH: IntRange = 1..50

private fun validatePetName(displayName: String) {
    require(displayName == displayName.trim()) { "Pet display name must be trimmed" }
    require(displayName.length in PET_NAME_LENGTH) {
        "Pet display name must contain 1 to 50 characters"
    }
}
