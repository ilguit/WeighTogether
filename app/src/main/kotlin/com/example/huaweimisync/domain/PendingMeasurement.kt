package com.example.huaweimisync.domain

import com.example.huaweimisync.core.RawScaleMeasurement
import java.time.Instant
import kotlin.math.roundToInt

@JvmInline
value class PendingMeasurementId(val value: String) {
    init {
        require(value.isNotBlank()) { "Pending measurement id must not be blank" }
    }

    override fun toString(): String = value
}

/** Durable, lossless representation of a raw scale reading awaiting an account decision. */
data class PendingMeasurement(
    val id: PendingMeasurementId,
    val deviceAddress: String,
    val measuredAt: Instant,
    val weightKg: Double,
    val impedanceOhm: Int,
    val isStable: Boolean,
    val hasImpedance: Boolean,
    val rawPayload: ByteArray,
    val deduplicationHash: String,
    val enqueuedAt: Instant,
    val rawWeight: Int = (weightKg / RawScaleMeasurement.WEIGHT_RESOLUTION_KG).roundToInt(),
    val finalizeAfter: Instant = enqueuedAt.plusSeconds(10),
) {
    init {
        require(deviceAddress.isNotBlank()) { "Device address must not be blank" }
        require(weightKg.isFinite()) { "Weight must be finite" }
        require(deduplicationHash.isNotBlank()) { "Deduplication hash must not be blank" }
    }

    override fun equals(other: Any?): Boolean =
        other is PendingMeasurement &&
            id == other.id &&
            deviceAddress == other.deviceAddress &&
            measuredAt == other.measuredAt &&
            weightKg == other.weightKg &&
            impedanceOhm == other.impedanceOhm &&
            isStable == other.isStable &&
            hasImpedance == other.hasImpedance &&
            rawPayload.contentEquals(other.rawPayload) &&
            rawWeight == other.rawWeight &&
            deduplicationHash == other.deduplicationHash &&
            enqueuedAt == other.enqueuedAt &&
            finalizeAfter == other.finalizeAfter

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + deviceAddress.hashCode()
        result = 31 * result + measuredAt.hashCode()
        result = 31 * result + weightKg.hashCode()
        result = 31 * result + impedanceOhm
        result = 31 * result + isStable.hashCode()
        result = 31 * result + hasImpedance.hashCode()
        result = 31 * result + rawPayload.contentHashCode()
        result = 31 * result + rawWeight
        result = 31 * result + deduplicationHash.hashCode()
        result = 31 * result + enqueuedAt.hashCode()
        result = 31 * result + finalizeAfter.hashCode()
        return result
    }
}

fun RawScaleMeasurement.toPendingMeasurement(
    id: PendingMeasurementId,
    deduplicationHash: String,
    enqueuedAt: Instant,
): PendingMeasurement = PendingMeasurement(
    id = id,
    deviceAddress = deviceAddress,
    measuredAt = measuredAt,
    weightKg = weightKg,
    impedanceOhm = impedanceOhm,
    isStable = isStable,
    hasImpedance = hasImpedance,
    rawPayload = rawPayload.copyOf(),
    deduplicationHash = deduplicationHash,
    enqueuedAt = enqueuedAt,
    rawWeight = rawWeight,
    finalizeAfter = enqueuedAt.plusSeconds(10),
)

fun PendingMeasurement.toRawScaleMeasurement(): RawScaleMeasurement = RawScaleMeasurement(
    deviceAddress = deviceAddress,
    measuredAt = measuredAt,
    weightKg = weightKg,
    impedanceOhm = impedanceOhm,
    isStable = isStable,
    hasImpedance = hasImpedance,
    rawPayload = rawPayload.copyOf(),
    rawWeight = rawWeight,
)
