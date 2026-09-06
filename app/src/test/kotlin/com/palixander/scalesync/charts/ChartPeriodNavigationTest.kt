package com.palixander.scalesync.charts

import java.time.LocalDate
import org.junit.Assert.assertEquals
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
}
