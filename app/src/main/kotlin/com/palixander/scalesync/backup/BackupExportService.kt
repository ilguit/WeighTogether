package com.palixander.scalesync.backup

import androidx.room.withTransaction
import com.palixander.scalesync.core.Sex
import com.palixander.scalesync.data.AccountEntity
import com.palixander.scalesync.data.AppDatabase
import com.palixander.scalesync.data.AppStateEntity
import com.palixander.scalesync.data.MeasurementEntity
import com.palixander.scalesync.data.PetEntity
import com.palixander.scalesync.data.PetMeasurementEntity
import com.palixander.scalesync.data.PortableProfileSettings
import com.palixander.scalesync.data.SyncStatus
import com.palixander.scalesync.domain.ExternalSyncPolicy
import java.io.IOException
import java.io.OutputStream
import java.time.Clock
import java.time.Instant

data class BackupDatabaseSnapshot(
    val accounts: List<AccountEntity>,
    val appState: AppStateEntity,
    val measurements: List<MeasurementEntity>,
    val pets: List<PetEntity> = emptyList(),
    val petMeasurements: List<PetMeasurementEntity> = emptyList(),
)

fun interface BackupSnapshotSource {
    suspend fun readSnapshot(): BackupDatabaseSnapshot
}

class RoomBackupSnapshotSource(
    private val database: AppDatabase,
) : BackupSnapshotSource {
    override suspend fun readSnapshot(): BackupDatabaseSnapshot = database.withTransaction {
        BackupDatabaseSnapshot(
            accounts = database.accountDao().getAll(),
            appState = database.appStateDao().get() ?: AppStateEntity(),
            measurements = database.measurementDao().getAllForBackup(),
            pets = database.petDao().getAllPetsForBackup(),
            petMeasurements = database.petDao().getAllMeasurementsForBackup(),
        )
    }
}

class BackupExportService(
    private val snapshotSource: BackupSnapshotSource,
    private val settingsSnapshot: () -> PortableProfileSettings,
    private val codec: BackupJsonCodec = BackupJsonCodec(),
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun createDocument(): BackupDocumentV1 {
        val database = snapshotSource.readSnapshot()
        val settings = settingsSnapshot()
        return BackupDocumentV1(
            exportedAt = Instant.now(clock).toString(),
            accounts = database.accounts.map(AccountEntity::toBackup),
            appState = database.appState.toBackup(),
            measurements = database.measurements.map(MeasurementEntity::toBackup),
            settings = settings.toBackup(),
            pets = database.pets.map(PetEntity::toBackup),
            petMeasurements = database.petMeasurements.map(PetMeasurementEntity::toBackup),
        )
    }

    /** Writes UTF-8 JSON and leaves ownership (and closing) of the SAF stream to the caller. */
    suspend fun writeTo(output: OutputStream): BackupDocumentV1 {
        val document = createDocument()
        try {
            output.write(codec.encode(document).toByteArray(Charsets.UTF_8))
            output.flush()
        } catch (error: IOException) {
            throw BackupException.Io(error)
        }
        return document
    }
}

private fun PetEntity.toBackup() = BackupPetV2(
    id, displayName, normalizedName, species, createdAtEpochMillis, updatedAtEpochMillis,
    sex, breedId, birthYear, birthMonth, birthDay, dogAdultWeightCategory,
)

private fun PetMeasurementEntity.toBackup() = BackupPetMeasurementV2(
    id, petId, measuredAtEpochSecond, firstWeightKg, secondWeightKg, petWeightKg, origin, isManuallyEdited,
)

private fun AccountEntity.toBackup() = BackupAccountV1(
    id = id,
    displayName = displayName,
    normalizedName = normalizedName,
    profile = BackupAccountProfileV1(
        heightCm = heightCm,
        birthDateEpochDay = birthDateEpochDay,
        sex = sex?.let(Sex::valueOf),
        complete = isProfileComplete,
    ),
    createdAtEpochMillis = createdAtEpochMillis,
    updatedAtEpochMillis = updatedAtEpochMillis,
)

private fun AppStateEntity.toBackup() = BackupAppStateV1(
    primaryAccountId = primaryAccountId,
    weightDeltaKg = weightDeltaKg,
    ignoreUnknownMeasurements = ignoreUnknownMeasurements,
)

private fun PortableProfileSettings.toBackup() = BackupSettingsV1(
    scaleAddress = scaleAddress,
    scaleName = scaleName,
    reliabilityMode = reliabilityMode,
    selectedChartMetricKeys = selectedChartMetricKeys?.sorted(),
    homeKgChartSeriesKeys = homeKgChartSeriesKeys?.sorted(),
)

private fun MeasurementEntity.toBackup() = BackupMeasurementV1(
    id = id,
    fingerprint = fingerprint,
    measurementType = measurementType,
    deviceAddress = deviceAddress,
    measuredAtEpochSecond = measuredAtEpochSecond,
    rawPayloadHex = rawPayloadHex,
    weightKg = weightKg,
    rawWeight = rawWeight,
    impedanceOhm = impedanceOhm,
    bmi = bmi,
    bodyFatPercent = bodyFatPercent,
    bodyFatMassKg = bodyFatMassKg,
    waterPercent = waterPercent,
    waterMassKg = waterMassKg,
    muscleMassKg = muscleMassKg,
    skeletalMuscleMassKg = skeletalMuscleMassKg,
    boneMassKg = boneMassKg,
    proteinPercent = proteinPercent,
    proteinMassKg = proteinMassKg,
    visceralFatLevel = visceralFatLevel,
    basalMetabolicRateKcal = basalMetabolicRateKcal,
    metabolicAge = metabolicAge,
    leanBodyMassKg = leanBodyMassKg,
    algorithmVersion = algorithmVersion,
    healthConnectStatus = SyncStatus.valueOf(healthConnectStatus),
    healthConnectError = healthConnectError,
    healthConnectWeightSynced = healthConnectWeightSynced,
    createdAtEpochMillis = createdAtEpochMillis,
    accountId = accountId,
    externalSyncPolicy = ExternalSyncPolicy.valueOf(externalSyncPolicy),
    sourcePendingId = sourcePendingId,
    deduplicationHash = deduplicationHash,
    healthConnectSyncedCalculatedValues = healthConnectSyncedCalculatedValues,
    ratingHeightCm = ratingHeightCm,
    ratingHeightOrigin = ratingHeightOrigin,
    origin = origin,
)
