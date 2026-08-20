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
import com.example.huaweimisync.HealthConnectAvailability
import java.time.Instant
import java.time.ZoneId

class HealthConnectGateway(private val context: Context) {
    val permissions: Set<String> = ALL_HEALTH_CONNECT_PERMISSIONS

    fun availability(): HealthConnectAvailability = healthConnectAvailability(
        HealthConnectClient.getSdkStatus(context),
    )

    fun isAvailable(): Boolean = availability() == HealthConnectAvailability.AVAILABLE

    suspend fun getGrantedPermissions(): Set<String> =
        if (isAvailable()) client().permissionController.getGrantedPermissions() else emptySet()

    suspend fun hasPermissions(): Boolean = getGrantedPermissions().containsAll(permissions)

    suspend fun write(payload: MeasurementSyncPayload): SyncResult {
        if (!isAvailable()) return SyncResult.Blocked("Health Connect недоступен")
        val permissionsGranted = runCatching {
            getGrantedPermissions().containsAll(requiredHealthConnectPermissions(payload))
        }.getOrElse {
            return it.toSyncFailure("Проверка разрешений Health Connect")
        }
        if (!permissionsGranted) return SyncResult.Blocked("Нет разрешения записи Health Connect")
        return try {
            client().insertRecords(buildHealthConnectRecords(payload))
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

internal fun healthConnectAvailability(sdkStatus: Int): HealthConnectAvailability =
    when (sdkStatus) {
        HealthConnectClient.SDK_AVAILABLE -> HealthConnectAvailability.AVAILABLE
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED ->
            HealthConnectAvailability.PROVIDER_UPDATE_REQUIRED
        else -> HealthConnectAvailability.UNAVAILABLE
    }

internal fun requiredHealthConnectPermissions(payload: MeasurementSyncPayload): Set<String> =
    buildSet {
        if (payload.includesWeight) add(WEIGHT_HEALTH_CONNECT_PERMISSION)
        if (payload.composition != null) addAll(COMPOSITION_HEALTH_CONNECT_PERMISSIONS)
    }

internal fun buildHealthConnectRecords(
    payload: MeasurementSyncPayload,
    zoneId: ZoneId = ZoneId.systemDefault(),
): List<Record> {
    val value = payload.measurement
    val time = Instant.ofEpochMilli(value.measuredAtEpochMillis)
    val zoneOffset = time.atZone(zoneId).offset
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

    return buildList {
        if (payload.includesWeight) {
            add(
                WeightRecord(
                    time = time,
                    zoneOffset = zoneOffset,
                    weight = Mass.kilograms(value.weightKg),
                    metadata = metadata("weight"),
                ),
            )
        }
        payload.composition?.let { composition ->
            add(
                BodyFatRecord(
                    time = time,
                    zoneOffset = zoneOffset,
                    percentage = Percentage(composition.bodyFatPercent),
                    metadata = metadata("body-fat"),
                ),
            )
            add(
                BodyWaterMassRecord(
                    time = time,
                    zoneOffset = zoneOffset,
                    mass = Mass.kilograms(composition.waterMassKg),
                    metadata = metadata("body-water"),
                ),
            )
            add(
                BoneMassRecord(
                    time = time,
                    zoneOffset = zoneOffset,
                    mass = Mass.kilograms(composition.boneMassKg),
                    metadata = metadata("bone-mass"),
                ),
            )
            add(
                LeanBodyMassRecord(
                    time = time,
                    zoneOffset = zoneOffset,
                    mass = Mass.kilograms(composition.leanBodyMassKg),
                    metadata = metadata("lean-body-mass"),
                ),
            )
            add(
                BasalMetabolicRateRecord(
                    time = time,
                    zoneOffset = zoneOffset,
                    basalMetabolicRate = Power.kilocaloriesPerDay(
                        composition.basalMetabolicRateKcal,
                    ),
                    metadata = metadata("basal-metabolic-rate"),
                ),
            )
        }
    }
}

private val WEIGHT_HEALTH_CONNECT_PERMISSION =
    HealthPermission.getWritePermission(WeightRecord::class)

private val COMPOSITION_HEALTH_CONNECT_PERMISSIONS = setOf(
    HealthPermission.getWritePermission(BodyFatRecord::class),
    HealthPermission.getWritePermission(BodyWaterMassRecord::class),
    HealthPermission.getWritePermission(BoneMassRecord::class),
    HealthPermission.getWritePermission(LeanBodyMassRecord::class),
    HealthPermission.getWritePermission(BasalMetabolicRateRecord::class),
)

private val ALL_HEALTH_CONNECT_PERMISSIONS =
    COMPOSITION_HEALTH_CONNECT_PERMISSIONS + WEIGHT_HEALTH_CONNECT_PERMISSION
