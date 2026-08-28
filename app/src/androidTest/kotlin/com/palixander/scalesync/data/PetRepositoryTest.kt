package com.palixander.scalesync.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.palixander.scalesync.domain.NewPet
import com.palixander.scalesync.domain.PetMeasurementNotFoundException
import com.palixander.scalesync.domain.PetSpecies
import com.palixander.scalesync.domain.PetId
import com.palixander.scalesync.domain.PetUpdate
import java.time.Instant
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PetRepositoryTest {
    private lateinit var database: AppDatabase

    @Before
    fun createDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun createPetEnforcesNormalizedNameUniqueness() = runBlocking {
        val ids = ArrayDeque(listOf("pet-a", "pet-b"))
        val repository = RoomPetRepository(
            database = database,
            now = { Instant.ofEpochMilli(100) },
            newId = { ids.removeFirst() },
        )

        val created = repository.createPet(NewPet("Барсик", PetSpecies.CAT))

        assertEquals("pet-a", created.id.value)
        assertEquals("барсик", created.normalizedName)
        assertThrows(PetNameConflictException::class.java) {
            runBlocking { repository.createPet(NewPet("БАРСИК", PetSpecies.DOG)) }
        }
    }

    @Test
    fun recordCompletedMeasurementStoresBothReadingsAndObservesLatestDelta() = runBlocking {
        val ids = ArrayDeque(listOf("pet", "older", "newer"))
        val times = ArrayDeque(
            listOf(
                Instant.ofEpochMilli(1_000),
                Instant.ofEpochMilli(2_000),
                Instant.ofEpochMilli(3_000),
            ),
        )
        val repository = RoomPetRepository(
            database = database,
            now = { times.removeFirst() },
            newId = { ids.removeFirst() },
        )
        val pet = repository.createPet(NewPet("Луна", PetSpecies.DOG))

        repository.recordCompletedMeasurement(
            petId = pet.id,
            measuredAt = Instant.ofEpochSecond(10),
            firstWeightKg = 70.0,
            secondWeightKg = 74.25,
        )
        val latest = repository.recordCompletedMeasurement(
            petId = pet.id,
            measuredAt = Instant.ofEpochSecond(20),
            firstWeightKg = 70.0,
            secondWeightKg = 66.5,
        )

        val stored = database.petDao().getMeasurement(latest.id)
        assertNotNull(stored)
        assertEquals(70.0, stored!!.firstWeightKg, 0.0)
        assertEquals(66.5, stored.secondWeightKg, 0.0)
        assertEquals(3.5, stored.petWeightKg, 0.0)
        val observed = repository.observePets().first().single()
        assertEquals(3.5, observed.latestPetWeightKg!!, 0.0)
        assertEquals(Instant.ofEpochSecond(20), observed.latestMeasuredAt)
        assertEquals(Instant.ofEpochMilli(3_000), observed.pet.updatedAt)
    }

    @Test
    fun measurementHistoryIsInitiallyEmptyAndIsolatedByPet() = runBlocking {
        val ids = ArrayDeque(listOf("first-pet", "second-pet", "first-measurement", "second-measurement"))
        val repository = RoomPetRepository(
            database = database,
            now = { Instant.EPOCH },
            newId = { ids.removeFirst() },
        )
        val firstPet = repository.createPet(NewPet("Луна", PetSpecies.DOG))
        val secondPet = repository.createPet(NewPet("Барсик", PetSpecies.CAT))

        assertEquals(emptyList<Any>(), repository.observeMeasurements(firstPet.id).first())

        repository.recordCompletedMeasurement(secondPet.id, Instant.ofEpochSecond(20), 70.0, 74.0)
        val firstMeasurement = repository.recordCompletedMeasurement(
            firstPet.id,
            Instant.ofEpochSecond(10),
            60.0,
            63.0,
        )

        assertEquals(listOf(firstMeasurement), repository.observeMeasurements(firstPet.id).first())
    }

    @Test
    fun measurementHistoryUsesNewestFirstDeterministicOrder() = runBlocking {
        val ids = ArrayDeque(listOf("pet", "older", "same-time-a", "same-time-z"))
        val repository = RoomPetRepository(
            database = database,
            now = { Instant.EPOCH },
            newId = { ids.removeFirst() },
        )
        val pet = repository.createPet(NewPet("Луна", PetSpecies.DOG))
        repository.recordCompletedMeasurement(pet.id, Instant.ofEpochSecond(10), 70.0, 72.0)
        repository.recordCompletedMeasurement(pet.id, Instant.ofEpochSecond(20), 70.0, 73.0)
        repository.recordCompletedMeasurement(pet.id, Instant.ofEpochSecond(20), 70.0, 74.0)

        assertEquals(
            listOf("same-time-z", "same-time-a", "older"),
            repository.observeMeasurements(pet.id).first().map { it.id },
        )
    }

    @Test
    fun measurementHistoryReactsToNewMeasurement() = runBlocking {
        val ids = ArrayDeque(listOf("pet", "measurement"))
        val repository = RoomPetRepository(
            database = database,
            now = { Instant.EPOCH },
            newId = { ids.removeFirst() },
        )
        val pet = repository.createPet(NewPet("Луна", PetSpecies.DOG))
        val emissions = async(start = CoroutineStart.UNDISPATCHED) {
            repository.observeMeasurements(pet.id).take(2).toList()
        }

        repository.recordCompletedMeasurement(pet.id, Instant.ofEpochSecond(10), 70.0, 74.0)

        val history = emissions.await()
        assertEquals(emptyList<Any>(), history.first())
        assertEquals(listOf("measurement"), history.last().map { it.id })
    }

    @Test
    fun deleteMeasurementRemovesOnlyScopedPetRowAndLeavesHumanMeasurement() = runBlocking {
        val ids = ArrayDeque(listOf("first-pet", "second-pet", "first-measurement", "second-measurement"))
        val repository = RoomPetRepository(
            database = database,
            now = { Instant.EPOCH },
            newId = { ids.removeFirst() },
        )
        val firstPet = repository.createPet(NewPet("Луна", PetSpecies.DOG))
        val secondPet = repository.createPet(NewPet("Барсик", PetSpecies.CAT))
        val firstMeasurement = repository.recordCompletedMeasurement(
            firstPet.id,
            Instant.ofEpochSecond(10),
            70.0,
            73.0,
        )
        val secondMeasurement = repository.recordCompletedMeasurement(
            secondPet.id,
            Instant.ofEpochSecond(20),
            70.0,
            74.0,
        )
        database.accountDao().insert(
            AccountEntity("human", "Человек", "человек", null, null, null, false, 1, 1),
        )
        database.measurementDao().insert(humanMeasurement("human-measurement"))

        repository.deleteMeasurement(firstPet.id, firstMeasurement.id)

        assertEquals(emptyList<Any>(), repository.observeMeasurements(firstPet.id).first())
        assertNull(repository.observePets().first().first { it.pet.id == firstPet.id }.latestMeasurement)
        assertEquals(
            listOf(secondMeasurement),
            repository.observeMeasurements(secondPet.id).first(),
        )
        assertNotNull(database.measurementDao().get("human-measurement"))
    }

    @Test
    fun deleteMeasurementRejectsMeasurementOwnedByAnotherPet() = runBlocking {
        val ids = ArrayDeque(listOf("first-pet", "second-pet", "measurement"))
        val repository = RoomPetRepository(
            database = database,
            now = { Instant.EPOCH },
            newId = { ids.removeFirst() },
        )
        val firstPet = repository.createPet(NewPet("Луна", PetSpecies.DOG))
        val secondPet = repository.createPet(NewPet("Барсик", PetSpecies.CAT))
        val measurement = repository.recordCompletedMeasurement(
            firstPet.id,
            Instant.EPOCH,
            70.0,
            73.0,
        )

        val error = assertThrows(PetMeasurementNotFoundException::class.java) {
            runBlocking { repository.deleteMeasurement(secondPet.id, measurement.id) }
        }

        assertEquals(secondPet.id, error.petId)
        assertEquals(measurement.id, error.measurementId)
        assertEquals(listOf(measurement), repository.observeMeasurements(firstPet.id).first())
    }

    @Test
    fun deleteMeasurementRejectsMissingMeasurement() = runBlocking {
        val repository = RoomPetRepository(
            database = database,
            now = { Instant.EPOCH },
            newId = { "pet" },
        )
        val pet = repository.createPet(NewPet("Луна", PetSpecies.DOG))

        val error = assertThrows(PetMeasurementNotFoundException::class.java) {
            runBlocking { repository.deleteMeasurement(pet.id, "missing-measurement") }
        }

        assertEquals(pet.id, error.petId)
        assertEquals("missing-measurement", error.measurementId)
    }

    @Test
    fun missingPetDoesNotCreateMeasurement() = runBlocking {
        val repository = RoomPetRepository(
            database = database,
            newId = { "measurement" },
        )

        assertThrows(PetNotFoundException::class.java) {
            runBlocking {
                repository.recordCompletedMeasurement(
                    petId = PetId("missing"),
                    measuredAt = Instant.EPOCH,
                    firstWeightKg = 10.0,
                    secondWeightKg = 12.0,
                )
            }
        }
        assertNull(database.petDao().getMeasurement("measurement"))
    }

    @Test
    fun detailsUpdateAndUnchangedNamePreserveCreationTime() = runBlocking {
        val times = ArrayDeque(listOf(Instant.ofEpochMilli(100), Instant.ofEpochMilli(200)))
        val repository = RoomPetRepository(database, now = { times.removeFirst() }, newId = { "pet" })
        val created = repository.createPet(NewPet("Барсик", PetSpecies.CAT))

        val updated = repository.updatePet(PetUpdate(created.id, "Барсик", PetSpecies.DOG))

        assertEquals(PetSpecies.DOG, updated.species)
        assertEquals(created.createdAt, updated.createdAt)
        assertEquals(Instant.ofEpochMilli(200), updated.updatedAt)
        val details = repository.getPetWithMeasurementCount(created.id)!!
        assertEquals(updated, details.pet)
        assertEquals(0, details.measurementCount)
    }

    @Test
    fun updateRejectsMissingPetAndNameOwnedByAnotherPet() = runBlocking {
        val ids = ArrayDeque(listOf("first", "second"))
        val times = ArrayDeque(
            listOf(Instant.ofEpochMilli(100), Instant.ofEpochMilli(200), Instant.ofEpochMilli(300)),
        )
        val repository = RoomPetRepository(database, now = { times.removeFirst() }, newId = { ids.removeFirst() })
        val first = repository.createPet(NewPet("Барсик", PetSpecies.CAT))
        val second = repository.createPet(NewPet("Луна", PetSpecies.DOG))

        assertThrows(PetNameConflictException::class.java) {
            runBlocking { repository.updatePet(PetUpdate(second.id, "БАРСИК", PetSpecies.DOG)) }
        }
        assertEquals("Луна", repository.getPet(second.id)!!.displayName)
        assertThrows(PetNotFoundException::class.java) {
            runBlocking { repository.updatePet(PetUpdate(PetId("missing"), "Рекс", PetSpecies.DOG)) }
        }
        assertEquals("Барсик", repository.getPet(first.id)!!.displayName)
    }

    @Test
    fun deleteReturnsPreviewCascadesPetHistoryAndLeavesOtherDataAlone() = runBlocking {
        val ids = ArrayDeque(listOf("pet", "measurement"))
        val repository = RoomPetRepository(database, now = { Instant.ofEpochMilli(100) }, newId = { ids.removeFirst() })
        val pet = repository.createPet(NewPet("Луна", PetSpecies.DOG))
        repository.recordCompletedMeasurement(pet.id, Instant.EPOCH, 70.0, 73.0)
        database.accountDao().insert(
            AccountEntity("human", "Человек", "человек", null, null, null, false, 1, 1),
        )

        val preview = repository.previewPetDeletion(pet.id)
        val deleted = repository.deletePet(pet.id)

        assertEquals(pet.id, preview.pet.id)
        assertEquals(1, preview.measurementCount)
        assertEquals(preview, deleted)
        assertNull(repository.getPet(pet.id))
        assertNull(database.petDao().getMeasurement("measurement"))
        assertNotNull(database.accountDao().get("human"))
        assertThrows(PetNotFoundException::class.java) {
            runBlocking { repository.deletePet(pet.id) }
        }
    }
}

private fun humanMeasurement(id: String) = MeasurementEntity(
    id = id,
    measurementType = MeasurementType.WEIGHT_ONLY,
    deviceAddress = "AA:BB:CC:DD:EE:FF",
    measuredAtEpochSecond = 1,
    rawPayloadHex = "010203",
    weightKg = 70.0,
    impedanceOhm = null,
    bmi = null,
    bodyFatPercent = null,
    bodyFatMassKg = null,
    waterPercent = null,
    waterMassKg = null,
    muscleMassKg = null,
    skeletalMuscleMassKg = null,
    boneMassKg = null,
    proteinPercent = null,
    proteinMassKg = null,
    visceralFatLevel = null,
    basalMetabolicRateKcal = null,
    metabolicAge = null,
    leanBodyMassKg = null,
    algorithmVersion = null,
    accountId = "human",
)
