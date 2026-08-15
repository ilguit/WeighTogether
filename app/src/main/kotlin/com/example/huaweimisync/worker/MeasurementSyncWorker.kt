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
            applyHuaweiResult = { measurementId, result ->
                applyHuaweiResult(dao, measurementId, result)
            },
            applyHealthConnectResult = { measurementId, result ->
                applyHealthConnectResult(dao, measurementId, result)
            },
        ).sync(id)

        return when (outcome) {
            MeasurementSyncOutcome.COMPLETE -> Result.success()
            MeasurementSyncOutcome.RETRY -> Result.retry()
        }
    }

    private suspend fun applyHuaweiResult(
        dao: com.example.huaweimisync.data.MeasurementDao,
        id: String,
        result: SyncResult,
    ) {
        val update = result.toStateUpdate()
        dao.updateHuaweiStatus(id, update.status.name, update.error)
    }

    private suspend fun applyHealthConnectResult(
        dao: com.example.huaweimisync.data.MeasurementDao,
        id: String,
        result: SyncResult,
    ) {
        val update = result.toStateUpdate()
        dao.updateHealthConnectStatus(id, update.status.name, update.error)
    }

    companion object {
        private const val KEY_ID = "measurement_id"
        fun inputData(id: String): Data = Data.Builder().putString(KEY_ID, id).build()
    }
}
