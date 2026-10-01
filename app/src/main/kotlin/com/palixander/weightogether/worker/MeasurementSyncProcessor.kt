package com.palixander.weightogether.worker

import com.palixander.weightogether.data.MeasurementEntity
import com.palixander.weightogether.data.SyncStatus
import com.palixander.weightogether.domain.ExternalSyncPolicy
import com.palixander.weightogether.sync.MeasurementSyncPayload
import com.palixander.weightogether.sync.SyncResult

internal sealed interface MeasurementSyncOutcome {
    data object Complete : MeasurementSyncOutcome
    data object Retry : MeasurementSyncOutcome
    data object Paused : MeasurementSyncOutcome
    data class Deferred(val notBeforeEpochMillis: Long) : MeasurementSyncOutcome
}

/**
 * Runs one measurement sync without depending on WorkManager. Reloads the measurement on each
 * attempt before deciding whether to send it to Health Connect.
 */
internal class MeasurementSyncProcessor(
    private val loadMeasurement: suspend (String) -> MeasurementEntity?,
    private val isEligible: suspend (MeasurementEntity) -> Boolean = { true },
    private val writeHealthConnect: suspend (MeasurementSyncPayload) -> SyncResult,
    private val applyHealthConnectResult:
        suspend (String, MeasurementSyncPayload, SyncResult) -> Unit,
    private val isPaused: () -> Boolean = { false },
    private val isHealthConnectEnabled: () -> Boolean = { true },
) {
    suspend fun sync(measurementId: String): MeasurementSyncOutcome {
        val value = loadMeasurement(measurementId) ?: return MeasurementSyncOutcome.Complete
        if (value.externalSyncPolicy != ExternalSyncPolicy.AUTO.name) return MeasurementSyncOutcome.Complete
        if (!isEligible(value)) return MeasurementSyncOutcome.Complete
        if (value.healthConnectStatus in HEALTH_CONNECT_TERMINAL_STATUSES) {
            return MeasurementSyncOutcome.Complete
        }
        if (!isHealthConnectEnabled()) return MeasurementSyncOutcome.Complete

        val payload = MeasurementSyncPayload(
            measurement = value,
            includesWeight = !value.healthConnectWeightSynced,
        )
        if (isPaused()) return MeasurementSyncOutcome.Paused
        val result = if (payload.isEmpty) SyncResult.Success else writeHealthConnect(payload)
        applyHealthConnectResult(measurementId, payload, result)
        return if (result is SyncResult.Retryable) {
            MeasurementSyncOutcome.Retry
        } else {
            MeasurementSyncOutcome.Complete
        }
    }

    private companion object {
        val HEALTH_CONNECT_TERMINAL_STATUSES = setOf(
            SyncStatus.SYNCED.name,
            SyncStatus.LOCAL_ONLY.name,
        )
    }
}
