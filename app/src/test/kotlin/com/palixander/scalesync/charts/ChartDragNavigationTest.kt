package com.palixander.scalesync.charts

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class ChartDragNavigationTest {
    private val start = LocalDate.of(2026, 8, 9)
    private val end = LocalDate.of(2026, 8, 15)

    @Test
    fun `drag follows content and scales to inclusive date window`() {
        assertEquals(-7L, chartDragDistanceToDays(300f, 300f, start, end))
        assertEquals(7L, chartDragDistanceToDays(-300f, 300f, start, end))
        assertEquals(-4L, chartDragDistanceToDays(150f, 300f, start, end))
    }

    @Test
    fun `short and invalid drags do not shift date window`() {
        assertEquals(0L, chartDragDistanceToDays(10f, 300f, start, end))
        assertEquals(0L, chartDragDistanceToDays(100f, 0f, start, end))
        assertEquals(0L, chartDragDistanceToDays(Float.NaN, 300f, start, end))
    }
}
