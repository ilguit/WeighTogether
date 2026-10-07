package com.palixander.weightogether.backup

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.palixander.weightogether.data.AccountEntity
import com.palixander.weightogether.data.AppDatabase
import com.palixander.weightogether.data.AppStateEntity
import com.palixander.weightogether.data.MeasurementEntity
import com.palixander.weightogether.data.MeasurementTombstoneEntity
import com.palixander.weightogether.data.MeasurementType
import com.palixander.weightogether.data.PendingMeasurementEntity
import com.palixander.weightogether.data.PetEntity
import com.palixander.weightogether.data.PetMeasurementEntity
import com.palixander.weightogether.data.PortableProfileSettings
import com.palixander.weightogether.data.ProfilePhotoReferenceCoordinator
import com.palixander.weightogether.data.RatingHeightOrigin
import com.palixander.weightogether.data.SyncStatus
import com.palixander.weightogether.data.VersionedPortableProfileSettings
import com.palixander.weightogether.domain.ExternalSyncPolicy
import com.palixander.weightogether.domain.PetSpecies
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
        assertEquals(SyncStatus.SYNCED.name, rows.single { it.id == "m" }.healthConnectStatus)
        assertEquals(SyncStatus.FAILED.name, rows.single { it.id == "n" }.healthConnectStatus)
    }

    @Test
    fun mergeSkipsMeasurementCollidingOnlyBySourcePendingId() = runBlocking {
        val account = account("a")
        val local = measurement("local", account.id, SyncStatus.SYNCED).copy(
            sourcePendingId = "pending-stable",
        )
        database.accountDao().insert(account)
        database.measurementDao().insert(local)
        val incoming = measurement("incoming", account.id, SyncStatus.FAILED).copy(
            sourcePendingId = local.sourcePendingId,
        )

        val preview = preview(BackupImportMode.MERGE, listOf(account), listOf(incoming))
        apply(preview)

        assertEquals(0, preview.counts.measurementsAdded)
        assertEquals(1, preview.counts.measurementsSkipped)
        assertEquals(listOf(local), database.measurementDao().getAllForBackup())
    }

    @Test
    fun v3MeasurementContextPersistsExactlyThroughRoomGateway() = runBlocking {
        val owner = account("owner").copy(heightCm = 190.0)
        val imported = measurement("context", owner.id, SyncStatus.LOCAL_ONLY).copy(
            ratingHeightCm = 172.75,
            ratingHeightOrigin = RatingHeightOrigin.RESTORED_CURRENT_ACCOUNT,
        )

        apply(preview(BackupImportMode.MERGE, listOf(owner), listOf(imported)))

        val stored = database.measurementDao().getAllForBackup().single()
        assertEquals(172.75, stored.ratingHeightCm)
        assertEquals(RatingHeightOrigin.RESTORED_CURRENT_ACCOUNT, stored.ratingHeightOrigin)
    }

    @Test
    fun replaceClearsTransientTablesAndRollsBackWholeReplacementOnDatabaseFailure() = runBlocking {
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

        database.pendingMeasurementDao().insert(pending("retained-pending"))
        val retainedTombstone = MeasurementTombstoneEntity("retained-hash", 20_000L)
        database.pendingMeasurementDao().upsertTombstone(retainedTombstone)
        val replacement = preview(
            BackupImportMode.REPLACE,
            listOf(account("replacement")),
            listOf(measurement("replacement-m", "replacement", SyncStatus.SYNCED)),
        )

        assertReplacementFailsDuringInsert(replacement, "measurements")

        assertEquals(listOf("new"), database.accountDao().getAll().map { it.id })
        assertEquals(listOf("new-m"), database.measurementDao().getAllForBackup().map { it.id })
        assertEquals(listOf("retained-pending"), database.pendingMeasurementDao().getAll().map { it.id })
        assertEquals(listOf(retainedTombstone), database.pendingMeasurementDao().getAllTombstones())
        assertEquals(null, gateway.pendingRecovery())
        assertEquals(null, database.backupImportCheckpointDao().get())
    }

    @Test
    fun replaceDeletesOnlyPhotosUnreferencedAfterCommittedDatabaseTransaction() = runBlocking {
        val removedAccountPhoto = "profile-photos/accounts/old/removed.jpg"
        val removedPetPhoto = "profile-photos/pets/old-pet/removed.jpg"
        val deleted = mutableListOf<String>()
        gateway = RoomBackupImportGateway(
            database,
            { currentSettings },
            photoReferences = ProfilePhotoReferenceCoordinator(database) { deleted += it },
        )
        database.accountDao().insert(account("old").copy(photoPath = removedAccountPhoto))
        database.petDao().insertPets(listOf(pet("old-pet").copy(photoPath = removedPetPhoto)))

        val replacement = preview(
            BackupImportMode.REPLACE,
            accounts = listOf(account("new")),
            measurements = emptyList(),
        )
        apply(replacement)

        assertEquals(setOf(removedAccountPhoto, removedPetPhoto), deleted.toSet())
        assertEquals(null, database.accountDao().getAll().single().photoPath)
    }

    @Test
    fun failedReplaceRollbackPreservesAllExistingPhotos() = runBlocking {
        val accountPhoto = "profile-photos/accounts/old/photo.jpg"
        val petPhoto = "profile-photos/pets/old-pet/photo.jpg"
        val deleted = mutableListOf<String>()
        gateway = RoomBackupImportGateway(
            database,
            { currentSettings },
            photoReferences = ProfilePhotoReferenceCoordinator(database) { deleted += it },
        )
        val originalAccount = account("old").copy(photoPath = accountPhoto)
        val originalPet = pet("old-pet").copy(photoPath = petPhoto)
        database.accountDao().insert(originalAccount)
        database.petDao().insertPets(listOf(originalPet))
        val replacement = preview(
            BackupImportMode.REPLACE,
            accounts = listOf(account("new")),
            measurements = listOf(measurement("new-m", "new", SyncStatus.SYNCED)),
        )

        assertReplacementFailsDuringInsert(replacement, "measurements")

        assertEquals(emptyList<String>(), deleted)
        assertEquals(listOf(originalAccount), database.accountDao().getAll())
        assertEquals(listOf(originalPet), database.petDao().getAllPetsForBackup())
        assertTrue(database.measurementDao().getAllForBackup().isEmpty())
        assertEquals(null, gateway.pendingRecovery())
        assertEquals(null, database.backupImportCheckpointDao().get())
    }

    @Test
    fun petGraphRoundTripsThroughMergeAndReplaceRollsBackWithoutOrphans() = runBlocking {
        val initial = preview(
            BackupImportMode.MERGE,
            listOf(account("owner")),
            emptyList(),
            listOf(pet("cat")),
            listOf(petMeasurement("pet-m", "cat")),
        )
        apply(initial)
        assertEquals(listOf("cat"), database.petDao().getAllPetsForBackup().map { it.id })
        assertEquals(listOf("pet-m"), database.petDao().getAllMeasurementsForBackup().map { it.id })

        val replacement = preview(
            BackupImportMode.REPLACE,
            listOf(account("new-owner")),
            emptyList(),
            listOf(pet("new-pet")),
            listOf(petMeasurement("new-pet-m", "new-pet")),
        )

        assertReplacementFailsDuringInsert(replacement, "pet_measurements")

        assertEquals(listOf("owner"), database.accountDao().getAll().map { it.id })
        assertEquals(initial.result.appState, database.appStateDao().get())
        assertEquals(listOf("cat"), database.petDao().getAllPetsForBackup().map { it.id })
        assertEquals(listOf("pet-m"), database.petDao().getAllMeasurementsForBackup().map { it.id })
        assertEquals(null, gateway.pendingRecovery())
        assertEquals(null, database.backupImportCheckpointDao().get())
    }

    @Test
    fun mergePreservesLocalOwnersAndPersistsNewMeasurementsWithRemappedReferences() = runBlocking {
        val localAccount = account("local-account").copy(
            displayName = "Owner",
            normalizedName = "owner",
            heightCm = 180.0,
            updatedAtEpochMillis = 20,
        )
        val localPet = pet("pet").copy(
            displayName = "Local cat",
            normalizedName = "local cat",
            updatedAtEpochMillis = 20,
        )
        database.accountDao().insert(localAccount)
        database.petDao().insertPet(localPet)

        val importedAccount = account("imported-account").copy(
            displayName = "Imported owner",
            normalizedName = localAccount.normalizedName,
            heightCm = 160.0,
            updatedAtEpochMillis = 200,
        )
        val importedPet = pet(localPet.id).copy(
            displayName = "Imported dog",
            normalizedName = "imported dog",
            species = PetSpecies.DOG,
            updatedAtEpochMillis = 200,
        )

        apply(
            preview(
                BackupImportMode.MERGE,
                listOf(importedAccount),
                listOf(measurement("account-m", importedAccount.id, SyncStatus.SYNCED)),
                listOf(importedPet),
                listOf(petMeasurement("pet-m", importedPet.id)),
            ),
        )

        assertEquals(localAccount, database.accountDao().get(localAccount.id))
        assertEquals(localPet, database.petDao().getPet(localPet.id))
        assertEquals(
            localAccount.id,
            database.measurementDao().getAllForBackup().single { it.id == "account-m" }.accountId,
        )
        val storedPetMeasurement = database.petDao().getMeasurement("pet-m")
        assertNotNull(storedPetMeasurement)
        assertEquals(localPet.id, storedPetMeasurement?.petId)
        assertEquals(localPet, database.petDao().getPet(storedPetMeasurement?.petId.orEmpty()))
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
    fun startupRecoveryRetriesLegacyV7TargetSweepBeforeCheckpointCleanup() = runBlocking {
        seedLegacyV7Checkpoint(
            phase = "TARGET_APPLIED",
            previousSettingsJson = LEGACY_PREVIOUS_SETTINGS_JSON,
            targetSettingsJson = LEGACY_TARGET_SETTINGS_JSON,
        )
        val events = mutableListOf<String>()
        val checkpoint = checkNotNull(database.backupImportCheckpointDao().get())
        val sweepFailure = IllegalStateException("sweep failed")
        var failSweep = true

        val applier = BackupImportApplier(
            gateway = gateway,
            settingsWriter = PortableSettingsWriter { events += "settings:${it.scaleAddress}" },
            completionHooks = listOf(
                BackupImportCompletionHook {
                    assertEquals(checkpoint, database.backupImportCheckpointDao().get())
                    events += "sweep"
                    if (failSweep) throw sweepFailure
                },
            ),
        )

        val failure = assertThrows(IllegalStateException::class.java) {
            runBlocking { applier.recoverPendingImport() }
        }

        assertEquals(sweepFailure, failure)
        assertEquals(listOf("settings:AA:BB", "sweep"), events)
        assertEquals(checkpoint, database.backupImportCheckpointDao().get())
        assertEquals(true, gateway.pendingRecovery()?.sweepNeeded)

        failSweep = false
        applier.recoverPendingImport()

        assertEquals(listOf("settings:AA:BB", "sweep", "settings:AA:BB", "sweep"), events)
        assertEquals(null, database.backupImportCheckpointDao().get())

        applier.recoverPendingImport()
        assertEquals(4, events.size)
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

    private fun assertReplacementFailsDuringInsert(preview: BackupImportPreview, table: String) {
        // Fail only after the valid preview passes freshness checks and replacement writes begin.
        database.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER fail_replacement AFTER INSERT ON $table
            BEGIN SELECT RAISE(ABORT, 'forced replacement failure'); END
            """.trimIndent(),
        )
        try {
            val failure = assertThrows(SQLiteConstraintException::class.java) {
                runBlocking { gateway.stage(preview) }
            }
            assertTrue(failure.message.orEmpty().contains("forced replacement failure"))
        } finally {
            database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_replacement")
        }
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
        pets: List<PetEntity> = emptyList(),
        petMeasurements: List<PetMeasurementEntity> = emptyList(),
    ): BackupImportPreview {
        val target = BackupDatabaseSnapshot(
            accounts,
            AppStateEntity(primaryAccountId = accounts.first().id),
            measurements,
            pets,
            petMeasurements,
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
                database.petDao().getAllPetsForBackup(),
                database.petDao().getAllMeasurementsForBackup(),
            ),
            currentSettings,
            mode,
        )
    }

    private val emptySettings = PortableProfileSettings(null, null, false, null, null)
    private val emptyDocument = BackupDocumentV1(
        exportedAt = "2026-08-25T00:00:00Z",
        accounts = emptyList(),
        appState = BackupAppStateV1(null, 5.0, false),
        measurements = emptyList(),
        settings = BackupSettingsV1(null, null, false, null, null),
    )

    private fun account(id: String) = AccountEntity(id, id, id, null, null, null, false, 1, 1)

    private fun pet(id: String) = PetEntity(id, id, id, PetSpecies.CAT, 1, 2)

    private fun petMeasurement(id: String, petId: String) =
        PetMeasurementEntity(id, petId, 3, 70.0, 74.0, 4.0)

    private fun measurement(id: String, accountId: String, status: SyncStatus) = MeasurementEntity(
        id = id, fingerprint = "fingerprint-$id", measurementType = MeasurementType.WEIGHT_ONLY,
        deviceAddress = "AA:BB", measuredAtEpochSecond = 1, rawPayloadHex = "00", weightKg = 70.0,
        impedanceOhm = null, bmi = null, bodyFatPercent = null, bodyFatMassKg = null,
        waterPercent = null, waterMassKg = null, muscleMassKg = null, skeletalMuscleMassKg = null,
        boneMassKg = null, proteinPercent = null, proteinMassKg = null, visceralFatLevel = null,
        basalMetabolicRateKcal = null, metabolicAge = null, leanBodyMassKg = null,
        algorithmVersion = null, healthConnectStatus = status.name,
        accountId = accountId, externalSyncPolicy = ExternalSyncPolicy.USER_LOCAL.name,
        deduplicationHash = "hash-$id",
        createdAtEpochMillis = 1,
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
