package com.example.huaweimisync.data

import androidx.room.withTransaction
import com.example.huaweimisync.domain.NewPet
import com.example.huaweimisync.domain.Pet
import com.example.huaweimisync.domain.PetId
import com.example.huaweimisync.domain.PetMeasurement
import com.example.huaweimisync.domain.PetRepository
import com.example.huaweimisync.domain.PetWithLatestWeight
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomPetRepository(
    private val database: AppDatabase,
    private val dao: PetDao = database.petDao(),
    private val now: () -> Instant = Instant::now,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : PetRepository {
    override fun observePets(): Flow<List<PetWithLatestWeight>> =
        dao.observePetsWithLatestMeasurement().map { rows -> rows.map { it.toDomain() } }

    override suspend fun getPet(id: PetId): Pet? = dao.getPet(id.value)?.toDomain()

    override suspend fun createPet(pet: NewPet): Pet = database.withTransaction {
        dao.getPetByNormalizedName(pet.normalizedName)?.let {
            throw PetNameConflictException(pet.normalizedName)
        }
        val timestamp = now()
        val entity = PetEntity(
            id = newId(),
            displayName = pet.displayName,
            normalizedName = pet.normalizedName,
            createdAtEpochMillis = timestamp.toEpochMilli(),
            updatedAtEpochMillis = timestamp.toEpochMilli(),
        )
        if (dao.insertPet(entity) == -1L) {
            throw PetNameConflictException(pet.normalizedName)
        }
        entity.toDomain()
    }

    override suspend fun recordCompletedMeasurement(
        petId: PetId,
        measuredAt: Instant,
        firstWeightKg: Double,
        secondWeightKg: Double,
    ): PetMeasurement = database.withTransaction {
        val pet = dao.getPet(petId.value) ?: throw PetNotFoundException(petId)
        val domain = PetMeasurement(
            id = newId(),
            petId = petId,
            measuredAt = measuredAt,
            firstWeightKg = firstWeightKg,
            secondWeightKg = secondWeightKg,
        )
        dao.insertMeasurement(domain.toEntity())
        check(dao.updatePetTimestamp(pet.id, now().toEpochMilli()) == 1) {
            "Pet ${pet.id} disappeared while recording its measurement"
        }
        domain
    }
}

class PetNameConflictException(val normalizedName: String) :
    IllegalArgumentException("A pet named '$normalizedName' already exists")

class PetNotFoundException(val petId: PetId) :
    NoSuchElementException("Pet ${petId.value} does not exist")

private fun PetMeasurement.toEntity(): PetMeasurementEntity = PetMeasurementEntity(
    id = id,
    petId = petId.value,
    measuredAtEpochSecond = measuredAt.epochSecond,
    firstWeightKg = firstWeightKg,
    secondWeightKg = secondWeightKg,
    petWeightKg = petWeightKg,
)

private fun PetWithLatestMeasurementRow.toDomain(): PetWithLatestWeight {
    val pet = PetEntity(
        id = id,
        displayName = displayName,
        normalizedName = normalizedName,
        createdAtEpochMillis = createdAtEpochMillis,
        updatedAtEpochMillis = updatedAtEpochMillis,
    ).toDomain()
    val latest = latestMeasurementId?.let { measurementId ->
        PetMeasurementEntity(
            id = measurementId,
            petId = id,
            measuredAtEpochSecond = requireNotNull(latestMeasuredAtEpochSecond),
            firstWeightKg = requireNotNull(latestFirstWeightKg),
            secondWeightKg = requireNotNull(latestSecondWeightKg),
            petWeightKg = requireNotNull(latestPetWeightKg),
        ).toDomain()
    }
    return PetWithLatestWeight(pet = pet, latestMeasurement = latest)
}
