package com.example.huaweimisync.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.huaweimisync.MiSyncApplication
import com.example.huaweimisync.core.MiScalePacketParser
import com.example.huaweimisync.data.MeasurementIngestionResult
import com.example.huaweimisync.core.RawScaleMeasurement
import kotlinx.coroutines.CancellationException

data class ScalePacket(
    val payload: ByteArray,
    val deviceAddress: String,
)

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

/** Shared parse/filter -> durable ingest -> finalization scheduling pipeline. */
class ScalePacketProcessor(
    private val parse: (ByteArray, String) -> RawScaleMeasurement?,
    private val ingestion: MeasurementIngestionWorkOrchestrator,
) {
    constructor(
        parser: MiScalePacketParser,
        ingest: suspend (RawScaleMeasurement) -> MeasurementIngestionResult,
        finalizationScheduler: PendingFinalizationScheduler,
    ) : this(
        parse = { payload, address -> parser.parse(payload, address) },
        ingestion = MeasurementIngestionWorkOrchestrator(ingest, finalizationScheduler),
    )

    suspend fun process(packet: ScalePacket): MeasurementIngestionResult {
        val parsed = parse(packet.payload, packet.deviceAddress)
            ?: return MeasurementIngestionResult.IgnoredNotFinal
        if (!parsed.isStableWeight) return MeasurementIngestionResult.IgnoredNotFinal
        return ingestion.process(parsed)
    }
}

enum class DirectPacketProcessingResult {
    PROCESSED_DIRECTLY,
    FALLBACK_ENQUEUED,
}

/** Keeps fallback policy independent of Android callbacks and straightforward to regression-test. */
class DirectPacketProcessingOrchestrator(
    private val process: suspend (ScalePacket) -> Unit,
    private val enqueueFallback: (ScalePacket) -> Unit,
) {
    suspend fun process(packet: ScalePacket): DirectPacketProcessingResult = try {
        this.process.invoke(packet)
        DirectPacketProcessingResult.PROCESSED_DIRECTLY
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        enqueueFallback(packet)
        DirectPacketProcessingResult.FALLBACK_ENQUEUED
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
        val outcome = try {
            container.packetProcessor.process(ScalePacket(payload, mac))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return Result.retry()
        }
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
