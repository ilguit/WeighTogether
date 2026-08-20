package com.example.huaweimisync.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.huaweimisync.MiSyncApplication
import com.example.huaweimisync.data.MeasurementIngestionResult

class ProcessMeasurementWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val payload = inputData.getByteArray(KEY_PAYLOAD) ?: return Result.failure()
        val mac = inputData.getString(KEY_MAC) ?: "unknown"
        val container = (applicationContext as MiSyncApplication).container
        val parsed = container.packetParser.parse(payload, mac) ?: return Result.success()
        if (!parsed.isStableWeight) return Result.success()
        return when (container.repository.ingest(parsed)) {
            is MeasurementIngestionResult.Assigned,
            is MeasurementIngestionResult.AwaitingDecision,
            MeasurementIngestionResult.IgnoredNotFinal,
            MeasurementIngestionResult.Tombstoned,
            MeasurementIngestionResult.PendingMissing,
            MeasurementIngestionResult.AutomaticallyIgnoredUnknown,
            MeasurementIngestionResult.LegacyDuplicate,
            -> Result.success()
            MeasurementIngestionResult.LegacyProfileMissing -> Result.failure()
        }
    }

    companion object {
        const val KEY_PAYLOAD = "payload"
        const val KEY_MAC = "mac"
    }
}
