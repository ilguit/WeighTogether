package com.palixander.weightogether.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import com.palixander.weightogether.ScaleSyncApplication
import com.palixander.weightogether.data.ExternalSyncDestination
import com.palixander.weightogether.data.toCalculatedValuesSnapshot
import com.palixander.weightogether.sync.SyncResult
import com.palixander.weightogether.sync.toStateUpdate

class MeasurementSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_ID) ?: return Result.failure()
        val container = (applicationContext as ScaleSyncApplication).container
        val dao = container.database.measurementDao()
        val eligibility = MeasurementSyncEligibilityPolicy(container.database)
        return container.externalSyncOperations.runExclusive {
            val outcome = MeasurementSyncProcessor(
                loadMeasurement = dao::get,
                isEligible = eligibility::isEligible,
                writeHealthConnect = container.healthConnect::write,
                applyHealthConnectResult = { measurementId, payload, result ->
                    applyHealthConnectResult(dao, measurementId, payload, result)
                },
                isPaused = { container.profileStore.externalSyncPaused },
                isHealthConnectEnabled = {
                    container.profileStore.isExternalSyncEnabled(ExternalSyncDestination.HEALTH_CONNECT)
                },
            ).sync(id)

            when (outcome) {
                MeasurementSyncOutcome.Complete -> Result.success()
                MeasurementSyncOutcome.Retry -> Result.retry()
                MeasurementSyncOutcome.Paused -> Result.success()
                is MeasurementSyncOutcome.Deferred -> {
                    // Append the deferred request while this worker still owns the operation
                    // serializer. Deletion-after-defer cancels the whole chain, while
                    // deletion-before-defer makes the Room reload complete without this id.
                    container.syncScheduler.deferCurrent(id, outcome.notBeforeEpochMillis)
                    Result.success()
                }
            }
        }
    }

    private suspend fun applyHealthConnectResult(
        dao: com.palixander.weightogether.data.MeasurementDao,
        id: String,
        payload: com.palixander.weightogether.sync.MeasurementSyncPayload,
        result: SyncResult,
    ) {
        val update = result.toStateUpdate()
        dao.applyHealthConnectSyncResult(
            id = id,
            expectedMeasurementType = payload.measurement.measurementType.name,
            status = update.status.name,
            error = update.error,
            markWeightSynced = payload.includesWeight && result is SyncResult.Success,
            syncedCalculatedValues = payload.successfulCalculatedValuesSnapshot(
                result,
                ExternalSyncDestination.HEALTH_CONNECT,
            ),
        )
    }

    companion object {
        private const val KEY_ID = "measurement_id"
        fun inputData(id: String): Data = Data.Builder().putString(KEY_ID, id).build()
    }
}

internal fun com.palixander.weightogether.sync.MeasurementSyncPayload.successfulCalculatedValuesSnapshot(
    result: SyncResult,
    destination: ExternalSyncDestination,
): String? = if (result is SyncResult.Success) {
    composition?.toCalculatedValuesSnapshot(destination)?.encode()
} else {
    null
}
