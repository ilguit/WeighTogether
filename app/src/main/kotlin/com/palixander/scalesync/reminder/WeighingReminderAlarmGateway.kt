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
        val result = if (importance == WeighingReminderImportance.ALARM || exactAllowed) {
            alarms.setAlarmClock(
                AlarmManager.AlarmClockInfo(dueEpochMillis, showIntent(id, kind)),
                operation,
            )
            ReminderScheduleResult.EXACT
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, dueEpochMillis, operation)
            ReminderScheduleResult.INEXACT
        }
        remember(identity(id, kind))
        clearFailure(id, kind)
        result
    }.getOrElse {
        rememberFailure(id, kind)
        ReminderScheduleResult.FAILED
    }

    fun hasSchedulingFailure(id: WeighingReminderId): Boolean =
        failedIdentities().any { it.substringBefore('|') == id.value }

    fun hasSchedulingFailure(ids: Iterable<WeighingReminderId>): Boolean {
        val validIds = ids.mapTo(mutableSetOf()) { it.value }
        return failedIdentities().any { it.substringBefore('|') in validIds }
    }

    fun hasAnySchedulingFailure(): Boolean = failedIdentities().isNotEmpty()

    fun cancel(id: WeighingReminderId, kind: ReminderCallbackKind) {
        pendingIntent(id, kind, "", PendingIntent.FLAG_NO_CREATE)?.let(alarms::cancel)
        forget(identity(id, kind))
        clearFailure(id, kind)
    }

    fun cancelUnknown(validScheduleIds: Set<String>): Set<WeighingReminderId> {
        val removed = linkedSetOf<WeighingReminderId>()
        knownIdentities().filter { it.substringBefore('|') !in validScheduleIds }.forEach { encoded ->
            val (id, kind) = encoded.split('|', limit = 2)
            val scheduleId = WeighingReminderId(id)
            removed += scheduleId
            cancel(scheduleId, ReminderCallbackKind.valueOf(kind))
        }
        failedIdentities().filter { it.substringBefore('|') !in validScheduleIds }.forEach { encoded ->
            val id = WeighingReminderId(encoded.substringBefore('|'))
            removed += id
            forgetFailure(encoded)
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

    private fun showIntent(id: WeighingReminderId, kind: ReminderCallbackKind): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                action = ACTION_SHOW_ALARM
                data = Uri.parse(
                    "scalesync://weighing-reminder/${Uri.encode(id.value)}/${kind.name.lowercase()}/show",
                )
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun remember(value: String) {
        registry.edit().putStringSet(KEY_IDENTITIES, knownIdentities() + value).apply()
    }

    private fun forget(value: String) {
        registry.edit().putStringSet(KEY_IDENTITIES, knownIdentities() - value).apply()
    }

    private fun knownIdentities(): Set<String> = registry.getStringSet(KEY_IDENTITIES, emptySet()).orEmpty().toSet()
    private fun identity(id: WeighingReminderId, kind: ReminderCallbackKind) = "${id.value}|${kind.name}"
    private fun failedIdentities(): Set<String> = registry.getStringSet(KEY_FAILURES, emptySet()).orEmpty().toSet()
    private fun rememberFailure(id: WeighingReminderId, kind: ReminderCallbackKind) {
        // Retained for migration from the first failure-registry format, which stored only an id.
        val migratedFailures = failedIdentities().mapTo(mutableSetOf()) {
            if ('|' in it) it else "$it|${ReminderCallbackKind.REGULAR.name}"
        }
        registry.edit().putStringSet(KEY_FAILURES, migratedFailures + identity(id, kind)).commit()
    }
    private fun clearFailure(id: WeighingReminderId, kind: ReminderCallbackKind) {
        registry.edit().putStringSet(
            KEY_FAILURES,
            failedIdentities() - id.value - identity(id, kind),
        ).commit()
    }
    private fun forgetFailure(value: String) {
        registry.edit().putStringSet(KEY_FAILURES, failedIdentities() - value).commit()
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
