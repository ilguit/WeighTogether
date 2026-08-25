package com.example.huaweimisync.backup

import androidx.room.withTransaction
import com.example.huaweimisync.data.AccountEntity
import com.example.huaweimisync.data.AppDatabase
import com.example.huaweimisync.data.AppStateEntity
import com.example.huaweimisync.data.BackupImportCheckpointEntity
import com.example.huaweimisync.data.MeasurementEntity
import com.example.huaweimisync.data.MeasurementTombstoneEntity
import com.example.huaweimisync.data.PendingMeasurementEntity
import com.example.huaweimisync.data.PortableProfileSettings
import com.example.huaweimisync.data.ProfileStore
import com.example.huaweimisync.data.VersionedPortableProfileSettings
import com.example.huaweimisync.worker.ExternalSyncOperationSerializer
import com.google.gson.Gson
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

const val MAX_BACKUP_BYTES: Int = 32 * 1024 * 1024

enum class BackupImportMode { MERGE, REPLACE }

sealed interface BackupImportConflict {
    data class AccountId(val id: String) : BackupImportConflict
    data class AccountName(val normalizedName: String) : BackupImportConflict
    data class MeasurementId(val id: String) : BackupImportConflict
    data class MeasurementFingerprint(val fingerprint: String) : BackupImportConflict
    data class MeasurementDeduplicationHash(val hash: String) : BackupImportConflict
}

class BackupImportConflicts(val conflicts: List<BackupImportConflict>) :
    BackupException("Backup conflicts with local data: ${conflicts.joinToString()}")

class BackupPreviewStale(val refreshedPreview: BackupImportPreview) :
    BackupException("Backup preview is stale")

data class BackupImportCounts(
    val accountsAdded: Int,
    val accountsSkipped: Int,
    val accountsReplaced: Int,
    val measurementsAdded: Int,
    val measurementsSkipped: Int,
    val measurementsReplaced: Int,
)

data class BackupImportPreview(
    val mode: BackupImportMode,
    val counts: BackupImportCounts,
    val result: BackupDatabaseSnapshot,
    val settings: PortableProfileSettings,
    val sourceDocument: BackupDocumentV1,
    val baselineToken: BackupImportBaselineToken,
)

data class BackupImportBaselineToken(
    val database: BackupDatabaseSnapshot,
    val settings: PortableProfileSettings,
    val settingsRevision: Long,
)

interface BackupImportGateway {
    suspend fun stage(preview: BackupImportPreview): PortableProfileSettings

    suspend fun beginRollback()

    suspend fun pendingRecovery(): BackupImportRecovery?

    suspend fun complete()
}

data class BackupImportRecovery(
    val settings: PortableProfileSettings,
    val completesSuccessfulImport: Boolean,
)

