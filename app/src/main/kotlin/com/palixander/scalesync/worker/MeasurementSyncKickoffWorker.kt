package com.palixander.scalesync.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import com.palixander.scalesync.ScaleSyncApplication

/** Turns the initial delay into an actual sync without replacing queued or running sync work. */
class MeasurementSyncKickoffWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val measurementId = inputData.getString(KEY_ID) ?: return Result.failure()
        val scheduler = (applicationContext as ScaleSyncApplication).container.syncScheduler
        scheduler.enqueue(measurementId)
        return Result.success()
    }

    companion object {
        private const val KEY_ID = "measurement_id"

        fun inputData(measurementId: String): Data =
            Data.Builder().putString(KEY_ID, measurementId).build()
    }
}
