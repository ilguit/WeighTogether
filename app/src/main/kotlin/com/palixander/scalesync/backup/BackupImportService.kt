package com.palixander.scalesync.backup

import androidx.room.withTransaction
import com.palixander.scalesync.data.AccountEntity
import com.palixander.scalesync.data.AppDatabase
import com.palixander.scalesync.data.AppStateEntity
import com.palixander.scalesync.data.BackupImportCheckpointEntity
import com.palixander.scalesync.data.MeasurementEntity
import com.palixander.scalesync.data.PetEntity
import com.palixander.scalesync.data.PetMeasurementEntity
import com.palixander.scalesync.data.PortableProfileSettings
import com.palixander.scalesync.data.ProfileStore
import com.palixander.scalesync.data.VersionedPortableProfileSettings
import com.palixander.scalesync.worker.ExternalSyncOperationSerializer
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlinx.coroutines.CancellationException

const val MAX_BACKUP_BYTES: Int = 32 * 1024 * 1024

enum class BackupImportMode { MERGE, REPLACE }

sealed interface BackupImportConflict {
    data class AccountId(val id: String) : BackupImportConflict
    data class AccountName(val normalizedName: String) : BackupImportConflict
    data class MeasurementId(val id: String) : BackupImportConflict
    data class MeasurementFingerprint(val fingerprint: String) : BackupImportConflict
    data class MeasurementDeduplicationHash(val hash: String) : BackupImportConflict
    data class PetId(val id: String) : BackupImportConflict
    data class PetName(val normalizedName: String) : BackupImportConflict
    data class PetMeasurementId(val id: String) : BackupImportConflict
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
    val petsAdded: Int = 0,
    val petsSkipped: Int = 0,
    val petsReplaced: Int = 0,
    val petMeasurementsAdded: Int = 0,
    val petMeasurementsSkipped: Int = 0,
    val petMeasurementsReplaced: Int = 0,
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
    suspend fun stage(preview: BackupImportPreview)

    suspend fun pendingRecovery(): BackupImportRecovery?

    suspend fun complete()
}

data class BackupImportRecovery(
    val operationId: String,
    val settings: PortableProfileSettings,
    val sweepNeeded: Boolean,
)

