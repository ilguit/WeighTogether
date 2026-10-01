package com.palixander.weightogether.charts

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot

internal const val HOURLY_SECTOR_STEP_DEGREES = 15f
internal const val HOURLY_SECTOR_GAP_DEGREES = 2f

internal data class HourlySectorAngles(
    val startDegrees: Float,
    val sweepDegrees: Float,
)

internal fun hourlySectorAngles(
    hour: Int,
    gapDegrees: Float = HOURLY_SECTOR_GAP_DEGREES,
): HourlySectorAngles {
    val safeGap = gapDegrees.coerceIn(0f, HOURLY_SECTOR_STEP_DEGREES)
    return HourlySectorAngles(
        startDegrees = hour.mod(24) * HOURLY_SECTOR_STEP_DEGREES - 97.5f + safeGap / 2f,
        sweepDegrees = HOURLY_SECTOR_STEP_DEGREES - safeGap,
    )
}

internal fun normalizedHourlyLengths(hourlyCounts: List<Int>): List<Float> {
    val counts = List(24) { hourlyCounts.getOrElse(it) { 0 }.coerceAtLeast(0) }
    val maximum = counts.maxOrNull() ?: 0
    return counts.map { if (maximum == 0) 0f else it.toFloat() / maximum }
}

internal fun hourForRadialPoint(
    x: Float,
    y: Float,
    centerX: Float,
    centerY: Float,
    deadZoneRadius: Float = 0f,
): Int? {
    if (hypot(x - centerX, y - centerY) <= deadZoneRadius.coerceAtLeast(0f)) return null
    val clockwiseFromTop = (atan2((y - centerY).toDouble(), (x - centerX).toDouble()) + PI / 2 + PI * 2) % (PI * 2)
    return ((clockwiseFromTop / (PI * 2) * 24 + 0.5).toInt() % 24).coerceIn(0, 23)
}
