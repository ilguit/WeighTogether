package com.example.huaweimisync.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import com.example.huaweimisync.MiSyncApplication
import com.example.huaweimisync.sync.SyncResult
import com.example.huaweimisync.sync.toStateUpdate

class MeasurementSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_ID) ?: return Result.failure()
        val container = (applicationContext as MiSyncApplication).container
        val dao = container.database.measurementDao()
        val eligibility = MeasurementSyncEligibilityPolicy(container.database)
        val outcome = MeasurementSyncProcessor(
            loadMeasurement = dao::get,
            isEligible = eligibility::isEligible,
            writeHuawei = container.huaweiHealth::write,
            writeHealthConnect = container.healthConnect::write,
            applyHuaweiResult = { measurementId, payload, result ->
                applyHuaweiResult(dao, measurementId, payload, result)
            },
            applyHealthConnectResult = { measurementId, payload, result ->
                applyHealthConnectResult(dao, measurementId, payload, result)
            },
            pausedUntilProvider = { container.profileStore.externalSyncPausedUntilEpochMillis },
        ).sync(id)

        return when (outcome) {
            MeasurementSyncOutcome.Complete -> Result.success()
            MeasurementSyncOutcome.Retry -> Result.retry()
            is MeasurementSyncOutcome.Deferred -> {
                container.syncScheduler.reschedule(id, outcome.notBeforeEpochMillis)
                Result.success()
            }
        }
    }

    private suspend fun applyHuaweiResult(
        dao: com.example.huaweimisync.data.MeasurementDao,
        id: String,
        payload: com.example.huaweimisync.sync.MeasurementSyncPayload,
        result: SyncResult,
    ) {
        val update = result.toStateUpdate()
        dao.applyHuaweiSyncResult(
            id = id,
            expectedMeasurementType = payload.measurement.measurementType.name,
            status = update.status.name,
            error = update.error,
            markWeightSynced = payload.includesWeight && result is SyncResult.Success,
        )
    }

    private suspend fun applyHealthConnectResult(
        dao: com.example.huaweimisync.data.MeasurementDao,
        id: String,
        payload: com.example.huaweimisync.sync.MeasurementSyncPayload,
        result: SyncResult,
    ) {
        val update = result.toStateUpdate()
        dao.applyHealthConnectSyncResult(
            id = id,
            expectedMeasurementType = payload.measurement.measurementType.name,
            status = update.status.name,
            error = update.error,
            markWeightSynced = payload.includesWeight && result is SyncResult.Success,
        )
    }

    companion object {
        private const val KEY_ID = "measurement_id"
        fun inputData(id: String): Data = Data.Builder().putString(KEY_ID, id).build()
    }
}
