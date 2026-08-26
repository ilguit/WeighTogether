package com.example.huaweimisync.domain

import java.time.Instant
import kotlinx.coroutines.flow.Flow

interface PetRepository {
    fun observePets(): Flow<List<PetWithLatestWeight>>

    suspend fun getPet(id: PetId): Pet?

    suspend fun createPet(pet: NewPet): Pet

    /** Atomically persists both stable scale readings and marks the pet as updated. */
    suspend fun recordCompletedMeasurement(
        petId: PetId,
        measuredAt: Instant,
        firstWeightKg: Double,
        secondWeightKg: Double,
    ): PetMeasurement
}
