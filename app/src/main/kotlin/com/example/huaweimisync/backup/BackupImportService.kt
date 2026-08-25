package com.example.huaweimisync.backup

import androidx.room.withTransaction
import com.example.huaweimisync.data.AccountEntity
import com.example.huaweimisync.data.AppDatabase
import com.example.huaweimisync.data.AppStateEntity
import com.example.huaweimisync.data.MeasurementEntity
import com.example.huaweimisync.data.PortableProfileSettings
import com.example.huaweimisync.data.ProfileStore
import com.example.huaweimisync.worker.ExternalSyncOperationSerializer
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
)

fun interface BackupImportGateway {
    suspend fun apply(preview: BackupImportPreview)
}

class RoomBackupImportGateway(
    private val database: AppDatabase,
) : BackupImportGateway {
    override suspend fun apply(preview: BackupImportPreview) = database.withTransaction {
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
    }
}

fun interface PortableSettingsWriter {
    fun apply(settings: PortableProfileSettings)
}

fun interface BackupImportSuccessHook {
    suspend fun onImportSucceeded(preview: BackupImportPreview)
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
) {
    suspend fun apply(preview: BackupImportPreview): BackupImportApplyResult {
        operations.runExclusive {
            gateway.apply(preview)
            settingsWriter.apply(preview.settings)
        }
        successHooks.forEach { it.onImportSucceeded(preview) }
        return BackupImportApplyResult(preview.counts, preview.mode)
    }
}

fun ProfileStore.asPortableSettingsWriter(): PortableSettingsWriter =
    PortableSettingsWriter(this::applyPortableSettings)

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
    ): BackupImportPreview = when (mode) {
        BackupImportMode.REPLACE -> replace(document, current)
        BackupImportMode.MERGE -> merge(document, current, currentSettings)
    }

    private fun replace(document: BackupDocumentV1, current: BackupDatabaseSnapshot): BackupImportPreview {
        val result = document.toSnapshot()
        return BackupImportPreview(
            BackupImportMode.REPLACE,
            BackupImportCounts(0, 0, current.accounts.size, 0, 0, current.measurements.size),
            result,
            document.settings.toSettings(),
        )
    }

    private fun merge(
        document: BackupDocumentV1,
        current: BackupDatabaseSnapshot,
        currentSettings: PortableProfileSettings,
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
