package com.palixander.scalesync.charts

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartPeriodNavigationTest {
    @Test
    fun `window length includes both boundary dates`() {
        assertEquals(
            7L,
            chartWindowLengthDays(
                startDate = LocalDate.of(2026, 8, 9),
                endDateInclusive = LocalDate.of(2026, 8, 15),
            ),
        )
    }

    @Test
    fun `single day window shifts by one day`() {
        val date = LocalDate.of(2026, 8, 15)

        assertEquals(1L, chartWindowLengthDays(date, date))
    }

    @Test
    fun `forward shift requires the complete inclusive window to fit by today`() {
        val start = LocalDate.of(2026, 8, 1)
        val end = LocalDate.of(2026, 8, 7)

        assertFalse(canShiftChartWindowForward(start, end, LocalDate.of(2026, 8, 13)))
        assertTrue(canShiftChartWindowForward(start, end, LocalDate.of(2026, 8, 14)))
    }

    @Test
    fun `single day forward shift becomes available on the next date`() {
        val date = LocalDate.of(2026, 8, 15)

        assertFalse(canShiftChartWindowForward(date, date, date))
        assertTrue(canShiftChartWindowForward(date, date, date.plusDays(1)))
    }
}
