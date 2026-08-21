package com.example.huaweimisync.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.huaweimisync.MiSyncApplication
import com.example.huaweimisync.data.MeasurementIngestionResult
import com.example.huaweimisync.core.RawScaleMeasurement

class MeasurementIngestionWorkOrchestrator(
    private val ingest: suspend (RawScaleMeasurement) -> MeasurementIngestionResult,
    private val finalizationScheduler: PendingFinalizationScheduler,
) {
    suspend fun process(raw: RawScaleMeasurement): MeasurementIngestionResult {
        val outcome = ingest(raw)
        when (outcome) {
            is MeasurementIngestionResult.CreatedAggregate ->
                finalizationScheduler.enqueueIfAbsent(outcome.pending)
            is MeasurementIngestionResult.UpdatedAggregate -> if (
                outcome.shouldScheduleFinalization
            ) {
                finalizationScheduler.enqueueIfAbsent(outcome.pending)
            }
            else -> Unit
        }
        return outcome
    }
}

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
        val outcome = MeasurementIngestionWorkOrchestrator(
            ingest = container.repository::ingest,
            finalizationScheduler = container.finalizationScheduler,
        ).process(parsed)
        return when (outcome) {
            is MeasurementIngestionResult.CreatedAggregate,
            is MeasurementIngestionResult.UpdatedAggregate,
            MeasurementIngestionResult.SuppressedFinal,
            MeasurementIngestionResult.SuppressedTombstone,
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
