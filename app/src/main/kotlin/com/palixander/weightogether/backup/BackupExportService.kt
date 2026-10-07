package com.palixander.weightogether.backup

import androidx.room.withTransaction
import com.palixander.weightogether.core.Sex
import com.palixander.weightogether.core.breed.canonicalBreedId
import com.palixander.weightogether.data.AccountEntity
import com.palixander.weightogether.data.AppDatabase
import com.palixander.weightogether.data.AppStateEntity
import com.palixander.weightogether.data.MeasurementEntity
import com.palixander.weightogether.data.PetEntity
import com.palixander.weightogether.data.PetMeasurementEntity
import com.palixander.weightogether.data.PortableProfileSettings
import com.palixander.weightogether.data.SyncStatus
import com.palixander.weightogether.data.WeighingReminderScheduleEntity
import com.palixander.weightogether.domain.ExternalSyncPolicy
import com.palixander.weightogether.data.ProfilePhotoReferenceCoordinator
import com.palixander.weightogether.profile.ProfilePhotoOwner
import com.palixander.weightogether.profile.ProfilePhotoOwnerType
import com.palixander.weightogether.profile.ProfilePhotoStore
import java.io.File
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
    val reminderSchedules: List<WeighingReminderScheduleEntity> = emptyList(),
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
            reminderSchedules = database.weighingReminderDao().getAll(),
        )
    }
}

class BackupExportService(
    private val snapshotSource: BackupSnapshotSource,
    private val settingsSnapshot: () -> PortableProfileSettings,
    private val codec: BackupJsonCodec = BackupJsonCodec(),
    private val clock: Clock = Clock.systemUTC(),
    private val archiveCodec: BackupArchiveCodec = BackupArchiveCodec(File(System.getProperty("java.io.tmpdir"), "backup-staging")),
    private val photoStore: ProfilePhotoStore? = null,
    private val photoReferences: ProfilePhotoReferenceCoordinator? = null,
) {
    suspend fun createDocument(): BackupDocumentV1 {
        val database = snapshotSource.readSnapshot()
        return createDocument(database)
    }

    private fun createDocument(database: BackupDatabaseSnapshot): BackupDocumentV1 {
        val settings = settingsSnapshot()
        return BackupDocumentV1(
            exportedAt = Instant.now(clock).toString(),
            accounts = database.accounts.map(AccountEntity::toBackup),
            appState = database.appState.toBackup(),
            measurements = database.measurements.map(MeasurementEntity::toBackup),
            settings = settings.toBackup(),
            pets = database.pets.map(PetEntity::toBackup),
            petMeasurements = database.petMeasurements.map(PetMeasurementEntity::toBackup),
            reminderSchedules = database.reminderSchedules.map(WeighingReminderScheduleEntity::toBackup),
        )
    }

    /** Writes a .wtrn ZIP archive; the caller owns the SAF stream. */
    suspend fun writeTo(output: OutputStream): BackupDocumentV1 {
        suspend fun capture(): BackupImportSource {
            val snapshot = snapshotSource.readSnapshot()
            val document = createDocument(snapshot)
            val files = buildMap {
                snapshot.accounts.forEach { account ->
                    account.photoPath?.let { path ->
                        val store = photoStore ?: throw BackupException.Invalid("photos", "photo storage unavailable")
                        put(ProfilePhotoOwner(ProfilePhotoOwnerType.ACCOUNT, account.id), store.resolve(path))
                    }
                }
                snapshot.pets.forEach { pet ->
                    pet.photoPath?.let { path ->
                        val store = photoStore ?: throw BackupException.Invalid("photos", "photo storage unavailable")
                        put(ProfilePhotoOwner(ProfilePhotoOwnerType.PET, pet.id), store.resolve(path))
                    }
                }
            }
            return archiveCodec.snapshot(document, files)
        }
        try {
            val source = photoReferences?.withStableReferences { capture() } ?: capture()
            return source.use {
                archiveCodec.write(it, output)
                it.document
            }
        } catch (error: IOException) {
            throw BackupException.Io(error)
        }
    }
}

private fun WeighingReminderScheduleEntity.toBackup() = BackupReminderScheduleV7(
    id, ownerType, ownerId, minuteOfDay, weekdaysMask, importance, enabled,
    createdAtEpochMillis, updatedAtEpochMillis, alarmSoundUri,
)

private fun PetEntity.toBackup() = BackupPetV2(
    id, displayName, normalizedName, species, createdAtEpochMillis, updatedAtEpochMillis,
    sex, breedId?.let(::canonicalBreedId), birthYear, birthMonth, birthDay, dogAdultWeightCategory, heightCm,
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
