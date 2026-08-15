package com.example.huaweimisync.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MeasurementDaoTest {
    private lateinit var database: AppDatabase
    private lateinit var dao: MeasurementDao

    @Before
    fun createDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.measurementDao()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun latestForDeviceIgnoresAddressCaseAndUsesDeterministicTieBreakers() = runBlocking {
        val measuredAt = 2_000L
        val matchingAddress = "AA:BB:CC:DD:EE:FF"
        listOf(
            measurement(
                id = "other-device-newer",
                deviceAddress = "11:22:33:44:55:66",
                measuredAt = 3_000L,
                createdAt = 3_000L,
            ),
            measurement(
                id = "older-measurement",
                deviceAddress = matchingAddress,
                measuredAt = 1_000L,
                createdAt = 9_000L,
            ),
            measurement(
                id = "id-a",
                deviceAddress = matchingAddress.lowercase(),
                measuredAt = measuredAt,
                createdAt = 1_000L,
            ),
            measurement(
                id = "id-z",
                deviceAddress = matchingAddress,
                measuredAt = measuredAt,
                createdAt = 1_000L,
            ),
            measurement(
                id = "created-latest",
                deviceAddress = matchingAddress,
                measuredAt = measuredAt,
                createdAt = 2_000L,
            ),
        ).forEach { measurement ->
            assertTrue(dao.insert(measurement) > 0L)
        }

        assertEquals(
            "created-latest",
            dao.getLatestForDevice(matchingAddress.lowercase())?.id,
        )

        assertEquals(1, dao.delete("created-latest"))
        assertEquals("id-z", dao.getLatestForDevice(matchingAddress)?.id)
    }
}

private fun measurement(
    id: String,
    deviceAddress: String,
    measuredAt: Long,
    createdAt: Long,
) = MeasurementEntity(
    id = id,
    deviceAddress = deviceAddress,
    measuredAtEpochMillis = measuredAt,
    rawPayloadHex = "010203",
    weightKg = 70.0,
    impedanceOhm = 500,
    bmi = 22.9,
    bodyFatPercent = 20.0,
    bodyFatMassKg = 14.0,
    waterPercent = 55.0,
    waterMassKg = 38.5,
    muscleMassKg = 40.0,
    skeletalMuscleMassKg = 20.0,
    boneMassKg = 3.0,
    proteinPercent = 18.0,
    proteinMassKg = 12.6,
    visceralFatLevel = 7.0,
    basalMetabolicRateKcal = 1_500.0,
    metabolicAge = 35,
    leanBodyMassKg = 56.0,
    algorithmVersion = "test-v1",
    createdAtEpochMillis = createdAt,
)