class RoomBackupImportGateway internal constructor(
    private val database: AppDatabase,
    private val settingsSnapshot: () -> VersionedPortableProfileSettings,
    private val importService: BackupImportService = BackupImportService(),
    private val checkpointCodec: BackupImportCheckpointCodec = BackupImportCheckpointCodec(),
) : BackupImportGateway {
    override suspend fun stage(preview: BackupImportPreview): PortableProfileSettings =
        database.withTransaction {
        val currentDatabase = BackupDatabaseSnapshot(
            accounts = database.accountDao().getAll(),
            appState = database.appStateDao().get() ?: AppStateEntity(),
            measurements = database.measurementDao().getAllForBackup(),
        )
        val currentSettings = settingsSnapshot()
        val refreshed = importService.preview(
            preview.sourceDocument,
            currentDatabase,
            currentSettings,
            preview.mode,
        )
        if (preview.baselineToken != refreshed.baselineToken ||
            preview.counts != refreshed.counts ||
            preview.result != refreshed.result ||
            preview.settings != refreshed.settings
        ) {
            throw BackupPreviewStale(refreshed)
        }
        val previousSettings = currentSettings.settings
        val rollback = BackupImportRollbackSnapshot(
            accounts = currentDatabase.accounts,
            appState = currentDatabase.appState,
            measurements = currentDatabase.measurements,
            pendingMeasurements = database.pendingMeasurementDao().getAll(),
            tombstones = database.pendingMeasurementDao().getAllTombstones(),
        )
        database.backupImportCheckpointDao().replace(
            BackupImportCheckpointEntity(
                phase = BackupImportCheckpointEntity.PHASE_TARGET_APPLIED,
                rollbackDatabaseJson = checkpointCodec.encodeRollback(rollback),
                previousSettingsJson = checkpointCodec.encodeSettings(previousSettings),
                targetSettingsJson = checkpointCodec.encodeSettings(preview.settings),
            ),
        )
        when (preview.mode) {
            BackupImportMode.MERGE -> {
                database.accountDao().insertAll(preview.result.accounts)
                database.measurementDao().insertAll(preview.result.measurements)
                database.appStateDao().replace(preview.result.appState)
            }
            BackupImportMode.REPLACE -> {
                database.pendingMeasurementDao().deleteAll()
                database.pendingMeasurementDao().deleteAllTombstones()
                database.measurementDao().deleteAll()
                database.accountDao().deleteAll()
                database.accountDao().insertAll(preview.result.accounts)
                database.appStateDao().replace(preview.result.appState)
                database.measurementDao().insertAll(preview.result.measurements)
            }
        }
        previousSettings
    }

    override suspend fun beginRollback() = database.withTransaction {
        val checkpoint = database.backupImportCheckpointDao().get() ?: return@withTransaction
        val rollback = checkpointCodec.decodeRollback(checkpoint.rollbackDatabaseJson)
        database.pendingMeasurementDao().deleteAll()
        database.pendingMeasurementDao().deleteAllTombstones()
        database.measurementDao().deleteAll()
        database.accountDao().deleteAll()
        database.accountDao().insertAll(rollback.accounts)
        database.appStateDao().replace(rollback.appState)
        database.measurementDao().insertAll(rollback.measurements)
        database.pendingMeasurementDao().insertAll(rollback.pendingMeasurements)
        database.pendingMeasurementDao().upsertAllTombstones(rollback.tombstones)
        check(
            database.backupImportCheckpointDao().setPhase(
                BackupImportCheckpointEntity.PHASE_ROLLBACK_APPLIED,
            ) == 1,
        )
    }

    override suspend fun pendingRecovery(): BackupImportRecovery? =
        database.backupImportCheckpointDao().get()?.let { checkpoint ->
            when (checkpoint.phase) {
                BackupImportCheckpointEntity.PHASE_TARGET_APPLIED ->
                    BackupImportRecovery(
                        settings = checkpointCodec.decodeSettings(checkpoint.targetSettingsJson),
                        completesSuccessfulImport = true,
                    )
                BackupImportCheckpointEntity.PHASE_ROLLBACK_APPLIED ->
                    BackupImportRecovery(
                        settings = checkpointCodec.decodeSettings(checkpoint.previousSettingsJson),
                        completesSuccessfulImport = false,
                    )
                else -> error("Unknown backup import checkpoint phase: ${checkpoint.phase}")
            }
        }

    override suspend fun complete() {
        check(database.backupImportCheckpointDao().delete() == 1) {
            "Backup import checkpoint disappeared before cleanup"
        }
    }
}

internal data class BackupImportRollbackSnapshot(
    val accounts: List<AccountEntity>,
    val appState: AppStateEntity,
    val measurements: List<MeasurementEntity>,
    val pendingMeasurements: List<PendingMeasurementEntity>,
    val tombstones: List<MeasurementTombstoneEntity>,
)

internal class BackupImportCheckpointCodec(private val gson: Gson = Gson()) {
    fun encodeRollback(value: BackupImportRollbackSnapshot): String = gson.toJson(value)

    fun decodeRollback(value: String): BackupImportRollbackSnapshot =
        gson.fromJson(value, BackupImportRollbackSnapshot::class.java)

    fun encodeSettings(value: PortableProfileSettings): String = gson.toJson(value)

    fun decodeSettings(value: String): PortableProfileSettings =
        gson.fromJson(value, PortableProfileSettings::class.java)
}

fun interface PortableSettingsWriter {
    fun apply(settings: PortableProfileSettings)
}

fun interface BackupImportSuccessHook {
    suspend fun onImportSucceeded(preview: BackupImportPreview)
}

/** Runs after the database, portable settings, and recovery checkpoint are fully committed. */
fun interface BackupImportCompletionHook {
    suspend fun onImportCompleted()
}

data class BackupImportApplyResult(
    val counts: BackupImportCounts,
    val mode: BackupImportMode,
)

