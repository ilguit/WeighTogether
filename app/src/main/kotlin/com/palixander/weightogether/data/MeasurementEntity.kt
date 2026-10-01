package com.palixander.weightogether.data

import com.palixander.weightogether.domain.MeasurementOrigin
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo
import androidx.annotation.StringRes
import com.palixander.weightogether.R
import com.palixander.weightogether.core.BodyComposition
import com.palixander.weightogether.core.RawScaleMeasurement
import com.palixander.weightogether.core.measurementFingerprint
import com.palixander.weightogether.core.measurementId
import com.palixander.weightogether.domain.AccountId
import com.palixander.weightogether.domain.AccountMeasurement
import com.palixander.weightogether.domain.ExternalSyncPolicy
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

enum class RatingHeightOrigin {
    CAPTURED,
    RESTORED_CURRENT_ACCOUNT,
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
    HEALTH_CONNECT,
}

/** Captures only values actually supported by the selected destination. */
fun MeasurementValues.toCalculatedValuesSnapshot(
    destination: ExternalSyncDestination,
): CalculatedValuesSnapshot = when (destination) {
    ExternalSyncDestination.HEALTH_CONNECT -> CalculatedValuesSnapshot(
        bodyFatPercent = bodyFatPercent,
        waterMassKg = waterMassKg,
        boneMassKg = boneMassKg,
        basalMetabolicRateKcal = basalMetabolicRateKcal,
        leanBodyMassKg = leanBodyMassKg,
    )
}

enum class MeasurementMetric(
    @param:StringRes val displayNameRes: Int,
    @param:StringRes val unitRes: Int,
    val decimalPlaces: Int,
    private val extract: (MeasurementValues) -> Number,
) {
    WEIGHT_KG(R.string.metric_weight, R.string.unit_kg, 2, MeasurementValues::weightKg),
    IMPEDANCE_OHM(R.string.metric_impedance, R.string.unit_ohm, 0, MeasurementValues::impedanceOhm),
    BMI(R.string.metric_bmi, R.string.unit_kg_per_square_meter, 1, MeasurementValues::bmi),
    BODY_FAT_PERCENT(R.string.metric_body_fat, R.string.unit_percent, 1, MeasurementValues::bodyFatPercent),
    BODY_FAT_MASS_KG(R.string.metric_body_fat_mass, R.string.unit_kg, 2, MeasurementValues::bodyFatMassKg),
    WATER_PERCENT(R.string.metric_water, R.string.unit_percent, 1, MeasurementValues::waterPercent),
    WATER_MASS_KG(R.string.metric_water_mass, R.string.unit_kg, 2, MeasurementValues::waterMassKg),
    MUSCLE_MASS_KG(R.string.metric_muscle_mass, R.string.unit_kg, 2, MeasurementValues::muscleMassKg),
    SKELETAL_MUSCLE_MASS_KG(
        R.string.metric_skeletal_muscle_mass,
        R.string.unit_kg,
        2,
        MeasurementValues::skeletalMuscleMassKg,
    ),
    BONE_MASS_KG(R.string.metric_bone_mass, R.string.unit_kg, 2, MeasurementValues::boneMassKg),
    PROTEIN_PERCENT(R.string.metric_protein, R.string.unit_percent, 1, MeasurementValues::proteinPercent),
    PROTEIN_MASS_KG(R.string.metric_protein_mass, R.string.unit_kg, 2, MeasurementValues::proteinMassKg),
    VISCERAL_FAT_LEVEL(
        R.string.metric_visceral_fat_level,
        R.string.unit_level,
        1,
        MeasurementValues::visceralFatLevel,
    ),
    BASAL_METABOLIC_RATE_KCAL(
        R.string.metric_basal_metabolic_rate,
        R.string.unit_kcal_per_day,
        0,
        MeasurementValues::basalMetabolicRateKcal,
    ),
    METABOLIC_AGE(R.string.metric_metabolic_age, R.string.unit_years, 0, MeasurementValues::metabolicAge),
    LEAN_BODY_MASS_KG(R.string.metric_lean_body_mass, R.string.unit_kg, 2, MeasurementValues::leanBodyMassKg),
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
    val healthConnectStatus: String = SyncStatus.PENDING.name,
    val healthConnectError: String? = null,
    val healthConnectWeightSynced: Boolean = false,
    val createdAtEpochMillis: Long = System.currentTimeMillis(),
    /** Compatibility default for legacy callers; persisted v2 writes must always supply an account. */
    val accountId: String = LEGACY_UNASSIGNED_ACCOUNT_ID,
    val externalSyncPolicy: String = ExternalSyncPolicy.AUTO.name,
    val sourcePendingId: String? = null,
    val deduplicationHash: String? = null,
    /** Exact profile-dependent values last successfully sent to Health Connect. */
    val healthConnectSyncedCalculatedValues: String? = null,
    /** Height snapshot used to interpret this measurement against reference ranges. */
    val ratingHeightCm: Double? = null,
    /** Whether [ratingHeightCm] was captured at measurement time or restored for legacy data. */
    @ColumnInfo(defaultValue = "'RESTORED_CURRENT_ACCOUNT'")
    val ratingHeightOrigin: RatingHeightOrigin = RatingHeightOrigin.CAPTURED,
    @ColumnInfo(defaultValue = "'LEGACY'")
    val origin: MeasurementOrigin = MeasurementOrigin.LEGACY,
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
            healthConnectSyncedCalculatedValues =
                healthConnectSyncedCalculatedValues.ifMissingAndSynced(healthConnectStatus) {
                    current.toCalculatedValuesSnapshot(ExternalSyncDestination.HEALTH_CONNECT)
                        .encode()
                },
        )
    }

    /** True for a known snapshot mismatch; legacy synced rows with no snapshot remain unknown. */
    val hasProfileSyncMismatch: Boolean
        get() = hasProfileSyncMismatch(healthConnectSyncedCalculatedValues)

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
    accountId: AccountId = AccountId(LEGACY_UNASSIGNED_ACCOUNT_ID),
    externalSyncPolicy: ExternalSyncPolicy = ExternalSyncPolicy.AUTO,
    sourcePendingId: String? = null,
    deduplicationHash: String? = null,
    ratingHeightCm: Double? = null,
    ratingHeightOrigin: RatingHeightOrigin = RatingHeightOrigin.CAPTURED,
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
    accountId = accountId.value,
    externalSyncPolicy = externalSyncPolicy.name,
    sourcePendingId = sourcePendingId,
    deduplicationHash = deduplicationHash,
    ratingHeightCm = ratingHeightCm,
    ratingHeightOrigin = ratingHeightOrigin,
    origin = MeasurementOrigin.SCALE,
)

fun RawScaleMeasurement.toWeightOnlyEntity(
    accountId: AccountId = AccountId(LEGACY_UNASSIGNED_ACCOUNT_ID),
    externalSyncPolicy: ExternalSyncPolicy = ExternalSyncPolicy.AUTO,
    sourcePendingId: String? = null,
    deduplicationHash: String? = null,
    ratingHeightCm: Double? = null,
    ratingHeightOrigin: RatingHeightOrigin = RatingHeightOrigin.CAPTURED,
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
    accountId = accountId.value,
    externalSyncPolicy = externalSyncPolicy.name,
    sourcePendingId = sourcePendingId,
    deduplicationHash = deduplicationHash,
    ratingHeightCm = ratingHeightCm,
    ratingHeightOrigin = ratingHeightOrigin,
    origin = MeasurementOrigin.SCALE,
)

const val LEGACY_UNASSIGNED_ACCOUNT_ID: String = "00000000-0000-0000-0000-000000000000"
