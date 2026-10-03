package com.palixander.weightogether.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

data class PetWithLatestMeasurementRow(
    val id: String,
    val displayName: String,
    val normalizedName: String,
    val species: com.palixander.weightogether.domain.PetSpecies,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val sex: com.palixander.weightogether.domain.PetSex?,
    val breedId: String?,
    val birthYear: Int?,
    val birthMonth: Int?,
    val birthDay: Int?,
    val dogAdultWeightCategory: com.palixander.weightogether.domain.reference.DogAdultWeightCategory?,
    val photoPath: String?,
    val heightCm: Double?,
    val latestMeasurementId: String?,
    val latestMeasuredAtEpochSecond: Long?,
    val latestFirstWeightKg: Double?,
    val latestSecondWeightKg: Double?,
    val latestPetWeightKg: Double?,
    val latestOrigin: com.palixander.weightogether.domain.MeasurementOrigin?,
    val latestIsManuallyEdited: Boolean?,
)

@Dao
interface PetDao {
    @Query("SELECT * FROM pet_measurements " +
        "WHERE petId = :ownerId " +
        "AND measuredAtEpochSecond >= :minuteStart AND measuredAtEpochSecond < :minuteStart + 60")
    suspend fun findManualDuplicateCandidates(ownerId: String, minuteStart: Long): List<PetMeasurementEntity>

    @Query("SELECT * FROM pets ORDER BY createdAtEpochMillis ASC, id ASC")
    suspend fun getAllPetsForBackup(): List<PetEntity>

    @Query("SELECT * FROM pet_measurements ORDER BY measuredAtEpochSecond ASC, id ASC")
    suspend fun getAllMeasurementsForBackup(): List<PetMeasurementEntity>

    @Query(
        """
        SELECT p.id, p.displayName, p.normalizedName, p.species, p.createdAtEpochMillis,
            p.updatedAtEpochMillis, p.sex, p.breedId, p.birthYear, p.birthMonth, p.birthDay,
            p.dogAdultWeightCategory, p.photoPath, p.heightCm, m.id AS latestMeasurementId,
            m.measuredAtEpochSecond AS latestMeasuredAtEpochSecond,
            m.firstWeightKg AS latestFirstWeightKg,
            m.secondWeightKg AS latestSecondWeightKg,
            m.petWeightKg AS latestPetWeightKg, m.origin AS latestOrigin,
            m.isManuallyEdited AS latestIsManuallyEdited
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

    @Query(
        """
        SELECT * FROM pet_measurements
        WHERE petId = :petId
        ORDER BY measuredAtEpochSecond DESC, id DESC
        """,
    )
    fun observeMeasurements(petId: String): Flow<List<PetMeasurementEntity>>

    @Query("SELECT * FROM pets WHERE id = :id")
    suspend fun getPet(id: String): PetEntity?

    @Query("SELECT * FROM pets WHERE normalizedName = :normalizedName")
    suspend fun getPetByNormalizedName(normalizedName: String): PetEntity?

    @Query("SELECT COUNT(*) FROM pet_measurements WHERE petId = :petId")
    suspend fun countMeasurements(petId: String): Int

    @Query("SELECT COUNT(*) FROM pets WHERE photoPath = :photoPath")
    suspend fun countPhotoReferences(photoPath: String): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPet(pet: PetEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPets(pets: List<PetEntity>)

    @Query(
        """
        UPDATE pets SET displayName = :displayName, normalizedName = :normalizedName,
            species = :species, sex = :sex, breedId = :breedId, birthYear = :birthYear,
            birthMonth = :birthMonth, birthDay = :birthDay,
            dogAdultWeightCategory = :dogAdultWeightCategory,
            photoPath = :photoPath, heightCm = :heightCm,
            updatedAtEpochMillis = :updatedAtEpochMillis
        WHERE id = :id
        """,
    )
    suspend fun updatePet(
        id: String,
        displayName: String,
        normalizedName: String,
        species: com.palixander.weightogether.domain.PetSpecies,
        sex: com.palixander.weightogether.domain.PetSex?,
        breedId: String?,
        birthYear: Int?,
        birthMonth: Int?,
        birthDay: Int?,
        dogAdultWeightCategory: com.palixander.weightogether.domain.reference.DogAdultWeightCategory?,
        photoPath: String?,
        heightCm: Double?,
        updatedAtEpochMillis: Long,
    ): Int

    @Query("DELETE FROM pets WHERE id = :id")
    suspend fun deletePet(id: String): Int

    @Query("DELETE FROM pet_measurements WHERE petId = :petId AND id = :measurementId")
    suspend fun deleteMeasurement(petId: String, measurementId: String): Int

    @Query("""
        UPDATE pet_measurements
        SET petWeightKg = :petWeightKg, isManuallyEdited = 1
        WHERE petId = :petId AND id = :measurementId
    """)
    suspend fun updateMeasurementWeight(petId: String, measurementId: String, petWeightKg: Double): Int

    @Query("DELETE FROM pet_measurements")
    suspend fun deleteAllMeasurements()

    @Query("DELETE FROM pets")
    suspend fun deleteAllPets()

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertMeasurement(measurement: PetMeasurementEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMeasurements(measurements: List<PetMeasurementEntity>)

    @Query("UPDATE pets SET updatedAtEpochMillis = :updatedAtEpochMillis WHERE id = :petId")
    suspend fun updatePetTimestamp(petId: String, updatedAtEpochMillis: Long): Int

    @Query("SELECT * FROM pet_measurements WHERE id = :id")
    suspend fun getMeasurement(id: String): PetMeasurementEntity?
}
