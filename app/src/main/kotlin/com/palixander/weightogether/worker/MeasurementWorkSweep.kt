package com.palixander.weightogether.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.palixander.weightogether.ScaleSyncApplication
import com.palixander.weightogether.data.MeasurementIngestionSweepResult
import com.palixander.weightogether.data.MeasurementRepository

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
        return runCatching {
            // WorkManager may start immediately while Application.onCreate is still finishing.
            val container = (applicationContext as ScaleSyncApplication).container
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
    private const val TAG = "MeasurementWorkSweep"
    private const val UNIQUE_WORK_NAME = "measurement-startup-sweep"

    fun enqueue(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<MeasurementWorkSweepWorker>().build(),
        )
    }

    /** Scheduling is a repair hint; durable state remains available for a later retry. */
    fun enqueueBestEffort(context: Context): Boolean = try {
        enqueue(context)
        true
    } catch (error: Exception) {
        Log.w(TAG, "Unable to schedule measurement work sweep; startup will retry", error)
        false
    }
}
