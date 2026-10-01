package com.palixander.weightogether.data

import androidx.room.withTransaction
import com.palixander.weightogether.core.breed.BreedCatalog
import com.palixander.weightogether.core.breed.BreedSpecies
import com.palixander.weightogether.core.breed.canonicalBreedId
import com.palixander.weightogether.domain.NewPet
import com.palixander.weightogether.domain.Pet
import com.palixander.weightogether.domain.PetDeletionPreview
import com.palixander.weightogether.domain.PetId
import com.palixander.weightogether.domain.PetMeasurement
import com.palixander.weightogether.domain.PetMeasurementNotFoundException
import com.palixander.weightogether.domain.PetRepository
import com.palixander.weightogether.domain.PetSpecies
import com.palixander.weightogether.domain.PetUpdate
import com.palixander.weightogether.domain.PetWithMeasurementCount
import com.palixander.weightogether.domain.PetWithLatestWeight
import com.palixander.weightogether.domain.PartialBirthDate
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
    private val photoLifecycle: ProfilePhotoLifecycle = ProfilePhotoLifecycle.None,
    private val photoReferences: ProfilePhotoReferenceCoordinator =
        ProfilePhotoReferenceCoordinator(database, photoLifecycle),
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

    override suspend fun createPet(pet: NewPet): Pet =
        photoReferences.mutate(setOfNotNull(pet.photoPath)) {
            val created = database.withTransaction {
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
            ProfilePhotoMutation(created)
        }

    override suspend fun updatePet(pet: PetUpdate): Pet {
        return photoReferences.mutate(setOfNotNull(pet.photoPath)) {
            val (saved, dereferencedPhotoPath) = database.withTransaction {
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
                        photoPath = updated.photoPath,
                        updatedAtEpochMillis = updated.updatedAtEpochMillis,
                    ) == 1,
                ) { "Pet ${existing.id} disappeared while updating" }
                updated.toDomain() to existing.photoPath?.takeIf { it != updated.photoPath }
            }
            ProfilePhotoMutation(saved, setOfNotNull(dereferencedPhotoPath))
        }
    }

    override suspend fun previewPetDeletion(id: PetId): PetDeletionPreview =
        database.withTransaction {
            val pet = dao.getPet(id.value) ?: throw PetNotFoundException(id)
            PetDeletionPreview(pet.toDomain(), dao.countMeasurements(id.value))
        }

    override suspend fun deletePet(id: PetId): PetDeletionPreview {
        return photoReferences.mutate(emptySet()) {
            val (deleted, photoPath) = database.withTransaction {
                val pet = dao.getPet(id.value) ?: throw PetNotFoundException(id)
                val preview = PetDeletionPreview(pet.toDomain(), dao.countMeasurements(id.value))
                check(dao.deletePet(id.value) == 1) {
                    "Pet ${id.value} disappeared while deleting"
                }
                preview to pet.photoPath
            }
            ProfilePhotoMutation(deleted, setOfNotNull(photoPath))
        }
    }

    override suspend fun deleteMeasurement(petId: PetId, measurementId: String) {
        database.withTransaction {
            if (dao.deleteMeasurement(petId.value, measurementId) != 1) {
                throw PetMeasurementNotFoundException(petId, measurementId)
            }
        }
    }

    override suspend fun updateMeasurementWeight(
        petId: PetId,
        measurementId: String,
        petWeightKg: Double,
    ): PetMeasurement = database.withTransaction {
        require(petWeightKg.isFinite() && petWeightKg > 0.0) {
            "Pet weight must be finite and positive"
        }
        if (dao.updateMeasurementWeight(petId.value, measurementId, petWeightKg) != 1) {
            throw PetMeasurementNotFoundException(petId, measurementId)
        }
        checkNotNull(dao.getMeasurement(measurementId)).toDomain()
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
    breedId = breedId?.value?.let(::canonicalBreedId),
    birthYear = birthDate?.yearValue,
    birthMonth = birthDate?.monthValue,
    birthDay = birthDate?.dayValue,
    dogAdultWeightCategory = dogAdultWeightCategory,
    photoPath = photoPath,
)

internal fun PetEntity.withUpdate(pet: PetUpdate, updatedAt: Instant): PetEntity = copy(
    displayName = pet.displayName,
    normalizedName = pet.normalizedName,
    species = pet.species,
    sex = pet.sex,
    breedId = pet.breedId?.value?.let(::canonicalBreedId),
    birthYear = pet.birthDate?.yearValue,
    birthMonth = pet.birthDate?.monthValue,
    birthDay = pet.birthDate?.dayValue,
    dogAdultWeightCategory = pet.dogAdultWeightCategory,
    photoPath = pet.photoPath,
    updatedAtEpochMillis = updatedAt.toEpochMilli(),
)

private fun PetMeasurement.toEntity(): PetMeasurementEntity = PetMeasurementEntity(
    id = id,
    petId = petId.value,
    measuredAtEpochSecond = measuredAt.epochSecond,
    firstWeightKg = firstWeightKg,
    secondWeightKg = secondWeightKg,
    petWeightKg = petWeightKg,
    origin = origin,
    isManuallyEdited = isManuallyEdited,
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
        photoPath = photoPath,
    ).toDomain()
    val latest = latestMeasurementId?.let { measurementId ->
        PetMeasurementEntity(
            id = measurementId,
            petId = id,
            measuredAtEpochSecond = requireNotNull(latestMeasuredAtEpochSecond),
            firstWeightKg = latestFirstWeightKg,
            secondWeightKg = latestSecondWeightKg,
            petWeightKg = requireNotNull(latestPetWeightKg),
            origin = requireNotNull(latestOrigin),
            isManuallyEdited = requireNotNull(latestIsManuallyEdited),
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
