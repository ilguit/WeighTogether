package com.example.huaweimisync.domain

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

    /** Atomically persists both stable scale readings and marks the pet as updated. */
    suspend fun recordCompletedMeasurement(
        petId: PetId,
        measuredAt: Instant,
        firstWeightKg: Double,
        secondWeightKg: Double,
    ): PetMeasurement
}
