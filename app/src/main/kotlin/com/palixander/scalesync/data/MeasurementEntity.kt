package com.palixander.scalesync.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.palixander.scalesync.core.BodyComposition
import com.palixander.scalesync.core.RawScaleMeasurement
import com.palixander.scalesync.core.measurementFingerprint
import com.palixander.scalesync.core.measurementId
import com.palixander.scalesync.domain.AccountId
import com.palixander.scalesync.domain.AccountMeasurement
import com.palixander.scalesync.domain.ExternalSyncPolicy
import java.time.Instant
import kotlin.math.roundToInt

enum class SyncStatus {
    PENDING,
    SYNCED,
    BLOCKED,
    DISABLED,
    FAILED,
    LOCAL_ONLY,
}

enum class MeasurementType {
    FULL,
    WEIGHT_ONLY,
}

data class MeasurementValues(
    val weightKg: Double,
    val impedanceOhm: Int,
    val bmi: Double,
    val bodyFatPercent: Double,
    val bodyFatMassKg: Double,
    val waterPercent: Double,
    val waterMassKg: Double,
    val muscleMassKg: Double,
    val skeletalMuscleMassKg: Double,
    val boneMassKg: Double,
    val proteinPercent: Double,
    val proteinMassKg: Double,
    val visceralFatLevel: Double,
    val basalMetabolicRateKcal: Double,
    val metabolicAge: Int,
    val leanBodyMassKg: Double,
)

/** Profile-dependent values written to one external destination after a successful sync. */
data class CalculatedValuesSnapshot(
    val bmi: Double? = null,
    val bodyFatPercent: Double? = null,
    val bodyFatMassKg: Double? = null,
    val waterPercent: Double? = null,
    val waterMassKg: Double? = null,
    val muscleMassKg: Double? = null,
    val skeletalMuscleMassKg: Double? = null,
    val boneMassKg: Double? = null,
    val proteinPercent: Double? = null,
    val proteinMassKg: Double? = null,
    val visceralFatLevel: Double? = null,
    val basalMetabolicRateKcal: Double? = null,
    val metabolicAge: Int? = null,
    val leanBodyMassKg: Double? = null,
) {
    fun encode(): String = listOf(
        FORMAT_VERSION,
        bmi,
        bodyFatPercent,
        bodyFatMassKg,
        waterPercent,
        waterMassKg,
        muscleMassKg,
        skeletalMuscleMassKg,
        boneMassKg,
        proteinPercent,
        proteinMassKg,
        visceralFatLevel,
        basalMetabolicRateKcal,
        metabolicAge,
        leanBodyMassKg,
    ).joinToString(SEPARATOR) { it?.toString() ?: MISSING_VALUE }

    fun matches(current: MeasurementValues): Boolean =
        bmi.matches(current.bmi) &&
            bodyFatPercent.matches(current.bodyFatPercent) &&
            bodyFatMassKg.matches(current.bodyFatMassKg) &&
            waterPercent.matches(current.waterPercent) &&
            waterMassKg.matches(current.waterMassKg) &&
            muscleMassKg.matches(current.muscleMassKg) &&
            skeletalMuscleMassKg.matches(current.skeletalMuscleMassKg) &&
            boneMassKg.matches(current.boneMassKg) &&
            proteinPercent.matches(current.proteinPercent) &&
            proteinMassKg.matches(current.proteinMassKg) &&
            visceralFatLevel.matches(current.visceralFatLevel) &&
            basalMetabolicRateKcal.matches(current.basalMetabolicRateKcal) &&
            metabolicAge.matches(current.metabolicAge) &&
            leanBodyMassKg.matches(current.leanBodyMassKg)

    companion object {
        private const val FORMAT_VERSION = "v1"
        private const val SEPARATOR = "|"
        private const val MISSING_VALUE = "_"
        private const val PART_COUNT = 15

        fun decode(value: String): CalculatedValuesSnapshot? {
            val parts = value.split(SEPARATOR)
            if (parts.size != PART_COUNT || parts.first() != FORMAT_VERSION) return null
            return runCatching {
                val snapshot = CalculatedValuesSnapshot(
                    bmi = parts[1].toOptionalDouble(),
                    bodyFatPercent = parts[2].toOptionalDouble(),
                    bodyFatMassKg = parts[3].toOptionalDouble(),
                    waterPercent = parts[4].toOptionalDouble(),
                    waterMassKg = parts[5].toOptionalDouble(),
                    muscleMassKg = parts[6].toOptionalDouble(),
                    skeletalMuscleMassKg = parts[7].toOptionalDouble(),
                    boneMassKg = parts[8].toOptionalDouble(),
                    proteinPercent = parts[9].toOptionalDouble(),
                    proteinMassKg = parts[10].toOptionalDouble(),
                    visceralFatLevel = parts[11].toOptionalDouble(),
                    basalMetabolicRateKcal = parts[12].toOptionalDouble(),
                    metabolicAge = parts[13].toOptionalInt(),
                    leanBodyMassKg = parts[14].toOptionalDouble(),
                )
                snapshot.takeIf(CalculatedValuesSnapshot::hasAnyValue)
            }.getOrNull()
        }

        private fun String.toOptionalDouble(): Double? =
            if (this == MISSING_VALUE) null else toDouble().also {
                require(it.isFinite()) { "Snapshot values must be finite" }
            }

        private fun String.toOptionalInt(): Int? = if (this == MISSING_VALUE) null else toInt()
    }

    private fun hasAnyValue(): Boolean =
        bmi != null ||
            bodyFatPercent != null ||
            bodyFatMassKg != null ||
            waterPercent != null ||
            waterMassKg != null ||
            muscleMassKg != null ||
            skeletalMuscleMassKg != null ||
            boneMassKg != null ||
            proteinPercent != null ||
            proteinMassKg != null ||
            visceralFatLevel != null ||
            basalMetabolicRateKcal != null ||
            metabolicAge != null ||
            leanBodyMassKg != null
}

