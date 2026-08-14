package com.example.huaweimisync.sync

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyWaterMassRecord
import androidx.health.connect.client.records.BoneMassRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.units.Percentage
import androidx.health.connect.client.units.Power
import com.example.huaweimisync.data.MeasurementEntity
import java.time.Instant
import java.time.ZoneId

class HealthConnectGateway(private val context: Context) {
    val permissions: Set<String> = setOf(
        HealthPermission.getWritePermission(WeightRecord::class),
        HealthPermission.getWritePermission(BodyFatRecord::class),
        HealthPermission.getWritePermission(BodyWaterMassRecord::class),
        HealthPermission.getWritePermission(BoneMassRecord::class),
        HealthPermission.getWritePermission(LeanBodyMassRecord::class),
        HealthPermission.getWritePermission(BasalMetabolicRateRecord::class),
    )

    fun isAvailable(): Boolean =
        HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    suspend fun hasPermissions(): Boolean = isAvailable() &&
        client().permissionController.getGrantedPermissions().containsAll(permissions)

    suspend fun write(value: MeasurementEntity): SyncResult {
        if (!isAvailable()) return SyncResult.Blocked("Health Connect недоступен")
        val permissionsGranted = runCatching { hasPermissions() }.getOrElse {
            return it.toSyncFailure("Проверка разрешений Health Connect")
        }
        if (!permissionsGranted) return SyncResult.Blocked("Нет разрешения записи Health Connect")
        return try {
            val time = Instant.ofEpochMilli(value.measuredAtEpochMillis)
            val zoneOffset = time.atZone(ZoneId.systemDefault()).offset
            val scale = Device(
                manufacturer = "Xiaomi",
                model = "Mi Body Composition Scale 2 (XMTZC05HM)",
                type = Device.TYPE_SCALE,
            )
            fun metadata(recordType: String) = Metadata.autoRecorded(
                clientRecordId = "${value.id}:$recordType",
                clientRecordVersion = 0,
                device = scale,
            )
            client().insertRecords(
                listOf<Record>(
                    WeightRecord(
                        time = time,
                        zoneOffset = zoneOffset,
                        weight = Mass.kilograms(value.weightKg),
                        metadata = metadata("weight"),
                    ),
                    BodyFatRecord(
                        time = time,
                        zoneOffset = zoneOffset,
                        percentage = Percentage(value.bodyFatPercent),
                        metadata = metadata("body-fat"),
                    ),
                    BodyWaterMassRecord(
                        time = time,
                        zoneOffset = zoneOffset,
                        mass = Mass.kilograms(value.waterMassKg),
                        metadata = metadata("body-water"),
                    ),
                    BoneMassRecord(
                        time = time,
                        zoneOffset = zoneOffset,
                        mass = Mass.kilograms(value.boneMassKg),
                        metadata = metadata("bone-mass"),
                    ),
                    LeanBodyMassRecord(
                        time = time,
                        zoneOffset = zoneOffset,
                        mass = Mass.kilograms(value.leanBodyMassKg),
                        metadata = metadata("lean-body-mass"),
                    ),
                    BasalMetabolicRateRecord(
                        time = time,
                        zoneOffset = zoneOffset,
                        basalMetabolicRate = Power.kilocaloriesPerDay(value.basalMetabolicRateKcal),
                        metadata = metadata("basal-metabolic-rate"),
                    ),
                ),
            )
            SyncResult.Success
        } catch (error: Throwable) {
            error.toSyncFailure("Запись Health Connect")
        }
    }

    private fun client(): HealthConnectClient = HealthConnectClient.getOrCreate(context)

    private fun Throwable.toSyncFailure(operation: String): SyncResult = when (this) {
        is SecurityException,
        is IllegalArgumentException,
        -> SyncResult.Blocked("$operation отклонена: ${message ?: javaClass.simpleName}")
        else -> SyncResult.Retryable("$operation временно не выполнена: ${message ?: javaClass.simpleName}")
    }
}
