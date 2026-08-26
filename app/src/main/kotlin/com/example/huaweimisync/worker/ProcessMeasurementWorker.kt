package com.example.huaweimisync.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.huaweimisync.MiSyncApplication
import com.example.huaweimisync.core.MiScalePacketParser
import com.example.huaweimisync.core.RawScaleMeasurement
import com.example.huaweimisync.data.MeasurementIngestionResult
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex

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

/** Application-scoped switch preventing pet readings from entering the human pipeline. */
class PetMeasurementIngestionGate {
    private val lock = Any()
    private val activationMutex = Mutex()
    private var petSessionActive = false
    private var activationOwner: Any? = null
    private var processingCount = 0
    private var processingDrained: CompletableDeferred<Unit>? = null

    suspend fun activate(): Lease {
        val owner = Any()
        activationMutex.lock(owner)
        val waitForProcessing = synchronized(lock) {
            petSessionActive = true
            activationOwner = owner
            if (processingCount == 0) null else CompletableDeferred<Unit>().also {
                processingDrained = it
            }
        }
        try {
            waitForProcessing?.await()
        } catch (cancelled: CancellationException) {
            deactivate(owner)
            throw cancelled
        }
        return Lease(this, owner)
    }

    suspend fun <T : Any> processWhenInactive(block: suspend () -> T): T? {
        val entered = synchronized(lock) {
            if (petSessionActive) false else {
                processingCount += 1
                true
            }
        }
        if (!entered) return null
        return try {
            block()
        } finally {
            val drained = synchronized(lock) {
                processingCount -= 1
                if (processingCount == 0) processingDrained.also { processingDrained = null }
                else null
            }
            drained?.complete(Unit)
        }
    }

    private fun deactivate(owner: Any) {
        val shouldUnlock = synchronized(lock) {
            if (activationOwner !== owner) {
                false
            } else {
                petSessionActive = false
                activationOwner = null
                processingDrained = null
                true
            }
        }
        if (shouldUnlock) activationMutex.unlock(owner)
    }

    class Lease internal constructor(
        private val gate: PetMeasurementIngestionGate,
        private val owner: Any,
    ) {
        private val released = AtomicBoolean(false)

        fun release() {
            if (released.compareAndSet(false, true)) gate.deactivate(owner)
        }
    }
}

/** Shared parse/filter -> durable ingest -> finalization scheduling pipeline. */
class ScalePacketProcessor(
    private val parse: (ByteArray, String) -> RawScaleMeasurement?,
    private val ingestion: MeasurementIngestionWorkOrchestrator,
    private val petMeasurementGate: PetMeasurementIngestionGate = PetMeasurementIngestionGate(),
) {
    constructor(
        parser: MiScalePacketParser,
        ingest: suspend (RawScaleMeasurement) -> MeasurementIngestionResult,
        finalizationScheduler: PendingFinalizationScheduler,
        petMeasurementGate: PetMeasurementIngestionGate = PetMeasurementIngestionGate(),
    ) : this(
        parse = { payload, address -> parser.parse(payload, address) },
        ingestion = MeasurementIngestionWorkOrchestrator(ingest, finalizationScheduler),
        petMeasurementGate = petMeasurementGate,
    )

    suspend fun process(packet: ScalePacket): MeasurementIngestionResult {
        return petMeasurementGate.processWhenInactive {
            val parsed = parse(packet.payload, packet.deviceAddress)
                ?: return@processWhenInactive MeasurementIngestionResult.IgnoredNotFinal
            if (!parsed.isStableWeight) {
                return@processWhenInactive MeasurementIngestionResult.IgnoredNotFinal
            }
            ingestion.process(parsed)
        } ?: MeasurementIngestionResult.IgnoredNotFinal
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
            is MeasurementIngestionResult.UpgradedFinalized,
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
