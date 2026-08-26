package com.example.huaweimisync.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.huaweimisync.domain.NewPet
import com.example.huaweimisync.domain.PetSpecies
import com.example.huaweimisync.domain.PetId
import java.time.Instant
import kotlinx.coroutines.flow.first
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
}
