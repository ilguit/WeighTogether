package com.example.huaweimisync.data

import com.example.huaweimisync.core.RawScaleMeasurement

/**
 * Decides whether a later full body-composition packet may enrich an earlier weight-only packet.
 *
 * This is intentionally separate from ordinary measurement deduplication: impedance calculation
 * can finish later than the normal duplicate window without making unrelated packets equivalent.
 */
object MeasurementEnrichmentPolicy {
    const val WINDOW_SECONDS: Long = 30L

    /** Bounds for the preceding candidate. The strict 30-second window includes 0 through 29. */
    fun candidateEpochSecondBounds(incomingEpochSecond: Long): LongRange =
        incomingEpochSecond.saturatingMinus(WINDOW_SECONDS - 1)..incomingEpochSecond

    fun canEnrich(
        candidate: RawScaleMeasurement,
        incoming: RawScaleMeasurement,
    ): Boolean = candidate.isStableWeight &&
        !candidate.hasFullBodyComposition &&
        incoming.hasFullBodyComposition &&
        candidate.deviceAddress.equals(incoming.deviceAddress, ignoreCase = true) &&
        candidate.rawWeight == incoming.rawWeight &&
        isPrecedingInsideWindow(
            candidateEpochSecond = candidate.measuredAt.epochSecond,
            incomingEpochSecond = incoming.measuredAt.epochSecond,
        )

    private fun isPrecedingInsideWindow(
        candidateEpochSecond: Long,
        incomingEpochSecond: Long,
    ): Boolean = candidateEpochSecond <= incomingEpochSecond &&
        incomingEpochSecond.toULong() - candidateEpochSecond.toULong() < WINDOW_SECONDS.toULong()
}

private fun Long.saturatingMinus(value: Long): Long =
    if (this < Long.MIN_VALUE + value) Long.MIN_VALUE else this - value
