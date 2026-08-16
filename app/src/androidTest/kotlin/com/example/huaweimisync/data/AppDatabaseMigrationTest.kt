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
            execSQL(
                """
                INSERT INTO measurements VALUES (
                    'legacy-synced', 'aa:bb:cc:dd:ee:ff', 1786452696000, '0023', 71.0, 510,
                    23.2, 19.0, 13.5, 56.0, 39.8, 41.0, 21.0, 3.1, 18.5, 13.1,
                    7.0, 1510.0, 35, 57.5, 'legacy-algorithm', 'SYNCED', 'SYNCED',
                    NULL, NULL, 1786452700000
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
            assertEquals(0, cursor.getInt(cursor.getColumnIndexOrThrow("huaweiWeightSynced")))
            assertEquals(
                0,
                cursor.getInt(cursor.getColumnIndexOrThrow("healthConnectWeightSynced")),
            )
        }
        migrated.query("SELECT * FROM measurements WHERE id = 'legacy-synced'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(cursor.getColumnIndexOrThrow("huaweiWeightSynced")))
            assertEquals(
                1,
                cursor.getInt(cursor.getColumnIndexOrThrow("healthConnectWeightSynced")),
            )
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

    @Test
    fun stalePartialSyncResultMarksWeightWithoutCompletingUpgradedRow() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        openedDatabase = database
        val dao = database.measurementDao()
        val partial = weightOnlyEntity(100)
        dao.upsertScaleMeasurement(partial)
        dao.upsertScaleMeasurement(fullEntity(partial))

        dao.applyHuaweiSyncResult(
            id = partial.id,
            expectedMeasurementType = MeasurementType.WEIGHT_ONLY.name,
            status = SyncStatus.SYNCED.name,
            error = null,
            markWeightSynced = true,
        )
        dao.applyHealthConnectSyncResult(
            id = partial.id,
            expectedMeasurementType = MeasurementType.WEIGHT_ONLY.name,
            status = SyncStatus.SYNCED.name,
            error = null,
            markWeightSynced = true,
        )

        val stored = dao.get(partial.id)
        assertEquals(MeasurementType.FULL, stored?.measurementType)
        assertEquals(SyncStatus.PENDING.name, stored?.huaweiStatus)
        assertEquals(SyncStatus.PENDING.name, stored?.healthConnectStatus)
        assertTrue(stored?.huaweiWeightSynced == true)
        assertTrue(stored?.healthConnectWeightSynced == true)
    }

    @Test
    fun directionSpecificPendingQueriesIgnoreOtherProvidersLocalOnlyStatus() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        openedDatabase = database
        val dao = database.measurementDao()
        dao.insert(
            fullEntity(weightOnlyEntity(200)).copy(
                huaweiStatus = SyncStatus.LOCAL_ONLY.name,
                healthConnectStatus = SyncStatus.FAILED.name,
            ),
        )
        dao.insert(
            fullEntity(weightOnlyEntity(201)).copy(
                huaweiStatus = SyncStatus.BLOCKED.name,
                healthConnectStatus = SyncStatus.LOCAL_ONLY.name,
            ),
        )

        assertEquals(listOf("new-200"), dao.idsNeedingHealthConnectSync())
        assertEquals(listOf("new-201"), dao.idsNeedingHuaweiSync())
        assertEquals(listOf("new-200", "new-201"), dao.idsNeedingSync())
    }

    @Test
    fun staleWeightOnlyEditorSnapshotCannotDowngradeConcurrentFullUpgrade() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        openedDatabase = database
        val dao = database.measurementDao()
        val partial = weightOnlyEntity(300)
        dao.upsertScaleMeasurement(partial)

        val staleEditedPartial = partial.copy(
            weightKg = 69.25,
            huaweiStatus = SyncStatus.LOCAL_ONLY.name,
            healthConnectStatus = SyncStatus.LOCAL_ONLY.name,
        )
        dao.upsertScaleMeasurement(fullEntity(partial))

        assertEquals(0, dao.updateIfSameType(staleEditedPartial))
        val stored = dao.get(partial.id)
        assertEquals(MeasurementType.FULL, stored?.measurementType)
        assertEquals(70.0, stored?.weightKg ?: 0.0, 0.0)
        assertEquals(500, stored?.impedanceOhm)
        assertTrue(stored?.fullValues != null)
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
