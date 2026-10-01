package com.palixander.weightogether.charts

import org.junit.Assert.assertEquals
import org.junit.Test

class AnalyticalTimeInputTest {
    @Test fun partialAndInvalidInputCannotBecomeValidWindow() {
        listOf("", "5", "05:", "05:1", "12:60", "24:01", "-1:00", "25:00", "05:000").forEach {
            assertEquals(it, -1, parseMinute(it))
        }
        assertEquals(300, parseMinute("05:00"))
        assertEquals(330, parseMinute("5:30"))
        assertEquals(1440, parseMinute("24:00"))
        assertEquals("24:00", minuteText(1440))
    }
}