private fun Double?.matches(current: Double): Boolean = this == null || this == current

private fun Int?.matches(current: Int): Boolean = this == null || this == current

enum class ExternalSyncDestination {
    HUAWEI,
    HEALTH_CONNECT,
}

/** Captures only values actually supported by the selected destination. */
fun MeasurementValues.toCalculatedValuesSnapshot(
    destination: ExternalSyncDestination,
): CalculatedValuesSnapshot = when (destination) {
    ExternalSyncDestination.HUAWEI -> CalculatedValuesSnapshot(
        bmi = bmi,
        bodyFatPercent = bodyFatPercent,
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
    )

    ExternalSyncDestination.HEALTH_CONNECT -> CalculatedValuesSnapshot(
        bodyFatPercent = bodyFatPercent,
        waterMassKg = waterMassKg,
        boneMassKg = boneMassKg,
        basalMetabolicRateKcal = basalMetabolicRateKcal,
        leanBodyMassKg = leanBodyMassKg,
    )
}

enum class MeasurementMetric(
    val displayName: String,
    val unit: String,
    val decimalPlaces: Int,
    private val extract: (MeasurementValues) -> Number,
) {
    WEIGHT_KG("Вес", "кг", 2, MeasurementValues::weightKg),
    IMPEDANCE_OHM("Импеданс", "Ом", 0, MeasurementValues::impedanceOhm),
    BMI("ИМТ", "кг/м²", 1, MeasurementValues::bmi),
    BODY_FAT_PERCENT("Жир", "%", 1, MeasurementValues::bodyFatPercent),
    BODY_FAT_MASS_KG("Масса жира", "кг", 2, MeasurementValues::bodyFatMassKg),
    WATER_PERCENT("Вода", "%", 1, MeasurementValues::waterPercent),
    WATER_MASS_KG("Масса воды", "кг", 2, MeasurementValues::waterMassKg),
    MUSCLE_MASS_KG("Мышечная масса", "кг", 2, MeasurementValues::muscleMassKg),
    SKELETAL_MUSCLE_MASS_KG(
        "Скелетные мышцы",
        "кг",
        2,
        MeasurementValues::skeletalMuscleMassKg,
    ),
    BONE_MASS_KG("Костная масса", "кг", 2, MeasurementValues::boneMassKg),
    PROTEIN_PERCENT("Белок", "%", 1, MeasurementValues::proteinPercent),
    PROTEIN_MASS_KG("Масса белка", "кг", 2, MeasurementValues::proteinMassKg),
    VISCERAL_FAT_LEVEL(
        "Уровень висцерального жира",
        "уровень",
        1,
        MeasurementValues::visceralFatLevel,
    ),
    BASAL_METABOLIC_RATE_KCAL(
        "Базальный обмен",
        "ккал/сут",
        0,
        MeasurementValues::basalMetabolicRateKcal,
    ),
    METABOLIC_AGE("Метаболический возраст", "лет", 0, MeasurementValues::metabolicAge),
    LEAN_BODY_MASS_KG("Безжировая масса", "кг", 2, MeasurementValues::leanBodyMassKg),
    ;

    fun valueOf(values: MeasurementValues): Double = extract(values).toDouble()

    fun valueOf(measurement: MeasurementEntity): Double? = when (this) {
        WEIGHT_KG -> measurement.weightKg
        else -> measurement.fullValues?.let(::valueOf)
    }
}

