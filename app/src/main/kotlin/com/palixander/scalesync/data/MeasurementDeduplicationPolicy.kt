package com.palixander.scalesync.data

/** Single source of truth for deciding whether two scale packets represent one measurement. */
object MeasurementDeduplicationPolicy {
    const val WINDOW_SECONDS: Long = 10L

    fun epochSecondBounds(measuredAtEpochSecond: Long): LongRange =
        measuredAtEpochSecond.saturatingMinus(WINDOW_SECONDS - 1)..
            measuredAtEpochSecond.saturatingPlus(WINDOW_SECONDS - 1)

    fun isSameMeasurement(
        deviceAddress: String,
        rawWeight: Int,
        measuredAtEpochSecond: Long,
        candidateDeviceAddress: String,
        candidateRawWeight: Int,
        candidateMeasuredAtEpochSecond: Long,
    ): Boolean = deviceAddress.equals(candidateDeviceAddress, ignoreCase = true) &&
        rawWeight == candidateRawWeight &&
        secondDifference(measuredAtEpochSecond, candidateMeasuredAtEpochSecond) <
        WINDOW_SECONDS.toULong()

    fun secondDifference(first: Long, second: Long): ULong =
        if (first >= second) {
            first.toULong() - second.toULong()
        } else {
            second.toULong() - first.toULong()
        }
}

private fun Long.saturatingMinus(value: Long): Long =
    if (this < Long.MIN_VALUE + value) Long.MIN_VALUE else this - value

private fun Long.saturatingPlus(value: Long): Long =
    if (this > Long.MAX_VALUE - value) Long.MAX_VALUE else this + value
