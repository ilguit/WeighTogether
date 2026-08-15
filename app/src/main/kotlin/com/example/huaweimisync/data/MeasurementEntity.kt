package com.example.huaweimisync.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.huaweimisync.core.BodyComposition
import com.example.huaweimisync.core.RawScaleMeasurement
import com.example.huaweimisync.core.measurementFingerprint
import com.example.huaweimisync.core.measurementId

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
    indices = [Index(value = ["fingerprint"], unique = true)],
)
data class MeasurementEntity(
    @PrimaryKey val id: String,
    val fingerprint: String = id,
    val measurementType: MeasurementType = MeasurementType.FULL,
    val deviceAddress: String,
    val measuredAtEpochMillis: Long,
    val rawPayloadHex: String,
    val weightKg: Double,
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
}

fun BodyComposition.toEntity(
    rawPayload: ByteArray,
    fingerprint: String,
    huaweiSyncEnabled: Boolean = true,
): MeasurementEntity = MeasurementEntity(
    id = measurementId,
    fingerprint = fingerprint,
    measurementType = MeasurementType.FULL,
    deviceAddress = deviceAddress,
    measuredAtEpochMillis = measuredAt.toEpochMilli(),
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
)

fun RawScaleMeasurement.toWeightOnlyEntity(
    huaweiSyncEnabled: Boolean = true,
): MeasurementEntity = MeasurementEntity(
    id = measurementId(this),
    fingerprint = measurementFingerprint(this),
    measurementType = MeasurementType.WEIGHT_ONLY,
    deviceAddress = deviceAddress,
    measuredAtEpochMillis = measuredAt.toEpochMilli(),
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
)
