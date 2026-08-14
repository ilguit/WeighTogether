package com.example.huaweimisync.worker

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

interface MeasurementSyncScheduler {
    fun enqueue(measurementId: String)

    fun cancel(measurementId: String)
}

class SyncWorkScheduler(private val context: Context) : MeasurementSyncScheduler {
    override fun enqueue(measurementId: String) {
        val work = OneTimeWorkRequestBuilder<MeasurementSyncWorker>()
            .setInputData(MeasurementSyncWorker.inputData(measurementId))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "sync-$measurementId",
            ExistingWorkPolicy.REPLACE,
            work,
        )
    }

    override fun cancel(measurementId: String) {
        WorkManager.getInstance(context).cancelUniqueWork("sync-$measurementId")
    }
}
