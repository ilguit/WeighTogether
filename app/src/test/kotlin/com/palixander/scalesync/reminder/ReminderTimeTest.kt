package com.palixander.scalesync.reminder

import com.palixander.scalesync.domain.AccountId
import com.palixander.scalesync.domain.WeighingReminderId
import com.palixander.scalesync.domain.WeighingReminderImportance
import com.palixander.scalesync.domain.WeighingReminderOwner
import com.palixander.scalesync.domain.WeighingReminderSchedule
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

    private fun schedule(time: LocalTime, day: DayOfWeek) = WeighingReminderSchedule(
        id = WeighingReminderId("id"),
        owner = WeighingReminderOwner.Account(AccountId("account")),
        time = time,
        weekdays = setOf(day),
        importance = WeighingReminderImportance.REGULAR,
        enabled = true,
    )
}
