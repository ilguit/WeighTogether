package com.palixander.scalesync.charts

import kotlin.math.PI
import kotlin.math.atan2

internal fun normalizedHourlyLengths(hourlyCounts: List<Int>): List<Float> {
    val counts = List(24) { hourlyCounts.getOrElse(it) { 0 }.coerceAtLeast(0) }
    val maximum = counts.maxOrNull() ?: 0
    return counts.map { if (maximum == 0) 0f else it.toFloat() / maximum }
}

internal fun hourForRadialPoint(x: Float, y: Float, centerX: Float, centerY: Float): Int {
    val clockwiseFromTop = (atan2((y - centerY).toDouble(), (x - centerX).toDouble()) + PI / 2 + PI * 2) % (PI * 2)
    return ((clockwiseFromTop / (PI * 2) * 24).toInt()).coerceIn(0, 23)
}