class BackupImportApplier(
    private val gateway: BackupImportGateway,
    private val settingsWriter: PortableSettingsWriter,
    private val operations: ExternalSyncOperationSerializer = ExternalSyncOperationSerializer(),
    private val successHooks: List<BackupImportSuccessHook> = emptyList(),
    private val completionHooks: List<BackupImportCompletionHook> = emptyList(),
) {
    suspend fun apply(preview: BackupImportPreview): BackupImportApplyResult {
        operations.runExclusive {
            val previousSettings = gateway.stage(preview)
            try {
                settingsWriter.apply(preview.settings)
                gateway.complete()
            } catch (settingsFailure: Exception) {
                try {
                    gateway.beginRollback()
                    settingsWriter.apply(previousSettings)
                    gateway.complete()
                } catch (rollbackFailure: Exception) {
                    settingsFailure.addSuppressed(rollbackFailure)
                }
                throw settingsFailure
            }
        }
        successHooks.forEach { it.onImportSucceeded(preview) }
        completionHooks.forEach { it.onImportCompleted() }
        return BackupImportApplyResult(preview.counts, preview.mode)
    }

    suspend fun recoverPendingImport() {
        var completesSuccessfulImport = false
        operations.runExclusive {
            val recovery = gateway.pendingRecovery() ?: return@runExclusive
            settingsWriter.apply(recovery.settings)
            gateway.complete()
            completesSuccessfulImport = recovery.completesSuccessfulImport
        }
        if (completesSuccessfulImport) {
            completionHooks.forEach { it.onImportCompleted() }
        }
    }
}

fun ProfileStore.asPortableSettingsWriter(): PortableSettingsWriter =
    PortableSettingsWriter(this::applyPortableSettingsWithinExclusiveOperation)

class BackupImportService(
    private val codec: BackupJsonCodec = BackupJsonCodec(),
    private val byteLimit: Int = MAX_BACKUP_BYTES,
) {
    init { require(byteLimit > 0) }

    fun read(input: InputStream): BackupDocumentV1 {
        val bytes = try {
            val output = ByteArrayOutputStream(minOf(byteLimit, DEFAULT_BUFFER_SIZE))
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                if (output.size() > byteLimit - count) throw BackupException.Limits("$", byteLimit)
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } catch (error: BackupException) {
            throw error
        } catch (error: IOException) {
            throw BackupException.Io(error)
        }
        val json = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (error: java.nio.charset.CharacterCodingException) {
            throw BackupException.Corrupt(error)
        }
        return codec.decode(json)
    }

    fun preview(
        input: InputStream,
        current: BackupDatabaseSnapshot,
        currentSettings: PortableProfileSettings,
        mode: BackupImportMode,
    ): BackupImportPreview = preview(read(input), current, currentSettings, mode)

    fun preview(
        document: BackupDocumentV1,
        current: BackupDatabaseSnapshot,
        currentSettings: PortableProfileSettings,
        mode: BackupImportMode,
    ): BackupImportPreview = preview(
        document,
        current,
        VersionedPortableProfileSettings(currentSettings, 0L),
        mode,
    )

    fun preview(
        document: BackupDocumentV1,
        current: BackupDatabaseSnapshot,
        currentSettings: VersionedPortableProfileSettings,
        mode: BackupImportMode,
    ): BackupImportPreview {
        val baseline = BackupImportBaselineToken(
            current,
            currentSettings.settings,
            currentSettings.revision,
        )
        return when (mode) {
            BackupImportMode.REPLACE -> replace(document, current, baseline)
            BackupImportMode.MERGE -> merge(document, current, currentSettings.settings, baseline)
        }
    }

    private fun replace(
        document: BackupDocumentV1,
        current: BackupDatabaseSnapshot,
        baseline: BackupImportBaselineToken,
    ): BackupImportPreview {
        val result = document.toSnapshot()
        return BackupImportPreview(
            BackupImportMode.REPLACE,
            BackupImportCounts(0, 0, current.accounts.size, 0, 0, current.measurements.size),
            result,
            document.settings.toSettings(),
            document,
            baseline,
        )
    }

    private fun merge(
        document: BackupDocumentV1,
        current: BackupDatabaseSnapshot,
        currentSettings: PortableProfileSettings,
        baseline: BackupImportBaselineToken,
    ): BackupImportPreview {
        val incoming = document.toSnapshot()
        val conflicts = mutableListOf<BackupImportConflict>()
        val existingAccountsById = current.accounts.associateBy { it.id }
        val existingAccountsByName = current.accounts.associateBy { it.normalizedName }
        val accountsToAdd = incoming.accounts.filter { account ->
            val byId = existingAccountsById[account.id]
            if (byId != null) {
                if (byId != account) conflicts += BackupImportConflict.AccountId(account.id)
                false
            } else {
                val byName = existingAccountsByName[account.normalizedName]
                if (byName != null) conflicts += BackupImportConflict.AccountName(account.normalizedName)
                byName == null
            }
        }
        val byId = current.measurements.associateBy { it.id }
        val byFingerprint = current.measurements.associateBy { it.fingerprint }
        val byHash = current.measurements.mapNotNull { value -> value.deduplicationHash?.let { it to value } }.toMap()
        val measurementsToAdd = incoming.measurements.filter { measurement ->
            val matches = listOfNotNull(
                byId[measurement.id]?.let { BackupImportConflict.MeasurementId(measurement.id) to it },
                byFingerprint[measurement.fingerprint]?.let { BackupImportConflict.MeasurementFingerprint(measurement.fingerprint) to it },
                measurement.deduplicationHash?.let { hash -> byHash[hash]?.let { BackupImportConflict.MeasurementDeduplicationHash(hash) to it } },
            )
            if (matches.isEmpty()) true else {
                val identical = matches.all { it.second == measurement }
                if (!identical) conflicts += matches.filter { it.second != measurement }.map { it.first }
                false
            }
        }
        if (conflicts.isNotEmpty()) throw BackupImportConflicts(conflicts.distinct())
        val result = BackupDatabaseSnapshot(
            accounts = current.accounts + accountsToAdd,
            appState = current.appState.copy(
                primaryAccountId = current.appState.primaryAccountId ?: incoming.appState.primaryAccountId,
            ),
            measurements = current.measurements + measurementsToAdd,
        )
        val importedSettings = document.settings.toSettings()
        return BackupImportPreview(
            BackupImportMode.MERGE,
            BackupImportCounts(
                accountsToAdd.size, incoming.accounts.size - accountsToAdd.size, 0,
                measurementsToAdd.size, incoming.measurements.size - measurementsToAdd.size, 0,
            ),
            result,
            PortableProfileSettings(
                scaleAddress = currentSettings.scaleAddress ?: importedSettings.scaleAddress,
                scaleName = currentSettings.scaleName ?: importedSettings.scaleName,
                reliabilityMode = currentSettings.reliabilityMode,
                selectedChartMetricKeys = currentSettings.selectedChartMetricKeys ?: importedSettings.selectedChartMetricKeys,
                homeKgChartSeriesKeys = currentSettings.homeKgChartSeriesKeys ?: importedSettings.homeKgChartSeriesKeys,
            ),
            document,
            baseline,
        )
    }
}

