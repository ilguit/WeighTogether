package com.palixander.scalesync.data

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.palixander.scalesync.domain.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ManualWeightRepositoryTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)
    private lateinit var database: AppDatabase
    private lateinit var repository: ManualWeightRepository
    private val human = ManualWeightOwner.Human(AccountId("human"))
    private val pet = ManualWeightOwner.Pet(PetId("pet"))
    private val now = Instant.parse("2026-09-04T12:34:56Z")

    @Before fun setup() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        database.accountDao().insert(AccountEntity("human", "Человек", "человек", null, null, null, false, 100, 100))
        database.accountDao().insert(AccountEntity("other", "Другой", "другой", null, null, null, false, 100, 100))
        database.appStateDao().replace(AppStateEntity(primaryAccountId = "human"))
        database.petDao().insertPet(PetEntity("pet", "Кот", "кот", PetSpecies.CAT, 100, 100))
        repository = ManualWeightRepository(
            database,
            { throw IllegalStateException("scheduler unavailable") },
            Clock.fixed(now, ZoneOffset.UTC),
        )
    }

    @After fun close() = database.close()

    private fun request(owner: ManualWeightOwner = human, weight: Double = 4.125, time: Instant = Instant.ofEpochSecond(-60)) =
        ManualWeightRequest(UUID.randomUUID().toString(), owner, weight, time)

    @Test fun incompleteOwnerPastDateIdempotencyAndFailedEnqueueStillSave() = runBlocking {
        val request = request()
        val saved = repository.save(request)
        assertTrue(saved is ManualWeightResult.Saved)
        assertEquals(saved, repository.save(request))
        val row = requireNotNull(database.multiAccountMeasurementDao().get(request.requestId))
        assertEquals(4.125, row.weightKg, 0.0)
        assertEquals(-60L, row.measuredAtEpochSecond)
        assertEquals(MeasurementOrigin.MANUAL, row.origin)
        assertEquals(MeasurementType.WEIGHT_ONLY, row.measurementType)
        assertNull(row.fullValues)
        assertNull(row.sourcePendingId)
        assertNull(row.deduplicationHash)
        assertEquals(ExternalSyncPolicy.AUTO.name, row.externalSyncPolicy)
        assertFalse(row.isManuallyEdited)
        assertEquals(SyncStatus.PENDING.name, row.healthConnectStatus)
    }

    @Test fun sameMinuteUsesCurrentEditedWeightAndConfirmationCreatesSeparateRecord() = runBlocking {
        val initial = request()
        repository.save(initial)
        val row = requireNotNull(database.multiAccountMeasurementDao().get(initial.requestId))
        database.multiAccountMeasurementDao().update(row.copy(weightKg = 4.124,
            measuredAtEpochSecond = -1, externalSyncPolicy = ExternalSyncPolicy.USER_LOCAL.name))
        val duplicate = request(weight = 4.124)
        assertTrue(repository.save(duplicate) is ManualWeightResult.Duplicate)
        assertTrue(repository.save(duplicate, allowDuplicate = true) is ManualWeightResult.Saved)
        assertTrue(repository.save(request(weight = 4.125)) is ManualWeightResult.Saved)
        assertEquals(3, database.measurementDao().getAllForBackup().size)
    }

    @Test fun petIsDirectAndLatestHistoryAndDuplicateWork() = runBlocking {
        val request = request(pet)
        assertTrue(repository.save(request) is ManualWeightResult.Saved)
        assertEquals(repository.save(request), repository.save(request, true))
        val row = requireNotNull(database.petDao().getMeasurement(request.requestId))
        assertNull(row.firstWeightKg)
        assertNull(row.secondWeightKg)
        assertEquals(4.125, row.toDomain().petWeightKg, 0.0)
        assertTrue(repository.save(request(pet)) is ManualWeightResult.Duplicate)
        val latest = RoomPetRepository(database).observePets().first().single().latestMeasurement
        assertEquals(row.id, latest?.id)
        assertEquals(MeasurementOrigin.MANUAL, latest?.origin)
    }

    @Test fun duplicateComparisonUsesRoundedWeightsForHumanAndPetManualWeights() = runBlocking {
        val duplicatedTime = Instant.ofEpochSecond(-120)
        database.multiAccountMeasurementDao().insert(
            MeasurementEntity(
                id = "existing-human-raw-weight",
                deviceAddress = "scale",
                measuredAtEpochSecond = duplicatedTime.epochSecond,
                measurementType = MeasurementType.WEIGHT_ONLY,
                rawPayloadHex = "",
                weightKg = 4.1200000000000045,
                rawWeight = 824,
                impedanceOhm = null, bmi = null, bodyFatPercent = null, bodyFatMassKg = null,
                waterPercent = null, waterMassKg = null, muscleMassKg = null, skeletalMuscleMassKg = null,
                boneMassKg = null, proteinPercent = null, proteinMassKg = null, visceralFatLevel = null,
                basalMetabolicRateKcal = null, metabolicAge = null, leanBodyMassKg = null, algorithmVersion = null,
                accountId = "human",
                externalSyncPolicy = ExternalSyncPolicy.AUTO.name,
                origin = MeasurementOrigin.SCALE,
                createdAtEpochMillis = 100,
            ),
        )
        assertTrue(repository.save(request(weight = 4.12, time = duplicatedTime)) is ManualWeightResult.Duplicate)

        database.petDao().insertMeasurement(
            PetMeasurementEntity(
                id = "existing-pet-raw-weight",
                petId = "pet",
                measuredAtEpochSecond = duplicatedTime.epochSecond,
                firstWeightKg = 70.0,
                secondWeightKg = 80.04,
                petWeightKg = 80.04 - 70.0,
                origin = MeasurementOrigin.SCALE,
            ),
        )
        assertTrue(repository.save(request(pet, weight = 10.04, time = duplicatedTime)) is ManualWeightResult.Duplicate)

        val halfEvenDuplicateTime = Instant.ofEpochSecond(-180)
        database.multiAccountMeasurementDao().insert(
            MeasurementEntity(
                id = "existing-human-half-even",
                deviceAddress = "scale",
                measuredAtEpochSecond = halfEvenDuplicateTime.epochSecond,
                measurementType = MeasurementType.WEIGHT_ONLY,
                rawPayloadHex = "",
                weightKg = 4.0625,
                rawWeight = 813,
                impedanceOhm = null, bmi = null, bodyFatPercent = null, bodyFatMassKg = null,
                waterPercent = null, waterMassKg = null, muscleMassKg = null, skeletalMuscleMassKg = null,
                boneMassKg = null, proteinPercent = null, proteinMassKg = null, visceralFatLevel = null,
                basalMetabolicRateKcal = null, metabolicAge = null, leanBodyMassKg = null, algorithmVersion = null,
                accountId = "human",
                externalSyncPolicy = ExternalSyncPolicy.AUTO.name,
                origin = MeasurementOrigin.SCALE,
                createdAtEpochMillis = 100,
            ),
        )
        val displayed = com.palixander.scalesync.measurements.formatWeight(4.0625, java.util.Locale.US).toDouble()
        assertTrue(repository.save(request(weight = displayed, time = halfEvenDuplicateTime)) is ManualWeightResult.Duplicate)
        assertTrue(repository.save(request(weight = 4.063, time = halfEvenDuplicateTime)) is ManualWeightResult.Saved)
        assertTrue(repository.save(request(weight = displayed, time = halfEvenDuplicateTime.minusSeconds(60))) is ManualWeightResult.Saved)
        assertTrue(repository.save(request(ManualWeightOwner.Human(AccountId("other")), weight = displayed,
            time = halfEvenDuplicateTime)) is ManualWeightResult.Saved)
    }

    @Test fun deletedOwnerFutureInvalidPrecisionAndCrossOwnerRequestReuseNeverInsert() = runBlocking {
        assertEquals(ManualWeightResult.Invalid, repository.save(request(time = now.plusSeconds(4))))
        assertEquals(ManualWeightResult.Invalid, repository.save(request(weight = 4.1251)))
        val saved = request()
        repository.save(saved)
        assertEquals(ManualWeightResult.Invalid, repository.save(saved.copy(owner = pet)))
        assertEquals(ManualWeightResult.Invalid, repository.save(saved.copy(owner = ManualWeightOwner.Human(AccountId("other")))))
        database.accountDao().delete("human")
        database.petDao().deletePet("pet")
        assertEquals(ManualWeightResult.OwnerUnavailable, repository.save(request()))
        assertEquals(ManualWeightResult.OwnerUnavailable, repository.save(request(pet)))
    }

    @Test fun otherAccountIsLocalAndOriginSurvivesRecalculationAndEditing() = runBlocking {
        val request = request(ManualWeightOwner.Human(AccountId("other")))
        repository.save(request)
        val row = requireNotNull(database.multiAccountMeasurementDao().get(request.requestId))
        assertEquals(ExternalSyncPolicy.ACCOUNT_LOCAL.name, row.externalSyncPolicy)
        assertEquals(SyncStatus.LOCAL_ONLY.name, row.healthConnectStatus)
        val updated = row.recalculate(com.palixander.scalesync.core.BodyCompositionCalculator(),
            com.palixander.scalesync.core.UserProfile(180.0, java.time.LocalDate.of(1990, 1, 1), com.palixander.scalesync.core.Sex.MALE))
        assertEquals(MeasurementOrigin.MANUAL, updated.origin)
        val edited = updated.copy(weightKg = 4.123, externalSyncPolicy = ExternalSyncPolicy.USER_LOCAL.name)
        assertTrue(edited.isManuallyEdited)
        assertEquals(MeasurementOrigin.MANUAL, edited.origin)
    }

    @Test fun migration13To14RetainsLegacySourcesAndAllowsDirectPetWeight() {
        val name = "manual-origin-migration"
        helper.createDatabase(name, 13).apply {
            execSQL("INSERT INTO accounts VALUES ('owner','Человек','человек',NULL,NULL,NULL,0,100,100)")
            execSQL("INSERT INTO measurements (id,fingerprint,measurementType,deviceAddress,measuredAtEpochSecond,rawPayloadHex,weightKg,rawWeight,huaweiStatus,healthConnectStatus,huaweiWeightSynced,healthConnectWeightSynced,createdAtEpochMillis,accountId,externalSyncPolicy) VALUES ('human-old','fingerprint','WEIGHT_ONLY','manual',-60,'',4.125,825,'SYNCED','FAILED',1,0,100,'owner','USER_LOCAL')")
            execSQL("INSERT INTO pets (id, displayName, normalizedName, species, createdAtEpochMillis, updatedAtEpochMillis) VALUES ('pet','Кот','кот','CAT',100,100)")
            execSQL("INSERT INTO pet_measurements VALUES ('old','pet',-60,70.0,74.125,4.125)")
            close()
        }
        helper.runMigrationsAndValidate(name, 14, true, AppDatabase.MIGRATION_13_14).apply {
            query("SELECT origin, weightKg, externalSyncPolicy, deviceAddress, huaweiStatus FROM measurements WHERE id = 'human-old'").use {
                assertTrue(it.moveToFirst())
                assertEquals("LEGACY", it.getString(0))
                assertEquals(4.125, it.getDouble(1), 0.0)
                assertEquals("USER_LOCAL", it.getString(2))
                assertEquals("manual", it.getString(3))
                assertEquals("SYNCED", it.getString(4))
            }
            query("SELECT firstWeightKg, secondWeightKg, petWeightKg, origin FROM pet_measurements WHERE id = 'old'").use {
                assertTrue(it.moveToFirst())
                assertEquals(70.0, it.getDouble(0), 0.0)
                assertEquals(74.125, it.getDouble(1), 0.0)
                assertEquals(4.125, it.getDouble(2), 0.0)
                assertEquals("LEGACY", it.getString(3))
            }
            execSQL("INSERT INTO pet_measurements VALUES ('direct','pet',0,NULL,NULL,4.125,'MANUAL')")
            execSQL("DELETE FROM pets WHERE id = 'pet'")
            query("SELECT COUNT(*) FROM pet_measurements").use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
            close()
        }
    }

    @Test fun editedPetWeightKeepsIdentityOriginTimeAndSourceReadings() = runBlocking {
        database.petDao().insertMeasurement(
            PetMeasurementEntity("scale", "pet", -60, 70.0, 74.125, 4.125, MeasurementOrigin.SCALE),
        )

        val edited = RoomPetRepository(database).updateMeasurementWeight(PetId("pet"), "scale", 4.2)

        assertEquals("scale", edited.id)
        assertEquals(PetId("pet"), edited.petId)
        assertEquals(Instant.ofEpochSecond(-60), edited.measuredAt)
        assertEquals(70.0, edited.firstWeightKg!!, 0.0)
        assertEquals(74.125, edited.secondWeightKg!!, 0.0)
        assertEquals(4.2, edited.petWeightKg, 0.0)
        assertEquals(MeasurementOrigin.SCALE, edited.origin)
        assertTrue(edited.isManuallyEdited)

        val restored = RoomPetRepository(database).updateMeasurementWeight(PetId("pet"), "scale", 4.125)
        assertTrue(restored.isManuallyEdited)
    }

    @Test fun updatePetWeightCannotChangeAnotherPetsMeasurement() = runBlocking {
        database.petDao().insertPet(PetEntity("other-pet", "Пёс", "пёс", PetSpecies.DOG, 100, 100))
        database.petDao().insertMeasurement(
            PetMeasurementEntity("scale", "pet", -60, 70.0, 74.125, 4.125, MeasurementOrigin.SCALE),
        )

        assertThrows(PetMeasurementNotFoundException::class.java) {
            runBlocking {
                RoomPetRepository(database).updateMeasurementWeight(PetId("other-pet"), "scale", 5.0)
            }
        }
        assertEquals(4.125, database.petDao().getMeasurement("scale")!!.petWeightKg, 0.0)
    }

    @Test fun migration14To15MarksExistingPetMeasurementsAsNotEdited() {
        val name = "pet-edited-migration"
        helper.createDatabase(name, 14).apply {
            execSQL("INSERT INTO pets (id, displayName, normalizedName, species, createdAtEpochMillis, updatedAtEpochMillis) VALUES ('pet','Кот','кот','CAT',100,100)")
            execSQL("INSERT INTO pet_measurements VALUES ('old','pet',-60,70.0,74.125,4.125,'SCALE')")
            close()
        }

        helper.runMigrationsAndValidate(name, 15, true, AppDatabase.MIGRATION_14_15).apply {
            query("SELECT isManuallyEdited FROM pet_measurements WHERE id = 'old'").use {
                assertTrue(it.moveToFirst())
                assertEquals(0, it.getInt(0))
            }
            close()
        }
    }
}
