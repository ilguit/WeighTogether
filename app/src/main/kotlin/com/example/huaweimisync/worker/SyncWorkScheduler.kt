package com.example.huaweimisync.worker

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import kotlin.math.max

interface MeasurementSyncScheduler {
    fun enqueue(measurementId: String)

    fun enqueueInitial(measurementId: String) {
        enqueue(measurementId)
    }

    fun enqueueImmediately(measurementId: String) {
        reschedule(measurementId, 0L)
    }

    fun enqueue(measurementId: String, notBeforeEpochMillis: Long) {
        enqueue(measurementId)
    }

    fun cancel(measurementId: String)

    fun cancelAll(measurementIds: Iterable<String>) {
        measurementIds.forEach(::cancel)
    }

    fun deferCurrent(measurementId: String, notBeforeEpochMillis: Long)

    fun reschedule(measurementId: String, notBeforeEpochMillis: Long) {
        cancel(measurementId)
        enqueue(measurementId, notBeforeEpochMillis)
    }

    fun rescheduleAll(measurementIds: Iterable<String>, notBeforeEpochMillis: Long) {
        measurementIds.forEach { reschedule(it, notBeforeEpochMillis) }
    }
}

class SyncWorkScheduler internal constructor(
    private val workManager: MeasurementSyncWorkManager,
    private val pausedUntilProvider: () -> Long = { 0L },
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) : MeasurementSyncScheduler {
    constructor(
        context: Context,
        pausedUntilProvider: () -> Long = { 0L },
        nowEpochMillis: () -> Long = System::currentTimeMillis,
    ) : this(
        workManager = AndroidMeasurementSyncWorkManager(WorkManager.getInstance(context)),
        pausedUntilProvider = pausedUntilProvider,
        nowEpochMillis = nowEpochMillis,
    )

    override fun enqueue(measurementId: String) {
        enqueueUnique(measurementId, pausedUntilProvider(), ExistingWorkPolicy.KEEP)
    }

    override fun enqueueInitial(measurementId: String) {
        val now = nowEpochMillis()
        val work = OneTimeWorkRequestBuilder<MeasurementSyncKickoffWorker>()
            .setInputData(MeasurementSyncKickoffWorker.inputData(measurementId))
            .setInitialDelay(
                initialDelayMillis(
                    effectiveNotBeforeEpochMillis(now + INITIAL_SYNC_DELAY_MILLIS, pausedUntilProvider()),
                    now,
                ),
                TimeUnit.MILLISECONDS,
            )
            .build()
        workManager.enqueueUniqueWork(
            kickoffWorkName(measurementId),
            ExistingWorkPolicy.KEEP,
            work,
        )
    }

    override fun enqueueImmediately(measurementId: String) {
        workManager.cancelUniqueWork(kickoffWorkName(measurementId))
        enqueueUnique(
            measurementId,
            pausedUntilProvider(),
            ExistingWorkPolicy.KEEP,
        )
    }

    override fun enqueue(measurementId: String, notBeforeEpochMillis: Long) {
        enqueueUnique(
            measurementId,
            effectiveNotBeforeEpochMillis(notBeforeEpochMillis, pausedUntilProvider()),
            ExistingWorkPolicy.KEEP,
        )
    }

    override fun reschedule(measurementId: String, notBeforeEpochMillis: Long) {
        enqueueUnique(
            measurementId,
            effectiveNotBeforeEpochMillis(notBeforeEpochMillis, pausedUntilProvider()),
            ExistingWorkPolicy.REPLACE,
        )
    }

    override fun deferCurrent(measurementId: String, notBeforeEpochMillis: Long) {
        enqueueUnique(
            measurementId,
            effectiveNotBeforeEpochMillis(notBeforeEpochMillis, pausedUntilProvider()),
            ExistingWorkPolicy.APPEND_OR_REPLACE,
        )
    }

    private fun enqueueUnique(
        measurementId: String,
        notBeforeEpochMillis: Long,
        policy: ExistingWorkPolicy,
        nowEpochMillis: Long = this.nowEpochMillis(),
    ) {
        val work = OneTimeWorkRequestBuilder<MeasurementSyncWorker>()
            .setInputData(MeasurementSyncWorker.inputData(measurementId))
            .setInitialDelay(
                initialDelayMillis(notBeforeEpochMillis, nowEpochMillis),
                TimeUnit.MILLISECONDS,
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniqueWork(
            "sync-$measurementId",
            policy,
            work,
        )
    }

    override fun cancel(measurementId: String) {
        workManager.cancelUniqueWork(kickoffWorkName(measurementId))
        workManager.cancelUniqueWork("sync-$measurementId")
    }

    private fun kickoffWorkName(measurementId: String): String = "sync-kickoff-$measurementId"
}

internal const val INITIAL_SYNC_DELAY_MILLIS = 60_000L

internal interface MeasurementSyncWorkManager {
    fun enqueueUniqueWork(
        uniqueWorkName: String,
        existingWorkPolicy: ExistingWorkPolicy,
        work: OneTimeWorkRequest,
    )

    fun cancelUniqueWork(uniqueWorkName: String)
}

private class AndroidMeasurementSyncWorkManager(
    private val workManager: WorkManager,
) : MeasurementSyncWorkManager {
    override fun enqueueUniqueWork(
        uniqueWorkName: String,
        existingWorkPolicy: ExistingWorkPolicy,
        work: OneTimeWorkRequest,
    ) {
        workManager.enqueueUniqueWork(uniqueWorkName, existingWorkPolicy, work)
    }

    override fun cancelUniqueWork(uniqueWorkName: String) {
        workManager.cancelUniqueWork(uniqueWorkName)
    }
}

internal fun initialDelayMillis(notBeforeEpochMillis: Long, nowEpochMillis: Long): Long =
    max(0L, notBeforeEpochMillis - nowEpochMillis)

internal fun effectiveNotBeforeEpochMillis(
    requestedEpochMillis: Long,
    pausedUntilEpochMillis: Long,
): Long = max(requestedEpochMillis, pausedUntilEpochMillis)
