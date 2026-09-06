package com.palixander.scalesync.domain

import java.time.Instant
import kotlinx.coroutines.flow.Flow

interface PetRepository {
    fun observePets(): Flow<List<PetWithLatestWeight>>

    fun observeMeasurements(petId: PetId): Flow<List<PetMeasurement>>

    suspend fun getPet(id: PetId): Pet?

    suspend fun getPetWithMeasurementCount(id: PetId): PetWithMeasurementCount?

    suspend fun createPet(pet: NewPet): Pet

    suspend fun updatePet(pet: PetUpdate): Pet

    suspend fun previewPetDeletion(id: PetId): PetDeletionPreview

    suspend fun deletePet(id: PetId): PetDeletionPreview

    suspend fun deleteMeasurement(petId: PetId, measurementId: String)

    /** Updates only the selected measurement's final weight and permanently marks it as edited. */
    suspend fun updateMeasurementWeight(
        petId: PetId,
        measurementId: String,
        petWeightKg: Double,
    ): PetMeasurement

    /** Atomically persists both stable scale readings and marks the pet as updated. */
    suspend fun recordCompletedMeasurement(
        petId: PetId,
        measuredAt: Instant,
        firstWeightKg: Double,
        secondWeightKg: Double,
    ): PetMeasurement
}

class PetMeasurementNotFoundException(
    val petId: PetId,
    val measurementId: String,
) : NoSuchElementException(
    "Measurement $measurementId does not exist for pet ${petId.value}",
)
