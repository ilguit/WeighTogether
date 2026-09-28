package com.palixander.scalesync.reminder

import com.palixander.scalesync.domain.WeighingReminderSchedule
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

internal fun nextReminderInstant(
    schedule: WeighingReminderSchedule,
    clock: Clock,
    zone: ZoneId = clock.zone,
): Instant {
    val now = clock.instant()
    val today = now.atZone(zone).toLocalDate()
    for (offset in 0..7) {
        val date = today.plusDays(offset.toLong())
        if (date.dayOfWeek !in schedule.weekdays) continue
        val local = LocalDateTime.of(date, schedule.time)
        val offsets = zone.rules.getValidOffsets(local)
        val candidate = when {
            offsets.isEmpty() -> zone.rules.getTransition(local).dateTimeAfter.atZone(zone).toInstant()
            else -> ZonedDateTime.ofLocal(local, zone, offsets.first()).toInstant()
        }
        if (candidate > now) return candidate
    }
    error("A non-empty weekly schedule must have a future occurrence")
}
