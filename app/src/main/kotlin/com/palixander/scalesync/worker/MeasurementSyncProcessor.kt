package com.palixander.scalesync.worker

import com.palixander.scalesync.data.MeasurementEntity
import com.palixander.scalesync.data.SyncStatus
import com.palixander.scalesync.domain.ExternalSyncPolicy
import com.palixander.scalesync.sync.MeasurementSyncPayload
import com.palixander.scalesync.sync.SyncResult

internal sealed interface MeasurementSyncOutcome {
    data object Complete : MeasurementSyncOutcome
    data object Retry : MeasurementSyncOutcome
    data object Paused : MeasurementSyncOutcome
    data class Deferred(val notBeforeEpochMillis: Long) : MeasurementSyncOutcome
}

/**
 * Runs one measurement sync without depending on WorkManager. Each destination reloads the
 * measurement immediately before deciding whether to call the external gateway. This prevents a
 * worker that was already running when a local edit happened from sending the edited record to the
 * next destination.
 */
internal class MeasurementSyncProcessor(
    private val loadMeasurement: suspend (String) -> MeasurementEntity?,
    private val isEligible: suspend (MeasurementEntity) -> Boolean = { true },
    private val writeHuawei: suspend (MeasurementSyncPayload) -> SyncResult,
    private val writeHealthConnect: suspend (MeasurementSyncPayload) -> SyncResult,
    private val applyHuaweiResult: suspend (String, MeasurementSyncPayload, SyncResult) -> Unit,
    private val applyHealthConnectResult:
        suspend (String, MeasurementSyncPayload, SyncResult) -> Unit,
    private val isPaused: () -> Boolean = { false },
    private val isHuaweiEnabled: () -> Boolean = { true },
    private val isHealthConnectEnabled: () -> Boolean = { true },
) {
    suspend fun sync(measurementId: String): MeasurementSyncOutcome {
        val huaweiResult = syncHuawei(measurementId)
        if (huaweiResult is DestinationResult.Paused) return MeasurementSyncOutcome.Paused
        val healthConnectResult = syncHealthConnect(measurementId)
        if (healthConnectResult is DestinationResult.Paused) return MeasurementSyncOutcome.Paused
        return if (huaweiResult.isRetryable() || healthConnectResult.isRetryable()) {
            MeasurementSyncOutcome.Retry
        } else {
            MeasurementSyncOutcome.Complete
        }
    }

    private suspend fun syncHuawei(measurementId: String): DestinationResult {
        val value = loadMeasurement(measurementId) ?: return DestinationResult.Skipped
        if (value.externalSyncPolicy != ExternalSyncPolicy.AUTO.name) return DestinationResult.Skipped
        if (!isEligible(value)) return DestinationResult.Skipped
        if (value.huaweiStatus in HUAWEI_TERMINAL_STATUSES) return DestinationResult.Skipped
        if (!isHuaweiEnabled()) return DestinationResult.Skipped

        val payload = MeasurementSyncPayload(
            measurement = value,
            includesWeight = !value.huaweiWeightSynced,
        )
        if (isPaused()) return DestinationResult.Paused
        val result = if (payload.isEmpty) SyncResult.Success else writeHuawei(payload)
        applyHuaweiResult(measurementId, payload, result)
        return DestinationResult.Attempted(result)
    }

    private suspend fun syncHealthConnect(measurementId: String): DestinationResult {
        val value = loadMeasurement(measurementId) ?: return DestinationResult.Skipped
        if (value.externalSyncPolicy != ExternalSyncPolicy.AUTO.name) return DestinationResult.Skipped
        if (!isEligible(value)) return DestinationResult.Skipped
        if (value.healthConnectStatus in HEALTH_CONNECT_TERMINAL_STATUSES) {
            return DestinationResult.Skipped
        }
        if (!isHealthConnectEnabled()) return DestinationResult.Skipped

        val payload = MeasurementSyncPayload(
            measurement = value,
            includesWeight = !value.healthConnectWeightSynced,
        )
        if (isPaused()) return DestinationResult.Paused
        val result = if (payload.isEmpty) SyncResult.Success else writeHealthConnect(payload)
        applyHealthConnectResult(measurementId, payload, result)
        return DestinationResult.Attempted(result)
    }

    private fun DestinationResult.isRetryable(): Boolean =
        this is DestinationResult.Attempted && result is SyncResult.Retryable

    private sealed interface DestinationResult {
        data object Skipped : DestinationResult
        data class Attempted(val result: SyncResult) : DestinationResult
        data object Paused : DestinationResult
    }

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