@Entity(
    tableName = "measurements",
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["accountId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["fingerprint"], unique = true),
        Index(value = ["accountId", "measuredAtEpochSecond"]),
        Index(value = ["deviceAddress", "rawWeight", "measuredAtEpochSecond"]),
        Index(value = ["sourcePendingId"], unique = true),
        Index(value = ["deduplicationHash"], unique = true),
    ],
)
data class MeasurementEntity(
    @PrimaryKey val id: String,
    val fingerprint: String = id,
    val measurementType: MeasurementType = MeasurementType.FULL,
    val deviceAddress: String,
    val measuredAtEpochSecond: Long,
    val rawPayloadHex: String,
    val weightKg: Double,
    val rawWeight: Int = (weightKg / RawScaleMeasurement.WEIGHT_RESOLUTION_KG).roundToInt(),
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
    val huaweiStatus: String = SyncStatus.PENDING.name,
    val healthConnectStatus: String = SyncStatus.PENDING.name,
    val huaweiError: String? = null,
    val healthConnectError: String? = null,
    val huaweiWeightSynced: Boolean = false,
    val healthConnectWeightSynced: Boolean = false,
    val createdAtEpochMillis: Long = System.currentTimeMillis(),
    /** Compatibility default for legacy callers; persisted v2 writes must always supply an account. */
    val accountId: String = LEGACY_UNASSIGNED_ACCOUNT_ID,
    val externalSyncPolicy: String = ExternalSyncPolicy.AUTO.name,
    val sourcePendingId: String? = null,
    val deduplicationHash: String? = null,
    /** Exact profile-dependent values last successfully sent to Huawei by this app version. */
    val huaweiSyncedCalculatedValues: String? = null,
    /** Exact profile-dependent values last successfully sent to Health Connect. */
    val healthConnectSyncedCalculatedValues: String? = null,
) {
    val values: MeasurementValues
        get() = checkNotNull(fullValues) { "Weight-only measurement $id has no composition values" }

    val fullValues: MeasurementValues?
        get() {
            if (measurementType != MeasurementType.FULL) return null
            return MeasurementValues(
                weightKg = weightKg,
                impedanceOhm = impedanceOhm ?: return null,
                bmi = bmi ?: return null,
                bodyFatPercent = bodyFatPercent ?: return null,
                bodyFatMassKg = bodyFatMassKg ?: return null,
                waterPercent = waterPercent ?: return null,
                waterMassKg = waterMassKg ?: return null,
                muscleMassKg = muscleMassKg ?: return null,
                skeletalMuscleMassKg = skeletalMuscleMassKg ?: return null,
                boneMassKg = boneMassKg ?: return null,
                proteinPercent = proteinPercent ?: return null,
                proteinMassKg = proteinMassKg ?: return null,
                visceralFatLevel = visceralFatLevel ?: return null,
                basalMetabolicRateKcal = basalMetabolicRateKcal ?: return null,
                metabolicAge = metabolicAge ?: return null,
                leanBodyMassKg = leanBodyMassKg ?: return null,
            )
        }

    fun currentCalculatedValuesSnapshot(
        destination: ExternalSyncDestination,
    ): CalculatedValuesSnapshot? = fullValues?.toCalculatedValuesSnapshot(destination)

    /**
     * Preserves the last payload of legacy synced rows before profile recalculation overwrites it.
     * Snapshot backfill is intentionally lazy so database migration remains a schema-only change.
     */
    internal fun backfillMissingSyncedCalculatedValues(): MeasurementEntity {
        val current = fullValues ?: return this
        return copy(
            huaweiSyncedCalculatedValues = huaweiSyncedCalculatedValues.ifMissingAndSynced(
                huaweiStatus,
            ) {
                current.toCalculatedValuesSnapshot(ExternalSyncDestination.HUAWEI).encode()
            },
            healthConnectSyncedCalculatedValues =
                healthConnectSyncedCalculatedValues.ifMissingAndSynced(healthConnectStatus) {
                    current.toCalculatedValuesSnapshot(ExternalSyncDestination.HEALTH_CONNECT)
                        .encode()
                },
        )
    }

    /** True for a known snapshot mismatch; legacy synced rows with no snapshot remain unknown. */
    val hasProfileSyncMismatch: Boolean
        get() = hasProfileSyncMismatch(huaweiSyncedCalculatedValues) ||
            hasProfileSyncMismatch(healthConnectSyncedCalculatedValues)

    /** Only an explicit user edit is presented as manual; account-local routing is not an edit. */
    val isManuallyEdited: Boolean
        get() = externalSyncPolicy == ExternalSyncPolicy.USER_LOCAL.name

    private fun hasProfileSyncMismatch(encodedSnapshot: String?): Boolean {
        if (encodedSnapshot == null) return false
        val current = fullValues ?: return true
        return CalculatedValuesSnapshot.decode(encodedSnapshot)?.matches(current) != true
    }

    fun toAccountMeasurement(): AccountMeasurement = AccountMeasurement(
        accountId = AccountId(accountId),
        composition = toBodyCompositionOrNull(),
        externalSyncPolicy = ExternalSyncPolicy.valueOf(externalSyncPolicy),
        createdAt = Instant.ofEpochMilli(createdAtEpochMillis),
        measurementId = id,
        weightKg = weightKg,
    )

    private fun toBodyCompositionOrNull(): BodyComposition? {
        val composition = fullValues ?: return null
        return BodyComposition(
            measurementId = id,
            deviceAddress = deviceAddress,
            measuredAt = measuredAt,
            weightKg = composition.weightKg,
            impedanceOhm = composition.impedanceOhm,
            bmi = composition.bmi,
            bodyFatPercent = composition.bodyFatPercent,
            bodyFatMassKg = composition.bodyFatMassKg,
            waterPercent = composition.waterPercent,
            waterMassKg = composition.waterMassKg,
            muscleMassKg = composition.muscleMassKg,
            skeletalMuscleMassKg = composition.skeletalMuscleMassKg,
            boneMassKg = composition.boneMassKg,
            proteinPercent = composition.proteinPercent,
            proteinMassKg = composition.proteinMassKg,
            visceralFatLevel = composition.visceralFatLevel,
            basalMetabolicRateKcal = composition.basalMetabolicRateKcal,
            metabolicAge = composition.metabolicAge,
            leanBodyMassKg = composition.leanBodyMassKg,
            algorithmVersion = algorithmVersion ?: return null,
        )
    }

    val measuredAt: Instant
        get() = Instant.ofEpochSecond(measuredAtEpochSecond)

    /** Milliseconds are derived only at presentation/integration boundaries, never persisted. */
    val measuredAtEpochMillis: Long
        get() = Math.multiplyExact(measuredAtEpochSecond, 1_000L)

    /** Retained as a source-compatibility view while storage has whole-second precision. */
    val measuredAtNano: Int
        get() = 0
}

