package com.example.huaweimisync.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.huaweimisync.MiSyncApplication
import com.example.huaweimisync.core.MiScalePacketParser
import com.example.huaweimisync.core.RawScaleMeasurement
import com.example.huaweimisync.data.MeasurementIngestionResult
import java.util.Locale
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

/** BLE callbacks can outlive a scan briefly; retain pet packet identities across gate release. */
internal const val PET_PACKET_QUARANTINE_TTL_NANOS = 120_000_000_000L
internal const val PET_PACKET_QUARANTINE_MAX_IDENTITIES = 32

/** Application-scoped switch preventing pet readings from entering the human pipeline. */
class PetMeasurementIngestionGate(
    private val monotonicNowNanos: () -> Long = System::nanoTime,
) {
    private val lock = Any()
    private val activationMutex = Mutex()
    private var petSessionActive = false
    private var activationOwner: Any? = null
    private var processingCount = 0
    private var processingDrained: CompletableDeferred<Unit>? = null
    private val activePetPackets = linkedSetOf<PacketIdentity>()
    private val quarantinedPetPackets = linkedMapOf<PacketIdentity, Long>()

    suspend fun activate(): Lease {
        val owner = Any()
        activationMutex.lock(owner)
        val waitForProcessing = synchronized(lock) {
            petSessionActive = true
            activationOwner = owner
            activePetPackets.clear()
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

    /** Records a packet observed by the selected-scale pet scanner for this active session. */
    fun registerPetPacket(deviceAddress: String, payload: ByteArray) = synchronized(lock) {
        if (petSessionActive) activePetPackets += PacketIdentity.of(deviceAddress, payload)
    }

    suspend fun <T : Any> processPacketWhenInactive(
        packet: ScalePacket,
        block: suspend () -> T,
    ): T? {
        val entered = synchronized(lock) {
            val now = monotonicNowNanos()
            pruneQuarantine(now)
            if (petSessionActive || quarantinedPetPackets.containsKey(PacketIdentity.of(packet))) {
                false
            } else {
                processingCount += 1
                true
            }
        }
        if (!entered) return null
        return try {
            block()
        } finally {
            leaveProcessing()
        }
    }

    private fun deactivate(owner: Any) {
        val shouldUnlock = synchronized(lock) {
            if (activationOwner !== owner) {
                false
            } else {
                val expiresAt = monotonicNowNanos() + PET_PACKET_QUARANTINE_TTL_NANOS
                activePetPackets.forEach { identity ->
                    quarantinedPetPackets.remove(identity)
                    quarantinedPetPackets[identity] = expiresAt
                }
                activePetPackets.clear()
                while (quarantinedPetPackets.size > PET_PACKET_QUARANTINE_MAX_IDENTITIES) {
                    quarantinedPetPackets.remove(quarantinedPetPackets.keys.first())
                }
                petSessionActive = false
                activationOwner = null
                processingDrained = null
                true
            }
        }
        if (shouldUnlock) activationMutex.unlock(owner)
    }

    private fun leaveProcessing() {
        val drained = synchronized(lock) {
            processingCount -= 1
            if (processingCount == 0) processingDrained.also { processingDrained = null }
            else null
        }
        drained?.complete(Unit)
    }

    private fun pruneQuarantine(now: Long) {
        val iterator = quarantinedPetPackets.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value <= now) iterator.remove()
        }
    }

    private data class PacketIdentity(val address: String, val payload: String) {
        companion object {
            fun of(packet: ScalePacket): PacketIdentity = of(packet.deviceAddress, packet.payload)

            fun of(deviceAddress: String, payload: ByteArray): PacketIdentity = PacketIdentity(
                address = deviceAddress.trim().uppercase(Locale.ROOT),
                payload = payload.joinToString("") { "%02x".format(it.toInt() and 0xff) },
            )
        }
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
        return petMeasurementGate.processPacketWhenInactive(packet) {
            val parsed = parse(packet.payload, packet.deviceAddress)
                ?: return@processPacketWhenInactive MeasurementIngestionResult.IgnoredNotFinal
            if (!parsed.isStableWeight) {
                return@processPacketWhenInactive MeasurementIngestionResult.IgnoredNotFinal
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
            MeasurementIngestionResult.ExactReplay,
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
