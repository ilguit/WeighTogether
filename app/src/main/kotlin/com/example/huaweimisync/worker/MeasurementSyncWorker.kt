package com.example.huaweimisync.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import com.example.huaweimisync.MiSyncApplication
import com.example.huaweimisync.data.SyncStatus
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
        val value = dao.get(id) ?: return Result.success()

        val huawei = when (value.huaweiStatus) {
            SyncStatus.SYNCED.name -> SyncResult.Success
            SyncStatus.DISABLED.name -> SyncResult.Disabled("Huawei adapter disabled")
            else -> container.huaweiHealth.write(value)
        }
        applyHuaweiResult(dao, id, huawei)

        val healthConnect = if (value.healthConnectStatus == SyncStatus.SYNCED.name) {
            SyncResult.Success
        } else {
            container.healthConnect.write(value)
        }
        applyHealthConnectResult(dao, id, healthConnect)

        return if (huawei is SyncResult.Retryable || healthConnect is SyncResult.Retryable) {
            Result.retry()
        } else {
            Result.success()
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
