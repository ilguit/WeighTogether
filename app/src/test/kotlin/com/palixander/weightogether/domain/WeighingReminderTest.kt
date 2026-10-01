package com.palixander.weightogether.domain

import java.time.DayOfWeek
import org.junit.Assert.assertEquals
import org.junit.Test

class WeighingReminderTest {
    @Test
    fun weekdayMaskIsCanonicalRegardlessOfInputOrder() {
        val first = linkedSetOf(DayOfWeek.FRIDAY, DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY)
        val second = linkedSetOf(DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY, DayOfWeek.MONDAY)

        assertEquals(first.toWeekdayMask(), second.toWeekdayMask())
        assertEquals(first, first.toWeekdayMask().toWeekdays())
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptyWeekdaysAreRejected() {
        emptySet<DayOfWeek>().toWeekdayMask()
    }
}