private inline fun String?.ifMissingAndSynced(
    status: String,
    snapshot: () -> String,
): String? = if (isNullOrBlank() && status == SyncStatus.SYNCED.name) snapshot() else this

fun BodyComposition.toEntity(
    rawPayload: ByteArray,
    fingerprint: String = measurementId,
    huaweiSyncEnabled: Boolean = true,
    accountId: AccountId = AccountId(LEGACY_UNASSIGNED_ACCOUNT_ID),
    externalSyncPolicy: ExternalSyncPolicy = ExternalSyncPolicy.AUTO,
    sourcePendingId: String? = null,
    deduplicationHash: String? = null,
): MeasurementEntity = MeasurementEntity(
    id = measurementId,
    fingerprint = fingerprint,
    measurementType = MeasurementType.FULL,
    deviceAddress = deviceAddress,
    measuredAtEpochSecond = measuredAt.epochSecond,
    rawPayloadHex = rawPayload.joinToString("") { "%02x".format(it) },
    weightKg = weightKg,
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
    huaweiStatus = if (huaweiSyncEnabled) SyncStatus.PENDING.name else SyncStatus.DISABLED.name,
    huaweiError = if (huaweiSyncEnabled) null else "Huawei adapter disabled in personal build",
    accountId = accountId.value,
    externalSyncPolicy = externalSyncPolicy.name,
    sourcePendingId = sourcePendingId,
    deduplicationHash = deduplicationHash,
)

fun RawScaleMeasurement.toWeightOnlyEntity(
    huaweiSyncEnabled: Boolean = true,
    accountId: AccountId = AccountId(LEGACY_UNASSIGNED_ACCOUNT_ID),
    externalSyncPolicy: ExternalSyncPolicy = ExternalSyncPolicy.AUTO,
    sourcePendingId: String? = null,
    deduplicationHash: String? = null,
): MeasurementEntity = MeasurementEntity(
    id = measurementId(this),
    fingerprint = measurementFingerprint(this),
    measurementType = MeasurementType.WEIGHT_ONLY,
    deviceAddress = deviceAddress,
    measuredAtEpochSecond = measuredAt.epochSecond,
    rawPayloadHex = rawPayload.joinToString("") { "%02x".format(it) },
    weightKg = weightKg,
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
    huaweiStatus = if (huaweiSyncEnabled) SyncStatus.PENDING.name else SyncStatus.DISABLED.name,
    huaweiError = if (huaweiSyncEnabled) null else "Huawei adapter disabled in personal build",
    accountId = accountId.value,
    externalSyncPolicy = externalSyncPolicy.name,
    sourcePendingId = sourcePendingId,
    deduplicationHash = deduplicationHash,
)

const val LEGACY_UNASSIGNED_ACCOUNT_ID: String = "00000000-0000-0000-0000-000000000000"
