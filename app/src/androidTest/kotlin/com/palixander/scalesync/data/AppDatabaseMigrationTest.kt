package com.palixander.scalesync.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.palixander.scalesync.core.BodyCompositionCalculator
import com.palixander.scalesync.core.Sex
import com.palixander.scalesync.domain.AccountId
import com.palixander.scalesync.domain.AccountProfile
import com.palixander.scalesync.domain.AccountUpdate
import com.palixander.scalesync.domain.ProfileHistoryUpdateMode
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {
    @get:Rule
    val helper = RetainedMigrationTestHelper(
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
    fun migrate17To18CreatesReminderTablesAndOwnerCascadeTriggers() {
        helper.createDatabase(MIGRATION_17_18_DB, 17).apply {
            execSQL("INSERT INTO accounts VALUES ('a','Alex','alex',NULL,NULL,NULL,0,2,3,NULL)")
            close()
        }

        helper.runMigrationsAndValidate(
            MIGRATION_17_18_DB,
            18,
            true,
            AppDatabase.MIGRATION_17_18,
        ).apply {
            execSQL("INSERT INTO weighing_reminder_schedules VALUES ('r','ACCOUNT','a',540,1,'REGULAR',1,1,1)")
            execSQL("INSERT INTO weighing_reminder_runtime (scheduleId,generation,regularStatus,snoozeStatus) VALUES ('r',0,'NONE','NONE')")
            execSQL("DELETE FROM accounts WHERE id='a'")
            query("SELECT COUNT(*) FROM weighing_reminder_schedules").use {
                assertTrue(it.moveToFirst())
                assertEquals(0, it.getInt(0))
            }
            query("SELECT COUNT(*) FROM weighing_reminder_runtime").use {
                assertTrue(it.moveToFirst())
                assertEquals(0, it.getInt(0))
            }
            close()
        }
    }

    @Test
    fun migrate18To19AddsNullablePerScheduleAlarmSound() {
        helper.createDatabase(MIGRATION_18_19_DB, 18).apply {
            execSQL("INSERT INTO accounts VALUES ('a','Alex','alex',NULL,NULL,NULL,0,2,3,NULL)")
            execSQL("INSERT INTO weighing_reminder_schedules VALUES ('r','ACCOUNT','a',540,1,'ALARM',1,1,1)")
            close()
        }
        helper.runMigrationsAndValidate(
            MIGRATION_18_19_DB, 19, true, AppDatabase.MIGRATION_18_19,
        ).apply {
            query("SELECT alarmSoundUri FROM weighing_reminder_schedules WHERE id='r'").use {
                assertTrue(it.moveToFirst())
                assertTrue(it.isNull(0))
            }
            close()
        }
    }

    @Test
    fun migrate14To15RetainsMeasurementAndDropsUnretainedColumns() {
        helper.createDatabase(MIGRATION_14_15_DB, 14).apply {
            execSQL("ALTER TABLE measurements ADD COLUMN unusedSyncState TEXT")
            execSQL("INSERT INTO accounts VALUES ('a','Alex','alex',180.0,1,'MALE',1,2,3)")
            execSQL(
                """
                INSERT INTO measurements VALUES (
                    'm','fingerprint','FULL','AA:BB',123,'00ff',70.5,14100,500,22.0,
                    20.0,14.1,55.0,38.8,40.0,20.0,3.0,18.0,12.6,7.0,1500.0,35,
                    56.4,'algo','SYNCED','health error',1,
                    456,'a','AUTO','pending','dedupe','health snapshot',
                    179.5,'CAPTURED','SCALE','discarded'
                )
                """.trimIndent(),
            )
            close()
        }

        helper.runMigrationsAndValidate(MIGRATION_14_15_DB, 15, true, AppDatabase.MIGRATION_14_15).apply {
            query("SELECT weightKg, rawWeight, healthConnectStatus, healthConnectError, healthConnectWeightSynced, accountId, deduplicationHash, healthConnectSyncedCalculatedValues, ratingHeightCm, ratingHeightOrigin, origin FROM measurements WHERE id='m'").use {
                assertTrue(it.moveToFirst())
                assertEquals(70.5, it.getDouble(0), 0.0)
                assertEquals(14100, it.getInt(1))
                assertEquals("SYNCED", it.getString(2))
                assertEquals("health error", it.getString(3))
                assertEquals(1, it.getInt(4))
                assertEquals("a", it.getString(5))
                assertEquals("dedupe", it.getString(6))
                assertEquals("health snapshot", it.getString(7))
                assertEquals(179.5, it.getDouble(8), 0.0)
                assertEquals("CAPTURED", it.getString(9))
                assertEquals("SCALE", it.getString(10))
            }
            query("PRAGMA table_info(measurements)").use { cursor ->
                val columns = buildSet { while (cursor.moveToNext()) add(cursor.getString(1)) }
                assertFalse("unusedSyncState" in columns)
            }
            close()
        }
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
                    7.0, 1500.0, 35, 56.0, 'legacy-algorithm', 'BLOCKED',
                    'health-error', 1786451700000
                )
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO measurements VALUES (
                    'legacy-synced', 'aa:bb:cc:dd:ee:ff', 1786452696000, '0023', 71.0, 510,
                    23.2, 19.0, 13.5, 56.0, 39.8, 41.0, 21.0, 3.1, 18.5, 13.1,
                    7.0, 1510.0, 35, 57.5, 'legacy-algorithm', 'SYNCED',
                    NULL, 1786452700000
                )
                """.trimIndent(),
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            MIGRATION_DB,
            2,
            true,
            AppDatabase.migration1To2(context),
        )

        migrated.query("SELECT * FROM measurements WHERE id = 'legacy-id'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("legacy-id", cursor.getString(cursor.getColumnIndexOrThrow("id")))
            assertEquals(
                "AA:BB:CC:DD:EE:FF|1786451696|14000",
                cursor.getString(cursor.getColumnIndexOrThrow("fingerprint")),
            )
            assertEquals("FULL", cursor.getString(cursor.getColumnIndexOrThrow("measurementType")))
            assertEquals(
                "BLOCKED",
                cursor.getString(cursor.getColumnIndexOrThrow("healthConnectStatus")),
            )
            assertEquals(1786451700000, cursor.getLong(cursor.getColumnIndexOrThrow("createdAtEpochMillis")))
            assertEquals(500, cursor.getInt(cursor.getColumnIndexOrThrow("impedanceOhm")))
            assertEquals(56.0, cursor.getDouble(cursor.getColumnIndexOrThrow("leanBodyMassKg")), 0.0)
            assertEquals(
                0,
                cursor.getInt(cursor.getColumnIndexOrThrow("healthConnectWeightSynced")),
            )
        }
        migrated.query("SELECT * FROM measurements WHERE id = 'legacy-synced'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(
                1,
                cursor.getInt(cursor.getColumnIndexOrThrow("healthConnectWeightSynced")),
            )
        }
        migrated.close()
    }

    @Test
    fun migrate2To3PreservesStateAndRestoresExactFieldsFromMillis() {
        helper.createDatabase(MIGRATION_2_3_DB, 2).apply {
            execSQL(
                """
                INSERT INTO accounts (
                    id, displayName, normalizedName, heightCm, birthDateEpochDay, sex,
                    isProfileComplete, createdAtEpochMillis, updatedAtEpochMillis
                ) VALUES ('account', 'Alice', 'alice', 175.0, 7305, 'FEMALE', 1, 10, 20)
                """.trimIndent(),
            )
            execSQL("INSERT INTO app_state VALUES (1, 'account', 4.25)")
            fun insertMeasurement(id: String, millis: Long) {
                execSQL(
                    """
                    INSERT INTO measurements (
                        id, fingerprint, measurementType, deviceAddress, measuredAtEpochMillis,
                        rawPayloadHex, weightKg, healthConnectStatus,
                        healthConnectWeightSynced, createdAtEpochMillis,
                        accountId, externalSyncPolicy
                    ) VALUES (?, ?, 'WEIGHT_ONLY', 'AA:BB:CC:DD:EE:FF', ?, '00', 70.0,
                        'PENDING', 0, 30, 'account', 'AUTO')
                    """.trimIndent(),
                    arrayOf<Any>(id, "fingerprint-$id", millis),
                )
            }
            insertMeasurement("positive", 1_786_451_696_123L)
            insertMeasurement("negative", -1L)
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            MIGRATION_2_3_DB,
            3,
            true,
            AppDatabase.MIGRATION_2_3,
        )

        migrated.query("SELECT primaryAccountId, weightDeltaKg, ignoreUnknownMeasurements FROM app_state").use {
            assertTrue(it.moveToFirst())
            assertEquals("account", it.getString(0))
            assertEquals(4.25, it.getDouble(1), 0.0)
            assertEquals(0, it.getInt(2))
        }
        migrated.query(
            "SELECT id, measuredAtEpochMillis, measuredAtEpochSecond, measuredAtNano " +
                "FROM measurements ORDER BY id",
        ).use {
            assertTrue(it.moveToFirst())
            assertEquals("negative", it.getString(0))
            assertEquals(-1L, it.getLong(1))
            assertEquals(-1L, it.getLong(2))
            assertEquals(999_000_000, it.getInt(3))
            assertTrue(it.moveToNext())
            assertEquals("positive", it.getString(0))
            assertEquals(1_786_451_696_123L, it.getLong(1))
            assertEquals(1_786_451_696L, it.getLong(2))
            assertEquals(123_000_000, it.getInt(3))
        }
        migrated.close()
    }

    @Test
    fun migrate3To4PreservesRowsRelationshipsAndStatusWhileDroppingSubseconds() {
        helper.createDatabase(MIGRATION_3_4_DB, 3).apply {
            execSQL(
                """
                INSERT INTO accounts (
                    id, displayName, normalizedName, heightCm, birthDateEpochDay, sex,
                    isProfileComplete, createdAtEpochMillis, updatedAtEpochMillis
                ) VALUES
                    ('account-a', 'Alice', 'alice', 175.0, 7305, 'FEMALE', 1, 10, 20),
                    ('account-b', 'Bob', 'bob', 180.0, 7000, 'MALE', 1, 11, 21)
                """.trimIndent(),
            )
            execSQL("INSERT INTO app_state VALUES (1, 'account-b', 4.25, 1)")
            fun insertMeasurement(
                id: String,
                fingerprint: String,
                millis: Long,
                second: Long,
                nano: Int,
                accountId: String,
                healthStatus: String,
                sourcePendingId: String?,
                deduplicationHash: String,
            ) {
                execSQL(
                    """
                    INSERT INTO measurements (
                        id, fingerprint, measurementType, deviceAddress, measuredAtEpochMillis,
                        measuredAtEpochSecond, measuredAtNano, rawPayloadHex, weightKg,
                        healthConnectStatus, healthConnectError,
                        healthConnectWeightSynced, createdAtEpochMillis,
                        accountId, externalSyncPolicy, sourcePendingId, deduplicationHash
                    ) VALUES (?, ?, 'WEIGHT_ONLY', 'AA:BB:CC:DD:EE:FF', ?, ?, ?, '00', 70.005,
                        ?, 'health-error', 0, 30, ?, 'AUTO', ?, ?)
                    """.trimIndent(),
                    arrayOf<Any?>(
                        id,
                        fingerprint,
                        millis,
                        second,
                        nano,
                        healthStatus,
                        accountId,
                        sourcePendingId,
                        deduplicationHash,
                    ),
                )
            }
            insertMeasurement(
                id = "duplicate-a",
                fingerprint = "fingerprint-a",
                millis = 1_234L,
                second = 1L,
                nano = 234_000_000,
                accountId = "account-a",
                healthStatus = "FAILED",
                sourcePendingId = "old-pending-a",
                deduplicationHash = "hash-a",
            )
            insertMeasurement(
                id = "duplicate-b",
                fingerprint = "fingerprint-b",
                millis = 1_999L,
                second = 1L,
                nano = 999_000_000,
                accountId = "account-b",
                healthStatus = "SYNCED",
                sourcePendingId = "old-pending-b",
                deduplicationHash = "hash-b",
            )
            execSQL(
                """
                INSERT INTO pending_measurements VALUES (
                    'pending', '11:22:33:44:55:66', 9, 987000000, 71.0, 501, 1, 1,
                    X'0102', 'pending-hash', 5000, 14200
                )
                """.trimIndent(),
            )
            execSQL("INSERT INTO measurement_tombstones VALUES ('old-tombstone', 9000)")
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            MIGRATION_3_4_DB,
            4,
            true,
            AppDatabase.MIGRATION_3_4,
        )

        migrated.query(
            """
            SELECT id, fingerprint, measuredAtEpochSecond, rawWeight, accountId,
                healthConnectStatus, healthConnectError,
                healthConnectWeightSynced, sourcePendingId,
                deduplicationHash
            FROM measurements ORDER BY id
            """.trimIndent(),
        ).use {
            assertTrue(it.moveToFirst())
            assertEquals("duplicate-a", it.getString(0))
            assertEquals("fingerprint-a", it.getString(1))
            assertEquals(1L, it.getLong(2))
            assertEquals(14_001, it.getInt(3))
            assertEquals("account-a", it.getString(4))
            assertEquals("FAILED", it.getString(5))
            assertEquals("health-error", it.getString(6))
            assertEquals(0, it.getInt(7))
            assertEquals("old-pending-a", it.getString(8))
            assertEquals("hash-a", it.getString(9))
            assertTrue(it.moveToNext())
            assertEquals("duplicate-b", it.getString(0))
            assertEquals(1L, it.getLong(2))
            assertEquals("account-b", it.getString(4))
            assertTrue(!it.moveToNext())
        }
        migrated.query(
            "SELECT measuredAtEpochSecond, rawWeight, finalizeAfterEpochMillis " +
                "FROM pending_measurements WHERE id = 'pending'",
        ).use {
            assertTrue(it.moveToFirst())
            assertEquals(9L, it.getLong(0))
            assertEquals(14_200, it.getInt(1))
            assertEquals(15_000L, it.getLong(2))
        }
        migrated.query(
            "SELECT expiresAtEpochMillis, deviceAddress, measuredAtEpochSecond, rawWeight " +
                "FROM measurement_tombstones WHERE deduplicationHash = 'old-tombstone'",
        ).use {
            assertTrue(it.moveToFirst())
            assertEquals(9_000L, it.getLong(0))
            assertTrue(it.isNull(1))
            assertTrue(it.isNull(2))
            assertTrue(it.isNull(3))
        }
        migrated.query("SELECT primaryAccountId FROM app_state").use {
            assertTrue(it.moveToFirst())
            assertEquals("account-b", it.getString(0))
        }
        migrated.query("PRAGMA table_info(measurements)").use {
            val names = buildSet {
                while (it.moveToNext()) add(it.getString(it.getColumnIndexOrThrow("name")))
            }
            assertTrue("measuredAtEpochMillis" !in names)
            assertTrue("measuredAtNano" !in names)
        }
        migrated.query("PRAGMA table_info(pending_measurements)").use {
            val names = buildSet {
                while (it.moveToNext()) add(it.getString(it.getColumnIndexOrThrow("name")))
            }
            assertTrue("measuredAtNano" !in names)
        }
        migrated.close()
    }

    @Test
    fun migrate4To5PreservesAccountsHistoryAndSyncStateWithoutInventingSnapshots() = runBlocking {
        helper.createDatabase(MIGRATION_4_5_DB, 4).apply {
            execSQL(
                """
                INSERT INTO accounts (
                    id, displayName, normalizedName, heightCm, birthDateEpochDay, sex,
                    isProfileComplete, createdAtEpochMillis, updatedAtEpochMillis
                ) VALUES ('account', 'Alice', 'alice', 175.0, 7305, 'FEMALE', 1, 10, 20)
                """.trimIndent(),
            )
            execSQL("INSERT INTO app_state VALUES (1, 'account', 4.25, 1)")
            execSQL(
                """
                INSERT INTO measurements (
                    id, fingerprint, measurementType, deviceAddress, measuredAtEpochSecond,
                    rawPayloadHex, weightKg, rawWeight, impedanceOhm, bmi, bodyFatPercent,
                    bodyFatMassKg, waterPercent, waterMassKg, muscleMassKg,
                    skeletalMuscleMassKg, boneMassKg, proteinPercent, proteinMassKg,
                    visceralFatLevel, basalMetabolicRateKcal, metabolicAge, leanBodyMassKg,
                    algorithmVersion, healthConnectStatus,
                    healthConnectError, healthConnectWeightSynced,
                    createdAtEpochMillis, accountId, externalSyncPolicy
                ) VALUES (
                    'measurement', 'fingerprint', 'FULL', 'AA:BB:CC:DD:EE:FF', 1786451696,
                    '00', 70.0, 14000, 500, 22.9, 20.0, 14.0, 55.0, 38.5, 40.0,
                    20.0, 3.0, 18.0, 12.6, 7.0, 1500.0, 35, 56.0, 'algorithm',
                    'FAILED', 'retry', 0, 30, 'account', 'AUTO'
                )
                """.trimIndent(),
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            MIGRATION_4_5_DB,
            5,
            true,
            AppDatabase.MIGRATION_4_5,
        )

        migrated.query(
            """
            SELECT accountId, healthConnectStatus,
                healthConnectWeightSynced,
                healthConnectSyncedCalculatedValues
            FROM measurements WHERE id = 'measurement'
            """.trimIndent(),
        ).use {
            assertTrue(it.moveToFirst())
            assertEquals("account", it.getString(0))
            assertEquals("FAILED", it.getString(1))
            assertEquals(0, it.getInt(2))
            assertTrue(it.isNull(3))
        }
        migrated.query("SELECT displayName FROM accounts WHERE id = 'account'").use {
            assertTrue(it.moveToFirst())
            assertEquals("Alice", it.getString(0))
        }
        migrated.query("SELECT primaryAccountId, weightDeltaKg FROM app_state").use {
            assertTrue(it.moveToFirst())
            assertEquals("account", it.getString(0))
            assertEquals(4.25, it.getDouble(1), 0.0)
        }
        migrated.close()

        val room = AppDatabase.build(context, MIGRATION_4_5_DB)
        openedDatabase = room
        val beforeRecalculation = requireNotNull(room.measurementDao().get("measurement"))
        val repository = RoomAccountRepository(
            database = room,
            calculator = BodyCompositionCalculator(ZoneId.of("UTC")),
            now = { Instant.parse("2026-08-21T12:00:00Z") },
        )

        repository.updateAccount(
            AccountUpdate(
                id = AccountId("account"),
                displayName = "Alice",
                profile = AccountProfile.Complete(
                    heightCm = 181.0,
                    birthDate = LocalDate.of(1990, 1, 1),
                    sex = Sex.FEMALE,
                ),
            ),
            ProfileHistoryUpdateMode.RECALCULATE,
        )

        val recalculated = requireNotNull(room.measurementDao().get("measurement"))
        assertTrue(recalculated.healthConnectSyncedCalculatedValues == null)
        assertEquals(SyncStatus.FAILED.name, recalculated.healthConnectStatus)
        assertEquals("retry", recalculated.healthConnectError)
        assertEquals(beforeRecalculation.rawPayloadHex, recalculated.rawPayloadHex)
        assertEquals(beforeRecalculation.rawWeight, recalculated.rawWeight)
        assertEquals(beforeRecalculation.impedanceOhm, recalculated.impedanceOhm)
        assertTrue(beforeRecalculation.fullValues != recalculated.fullValues)
        assertTrue(!recalculated.hasProfileSyncMismatch)
    }

    @Test
    fun migrate5To6PreservesPendingRowsAsUnassignedAndAddsAccountIndex() {
        helper.createDatabase(MIGRATION_5_6_DB, 5).apply {
            execSQL(
                """
                INSERT INTO pending_measurements (
                    id, deviceAddress, measuredAtEpochSecond, weightKg, impedanceOhm,
                    isStable, hasImpedance, rawPayload, deduplicationHash,
                    enqueuedAtEpochMillis, rawWeight, finalizeAfterEpochMillis
                ) VALUES (
                    'pending', 'AA:BB:CC:DD:EE:FF', 1786451696, 70.0, 0,
                    1, 0, X'01', 'hash', 1000, 14000, 11000
                )
                """.trimIndent(),
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            MIGRATION_5_6_DB,
            6,
            true,
            AppDatabase.MIGRATION_5_6,
        )
        migrated.query(
            "SELECT id, provisionalAccountId FROM pending_measurements WHERE id = 'pending'",
        ).use {
            assertTrue(it.moveToFirst())
            assertEquals("pending", it.getString(0))
            assertTrue(it.isNull(1))
        }
        migrated.query("PRAGMA index_list('pending_measurements')").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            val names = buildSet {
                while (cursor.moveToNext()) add(cursor.getString(nameIndex))
            }
            assertTrue(
                "index_pending_measurements_provisionalAccountId_measuredAtEpochSecond_id" in names,
            )
        }
        migrated.close()
    }

    @Test
    fun migrate6To7AddsEmptyBackupImportCheckpointJournal() {
        helper.createDatabase(MIGRATION_6_7_DB, 6).close()

        val migrated = helper.runMigrationsAndValidate(
            MIGRATION_6_7_DB,
            7,
            true,
            AppDatabase.MIGRATION_6_7,
        )

        migrated.query("SELECT COUNT(*) FROM backup_import_checkpoint").use {
            assertTrue(it.moveToFirst())
            assertEquals(0, it.getInt(0))
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

        dao.applyHealthConnectSyncResult(
            id = partial.id,
            expectedMeasurementType = MeasurementType.WEIGHT_ONLY.name,
            status = SyncStatus.SYNCED.name,
            error = null,
            markWeightSynced = true,
        )

        val stored = dao.get(partial.id)
        assertEquals(MeasurementType.FULL, stored?.measurementType)
        assertEquals(SyncStatus.PENDING.name, stored?.healthConnectStatus)
        assertTrue(stored?.healthConnectWeightSynced == true)
    }

    @Test
    fun pendingQueriesExcludeHealthConnectLocalOnlyRows() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        openedDatabase = database
        val dao = database.measurementDao()
        dao.insert(
            fullEntity(weightOnlyEntity(200)).copy(
                healthConnectStatus = SyncStatus.FAILED.name,
            ),
        )
        dao.insert(
            fullEntity(weightOnlyEntity(201)).copy(
                healthConnectStatus = SyncStatus.LOCAL_ONLY.name,
            ),
        )

        assertEquals(listOf("new-200"), dao.idsNeedingHealthConnectSync())
        assertEquals(listOf("new-200"), dao.idsNeedingSync())
    }

    @Test
    fun successfulSyncResultStoresHealthConnectCalculatedSnapshot() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        openedDatabase = database
        val dao = database.measurementDao()
        val measurement = fullEntity(weightOnlyEntity(250))
        dao.insert(measurement)
        val healthConnectSnapshot = measurement.copy(bodyFatPercent = 24.0)
            .currentCalculatedValuesSnapshot(ExternalSyncDestination.HEALTH_CONNECT)!!.encode()
        dao.applyHealthConnectSyncResult(
            id = measurement.id,
            expectedMeasurementType = MeasurementType.FULL.name,
            status = SyncStatus.SYNCED.name,
            error = null,
            markWeightSynced = true,
            syncedCalculatedValues = healthConnectSnapshot,
        )

        val stored = dao.get(measurement.id)!!
        assertEquals(healthConnectSnapshot, stored.healthConnectSyncedCalculatedValues)
        assertTrue(stored.hasProfileSyncMismatch)
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
        measuredAtEpochSecond = index.toLong(),
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
        const val MIGRATION_2_3_DB = "measurement-migration-2-3-test"
        const val MIGRATION_3_4_DB = "measurement-migration-3-4-test"
        const val MIGRATION_4_5_DB = "measurement-migration-4-5-test"
        const val MIGRATION_5_6_DB = "measurement-migration-5-6-test"
        const val MIGRATION_6_7_DB = "measurement-migration-6-7-test"
        const val MIGRATION_14_15_DB = "measurement-migration-14-15-test"
        const val MIGRATION_17_18_DB = "measurement-migration-17-18-test"
        const val MIGRATION_18_19_DB = "measurement-migration-18-19-test"
    }
}