class RoomBackupImportGateway internal constructor(
    private val database: AppDatabase,
    private val settingsSnapshot: () -> VersionedPortableProfileSettings,
    private val importService: BackupImportService = BackupImportService(),
    private val checkpointCodec: BackupImportCheckpointCodec = BackupImportCheckpointCodec(),
) : BackupImportGateway {
    override suspend fun stage(preview: BackupImportPreview): Unit =
        database.withTransaction {
        val currentDatabase = BackupDatabaseSnapshot(
            accounts = database.accountDao().getAll(),
            appState = database.appStateDao().get() ?: AppStateEntity(),
            measurements = database.measurementDao().getAllForBackup(),
            pets = database.petDao().getAllPetsForBackup(),
            petMeasurements = database.petDao().getAllMeasurementsForBackup(),
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
        database.backupImportCheckpointDao().replace(
            BackupImportCheckpointEntity(
                operationId = UUID.randomUUID().toString(),
                sweepNeeded = true.toString(),
                targetSettingsJson = checkpointCodec.encodeSettings(preview.settings),
            ),
        )
        when (preview.mode) {
            BackupImportMode.MERGE -> {
                database.accountDao().insertAll(preview.result.accounts)
                database.measurementDao().insertAll(preview.result.measurements)
                database.petDao().insertPets(preview.result.pets)
                database.petDao().insertMeasurements(preview.result.petMeasurements)
                database.appStateDao().replace(preview.result.appState)
            }
            BackupImportMode.REPLACE -> {
                database.pendingMeasurementDao().deleteAll()
                database.pendingMeasurementDao().deleteAllTombstones()
                database.measurementDao().deleteAll()
                database.petDao().deleteAllMeasurements()
                database.petDao().deleteAllPets()
                database.accountDao().deleteAll()
                database.accountDao().insertAll(preview.result.accounts)
                database.appStateDao().replace(preview.result.appState)
                database.measurementDao().insertAll(preview.result.measurements)
                database.petDao().insertPets(preview.result.pets)
                database.petDao().insertMeasurements(preview.result.petMeasurements)
            }
        }
        Unit
    }

    override suspend fun pendingRecovery(): BackupImportRecovery? =
        database.backupImportCheckpointDao().get()?.let(checkpointCodec::decodeRecovery)

    override suspend fun complete() {
        check(database.backupImportCheckpointDao().delete() == 1) {
            "Backup import checkpoint disappeared before cleanup"
        }
    }
}

internal class BackupImportCheckpointCodec(private val gson: Gson = Gson()) {
    fun encodeSettings(value: PortableProfileSettings): String = gson.toJson(value)

    fun decodeSettings(value: String): PortableProfileSettings =
        gson.fromJson(value, PortableProfileSettings::class.java)

    /**
     * Version 7 shipped two checkpoint layouts using the same physical Room columns. The old
     * layout stored a full rollback JSON document in [BackupImportCheckpointEntity.operationId]
     * and the previous settings JSON in [BackupImportCheckpointEntity.sweepNeeded]. New rows use
     * a UUID and a literal boolean in those columns. Keep this decoder while version 7 databases
     * can contain a checkpoint written by either layout.
     *
     * An unrecognizable row is deliberately left in place. Startup treats it as no actionable
     * recovery instead of crashing or guessing which settings to apply; a later compatible build
     * can retry it, and a newly staged import can atomically replace it.
     */
    fun decodeRecovery(checkpoint: BackupImportCheckpointEntity): BackupImportRecovery? =
        when (checkpoint.phase) {
            BackupImportCheckpointEntity.PHASE_TARGET_APPLIED -> {
                if (checkpoint.operationId.isUuid()) {
                    val sweepNeeded = checkpoint.sweepNeeded.toCheckpointBoolean()
                        ?: return null
                    decodeSettingsSafely(checkpoint.targetSettingsJson)?.let { settings ->
                        BackupImportRecovery(checkpoint.operationId, settings, sweepNeeded)
                    }
                } else if (checkpoint.operationId.isLegacyRollbackJson()) {
                    decodeSettingsSafely(checkpoint.targetSettingsJson)?.let { settings ->
                        BackupImportRecovery(LEGACY_OPERATION_ID, settings, true)
                    }
                } else {
                    null
                }
            }
            BackupImportCheckpointEntity.PHASE_ROLLBACK_APPLIED ->
                if (checkpoint.operationId.isLegacyRollbackJson()) {
                    decodeSettingsSafely(checkpoint.sweepNeeded)?.let { settings ->
                        BackupImportRecovery(LEGACY_OPERATION_ID, settings, false)
                    }
                } else {
                    null
                }
            else -> null
        }

    private fun decodeSettingsSafely(value: String): PortableProfileSettings? = runCatching {
        val json = JsonParser.parseString(value)
        if (!json.isJsonObject || !json.asJsonObject.isPortableSettingsJson()) return null
        gson.fromJson(json, PortableProfileSettings::class.java)
    }.getOrNull()

    private fun JsonObject.isPortableSettingsJson(): Boolean {
        val reliability = get("reliabilityMode") ?: return false
        if (!reliability.isJsonPrimitive || !reliability.asJsonPrimitive.isBoolean) return false
        return optionalString("scaleAddress") &&
            optionalString("scaleName") &&
            optionalStringArray("selectedChartMetricKeys") &&
            optionalStringArray("homeKgChartSeriesKeys")
    }

    private fun JsonObject.optionalString(name: String): Boolean =
        get(name)?.let { it.isJsonNull || it.isJsonPrimitive && it.asJsonPrimitive.isString } ?: true

    private fun JsonObject.optionalStringArray(name: String): Boolean = get(name)?.let { value ->
        value.isJsonNull || value.isJsonArray && value.asJsonArray.all {
            it.isJsonPrimitive && it.asJsonPrimitive.isString
        }
    } ?: true

    private fun String.isUuid(): Boolean = runCatching {
        UUID.fromString(this).toString() == lowercase()
    }.getOrDefault(false)

    private fun String.toCheckpointBoolean(): Boolean? = when (this) {
        "true" -> true
        "false" -> false
        else -> null
    }

    private fun String.isLegacyRollbackJson(): Boolean = runCatching {
        val value = JsonParser.parseString(this)
        value.isJsonObject && value.asJsonObject.let { rollback ->
            rollback.get("accounts")?.isJsonArray == true &&
                rollback.get("appState")?.isJsonObject == true &&
                rollback.get("measurements")?.isJsonArray == true &&
                rollback.get("pendingMeasurements")?.isJsonArray == true &&
                rollback.get("tombstones")?.isJsonArray == true
        }
    }.getOrDefault(false)

    private companion object {
        const val LEGACY_OPERATION_ID = "legacy-v7"
    }
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

sealed interface BackupImportApplyResult {
    val counts: BackupImportCounts
    val mode: BackupImportMode

    data class Completed(
        override val counts: BackupImportCounts,
        override val mode: BackupImportMode,
    ) : BackupImportApplyResult

    /** The database is committed and startup recovery will retry the settings commit. */
    data class CompletedPendingRecovery(
        override val counts: BackupImportCounts,
        override val mode: BackupImportMode,
        val settingsFailure: Exception,
    ) : BackupImportApplyResult
}

class BackupImportApplier(
    private val gateway: BackupImportGateway,
    private val settingsWriter: PortableSettingsWriter,
    private val operations: ExternalSyncOperationSerializer = ExternalSyncOperationSerializer(),
    private val successHooks: List<BackupImportSuccessHook> = emptyList(),
    private val completionHooks: List<BackupImportCompletionHook> = emptyList(),
) {
    suspend fun apply(preview: BackupImportPreview): BackupImportApplyResult {
        var pendingRecovery: Exception? = null
        operations.runExclusive {
            gateway.stage(preview)
            try {
                settingsWriter.apply(preview.settings)
                gateway.complete()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (settingsFailure: Exception) {
                pendingRecovery = settingsFailure
            }
        }
        pendingRecovery?.let {
            return BackupImportApplyResult.CompletedPendingRecovery(
                preview.counts,
                preview.mode,
                it,
            )
        }
        // The import is durably committed once the checkpoint is removed. Observability and
        // follow-up scheduling are best effort from this point and must not turn that committed
        // outcome into a reported failure.
        successHooks.forEach { hook ->
            try {
                hook.onImportSucceeded(preview)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Post-commit observers cannot change the durable import outcome.
            }
        }
        runCompletionHooksBestEffort()
        return BackupImportApplyResult.Completed(preview.counts, preview.mode)
    }

    suspend fun recoverPendingImport() {
        var sweepNeeded = false
        operations.runExclusive {
            val recovery = gateway.pendingRecovery() ?: return@runExclusive
            settingsWriter.apply(recovery.settings)
            gateway.complete()
            sweepNeeded = recovery.sweepNeeded
        }
        if (sweepNeeded) {
            runCompletionHooksBestEffort()
        }
    }

    private suspend fun runCompletionHooksBestEffort() {
        completionHooks.forEach { hook ->
            try {
                hook.onImportCompleted()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Completion hooks only schedule durable repair work. Startup/foreground retries.
            }
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
        // Callers may supply a model directly instead of going through read(); apply the same
        // shape-independent semantic validation before constructing a database snapshot.
        val validatedDocument = codec.decode(codec.encode(document))
        val baseline = BackupImportBaselineToken(
            current,
            currentSettings.settings,
            currentSettings.revision,
        )
        return when (mode) {
            BackupImportMode.REPLACE -> replace(validatedDocument, current, baseline)
            BackupImportMode.MERGE -> merge(validatedDocument, current, currentSettings.settings, baseline)
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
            BackupImportCounts(
                accountsAdded = result.accounts.size,
                accountsSkipped = 0,
                accountsReplaced = current.accounts.size,
                measurementsAdded = result.measurements.size,
                measurementsSkipped = 0,
                measurementsReplaced = current.measurements.size,
                petsAdded = result.pets.size,
                petsSkipped = 0,
                petsReplaced = current.pets.size,
                petMeasurementsAdded = result.petMeasurements.size,
                petMeasurementsSkipped = 0,
                petMeasurementsReplaced = current.petMeasurements.size,
            ),
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
        val existingPetsById = current.pets.associateBy { it.id }
        val existingPetsByName = current.pets.associateBy { it.normalizedName }
        val petsToAdd = incoming.pets.filter { pet ->
            val byPetId = existingPetsById[pet.id]
            if (byPetId != null) {
                if (byPetId != pet) conflicts += BackupImportConflict.PetId(pet.id)
                false
            } else {
                val byName = existingPetsByName[pet.normalizedName]
                if (byName != null) conflicts += BackupImportConflict.PetName(pet.normalizedName)
                byName == null
            }
        }
        val existingPetMeasurementsById = current.petMeasurements.associateBy { it.id }
        val petMeasurementsToAdd = incoming.petMeasurements.filter { measurement ->
            val existing = existingPetMeasurementsById[measurement.id]
            if (existing == null) true else {
                if (existing != measurement) conflicts += BackupImportConflict.PetMeasurementId(measurement.id)
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
            pets = current.pets + petsToAdd,
            petMeasurements = current.petMeasurements + petMeasurementsToAdd,
        )
        val importedSettings = document.settings.toSettings()
        return BackupImportPreview(
            BackupImportMode.MERGE,
            BackupImportCounts(
                accountsToAdd.size, incoming.accounts.size - accountsToAdd.size, 0,
                measurementsToAdd.size, incoming.measurements.size - measurementsToAdd.size, 0,
                petsToAdd.size, incoming.pets.size - petsToAdd.size, 0,
                petMeasurementsToAdd.size,
                incoming.petMeasurements.size - petMeasurementsToAdd.size,
                0,
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
    pets = pets.map {
        PetEntity(it.id, it.displayName, it.normalizedName, it.species, it.createdAtEpochMillis, it.updatedAtEpochMillis,
            it.sex, it.breedId, it.birthYear, it.birthMonth, it.birthDay, it.dogAdultWeightCategory)
    },
    petMeasurements = petMeasurements.map {
        PetMeasurementEntity(it.id, it.petId, it.measuredAtEpochSecond, it.firstWeightKg, it.secondWeightKg, it.petWeightKg)
    },
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