private fun BackupDocumentV1.toSnapshot() = BackupDatabaseSnapshot(
    accounts = accounts.map { account ->
        AccountEntity(account.id, account.displayName, account.normalizedName, account.profile.heightCm,
            account.profile.birthDateEpochDay, account.profile.sex?.name, account.profile.complete,
            account.createdAtEpochMillis, account.updatedAtEpochMillis)
    },
    appState = AppStateEntity(primaryAccountId = appState.primaryAccountId,
        weightDeltaKg = appState.weightDeltaKg, ignoreUnknownMeasurements = appState.ignoreUnknownMeasurements),
    measurements = measurements.map { it.toEntity() },
)

private fun BackupSettingsV1.toSettings() = PortableProfileSettings(
    scaleAddress, scaleName, reliabilityMode, selectedChartMetricKeys?.toSet(), homeKgChartSeriesKeys?.toSet(),
)

private fun BackupMeasurementV1.toEntity() = MeasurementEntity(
    id, fingerprint, measurementType, deviceAddress, measuredAtEpochSecond, rawPayloadHex, weightKg,
    rawWeight, impedanceOhm, bmi, bodyFatPercent, bodyFatMassKg, waterPercent, waterMassKg,
    muscleMassKg, skeletalMuscleMassKg, boneMassKg, proteinPercent, proteinMassKg, visceralFatLevel,
    basalMetabolicRateKcal, metabolicAge, leanBodyMassKg, algorithmVersion, huaweiStatus.name,
    healthConnectStatus.name, huaweiError, healthConnectError, huaweiWeightSynced,
    healthConnectWeightSynced, createdAtEpochMillis, accountId, externalSyncPolicy.name,
    sourcePendingId, deduplicationHash, huaweiSyncedCalculatedValues, healthConnectSyncedCalculatedValues,
)
