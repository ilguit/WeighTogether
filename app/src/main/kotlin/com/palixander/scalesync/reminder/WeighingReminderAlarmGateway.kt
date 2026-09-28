package com.palixander.scalesync.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.palixander.scalesync.MainActivity
import com.palixander.scalesync.data.ReminderCallbackKind
import com.palixander.scalesync.domain.WeighingReminderId
import com.palixander.scalesync.domain.WeighingReminderImportance

enum class ReminderScheduleResult { EXACT, INEXACT, FAILED }

class WeighingReminderAlarmGateway(private val context: Context) {
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val registry = context.getSharedPreferences(REGISTRY, Context.MODE_PRIVATE)

    fun schedule(
        id: WeighingReminderId,
        kind: ReminderCallbackKind,
        token: String,
        dueEpochMillis: Long,
        importance: WeighingReminderImportance,
    ): ReminderScheduleResult = runCatching {
        val operation = requireNotNull(pendingIntent(id, kind, token, PendingIntent.FLAG_UPDATE_CURRENT))
        val exactAllowed = Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()
        val result = if (importance == WeighingReminderImportance.ALARM) {
            val showIntent = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java).apply {
                    action = ACTION_SHOW_ALARM
                    data = Uri.parse("scalesync://weighing-reminder/${Uri.encode(id.value)}/show")
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            alarms.setAlarmClock(AlarmManager.AlarmClockInfo(dueEpochMillis, showIntent), operation)
            ReminderScheduleResult.EXACT
        } else if (exactAllowed) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, dueEpochMillis, operation)
            ReminderScheduleResult.EXACT
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, dueEpochMillis, operation)
            ReminderScheduleResult.INEXACT
        }
        remember(identity(id, kind))
        clearFailure(id)
        result
    }.getOrElse {
        rememberFailure(id)
        ReminderScheduleResult.FAILED
    }

    fun hasSchedulingFailure(id: WeighingReminderId): Boolean = id.value in failedIds()

    fun hasAnySchedulingFailure(): Boolean = failedIds().isNotEmpty()

    fun cancel(id: WeighingReminderId, kind: ReminderCallbackKind) {
        pendingIntent(id, kind, "", PendingIntent.FLAG_NO_CREATE)?.let(alarms::cancel)
        forget(identity(id, kind))
        if (kind == ReminderCallbackKind.REGULAR) clearFailure(id)
    }

    fun cancelUnknown(validScheduleIds: Set<String>): Set<WeighingReminderId> {
        val removed = linkedSetOf<WeighingReminderId>()
        knownIdentities().filter { it.substringBefore('|') !in validScheduleIds }.forEach { encoded ->
            val (id, kind) = encoded.split('|', limit = 2)
            val scheduleId = WeighingReminderId(id)
            removed += scheduleId
            cancel(scheduleId, ReminderCallbackKind.valueOf(kind))
        }
        return removed
    }

    private fun pendingIntent(
        id: WeighingReminderId,
        kind: ReminderCallbackKind,
        token: String,
        flags: Int,
    ): PendingIntent? = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, WeighingReminderFireReceiver::class.java).apply {
            action = ACTION_FIRE
            data = Uri.parse("scalesync://weighing-reminder/${Uri.encode(id.value)}/${kind.name.lowercase()}")
            putExtra(EXTRA_SCHEDULE_ID, id.value)
            putExtra(EXTRA_CALLBACK_KIND, kind.name)
            putExtra(EXTRA_OCCURRENCE_TOKEN, token)
        },
        flags or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun remember(value: String) {
        registry.edit().putStringSet(KEY_IDENTITIES, knownIdentities() + value).apply()
    }

    private fun forget(value: String) {
        registry.edit().putStringSet(KEY_IDENTITIES, knownIdentities() - value).apply()
    }

    private fun knownIdentities(): Set<String> = registry.getStringSet(KEY_IDENTITIES, emptySet()).orEmpty().toSet()
    private fun identity(id: WeighingReminderId, kind: ReminderCallbackKind) = "${id.value}|${kind.name}"
    private fun failedIds(): Set<String> = registry.getStringSet(KEY_FAILURES, emptySet()).orEmpty().toSet()
    private fun rememberFailure(id: WeighingReminderId) {
        registry.edit().putStringSet(KEY_FAILURES, failedIds() + id.value).commit()
    }
    private fun clearFailure(id: WeighingReminderId) {
        registry.edit().putStringSet(KEY_FAILURES, failedIds() - id.value).commit()
    }

    companion object {
        const val ACTION_FIRE = "com.palixander.scalesync.action.FIRE_WEIGHING_REMINDER"
        const val ACTION_SHOW_ALARM = "com.palixander.scalesync.action.SHOW_WEIGHING_ALARM"
        const val EXTRA_SCHEDULE_ID = "weighing_reminder_schedule_id"
        const val EXTRA_CALLBACK_KIND = "weighing_reminder_callback_kind"
        const val EXTRA_OCCURRENCE_TOKEN = "weighing_reminder_occurrence_token"
        private const val REGISTRY = "weighing_reminder_alarm_registry"
        private const val KEY_IDENTITIES = "identities"
        private const val KEY_FAILURES = "failures"
    }
}
