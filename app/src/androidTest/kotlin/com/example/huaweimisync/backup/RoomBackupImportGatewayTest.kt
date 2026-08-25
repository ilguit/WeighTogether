package com.example.huaweimisync.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.huaweimisync.data.AccountEntity
import com.example.huaweimisync.data.AppDatabase
import com.example.huaweimisync.data.AppStateEntity
import com.example.huaweimisync.data.MeasurementEntity
import com.example.huaweimisync.data.MeasurementTombstoneEntity
import com.example.huaweimisync.data.MeasurementType
import com.example.huaweimisync.data.PendingMeasurementEntity
import com.example.huaweimisync.data.PortableProfileSettings
import com.example.huaweimisync.data.SyncStatus
import com.example.huaweimisync.data.VersionedPortableProfileSettings
import com.example.huaweimisync.domain.ExternalSyncPolicy
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomBackupImportGatewayTest {
    private lateinit var database: AppDatabase
    private lateinit var gateway: RoomBackupImportGateway
    private var currentSettings = VersionedPortableProfileSettings(
        PortableProfileSettings(null, null, false, null, null),
        0L,
    )

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            AppDatabase::class.java,
        ).build()
        gateway = RoomBackupImportGateway(
            database,
            { currentSettings },
        )
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun mergeSkipsExactRowsAndPreservesImportedSyncState() = runBlocking {
        val account = account("a")
        val measurement = measurement("m", "a", SyncStatus.SYNCED)
        database.accountDao().insert(account)
        database.measurementDao().insert(measurement)

        apply(preview(BackupImportMode.MERGE, listOf(account, account("b")),
            listOf(measurement, measurement("n", "b", SyncStatus.FAILED))))

        assertEquals(listOf("a", "b"), database.accountDao().getAll().map { it.id })
        val rows = database.measurementDao().getAllForBackup()
        assertEquals(2, rows.size)
        assertEquals(SyncStatus.SYNCED.name, rows.single { it.id == "m" }.huaweiStatus)
        assertEquals(SyncStatus.FAILED.name, rows.single { it.id == "n" }.huaweiStatus)
    }

    @Test
    fun replaceClearsTransientTablesAndRollsBackWholeReplacementOnForeignKeyFailure() = runBlocking {
        val original = account("old")
        database.accountDao().insert(original)
        database.measurementDao().insert(measurement("old-m", "old", SyncStatus.LOCAL_ONLY))
        database.pendingMeasurementDao().insert(pending("pending"))
        database.pendingMeasurementDao().upsertTombstone(
            MeasurementTombstoneEntity("hash", 10_000L),
        )

        apply(preview(BackupImportMode.REPLACE, listOf(account("new")),
            listOf(measurement("new-m", "new", SyncStatus.SYNCED))))
        assertEquals(listOf("new"), database.accountDao().getAll().map { it.id })
        assertEquals(0, database.pendingMeasurementDao().getAll().size)
        assertEquals(0, database.pendingMeasurementDao().tombstoneCount())

        assertThrows(Exception::class.java) {
            runBlocking {
                gateway.stage(
                    preview(
                        BackupImportMode.REPLACE,
                        listOf(account("broken")),
                        listOf(measurement("orphan", "missing", SyncStatus.SYNCED)),
                    ),
                )
            }
        }
        assertEquals(listOf("new"), database.accountDao().getAll().map { it.id })
        assertEquals(listOf("new-m"), database.measurementDao().getAllForBackup().map { it.id })
        assertEquals(null, gateway.pendingRecovery())
    }

    @Test
    fun settingsFailureKeepsCommittedDatabaseAndConcurrentWriterForRecovery() = runBlocking {
        database.accountDao().insert(account("old"))
        database.measurementDao().insert(measurement("old-m", "old", SyncStatus.LOCAL_ONLY))
        val target = preview(
            BackupImportMode.REPLACE,
            listOf(account("new")),
            listOf(measurement("new-m", "new", SyncStatus.SYNCED)),
        )
        val result = BackupImportApplier(
            gateway,
            PortableSettingsWriter {
                runBlocking {
                    database.accountDao().insert(account("concurrent"))
                    database.measurementDao().insert(
                        measurement("concurrent-m", "concurrent", SyncStatus.LOCAL_ONLY),
                    )
                }
                error("settings commit failed")
            },
        ).apply(target)

        assertTrue(result is BackupImportApplyResult.CompletedPendingRecovery)
        assertEquals(listOf("concurrent", "new"), database.accountDao().getAll().map { it.id })
        assertEquals(
            listOf("concurrent-m", "new-m"),
            database.measurementDao().getAllForBackup().map { it.id },
        )
        assertEquals(target.settings, gateway.pendingRecovery()?.settings)
    }

    @Test
    fun stagedImportLeavesTargetSettingsForStartupRollForward() = runBlocking {
        val target = preview(
            BackupImportMode.MERGE,
            listOf(account("new")),
            listOf(measurement("new-m", "new", SyncStatus.SYNCED)),
        )

        gateway.stage(target)

        assertEquals(target.settings, gateway.pendingRecovery()?.settings)
        assertEquals(true, gateway.pendingRecovery()?.sweepNeeded)
        assertEquals(listOf("new"), database.accountDao().getAll().map { it.id })
    }

    @Test
    fun startupRecoveryRollsForwardLegacyV7TargetCheckpointAndSweepsAfterCleanup() = runBlocking {
        seedLegacyV7Checkpoint(
            phase = "TARGET_APPLIED",
            previousSettingsJson = LEGACY_PREVIOUS_SETTINGS_JSON,
            targetSettingsJson = LEGACY_TARGET_SETTINGS_JSON,
        )
        val events = mutableListOf<String>()

        BackupImportApplier(
            gateway = gateway,
            settingsWriter = PortableSettingsWriter { events += "settings:${it.scaleAddress}" },
            completionHooks = listOf(
                BackupImportCompletionHook {
                    assertEquals(null, database.backupImportCheckpointDao().get())
                    events += "sweep"
                },
            ),
        ).recoverPendingImport()

        assertEquals(listOf("settings:AA:BB", "sweep"), events)
        assertEquals(null, database.backupImportCheckpointDao().get())
    }

    @Test
    fun startupRecoveryRestoresLegacyV7RollbackSettingsWithoutSweep() = runBlocking {
        seedLegacyV7Checkpoint(
            phase = "ROLLBACK_APPLIED",
            previousSettingsJson = LEGACY_PREVIOUS_SETTINGS_JSON,
            targetSettingsJson = LEGACY_TARGET_SETTINGS_JSON,
        )
        val applied = mutableListOf<PortableProfileSettings>()
        var sweeps = 0

        BackupImportApplier(
            gateway = gateway,
            settingsWriter = PortableSettingsWriter(applied::add),
            completionHooks = listOf(BackupImportCompletionHook { sweeps += 1 }),
        ).recoverPendingImport()

        assertEquals(1, applied.size)
        assertEquals("Old scale", applied.single().scaleName)
        assertEquals(0, sweeps)
        assertEquals(null, database.backupImportCheckpointDao().get())
    }

    @Test
    fun startupRecoveryPreservesMalformedLegacyV7CheckpointWithoutCrashing() = runBlocking {
        seedLegacyV7Checkpoint(
            phase = "TARGET_APPLIED",
            previousSettingsJson = LEGACY_PREVIOUS_SETTINGS_JSON,
            targetSettingsJson = "{not-json",
        )
        var settingsWrites = 0
        var sweeps = 0

        BackupImportApplier(
            gateway = gateway,
            settingsWriter = PortableSettingsWriter { settingsWrites += 1 },
            completionHooks = listOf(BackupImportCompletionHook { sweeps += 1 }),
        ).recoverPendingImport()

        assertEquals(0, settingsWrites)
        assertEquals(0, sweeps)
        assertNotNull(database.backupImportCheckpointDao().get())
    }

    @Test
    fun checkpointSizeDoesNotGrowWithLargeLocalHistory() = runBlocking {
        val accounts = (1..200).map { account("local-$it") }
        database.accountDao().insertAll(accounts)
        database.measurementDao().insertAll(
            accounts.flatMap { account ->
                (1..10).map { index ->
                    measurement("${account.id}-$index", account.id, SyncStatus.LOCAL_ONLY)
                }
            },
        )
        val target = preview(
            BackupImportMode.MERGE,
            accounts,
            database.measurementDao().getAllForBackup(),
        )

        gateway.stage(target)

        val checkpoint = database.backupImportCheckpointDao().get()!!
        assertEquals(36, checkpoint.operationId.length)
        assertEquals("true", checkpoint.sweepNeeded)
        assertTrue(checkpoint.targetSettingsJson.length < 2_048)
    }

    @Test
    fun stalePreviewRejectsConcurrentAccountMeasurementAppStateAndSettingsForBothModes() =
        runBlocking {
            BackupImportMode.entries.forEach { mode ->
                resetDatabase()
                assertStale(mode) { database.accountDao().insert(account("concurrent")) }
                assertEquals(listOf("concurrent"), database.accountDao().getAll().map { it.id })

                resetDatabase()
                database.accountDao().insert(account("local"))
                assertStale(mode) {
                    database.measurementDao().insert(
                        measurement("concurrent-m", "local", SyncStatus.LOCAL_ONLY),
                    )
                }
                assertEquals(
                    listOf("concurrent-m"),
                    database.measurementDao().getAllForBackup().map { it.id },
                )

                resetDatabase()
                assertStale(mode) {
                    database.appStateDao().replace(AppStateEntity(weightDeltaKg = 7.0))
                }
                assertEquals(7.0, database.appStateDao().get()?.weightDeltaKg)

                resetDatabase()
                assertStale(mode) {
                    currentSettings = VersionedPortableProfileSettings(
                        emptySettings.copy(scaleName = "Concurrent"),
                        currentSettings.revision + 1,
                    )
                }
                assertEquals("Concurrent", currentSettings.settings.scaleName)
            }
        }

    private suspend fun assertStale(mode: BackupImportMode, mutate: suspend () -> Unit) {
        val service = BackupImportService()
        val preview = service.preview(
            emptyDocument,
            BackupDatabaseSnapshot(
                database.accountDao().getAll(),
                database.appStateDao().get() ?: AppStateEntity(),
                database.measurementDao().getAllForBackup(),
            ),
            currentSettings,
            mode,
        )
        mutate()
        val validatingGateway = RoomBackupImportGateway(
            database,
            { currentSettings },
            service,
        )

        val stale = assertThrows(BackupPreviewStale::class.java) {
            runBlocking { validatingGateway.stage(preview) }
        }

        assertEquals(mode, stale.refreshedPreview.mode)
        assertEquals(emptyDocument, stale.refreshedPreview.sourceDocument)
        assertEquals(null, validatingGateway.pendingRecovery())
    }

    private suspend fun resetDatabase() {
        database.measurementDao().deleteAll()
        database.accountDao().deleteAll()
        database.appStateDao().replace(AppStateEntity())
        currentSettings = VersionedPortableProfileSettings(emptySettings, currentSettings.revision + 1)
    }

    private suspend fun apply(preview: BackupImportPreview) {
        gateway.stage(preview)
        gateway.complete()
    }

    private fun seedLegacyV7Checkpoint(
        phase: String,
        previousSettingsJson: String,
        targetSettingsJson: String,
    ) {
        database.openHelper.writableDatabase.execSQL(
            """
            INSERT OR REPLACE INTO backup_import_checkpoint (
                singletonId, phase, rollbackDatabaseJson, previousSettingsJson, targetSettingsJson
            ) VALUES (1, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf(
                phase,
                LEGACY_ROLLBACK_DATABASE_JSON,
                previousSettingsJson,
                targetSettingsJson,
            ),
        )
    }

    private suspend fun preview(
        mode: BackupImportMode,
        accounts: List<AccountEntity>,
        measurements: List<MeasurementEntity>,
    ): BackupImportPreview {
        val target = BackupDatabaseSnapshot(
            accounts,
            AppStateEntity(primaryAccountId = accounts.first().id),
            measurements,
        )
        val document = BackupExportService(
            BackupSnapshotSource { target },
            { emptySettings },
        ).createDocument()
        return BackupImportService().preview(
            document,
            BackupDatabaseSnapshot(
                database.accountDao().getAll(),
                database.appStateDao().get() ?: AppStateEntity(),
                database.measurementDao().getAllForBackup(),
            ),
            currentSettings,
            mode,
        )
    }

    private val emptySettings = PortableProfileSettings(null, null, false, null, null)
    private val emptyDocument = BackupDocumentV1(
        exportedAt = "2026-08-25T00:00:00Z",
        accounts = emptyList(),
        appState = BackupAppStateV1(null, 0.0, false),
        measurements = emptyList(),
        settings = BackupSettingsV1(null, null, false, null, null),
    )

    private fun account(id: String) = AccountEntity(id, id, id, null, null, null, false, 1, 1)

    private fun measurement(id: String, accountId: String, status: SyncStatus) = MeasurementEntity(
        id = id, fingerprint = "fingerprint-$id", measurementType = MeasurementType.WEIGHT_ONLY,
        deviceAddress = "AA:BB", measuredAtEpochSecond = 1, rawPayloadHex = "00", weightKg = 70.0,
        impedanceOhm = null, bmi = null, bodyFatPercent = null, bodyFatMassKg = null,
        waterPercent = null, waterMassKg = null, muscleMassKg = null, skeletalMuscleMassKg = null,
        boneMassKg = null, proteinPercent = null, proteinMassKg = null, visceralFatLevel = null,
        basalMetabolicRateKcal = null, metabolicAge = null, leanBodyMassKg = null,
        algorithmVersion = null, huaweiStatus = status.name, healthConnectStatus = status.name,
        accountId = accountId, externalSyncPolicy = ExternalSyncPolicy.USER_LOCAL.name,
        deduplicationHash = "hash-$id",
    )

    private fun pending(id: String) = PendingMeasurementEntity(
        id, "AA:BB", 1, 70.0, 0, true, false, byteArrayOf(0), "pending-$id", 1,
    )

    private companion object {
        const val LEGACY_ROLLBACK_DATABASE_JSON =
            """{"accounts":[],"appState":{},"measurements":[],"pendingMeasurements":[],"tombstones":[]}"""
        const val LEGACY_PREVIOUS_SETTINGS_JSON =
            """{"scaleAddress":"11:22","scaleName":"Old scale","reliabilityMode":false,"selectedChartMetricKeys":["weight"],"homeKgChartSeriesKeys":[]}"""
        const val LEGACY_TARGET_SETTINGS_JSON =
            """{"scaleAddress":"AA:BB","scaleName":"Imported scale","reliabilityMode":true,"selectedChartMetricKeys":[],"homeKgChartSeriesKeys":["weight"]}"""
    }
}
