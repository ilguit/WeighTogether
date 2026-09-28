package com.palixander.scalesync.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.palixander.scalesync.domain.AccountId
import com.palixander.scalesync.domain.PetId
import com.palixander.scalesync.domain.WeighingReminderId
import com.palixander.scalesync.domain.WeighingReminderImportance
import com.palixander.scalesync.domain.WeighingReminderOwner
import com.palixander.scalesync.domain.WeighingReminderSchedule
import com.palixander.scalesync.domain.toWeekdays
import java.time.LocalTime

enum class WeighingReminderOwnerType { ACCOUNT, PET }

@Entity(
    tableName = "weighing_reminder_schedules",
    indices = [
        Index(value = ["ownerType", "ownerId"]),
        Index(
            value = ["ownerType", "ownerId", "minuteOfDay", "weekdaysMask", "importance"],
            unique = true,
        ),
    ],
)
data class WeighingReminderScheduleEntity(
    @PrimaryKey val id: String,
    val ownerType: WeighingReminderOwnerType,
    val ownerId: String,
    val minuteOfDay: Int,
    val weekdaysMask: Int,
    val importance: WeighingReminderImportance,
    val enabled: Boolean,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val alarmSoundUri: String? = null,
) {
    fun toDomain(): WeighingReminderSchedule = WeighingReminderSchedule(
        id = WeighingReminderId(id),
        owner = when (ownerType) {
            WeighingReminderOwnerType.ACCOUNT -> WeighingReminderOwner.Account(AccountId(ownerId))
            WeighingReminderOwnerType.PET -> WeighingReminderOwner.Pet(PetId(ownerId))
        },
        time = LocalTime.of(minuteOfDay / 60, minuteOfDay % 60),
        weekdays = weekdaysMask.toWeekdays(),
        importance = importance,
        enabled = enabled,
        alarmSoundUri = alarmSoundUri,
    )
}

enum class ReminderOccurrenceStatus { NONE, SCHEDULED, CLAIMED }
enum class ReminderSnoozeStatus { NONE, SCHEDULED, CONSUMED }

@Entity(
    tableName = "weighing_reminder_runtime",
    foreignKeys = [
        ForeignKey(
            entity = WeighingReminderScheduleEntity::class,
            parentColumns = ["id"],
            childColumns = ["scheduleId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class WeighingReminderRuntimeEntity(
    @PrimaryKey val scheduleId: String,
    val generation: Long = 0,
    val regularOccurrenceToken: String? = null,
    val regularDueEpochMillis: Long? = null,
    val regularStatus: ReminderOccurrenceStatus = ReminderOccurrenceStatus.NONE,
    val activeOccurrenceToken: String? = null,
    val snoozeGeneration: Long? = null,
    val snoozeSourceOccurrenceToken: String? = null,
    val snoozeOccurrenceToken: String? = null,
    val snoozeDueEpochMillis: Long? = null,
    val snoozeStatus: ReminderSnoozeStatus = ReminderSnoozeStatus.NONE,
)
