package com.example.huaweimisync.domain

import java.time.Instant
import java.util.Locale
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

data class Pet(
    val id: PetId,
    val displayName: String,
    val species: PetSpecies = PetSpecies.UNSPECIFIED,
    val normalizedName: String = normalizePetName(displayName),
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    init {
        validatePetName(displayName)
        require(normalizedName == normalizePetName(displayName)) {
            "Normalized name must match the display name"
        }
        require(!updatedAt.isBefore(createdAt)) { "Updated time cannot precede created time" }
    }
}

data class NewPet(
    val displayName: String,
    val species: PetSpecies,
) {
    init {
        validatePetName(displayName)
        require(species != PetSpecies.UNSPECIFIED) { "Pet species must be CAT or DOG" }
    }

    val normalizedName: String = normalizePetName(displayName)
}

data class PetUpdate(
    val id: PetId,
    val displayName: String,
    val species: PetSpecies,
) {
    init {
        validatePetName(displayName)
        require(species != PetSpecies.UNSPECIFIED) { "Pet species must be CAT or DOG" }
    }

    val normalizedName: String = normalizePetName(displayName)
}

data class PetMeasurement(
    val id: String,
    val petId: PetId,
    val measuredAt: Instant,
    val firstWeightKg: Double,
    val secondWeightKg: Double,
) {
    init {
        require(id.isNotBlank()) { "Pet measurement id must not be blank" }
        require(firstWeightKg.isFinite() && firstWeightKg > 0.0) {
            "First stable weight must be finite and positive"
        }
        require(secondWeightKg.isFinite() && secondWeightKg > 0.0) {
            "Second stable weight must be finite and positive"
        }
        require(petWeightKg > 0.0) { "Pet weight must be positive" }
    }

    val petWeightKg: Double
        get() = abs(secondWeightKg - firstWeightKg)
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
