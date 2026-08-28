package com.palixander.scalesync.domain

import com.palixander.scalesync.core.RawScaleMeasurement
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
    /** Best-effort weight-only match. Finalization always recalculates this decision. */
    val provisionalAccountId: AccountId? = null,
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
            finalizeAfter == other.finalizeAfter &&
            provisionalAccountId == other.provisionalAccountId

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
        result = 31 * result + (provisionalAccountId?.hashCode() ?: 0)
        return result
    }
}

enum class PreliminaryMeasurementStage {
    AGGREGATING,
    ENRICHED,
}

enum class PreliminaryDecisionReadiness {
    AGGREGATING,
    READY_FOR_DECISION,
}

/**
 * Stable lifecycle identity shared by a pending aggregate and any final row created from it.
 * Suppression is terminal and intentionally has no final measurement id.
 */
sealed interface MeasurementLifecycle {
    val presentationKey: PendingMeasurementId
    val finalMeasurementId: String?

    data class Preliminary(
        override val presentationKey: PendingMeasurementId,
        val stage: PreliminaryMeasurementStage,
        val decisionReadiness: PreliminaryDecisionReadiness,
    ) : MeasurementLifecycle {
        override val finalMeasurementId: String? = null
        val isReadyForDecision: Boolean
            get() = decisionReadiness == PreliminaryDecisionReadiness.READY_FOR_DECISION
    }

    data class Finalized(
        override val presentationKey: PendingMeasurementId,
        override val finalMeasurementId: String,
    ) : MeasurementLifecycle {
        init {
            require(finalMeasurementId.isNotBlank()) { "Final measurement id must not be blank" }
        }
    }

    data class Suppressed(
        override val presentationKey: PendingMeasurementId,
    ) : MeasurementLifecycle {
        override val finalMeasurementId: String? = null
    }
}

/** A pending row is preliminary regardless of whether impedance enrichment has arrived. */
val PendingMeasurement.isPreliminary: Boolean
    get() = true

fun PendingMeasurement.lifecycleAt(now: Instant): MeasurementLifecycle.Preliminary =
    MeasurementLifecycle.Preliminary(
        presentationKey = id,
        stage = if (hasImpedance) {
            PreliminaryMeasurementStage.ENRICHED
        } else {
            PreliminaryMeasurementStage.AGGREGATING
        },
        decisionReadiness = if (isAwaitingDecisionAt(now)) {
            PreliminaryDecisionReadiness.READY_FOR_DECISION
        } else {
            PreliminaryDecisionReadiness.AGGREGATING
        },
    )

fun PendingMeasurement.finalizedLifecycle(finalMeasurementId: String): MeasurementLifecycle.Finalized =
    MeasurementLifecycle.Finalized(
        presentationKey = id,
        finalMeasurementId = finalMeasurementId,
    )

fun PendingMeasurement.suppressedLifecycle(): MeasurementLifecycle.Suppressed =
    MeasurementLifecycle.Suppressed(presentationKey = id)

fun PendingMeasurement.isAwaitingDecisionAt(now: Instant): Boolean = !finalizeAfter.isAfter(now)

fun RawScaleMeasurement.toPendingMeasurement(
    id: PendingMeasurementId,
    deduplicationHash: String,
    enqueuedAt: Instant,
): PendingMeasurement = PendingMeasurement(
    id = id,
    deviceAddress = deviceAddress,
    measuredAt = Instant.ofEpochSecond(measuredAt.epochSecond),
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
    measuredAt = Instant.ofEpochSecond(measuredAt.epochSecond),
    weightKg = weightKg,
    impedanceOhm = impedanceOhm,
    isStable = isStable,
    hasImpedance = hasImpedance,
    rawPayload = rawPayload.copyOf(),
    rawWeight = rawWeight,
)
