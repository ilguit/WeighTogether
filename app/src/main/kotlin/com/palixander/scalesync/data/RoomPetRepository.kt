package com.palixander.scalesync.data

import androidx.room.withTransaction
import com.palixander.scalesync.domain.NewPet
import com.palixander.scalesync.domain.Pet
import com.palixander.scalesync.domain.PetDeletionPreview
import com.palixander.scalesync.domain.PetId
import com.palixander.scalesync.domain.PetMeasurement
import com.palixander.scalesync.domain.PetMeasurementNotFoundException
import com.palixander.scalesync.domain.PetRepository
import com.palixander.scalesync.domain.PetSpecies
import com.palixander.scalesync.domain.PetUpdate
import com.palixander.scalesync.domain.PetWithMeasurementCount
import com.palixander.scalesync.domain.PetWithLatestWeight
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

    override fun observeMeasurements(petId: PetId): Flow<List<PetMeasurement>> =
        dao.observeMeasurements(petId.value).map { entities -> entities.map { it.toDomain() } }

    override suspend fun getPet(id: PetId): Pet? = dao.getPet(id.value)?.toDomain()

    override suspend fun getPetWithMeasurementCount(id: PetId): PetWithMeasurementCount? =
        database.withTransaction {
            val pet = dao.getPet(id.value) ?: return@withTransaction null
            PetWithMeasurementCount(pet.toDomain(), dao.countMeasurements(id.value))
        }

    override suspend fun createPet(pet: NewPet): Pet = database.withTransaction {
        require(pet.species != PetSpecies.UNSPECIFIED) {
            "A species is required when creating a pet"
        }
        dao.getPetByNormalizedName(pet.normalizedName)?.let {
            throw PetNameConflictException(pet.normalizedName)
        }
        val timestamp = now()
        val entity = PetEntity(
            id = newId(),
            displayName = pet.displayName,
            normalizedName = pet.normalizedName,
            species = pet.species,
            createdAtEpochMillis = timestamp.toEpochMilli(),
            updatedAtEpochMillis = timestamp.toEpochMilli(),
        )
        if (dao.insertPet(entity) == -1L) {
            throw PetNameConflictException(pet.normalizedName)
        }
        entity.toDomain()
    }

    override suspend fun updatePet(pet: PetUpdate): Pet = database.withTransaction {
        val existing = dao.getPet(pet.id.value) ?: throw PetNotFoundException(pet.id)
        val conflicting = dao.getPetByNormalizedName(pet.normalizedName)
        if (conflicting != null && conflicting.id != existing.id) {
            throw PetNameConflictException(pet.normalizedName)
        }
        val updatedAt = now().toEpochMilli()
        check(
            dao.updatePet(
                id = existing.id,
                displayName = pet.displayName,
                normalizedName = pet.normalizedName,
                species = pet.species,
                updatedAtEpochMillis = updatedAt,
            ) == 1,
        ) { "Pet ${existing.id} disappeared while updating" }
        existing.copy(
            displayName = pet.displayName,
            normalizedName = pet.normalizedName,
            species = pet.species,
            updatedAtEpochMillis = updatedAt,
        ).toDomain()
    }

    override suspend fun previewPetDeletion(id: PetId): PetDeletionPreview =
        database.withTransaction {
            val pet = dao.getPet(id.value) ?: throw PetNotFoundException(id)
            PetDeletionPreview(pet.toDomain(), dao.countMeasurements(id.value))
        }

    override suspend fun deletePet(id: PetId): PetDeletionPreview = database.withTransaction {
        val pet = dao.getPet(id.value) ?: throw PetNotFoundException(id)
        val deleted = PetDeletionPreview(pet.toDomain(), dao.countMeasurements(id.value))
        check(dao.deletePet(id.value) == 1) { "Pet ${id.value} disappeared while deleting" }
        deleted
    }

    override suspend fun deleteMeasurement(petId: PetId, measurementId: String) {
        database.withTransaction {
            if (dao.deleteMeasurement(petId.value, measurementId) != 1) {
                throw PetMeasurementNotFoundException(petId, measurementId)
            }
        }
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
        species = species,
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
