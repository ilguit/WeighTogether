package com.example.huaweimisync.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.huaweimisync.core.BodyComposition
import com.example.huaweimisync.domain.AccountId
import com.example.huaweimisync.domain.AccountMeasurement
import com.example.huaweimisync.domain.ExternalSyncPolicy
import java.time.Instant

enum class SyncStatus {
    PENDING,
    SYNCED,
    BLOCKED,
    DISABLED,
    FAILED,
    LOCAL_ONLY,
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

    fun valueOf(measurement: MeasurementEntity): Double = valueOf(measurement.values)
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
        Index(value = ["accountId", "measuredAtEpochMillis"]),
        Index(value = ["sourcePendingId"], unique = true),
        Index(value = ["deduplicationHash"], unique = true),
    ],
)
data class MeasurementEntity(
    @PrimaryKey val id: String,
    val deviceAddress: String,
    val measuredAtEpochMillis: Long,
    val rawPayloadHex: String,
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
    val algorithmVersion: String,
    val huaweiStatus: String = SyncStatus.PENDING.name,
    val healthConnectStatus: String = SyncStatus.PENDING.name,
    val huaweiError: String? = null,
    val healthConnectError: String? = null,
    val createdAtEpochMillis: Long = System.currentTimeMillis(),
    /** Compatibility default for legacy callers; persisted v2 writes must always supply an account. */
    val accountId: String = LEGACY_UNASSIGNED_ACCOUNT_ID,
    val externalSyncPolicy: String = ExternalSyncPolicy.AUTO.name,
    val sourcePendingId: String? = null,
    val deduplicationHash: String? = null,
) {
    val values: MeasurementValues
        get() = MeasurementValues(
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
        )

    fun toAccountMeasurement(): AccountMeasurement = AccountMeasurement(
        accountId = AccountId(accountId),
        composition = BodyComposition(
            measurementId = id,
            deviceAddress = deviceAddress,
            measuredAt = Instant.ofEpochMilli(measuredAtEpochMillis),
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
        ),
        externalSyncPolicy = ExternalSyncPolicy.valueOf(externalSyncPolicy),
        createdAt = Instant.ofEpochMilli(createdAtEpochMillis),
    )
}

fun BodyComposition.toEntity(
    rawPayload: ByteArray,
    huaweiSyncEnabled: Boolean = true,
    accountId: AccountId = AccountId(LEGACY_UNASSIGNED_ACCOUNT_ID),
    externalSyncPolicy: ExternalSyncPolicy = ExternalSyncPolicy.AUTO,
    sourcePendingId: String? = null,
    deduplicationHash: String? = null,
): MeasurementEntity = MeasurementEntity(
    id = measurementId,
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
    accountId = accountId.value,
    externalSyncPolicy = externalSyncPolicy.name,
    sourcePendingId = sourcePendingId,
    deduplicationHash = deduplicationHash,
)

const val LEGACY_UNASSIGNED_ACCOUNT_ID: String = "00000000-0000-0000-0000-000000000000"
