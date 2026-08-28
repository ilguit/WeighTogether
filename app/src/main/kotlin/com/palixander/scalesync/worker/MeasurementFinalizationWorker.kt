package com.palixander.scalesync.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.palixander.scalesync.ScaleSyncApplication
import com.palixander.scalesync.data.AggregateFinalizationResult
import com.palixander.scalesync.domain.PendingMeasurement
import com.palixander.scalesync.domain.PendingMeasurementId
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

interface PendingFinalizationScheduler {
    /** Schedules the exact persisted deadline, including replacing the currently running watchdog. */
    fun enqueue(pending: PendingMeasurement)

    /** Ensures a watchdog exists without cancelling/replacing one already queued or running. */
    fun enqueueIfAbsent(pending: PendingMeasurement) = enqueue(pending)
}

internal val FINALIZATION_RESCHEDULE_POLICY: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE
internal val FINALIZATION_ENSURE_POLICY: ExistingWorkPolicy = ExistingWorkPolicy.KEEP

data class PendingFinalizationWorkPlan(
    val uniqueName: String,
    val pendingId: String,
    val delayMillis: Long,
)

internal fun pendingFinalizationWorkPlan(
    pending: PendingMeasurement,
    now: Instant,
): PendingFinalizationWorkPlan = PendingFinalizationWorkPlan(
    uniqueName = "finalize-${pending.id.value}",
    pendingId = pending.id.value,
    delayMillis = Duration.between(now, pending.finalizeAfter).toMillis().coerceAtLeast(0L),
)

class WorkManagerPendingFinalizationScheduler(
    private val context: Context,
    private val now: () -> Instant = Instant::now,
) : PendingFinalizationScheduler {
    override fun enqueue(pending: PendingMeasurement) {
        schedule(pending, FINALIZATION_RESCHEDULE_POLICY)
    }

    override fun enqueueIfAbsent(pending: PendingMeasurement) {
        schedule(pending, FINALIZATION_ENSURE_POLICY)
    }

    private fun schedule(pending: PendingMeasurement, policy: ExistingWorkPolicy) {
        val plan = pendingFinalizationWorkPlan(pending, now())
        val request = OneTimeWorkRequestBuilder<MeasurementFinalizationWorker>()
            .setInputData(MeasurementFinalizationWorker.inputData(plan.pendingId))
            .setInitialDelay(plan.delayMillis, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            plan.uniqueName,
            policy,
            request,
        )
    }
}

class MeasurementFinalizationWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val pendingId = inputData.getString(KEY_PENDING_ID)
            ?.let(::PendingMeasurementId)
            ?: return Result.failure()
        val container = (applicationContext as ScaleSyncApplication).container
        return runCatching { container.repository.finalizeDue(pendingId) }.fold(
            onSuccess = { result ->
                if (result is AggregateFinalizationResult.Reschedule) {
                    container.finalizationScheduler.enqueue(result.pending)
                }
                Result.success()
            },
            onFailure = { Result.retry() },
        )
    }

    companion object {
        private const val KEY_PENDING_ID = "pending_measurement_id"

        fun inputData(pendingId: String): Data = Data.Builder()
            .putString(KEY_PENDING_ID, pendingId)
            .build()
    }
}
