package com.palixander.scalesync.data

import androidx.room.withTransaction
import com.palixander.scalesync.core.breed.BreedCatalog
import com.palixander.scalesync.core.breed.BreedSpecies
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
import com.palixander.scalesync.domain.PartialBirthDate
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomPetRepository(
    private val database: AppDatabase,
    private val dao: PetDao = database.petDao(),
    private val now: () -> Instant = Instant::now,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val breedCatalog: BreedCatalog = BreedCatalog.bundled(),
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
        validateBreedSpecies(pet.breedId?.value, pet.species)
        dao.getPetByNormalizedName(pet.normalizedName)?.let {
            throw PetNameConflictException(pet.normalizedName)
        }
        val entity = pet.toPetEntity(id = newId(), timestamp = now())
        if (dao.insertPet(entity) == -1L) {
            throw PetNameConflictException(pet.normalizedName)
        }
        entity.toDomain()
    }

    override suspend fun updatePet(pet: PetUpdate): Pet = database.withTransaction {
        val existing = dao.getPet(pet.id.value) ?: throw PetNotFoundException(pet.id)
        validateBreedSpecies(pet.breedId?.value, pet.species)
        val conflicting = dao.getPetByNormalizedName(pet.normalizedName)
        if (conflicting != null && conflicting.id != existing.id) {
            throw PetNameConflictException(pet.normalizedName)
        }
        val updated = existing.withUpdate(pet, updatedAt = now())
        check(
            dao.updatePet(
                id = updated.id,
                displayName = updated.displayName,
                normalizedName = updated.normalizedName,
                species = updated.species,
                sex = updated.sex,
                breedId = updated.breedId,
                birthYear = updated.birthYear,
                birthMonth = updated.birthMonth,
                birthDay = updated.birthDay,
                dogAdultWeightCategory = updated.dogAdultWeightCategory,
                updatedAtEpochMillis = updated.updatedAtEpochMillis,
            ) == 1,
        ) { "Pet ${existing.id} disappeared while updating" }
        updated.toDomain()
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

    private fun validateBreedSpecies(breedId: String?, species: PetSpecies) {
        val breed = breedId?.let(breedCatalog::findById) ?: return
        val expectedSpecies = when (species) {
            PetSpecies.CAT -> BreedSpecies.CAT
            PetSpecies.DOG -> BreedSpecies.DOG
            PetSpecies.UNSPECIFIED -> return
        }
        require(breed.species == expectedSpecies) {
            "Breed $breedId does not belong to ${species.name.lowercase()} species"
        }
    }
}

class PetNameConflictException(val normalizedName: String) :
    IllegalArgumentException("A pet named '$normalizedName' already exists")

class PetNotFoundException(val petId: PetId) :
    NoSuchElementException("Pet ${petId.value} does not exist")

internal fun NewPet.toPetEntity(id: String, timestamp: Instant): PetEntity = PetEntity(
    id = id,
    displayName = displayName,
    normalizedName = normalizedName,
    species = species,
    createdAtEpochMillis = timestamp.toEpochMilli(),
    updatedAtEpochMillis = timestamp.toEpochMilli(),
    sex = sex,
    breedId = breedId?.value,
    birthYear = birthDate?.yearValue,
    birthMonth = birthDate?.monthValue,
    birthDay = birthDate?.dayValue,
    dogAdultWeightCategory = dogAdultWeightCategory,
)

internal fun PetEntity.withUpdate(pet: PetUpdate, updatedAt: Instant): PetEntity = copy(
    displayName = pet.displayName,
    normalizedName = pet.normalizedName,
    species = pet.species,
    sex = pet.sex,
    breedId = pet.breedId?.value,
    birthYear = pet.birthDate?.yearValue,
    birthMonth = pet.birthDate?.monthValue,
    birthDay = pet.birthDate?.dayValue,
    dogAdultWeightCategory = pet.dogAdultWeightCategory,
    updatedAtEpochMillis = updatedAt.toEpochMilli(),
)

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
        sex = sex,
        breedId = breedId,
        birthYear = birthYear,
        birthMonth = birthMonth,
        birthDay = birthDay,
        dogAdultWeightCategory = dogAdultWeightCategory,
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

private val PartialBirthDate.yearValue: Int
    get() = when (this) {
        is PartialBirthDate.Year -> value.value
        is PartialBirthDate.Month -> value.year
        is PartialBirthDate.Day -> value.year
    }

private val PartialBirthDate.monthValue: Int?
    get() = when (this) {
        is PartialBirthDate.Year -> null
        is PartialBirthDate.Month -> value.monthValue
        is PartialBirthDate.Day -> value.monthValue
    }

private val PartialBirthDate.dayValue: Int?
    get() = (this as? PartialBirthDate.Day)?.value?.dayOfMonth
