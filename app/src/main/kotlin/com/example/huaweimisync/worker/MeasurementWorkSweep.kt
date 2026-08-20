package com.example.huaweimisync.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.huaweimisync.MiSyncApplication
import com.example.huaweimisync.data.MeasurementIngestionSweepResult
import com.example.huaweimisync.data.MeasurementRepository

data class MeasurementWorkSweepResult(
    val routing: MeasurementIngestionSweepResult?,
    val syncEnqueuedCount: Int,
)

/** Shared startup/foreground entry point which closes both durable commit/enqueue gaps. */
class MeasurementWorkSweep(private val repository: MeasurementRepository) {
    suspend fun run(): MeasurementWorkSweepResult = repository.sweepPendingWork()
}

class MeasurementWorkSweepWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as MiSyncApplication).container
        return runCatching {
            // Restore any durable commit/enqueue gap. REPLACE also refreshes stale timers.
            container.measurementPersistence.pendingSnapshot()
                .forEach(container.finalizationScheduler::enqueue)
            MeasurementWorkSweep(container.repository).run()
        }.fold(
            onSuccess = { Result.success() },
            onFailure = { Result.retry() },
        )
    }
}

object MeasurementWorkSweepScheduler {
    private const val UNIQUE_WORK_NAME = "measurement-startup-sweep"

    fun enqueue(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<MeasurementWorkSweepWorker>().build(),
        )
    }
}
