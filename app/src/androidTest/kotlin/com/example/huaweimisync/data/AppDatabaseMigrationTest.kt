package com.example.huaweimisync.data

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var openedDatabase: AppDatabase? = null

    @After
    fun closeDatabase() {
        openedDatabase?.close()
        openedDatabase = null
    }

    @Test
    @Throws(IOException::class)
    fun migrate1To2PreservesIdentityHistoryAndSyncState() {
        helper.createDatabase(MIGRATION_DB, 1).apply {
            execSQL(
                """
                INSERT INTO measurements VALUES (
                    'legacy-id', 'aa:bb:cc:dd:ee:ff', 1786451696000, '0022', 70.0, 500,
                    22.9, 20.0, 14.0, 55.0, 38.5, 40.0, 20.0, 3.0, 18.0, 12.6,
                    7.0, 1500.0, 35, 56.0, 'legacy-algorithm', 'FAILED', 'BLOCKED',
                    'huawei-error', 'health-error', 1786451700000
                )
                """.trimIndent(),
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            MIGRATION_DB,
            2,
            true,
            AppDatabase.MIGRATION_1_2,
        )

        migrated.query("SELECT * FROM measurements WHERE id = 'legacy-id'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("legacy-id", cursor.getString(cursor.getColumnIndexOrThrow("id")))
            assertEquals(
                "AA:BB:CC:DD:EE:FF|1786451696|14000",
                cursor.getString(cursor.getColumnIndexOrThrow("fingerprint")),
            )
            assertEquals("FULL", cursor.getString(cursor.getColumnIndexOrThrow("measurementType")))
            assertEquals("FAILED", cursor.getString(cursor.getColumnIndexOrThrow("huaweiStatus")))
            assertEquals(
                "BLOCKED",
                cursor.getString(cursor.getColumnIndexOrThrow("healthConnectStatus")),
            )
            assertEquals(1786451700000, cursor.getLong(cursor.getColumnIndexOrThrow("createdAtEpochMillis")))
            assertEquals(500, cursor.getInt(cursor.getColumnIndexOrThrow("impedanceOhm")))
            assertEquals(56.0, cursor.getDouble(cursor.getColumnIndexOrThrow("leanBodyMassKg")), 0.0)
        }
        migrated.close()
    }

    @Test
    fun concurrentPartialAndFullUpsertsAlwaysLeaveOneFullRow() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        openedDatabase = database
        val dao = database.measurementDao()

        repeat(20) { index ->
            val partial = weightOnlyEntity(index)
            val full = fullEntity(partial)
            coroutineScope {
                val operations = if (index % 2 == 0) {
                    listOf(partial, full)
                } else {
                    listOf(full, partial)
                }
                operations.map { candidate ->
                    async(Dispatchers.IO) { dao.upsertScaleMeasurement(candidate) }
                }.awaitAll()
            }

            val stored = dao.getByFingerprint(partial.fingerprint)
            assertEquals(MeasurementType.FULL, stored?.measurementType)
            assertEquals(500, stored?.impedanceOhm)
            assertTrue(stored?.fullValues != null)
        }

        assertEquals(20, dao.observeAll().first().size)
    }

    private fun weightOnlyEntity(index: Int) = MeasurementEntity(
        id = "new-$index",
        fingerprint = "AA:BB:CC:DD:EE:FF|$index|14000",
        measurementType = MeasurementType.WEIGHT_ONLY,
        deviceAddress = "AA:BB:CC:DD:EE:FF",
        measuredAtEpochMillis = index * 1_000L,
        rawPayloadHex = "00",
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
    )

    private fun fullEntity(partial: MeasurementEntity) = partial.copy(
        measurementType = MeasurementType.FULL,
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
        algorithmVersion = "test",
    )

    private companion object {
        const val MIGRATION_DB = "measurement-migration-test"
    }
}
