package com.palixander.scalesync.charts

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.ceil
import kotlin.math.floor

internal const val MaxCalendarXAxisLabelCount = 8

internal fun calendarXAxisLabelCount(
    availableWidth: Float,
    maxLabelWidth: Float,
): Int {
    if (!availableWidth.isFinite() || !maxLabelWidth.isFinite() || availableWidth <= 0f || maxLabelWidth <= 0f) {
        return 1
    }
    return floor(availableWidth / maxLabelWidth).toInt().coerceIn(1, MaxCalendarXAxisLabelCount)
}

/** Prunes calendar candidates using their actual projected distance, including partial days and DST. */
internal fun spacedCalendarXAxisLabelValues(
    visibleMinX: Double,
    visibleMaxX: Double,
    zoneId: ZoneId,
    availableWidth: Float,
    maxLabelWidth: Float,
): List<Double> {
    val candidates = calendarXAxisLabelValues(
        visibleMinX,
        visibleMaxX,
        zoneId,
        calendarXAxisLabelCount(availableWidth, maxLabelWidth),
    )
    if (candidates.size <= 1) return candidates

    val visibleSpan = visibleMaxX - visibleMinX
    return buildList {
        candidates.forEach { candidate ->
            val previous = lastOrNull()
            if (previous == null || (candidate - previous) / visibleSpan * availableWidth >= maxLabelWidth) {
                add(candidate)
            }
        }
    }
}

/**
 * Selects a bounded set of epoch-millisecond values suitable for calendar labels on an x axis.
 *
 * The selector is deliberately independent of Vico. Except for a degenerate range, returned values
 * are local-day boundaries in [zoneId] that fall inside the visible range. A range containing no
 * such boundary gets its lower bound as a fallback, so even a narrow zoom window remains labelled.
 */
internal fun calendarXAxisLabelValues(
    visibleMinX: Double,
    visibleMaxX: Double,
    zoneId: ZoneId,
    maxLabelCount: Int,
): List<Double> {
    require(visibleMinX.isFinite() && visibleMaxX.isFinite()) { "The visible x range must be finite." }
    require(visibleMaxX >= visibleMinX) { "The maximum visible x must not precede the minimum." }
    require(maxLabelCount > 0) { "The label count limit must be positive." }

    if (visibleMinX == visibleMaxX) return listOf(visibleMinX)

    val minMillis = ceil(visibleMinX).toLong()
    val maxMillis = floor(visibleMaxX).toLong()
    if (minMillis > maxMillis) return listOf(visibleMinX)
    val firstDate = Instant.ofEpochMilli(minMillis).atZone(zoneId).toLocalDate()
        .let { date ->
            if (date.atStartOfDay(zoneId).toInstant().toEpochMilli() < minMillis) date.plusDays(1) else date
        }
    val lastDate = Instant.ofEpochMilli(maxMillis).atZone(zoneId).toLocalDate()
        .let { date ->
            if (date.atStartOfDay(zoneId).toInstant().toEpochMilli() > maxMillis) date.minusDays(1) else date
        }

    if (lastDate.isBefore(firstDate)) return listOf(visibleMinX)

    val boundaryCount = ChronoUnit.DAYS.between(firstDate, lastDate) + 1L
    val selectedCount = minOf(boundaryCount, maxLabelCount.toLong(), MaxCalendarXAxisLabelCount.toLong()).toInt()
    return (0 until selectedCount).map { index ->
        val dayOffset = if (selectedCount == 1) {
            0L
        } else {
            index.toLong() * (boundaryCount - 1L) / (selectedCount - 1L)
        }
        firstDate.plusDays(dayOffset).atStartOfDay(zoneId).toInstant().toEpochMilli().toDouble()
    }.distinct()
}
