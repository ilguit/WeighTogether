package com.palixander.weightogether.charts

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class ChartPeriodNavigationTest {
    @Test
    fun `viewport retains history outside the selected dates`() {
        val zone = java.time.ZoneOffset.UTC
        val start = LocalDate.of(2026, 8, 9)
        val end = LocalDate.of(2026, 8, 15)
        val older = start.minusMonths(2).atStartOfDay(zone).toInstant().toEpochMilli()
        val newer = end.plusMonths(1).atStartOfDay(zone).toInstant().toEpochMilli()

        val viewport = chartViewport(listOf(newer, older), start, end, zone)

        assertEquals(older.toDouble(), viewport.modelRange.minX, 0.0)
        assertEquals(newer.toDouble(), viewport.modelRange.maxX, 0.0)
        assertEquals(chartXRange(start, end, zone), viewport.initialVisibleRange)
        assertEquals(7 * 24 * 60 * 60 * 1000.0, viewport.initialVisibleWidth(), 0.0)
    }

    @Test
    fun `empty model still exposes the complete selected window`() {
        val date = LocalDate.of(2026, 8, 15)
        val viewport = chartViewport(emptyList(), date, date, java.time.ZoneOffset.UTC)

        assertEquals(viewport.initialVisibleRange, viewport.modelRange)
    }
}
