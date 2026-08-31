package com.palixander.scalesync.backup

import com.palixander.scalesync.core.Sex
import com.palixander.scalesync.data.MeasurementType
import com.palixander.scalesync.data.RatingHeightOrigin
import com.palixander.scalesync.data.SyncStatus
import com.palixander.scalesync.domain.ExternalSyncPolicy
import com.palixander.scalesync.domain.PetSpecies
import com.palixander.scalesync.domain.PetSex
import com.palixander.scalesync.domain.reference.DogAdultWeightCategory

const val BACKUP_FORMAT_ID: String = "scalesync-backup"
const val BACKUP_SCHEMA_VERSION: Int = 4
const val BACKUP_SCHEMA_VERSION_V1: Int = 1
const val BACKUP_SCHEMA_VERSION_V2: Int = 2
const val BACKUP_SCHEMA_VERSION_V3: Int = 3
const val MAX_BACKUP_ACCOUNTS: Int = 1_000
const val MAX_BACKUP_MEASUREMENTS: Int = 100_000
const val MAX_BACKUP_PETS: Int = 1_000
const val MAX_BACKUP_PET_MEASUREMENTS: Int = 100_000
const val MAX_BACKUP_SERIES_KEYS: Int = 100

data class BackupDocumentV1(
    val format: String = BACKUP_FORMAT_ID,
    val schemaVersion: Int = BACKUP_SCHEMA_VERSION,
    val exportedAt: String,
    val accounts: List<BackupAccountV1>,
    val appState: BackupAppStateV1,
    val measurements: List<BackupMeasurementV1>,
    val settings: BackupSettingsV1,
    val pets: List<BackupPetV2> = emptyList(),
    val petMeasurements: List<BackupPetMeasurementV2> = emptyList(),
)

data class BackupPetV2(
    val id: String,
    val displayName: String,
    val normalizedName: String,
    val species: PetSpecies,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val sex: PetSex? = null,
    val breedId: String? = null,
    val birthYear: Int? = null,
    val birthMonth: Int? = null,
    val birthDay: Int? = null,
    val dogAdultWeightCategory: DogAdultWeightCategory? = null,
)

data class BackupPetMeasurementV2(
    val id: String,
    val petId: String,
    val measuredAtEpochSecond: Long,
    val firstWeightKg: Double,
    val secondWeightKg: Double,
    val petWeightKg: Double,
)

data class BackupAccountV1(
    val id: String,
    val displayName: String,
    val normalizedName: String,
    val profile: BackupAccountProfileV1,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

data class BackupAccountProfileV1(
    val heightCm: Double?,
    val birthDateEpochDay: Long?,
    val sex: Sex?,
    val complete: Boolean,
)

data class BackupAppStateV1(
    val primaryAccountId: String?,
    val weightDeltaKg: Double,
    val ignoreUnknownMeasurements: Boolean,
)

data class BackupSettingsV1(
    val scaleAddress: String?,
    val scaleName: String?,
    val reliabilityMode: Boolean,
    val selectedChartMetricKeys: List<String>?,
    val homeKgChartSeriesKeys: List<String>?,
)

data class BackupMeasurementV1(
    val id: String,
    val fingerprint: String,
    val measurementType: MeasurementType,
    val deviceAddress: String,
    val measuredAtEpochSecond: Long,
    val rawPayloadHex: String,
    val weightKg: Double,
    val rawWeight: Int,
    val impedanceOhm: Int?,
    val bmi: Double?,
    val bodyFatPercent: Double?,
    val bodyFatMassKg: Double?,
    val waterPercent: Double?,
    val waterMassKg: Double?,
    val muscleMassKg: Double?,
    val skeletalMuscleMassKg: Double?,
    val boneMassKg: Double?,
    val proteinPercent: Double?,
    val proteinMassKg: Double?,
    val visceralFatLevel: Double?,
    val basalMetabolicRateKcal: Double?,
    val metabolicAge: Int?,
    val leanBodyMassKg: Double?,
    val algorithmVersion: String?,
    val huaweiStatus: SyncStatus,
    val healthConnectStatus: SyncStatus,
    val huaweiError: String?,
    val healthConnectError: String?,
    val huaweiWeightSynced: Boolean,
    val healthConnectWeightSynced: Boolean,
    val createdAtEpochMillis: Long,
    val accountId: String,
    val externalSyncPolicy: ExternalSyncPolicy,
    val sourcePendingId: String?,
    val deduplicationHash: String?,
    val huaweiSyncedCalculatedValues: String?,
    val healthConnectSyncedCalculatedValues: String?,
    val ratingHeightCm: Double? = null,
    val ratingHeightOrigin: RatingHeightOrigin? = RatingHeightOrigin.CAPTURED,
)

sealed class BackupException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class UnsupportedVersion(val version: Int?) :
        BackupException("Unsupported backup schema version: $version")

    class Corrupt(cause: Throwable? = null) : BackupException("Backup is not valid JSON", cause)
    class Invalid(val path: String, detail: String) : BackupException("Invalid $path: $detail")
    class Duplicate(val path: String, val value: String) :
        BackupException("Duplicate $path: $value")

    class MissingAccount(val accountId: String) :
        BackupException("Measurement references missing account: $accountId")

    class MissingPet(val petId: String) :
        BackupException("Pet measurement references missing pet: $petId")

    class Conflict(detail: String) : BackupException(detail)
    class Limits(val path: String, val limit: Int) : BackupException("$path exceeds limit $limit")
    class Io(cause: Throwable) : BackupException("Backup I/O failed", cause)
}
