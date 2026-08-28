package com.palixander.scalesync.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.palixander.scalesync.ScaleSyncApplication
import com.palixander.scalesync.core.MiScalePacketParser
import com.palixander.scalesync.core.RawScaleMeasurement
import com.palixander.scalesync.data.MeasurementIngestionResult
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
private const val XIAOMI_SCALE_PACKET_PAYLOAD_SIZE = 13

/** Application-scoped switch preventing pet readings from entering the human pipeline. */
class PetMeasurementIngestionGate(
    private val monotonicNowNanos: () -> Long = System::nanoTime,
) {
    private val lock = Any()
    private val activationMutex = Mutex()
    private var petSessionActive = false
    private var activationOwner: Any? = null
    private var closingOwner: Any? = null
    private var closingExpiresAtNanos = 0L
    private var processingCount = 0
    private var processingDrained: CompletableDeferred<Unit>? = null
    private val activePetPackets = linkedSetOf<PacketIdentity>()
    private val protectedPetPackets = linkedMapOf<PacketIdentity, StableReadingIdentity?>()
    private val quarantinedPetPackets = linkedMapOf<PacketIdentity, QuarantineEntry>()

    suspend fun activate(): Lease {
        val owner = Any()
        activationMutex.lock(owner)
        val waitForProcessing = synchronized(lock) {
            closingOwner = null
            closingExpiresAtNanos = 0L
            petSessionActive = true
            activationOwner = owner
            activePetPackets.clear()
            protectedPetPackets.clear()
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

    suspend fun <T : Any> processPacketWhenInactive(
        packet: ScalePacket,
        block: suspend () -> T,
    ): T? {
        val entered = synchronized(lock) {
            val now = monotonicNowNanos()
            pruneQuarantine(now)
            val identity = PacketIdentity.of(packet)
            when {
                petSessionActive -> {
                    activePetPackets += identity
                    false
                }
                quarantinedPetPackets.containsKey(identity) -> false
                else -> {
                    processingCount += 1
                    true
                }
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
                    putQuarantined(identity, expiresAt, protected = false)
                }
                protectedPetPackets.forEach { (identity, stableReading) ->
                    putQuarantined(
                        identity,
                        expiresAt,
                        protected = true,
                        stableReading = stableReading,
                    )
                }
                activePetPackets.clear()
                protectedPetPackets.clear()
                trimQuarantine()
                petSessionActive = false
                activationOwner = null
                closingOwner = owner
                closingExpiresAtNanos = expiresAt
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
            if (iterator.next().value.expiresAtNanos <= now) iterator.remove()
        }
        if (closingExpiresAtNanos <= now) {
            closingOwner = null
            closingExpiresAtNanos = 0L
        }
    }

    private fun registerPetPacket(owner: Any, deviceAddress: String, payload: ByteArray) =
        synchronized(lock) {
            val identity = PacketIdentity.of(deviceAddress, payload)
            when {
                petSessionActive && activationOwner === owner -> {
                    activePetPackets += identity
                    while (activePetPackets.size > PET_PACKET_QUARANTINE_MAX_IDENTITIES) {
                        activePetPackets.remove(activePetPackets.first())
                    }
                }
                !petSessionActive && closingOwner === owner -> {
                    val now = monotonicNowNanos()
                    pruneQuarantine(now)
                    if (closingOwner === owner) {
                        putQuarantined(identity, closingExpiresAtNanos, protected = false)
                        trimQuarantine()
                    }
                }
            }
        }

    private fun protectPetPacket(owner: Any, deviceAddress: String, rawIdentity: String) =
        synchronized(lock) {
            if (petSessionActive && activationOwner === owner) {
                val identity = PacketIdentity.ofRawIdentity(deviceAddress, rawIdentity)
                activePetPackets.remove(identity)
                protectedPetPackets[identity] = protectedPetPackets[identity]
            }
        }

    private fun protectPetReading(
        owner: Any,
        deviceAddress: String,
        rawIdentity: String,
        measuredAt: java.time.Instant,
        rawWeight: Int,
    ) = synchronized(lock) {
        if (petSessionActive && activationOwner === owner) {
            val identity = PacketIdentity.ofRawIdentity(deviceAddress, rawIdentity)
            activePetPackets.remove(identity)
            protectedPetPackets[identity] = StableReadingIdentity.of(
                deviceAddress,
                rawWeight,
            )
        }
    }

    internal fun isQuarantinedPetReading(raw: RawScaleMeasurement): Boolean = synchronized(lock) {
        pruneQuarantine(monotonicNowNanos())
        val stableReading = StableReadingIdentity.of(raw)
        quarantinedPetPackets.values.any {
            it.protected && it.stableReading == stableReading
        }
    }

    private fun putQuarantined(
        identity: PacketIdentity,
        expiresAtNanos: Long,
        protected: Boolean,
        stableReading: StableReadingIdentity? = null,
    ) {
        val previous = quarantinedPetPackets.remove(identity)
        quarantinedPetPackets[identity] = QuarantineEntry(
            expiresAtNanos = expiresAtNanos,
            protected = protected || previous?.protected == true,
            stableReading = stableReading ?: previous?.stableReading,
        )
    }

    private fun trimQuarantine() {
        while (quarantinedPetPackets.size > PET_PACKET_QUARANTINE_MAX_IDENTITIES) {
            val removable = quarantinedPetPackets.entries.firstOrNull { !it.value.protected }
                ?: quarantinedPetPackets.entries.first()
            quarantinedPetPackets.remove(removable.key)
        }
    }

    private data class QuarantineEntry(
        val expiresAtNanos: Long,
        val protected: Boolean,
        val stableReading: StableReadingIdentity? = null,
    )

    private data class StableReadingIdentity(
        val address: String,
        val rawWeight: Int,
    ) {
        companion object {
            fun of(raw: RawScaleMeasurement) = of(
                raw.deviceAddress,
                raw.rawWeight,
            )

            fun of(deviceAddress: String, rawWeight: Int) =
                StableReadingIdentity(
                    address = deviceAddress.trim().uppercase(Locale.ROOT),
                    rawWeight = rawWeight,
                )
        }
    }

    private data class PacketIdentity(val address: String, val payload: String) {
        companion object {
            fun of(packet: ScalePacket): PacketIdentity = of(packet.deviceAddress, packet.payload)

            fun of(deviceAddress: String, payload: ByteArray): PacketIdentity = PacketIdentity(
                address = deviceAddress.trim().uppercase(Locale.ROOT),
                payload = payload
                    .takeLast(XIAOMI_SCALE_PACKET_PAYLOAD_SIZE)
                    .joinToString("") { "%02x".format(it.toInt() and 0xff) },
            )

            fun ofRawIdentity(deviceAddress: String, rawIdentity: String): PacketIdentity =
                PacketIdentity(
                    address = deviceAddress.trim().uppercase(Locale.ROOT),
                    payload = rawIdentity.lowercase(Locale.ROOT)
                        .takeLast(XIAOMI_SCALE_PACKET_PAYLOAD_SIZE * 2),
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

        fun registerPetPacket(deviceAddress: String, payload: ByteArray) {
            gate.registerPetPacket(owner, deviceAddress, payload)
        }

        fun protectPetPacket(deviceAddress: String, rawIdentity: String) {
            gate.protectPetPacket(owner, deviceAddress, rawIdentity)
        }

        fun protectPetReading(
            deviceAddress: String,
            rawIdentity: String,
            measuredAt: java.time.Instant,
            rawWeight: Int,
        ) {
            gate.protectPetReading(owner, deviceAddress, rawIdentity, measuredAt, rawWeight)
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
            if (petMeasurementGate.isQuarantinedPetReading(parsed)) {
                return@processPacketWhenInactive MeasurementIngestionResult.IgnoredNotFinal
            }
            ingestion.process(parsed)
        } ?: MeasurementIngestionResult.IgnoredNotFinal
    }
}

enum class DirectPacketProcessingResult {
    PROCESSED_DIRECTLY,
    FALLBACK_ENQUEUED,
    REJECTED_STALE_SCALE,
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
        val container = (applicationContext as ScaleSyncApplication).container
        val packet = ScalePacket(payload, mac)
        val outcome = try {
            container.scalePacketProcessingGate.processIfSelected(
                packet = packet,
                selectedAddress = { container.profileStore.settings.value.scaleAddress },
            ) {
                container.packetProcessor.process(packet)
            } ?: return Result.success()
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
