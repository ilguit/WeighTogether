package com.example.huaweimisync.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

data class PetWithLatestMeasurementRow(
    val id: String,
    val displayName: String,
    val normalizedName: String,
    val species: com.example.huaweimisync.domain.PetSpecies,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val latestMeasurementId: String?,
    val latestMeasuredAtEpochSecond: Long?,
    val latestFirstWeightKg: Double?,
    val latestSecondWeightKg: Double?,
    val latestPetWeightKg: Double?,
)

@Dao
interface PetDao {
    @Query(
        """
        SELECT p.id, p.displayName, p.normalizedName, p.species, p.createdAtEpochMillis,
            p.updatedAtEpochMillis, m.id AS latestMeasurementId,
            m.measuredAtEpochSecond AS latestMeasuredAtEpochSecond,
            m.firstWeightKg AS latestFirstWeightKg,
            m.secondWeightKg AS latestSecondWeightKg,
            m.petWeightKg AS latestPetWeightKg
        FROM pets p
        LEFT JOIN pet_measurements m ON m.id = (
            SELECT latest.id FROM pet_measurements latest
            WHERE latest.petId = p.id
            ORDER BY latest.measuredAtEpochSecond DESC, latest.id DESC
            LIMIT 1
        )
        ORDER BY p.createdAtEpochMillis ASC, p.id ASC
        """,
    )
    fun observePetsWithLatestMeasurement(): Flow<List<PetWithLatestMeasurementRow>>

    @Query("SELECT * FROM pets WHERE id = :id")
    suspend fun getPet(id: String): PetEntity?

    @Query("SELECT * FROM pets WHERE normalizedName = :normalizedName")
    suspend fun getPetByNormalizedName(normalizedName: String): PetEntity?

    @Query("SELECT COUNT(*) FROM pet_measurements WHERE petId = :petId")
    suspend fun countMeasurements(petId: String): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPet(pet: PetEntity): Long

    @Query(
        """
        UPDATE pets SET displayName = :displayName, normalizedName = :normalizedName,
            species = :species, updatedAtEpochMillis = :updatedAtEpochMillis
        WHERE id = :id
        """,
    )
    suspend fun updatePet(
        id: String,
        displayName: String,
        normalizedName: String,
        species: com.example.huaweimisync.domain.PetSpecies,
        updatedAtEpochMillis: Long,
    ): Int

    @Query("DELETE FROM pets WHERE id = :id")
    suspend fun deletePet(id: String): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertMeasurement(measurement: PetMeasurementEntity)

    @Query("UPDATE pets SET updatedAtEpochMillis = :updatedAtEpochMillis WHERE id = :petId")
    suspend fun updatePetTimestamp(petId: String, updatedAtEpochMillis: Long): Int

    @Query("SELECT * FROM pet_measurements WHERE id = :id")
    suspend fun getMeasurement(id: String): PetMeasurementEntity?
}
