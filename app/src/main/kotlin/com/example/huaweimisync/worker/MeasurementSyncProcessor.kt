package com.example.huaweimisync.worker

import com.example.huaweimisync.data.MeasurementEntity
import com.example.huaweimisync.data.SyncStatus
import com.example.huaweimisync.sync.MeasurementSyncPayload
import com.example.huaweimisync.sync.SyncResult

internal enum class MeasurementSyncOutcome {
    COMPLETE,
    RETRY,
}

/**
 * Runs one measurement sync without depending on WorkManager. Each destination reloads the
 * measurement immediately before deciding whether to call the external gateway. This prevents a
 * worker that was already running when a local edit happened from sending the edited record to the
 * next destination.
 */
internal class MeasurementSyncProcessor(
    private val loadMeasurement: suspend (String) -> MeasurementEntity?,
    private val writeHuawei: suspend (MeasurementSyncPayload) -> SyncResult,
    private val writeHealthConnect: suspend (MeasurementSyncPayload) -> SyncResult,
    private val applyHuaweiResult: suspend (String, MeasurementSyncPayload, SyncResult) -> Unit,
    private val applyHealthConnectResult:
        suspend (String, MeasurementSyncPayload, SyncResult) -> Unit,
) {
    suspend fun sync(measurementId: String): MeasurementSyncOutcome {
        val huaweiResult = syncHuawei(measurementId)
        val healthConnectResult = syncHealthConnect(measurementId)
        return if (huaweiResult is SyncResult.Retryable || healthConnectResult is SyncResult.Retryable) {
            MeasurementSyncOutcome.RETRY
        } else {
            MeasurementSyncOutcome.COMPLETE
        }
    }

    private suspend fun syncHuawei(measurementId: String): SyncResult? {
        val value = loadMeasurement(measurementId) ?: return null
        if (value.isLocalOnly()) return null
        if (value.huaweiStatus in HUAWEI_TERMINAL_STATUSES) return null

        val payload = MeasurementSyncPayload(
            measurement = value,
            includesWeight = !value.huaweiWeightSynced,
        )
        val result = if (payload.isEmpty) SyncResult.Success else writeHuawei(payload)
        applyHuaweiResult(measurementId, payload, result)
        return result
    }

    private suspend fun syncHealthConnect(measurementId: String): SyncResult? {
        val value = loadMeasurement(measurementId) ?: return null
        if (value.isLocalOnly()) return null
        if (value.healthConnectStatus in HEALTH_CONNECT_TERMINAL_STATUSES) return null

        val payload = MeasurementSyncPayload(
            measurement = value,
            includesWeight = !value.healthConnectWeightSynced,
        )
        val result = if (payload.isEmpty) SyncResult.Success else writeHealthConnect(payload)
        applyHealthConnectResult(measurementId, payload, result)
        return result
    }

    private fun MeasurementEntity.isLocalOnly(): Boolean =
        huaweiStatus == SyncStatus.LOCAL_ONLY.name ||
            healthConnectStatus == SyncStatus.LOCAL_ONLY.name

    private companion object {
        val HUAWEI_TERMINAL_STATUSES = setOf(
            SyncStatus.SYNCED.name,
            SyncStatus.DISABLED.name,
            SyncStatus.LOCAL_ONLY.name,
        )
        val HEALTH_CONNECT_TERMINAL_STATUSES = setOf(
            SyncStatus.SYNCED.name,
            SyncStatus.LOCAL_ONLY.name,
        )
    }
}
