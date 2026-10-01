package com.palixander.weightogether.reminder

import com.palixander.weightogether.domain.AccountId
import com.palixander.weightogether.domain.WeighingReminderId
import com.palixander.weightogether.domain.WeighingReminderImportance
import com.palixander.weightogether.domain.WeighingReminderOwner
import com.palixander.weightogether.domain.WeighingReminderSchedule
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class ReminderTimeTest {
    private val berlin = ZoneId.of("Europe/Berlin")

    @Test
    fun `weekly occurrence preserves local wall clock`() {
        val schedule = schedule(LocalTime.of(9, 15), DayOfWeek.MONDAY)
        val clock = Clock.fixed(Instant.parse("2026-02-01T12:00:00Z"), berlin)

        assertEquals(Instant.parse("2026-02-02T08:15:00Z"), nextReminderInstant(schedule, clock))
    }

    @Test
    fun `dst gap advances to first valid instant`() {
        val schedule = schedule(LocalTime.of(2, 30), DayOfWeek.SUNDAY)
        val clock = Clock.fixed(Instant.parse("2026-03-28T12:00:00Z"), berlin)

        assertEquals(Instant.parse("2026-03-29T01:00:00Z"), nextReminderInstant(schedule, clock))
    }

    @Test
    fun `dst overlap chooses first occurrence`() {
        val schedule = schedule(LocalTime.of(2, 30), DayOfWeek.SUNDAY)
        val clock = Clock.fixed(Instant.parse("2026-10-24T12:00:00Z"), berlin)

        assertEquals(Instant.parse("2026-10-25T00:30:00Z"), nextReminderInstant(schedule, clock))
    }

    @Test
    fun `summary occurrence selects next weekday rather than earliest wall clock time`() {
        val sundayEvening = schedule(LocalTime.of(20, 0), DayOfWeek.SUNDAY, "sunday")
        val mondayMorning = schedule(LocalTime.of(8, 0), DayOfWeek.MONDAY, "monday")
        val clock = Clock.fixed(Instant.parse("2026-02-01T18:00:00Z"), berlin)

        val occurrence = nextEnabledReminderOccurrence(listOf(mondayMorning, sundayEvening), clock)

        assertEquals(sundayEvening, occurrence?.schedule)
        assertEquals(Instant.parse("2026-02-01T19:00:00Z"), occurrence?.instant)
    }

    @Test
    fun `summary occurrence rolls into next week after todays time passes`() {
        val sundayEvening = schedule(LocalTime.of(20, 0), DayOfWeek.SUNDAY, "sunday")
        val mondayMorning = schedule(LocalTime.of(8, 0), DayOfWeek.MONDAY, "monday")
        val clock = Clock.fixed(Instant.parse("2026-02-01T20:00:00Z"), berlin)

        val occurrence = nextEnabledReminderOccurrence(listOf(sundayEvening, mondayMorning), clock)

        assertEquals(mondayMorning, occurrence?.schedule)
        assertEquals(Instant.parse("2026-02-02T07:00:00Z"), occurrence?.instant)
    }

    @Test
    fun `summary occurrence compares resolved dst gap instants`() {
        val gap = schedule(LocalTime.of(2, 30), DayOfWeek.SUNDAY, "gap")
        val later = schedule(LocalTime.of(3, 15), DayOfWeek.SUNDAY, "later")
        val clock = Clock.fixed(Instant.parse("2026-03-28T12:00:00Z"), berlin)

        val occurrence = nextEnabledReminderOccurrence(listOf(later, gap), clock)

        assertEquals(gap, occurrence?.schedule)
        assertEquals(Instant.parse("2026-03-29T01:00:00Z"), occurrence?.instant)
    }

    private fun schedule(time: LocalTime, day: DayOfWeek, id: String = "id") = WeighingReminderSchedule(
        id = WeighingReminderId(id),
        owner = WeighingReminderOwner.Account(AccountId("account")),
        time = time,
        weekdays = setOf(day),
        importance = WeighingReminderImportance.REGULAR,
        enabled = true,
    )
}
