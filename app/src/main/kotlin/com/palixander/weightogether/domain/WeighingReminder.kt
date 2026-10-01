package com.palixander.weightogether.domain

import java.time.DayOfWeek
import java.time.LocalTime

@JvmInline
value class WeighingReminderId(val value: String) {
    init {
        require(value.isNotBlank())
    }
}

sealed interface WeighingReminderOwner {
    val value: String

    data class Account(val id: AccountId) : WeighingReminderOwner {
        override val value: String = id.value
    }

    data class Pet(val id: PetId) : WeighingReminderOwner {
        override val value: String = id.value
    }
}

enum class WeighingReminderImportance {
    REGULAR,
    ALARM,
}

data class WeighingReminderSchedule(
    val id: WeighingReminderId,
    val owner: WeighingReminderOwner,
    val time: LocalTime,
    val weekdays: Set<DayOfWeek>,
    val importance: WeighingReminderImportance,
    val enabled: Boolean,
    val alarmSoundUri: String? = null,
) {
    init {
        require(weekdays.isNotEmpty())
    }
}

internal fun Set<DayOfWeek>.toWeekdayMask(): Int {
    require(isNotEmpty())
    return fold(0) { mask, day -> mask or (1 shl (day.value - 1)) }
}

internal fun Int.toWeekdays(): Set<DayOfWeek> {
    require(this in 1..0x7f)
    return DayOfWeek.entries.filterTo(linkedSetOf()) { day ->
        this and (1 shl (day.value - 1)) != 0
    }
}
