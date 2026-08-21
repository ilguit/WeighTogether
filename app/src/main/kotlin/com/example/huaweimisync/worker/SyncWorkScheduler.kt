package com.example.huaweimisync.worker

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import kotlin.math.max

interface MeasurementSyncScheduler {
    fun enqueue(measurementId: String)

    fun enqueue(measurementId: String, notBeforeEpochMillis: Long) {
        enqueue(measurementId)
    }

    fun cancel(measurementId: String)

    fun cancelAll(measurementIds: Iterable<String>) {
        measurementIds.forEach(::cancel)
    }

    fun reschedule(measurementId: String, notBeforeEpochMillis: Long) {
        cancel(measurementId)
        enqueue(measurementId, notBeforeEpochMillis)
    }

    fun rescheduleAll(measurementIds: Iterable<String>, notBeforeEpochMillis: Long) {
        measurementIds.forEach { reschedule(it, notBeforeEpochMillis) }
    }
}

class SyncWorkScheduler(
    private val context: Context,
    private val pausedUntilProvider: () -> Long = { 0L },
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) : MeasurementSyncScheduler {
    override fun enqueue(measurementId: String) {
        enqueueUnique(measurementId, pausedUntilProvider(), ExistingWorkPolicy.KEEP)
    }

    override fun enqueue(measurementId: String, notBeforeEpochMillis: Long) {
        enqueueUnique(
            measurementId,
            effectiveNotBeforeEpochMillis(notBeforeEpochMillis, pausedUntilProvider()),
            ExistingWorkPolicy.KEEP,
        )
    }

    override fun reschedule(measurementId: String, notBeforeEpochMillis: Long) {
        enqueueUnique(measurementId, notBeforeEpochMillis, ExistingWorkPolicy.REPLACE)
    }

    private fun enqueueUnique(
        measurementId: String,
        notBeforeEpochMillis: Long,
        policy: ExistingWorkPolicy,
    ) {
        val work = OneTimeWorkRequestBuilder<MeasurementSyncWorker>()
            .setInputData(MeasurementSyncWorker.inputData(measurementId))
            .setInitialDelay(
                initialDelayMillis(notBeforeEpochMillis, nowEpochMillis()),
                TimeUnit.MILLISECONDS,
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "sync-$measurementId",
            policy,
            work,
        )
    }

    override fun cancel(measurementId: String) {
        WorkManager.getInstance(context).cancelUniqueWork("sync-$measurementId")
    }
}

internal fun initialDelayMillis(notBeforeEpochMillis: Long, nowEpochMillis: Long): Long =
    max(0L, notBeforeEpochMillis - nowEpochMillis)

internal fun effectiveNotBeforeEpochMillis(
    requestedEpochMillis: Long,
    pausedUntilEpochMillis: Long,
): Long = max(requestedEpochMillis, pausedUntilEpochMillis)
