package com.example.huaweimisync.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.huaweimisync.core.BodyComposition

enum class SyncStatus {
    PENDING,
    SYNCED,
    BLOCKED,
    DISABLED,
    FAILED,
}

@Entity(tableName = "measurements")
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
)

fun BodyComposition.toEntity(
    rawPayload: ByteArray,
    huaweiSyncEnabled: Boolean = true,
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
)
