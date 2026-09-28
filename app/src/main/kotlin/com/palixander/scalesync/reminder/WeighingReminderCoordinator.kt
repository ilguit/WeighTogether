package com.palixander.scalesync.reminder

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import com.palixander.scalesync.MainActivity
import com.palixander.scalesync.NotificationChannelRegistry
import com.palixander.scalesync.R
import com.palixander.scalesync.data.ReminderCallbackKind
import com.palixander.scalesync.data.ReminderClaimResult
import com.palixander.scalesync.data.ReminderSnoozeStatus
import com.palixander.scalesync.data.RoomWeighingReminderRepository
import com.palixander.scalesync.domain.WeighingReminderId
import com.palixander.scalesync.domain.WeighingReminderImportance
import com.palixander.scalesync.domain.WeighingReminderOwner
import java.time.Clock
import kotlin.time.Duration.Companion.minutes

data class WeighingReminderNavigationTarget(val owner: WeighingReminderOwner) {
    fun putInto(intent: Intent): Intent = intent.apply {
        putExtra(EXTRA_KIND, if (owner is WeighingReminderOwner.Account) KIND_ACCOUNT else KIND_PET)
        putExtra(EXTRA_OWNER_ID, owner.value)
    }

    companion object {
        const val ACTION_OPEN_PROFILE = "com.palixander.scalesync.action.OPEN_REMINDER_PROFILE"
        const val EXTRA_KIND = "weighing_reminder_owner_kind"
        const val EXTRA_OWNER_ID = "weighing_reminder_owner_id"
        const val KIND_ACCOUNT = "account"
        const val KIND_PET = "pet"
    }
}

class WeighingReminderCoordinator(
    private val context: Context,
    private val repository: RoomWeighingReminderRepository,
    private val alarmGateway: WeighingReminderAlarmGateway,
    private val capabilityGateway: WeighingReminderCapabilityGateway,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    private val notifications = context.getSystemService(NotificationManager::class.java)

    suspend fun reconcile() {
        val snapshots = repository.snapshotEnabled()
        alarmGateway.cancelUnknown(snapshots.mapTo(mutableSetOf()) { it.schedule.id.value })
            .forEach { notifications.cancel(notificationId(it)) }
        val capability = capabilityGateway.read()
        snapshots.forEach { snapshot ->
            val id = snapshot.schedule.id
            if (!capability.canPublish(snapshot.schedule.importance)) {
                notifications.cancel(notificationId(id))
                alarmGateway.cancel(id, ReminderCallbackKind.REGULAR)
                alarmGateway.cancel(id, ReminderCallbackKind.SNOOZE)
                repository.invalidateRuntime(id)
                return@forEach
            }
            if (snapshot.activeOccurrenceToken == null) notifications.cancel(notificationId(id))
            scheduleNextRegular(snapshot.schedule.id)
            val snoozeDue = snapshot.snoozeDueEpochMillis
            val snoozeToken = snapshot.snoozeOccurrenceToken
            if (snapshot.snoozeStatus == ReminderSnoozeStatus.SCHEDULED &&
                snoozeDue != null && snoozeToken != null && snoozeDue > clock.millis()
            ) {
                alarmGateway.schedule(
                    id,
                    ReminderCallbackKind.SNOOZE,
                    snoozeToken,
                    snoozeDue,
                    snapshot.schedule.importance,
                )
            } else {
                alarmGateway.cancel(id, ReminderCallbackKind.SNOOZE)
                if (snapshot.snoozeStatus == ReminderSnoozeStatus.SCHEDULED &&
                    snoozeDue != null && snoozeDue <= clock.millis()
                ) {
                    repository.expireSnooze(id, clock.millis())
                }
            }
        }
    }

    /** Cleans up platform state for schedules already removed by an owner cascade. */
    fun cancelDeleted(scheduleIds: Iterable<WeighingReminderId>) {
        scheduleIds.forEach { id ->
            alarmGateway.cancel(id, ReminderCallbackKind.REGULAR)
            alarmGateway.cancel(id, ReminderCallbackKind.SNOOZE)
            notifications.cancel(notificationId(id))
        }
    }

    suspend fun onFire(id: WeighingReminderId, kind: ReminderCallbackKind, token: String) {
        if (repository.claimDue(id, kind, token) !is ReminderClaimResult.Publish) return
        val snapshot = repository.snapshot(id) ?: return
        scheduleNextRegular(id)
        if (!capabilityGateway.read().canPublish(snapshot.schedule.importance)) {
            repository.invalidateRuntime(id)
            alarmGateway.cancel(id, ReminderCallbackKind.REGULAR)
            alarmGateway.cancel(id, ReminderCallbackKind.SNOOZE)
            return
        }
        val name = repository.ownerDisplayName(snapshot.schedule.owner) ?: return
        publish(snapshot.schedule.importance, id, token, snapshot.schedule.owner, name)
    }

    suspend fun onSnooze(id: WeighingReminderId, token: String) {
        val snapshot = repository.snapshot(id) ?: return
        if (!capabilityGateway.read().canPublish(snapshot.schedule.importance)) return
        val due = clock.millis() + 10.minutes.inWholeMilliseconds
        val snoozeToken = repository.snooze(id, token, due) ?: return
        notifications.cancel(notificationId(id))
        alarmGateway.cancel(id, ReminderCallbackKind.SNOOZE)
        if (!alarmGateway.schedule(id, ReminderCallbackKind.SNOOZE, snoozeToken, due, snapshot.schedule.importance)) {
            repository.invalidateRuntime(id)
        }
    }

    suspend fun onContentTap(id: WeighingReminderId, token: String) {
        val snapshot = repository.snapshot(id) ?: run {
            notifications.cancel(notificationId(id))
            return launchFallback()
        }
        if (!repository.consumeAction(id, token)) return
        notifications.cancel(notificationId(id))
        alarmGateway.cancel(id, ReminderCallbackKind.SNOOZE)
        launch(WeighingReminderNavigationTarget(snapshot.schedule.owner))
    }

    private suspend fun scheduleNextRegular(id: WeighingReminderId) {
        val schedule = repository.get(id) ?: return
        val due = nextReminderInstant(schedule, clock).toEpochMilli()
        val token = repository.prepareRegularOccurrence(id, due) ?: return
        alarmGateway.cancel(id, ReminderCallbackKind.REGULAR)
        alarmGateway.schedule(id, ReminderCallbackKind.REGULAR, token, due, schedule.importance)
    }

    private fun publish(
        importance: WeighingReminderImportance,
        id: WeighingReminderId,
        token: String,
        owner: WeighingReminderOwner,
        name: String,
    ) {
        val channel = if (importance == WeighingReminderImportance.ALARM) {
            NotificationChannelRegistry.weighingAlarms.id
        } else {
            NotificationChannelRegistry.weighingReminders.id
        }
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(weighingReminderNotificationSmallIcon(importance))
            .setContentTitle(context.getString(R.string.weighing_reminder_notification_title, name))
            .setContentText(context.getString(R.string.weighing_reminder_notification_text))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(importance == WeighingReminderImportance.REGULAR)
            .setOngoing(importance == WeighingReminderImportance.ALARM)
            .setContentIntent(contentIntent(id, token, owner))
            .addAction(
                0,
                context.getString(R.string.weighing_reminder_snooze),
                actionIntent(ACTION_SNOOZE, id, token, owner),
            )
            .build()
        notifications.notify(notificationId(id), notification)
    }

    private fun actionIntent(
        action: String,
        id: WeighingReminderId,
        token: String,
        owner: WeighingReminderOwner,
    ): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, WeighingReminderActionReceiver::class.java).apply {
            this.action = action
            data = Uri.parse("scalesync://weighing-reminder-action/${Uri.encode(id.value)}/${action.substringAfterLast('.')}")
            putExtra(WeighingReminderAlarmGateway.EXTRA_SCHEDULE_ID, id.value)
            putExtra(WeighingReminderAlarmGateway.EXTRA_OCCURRENCE_TOKEN, token)
            WeighingReminderNavigationTarget(owner).putInto(this)
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun contentIntent(
        id: WeighingReminderId,
        token: String,
        owner: WeighingReminderOwner,
    ): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, WeighingReminderOpenActivity::class.java).apply {
            action = ACTION_OPEN
            data = Uri.parse("scalesync://weighing-reminder-action/${Uri.encode(id.value)}/open")
            putExtra(WeighingReminderAlarmGateway.EXTRA_SCHEDULE_ID, id.value)
            putExtra(WeighingReminderAlarmGateway.EXTRA_OCCURRENCE_TOKEN, token)
            WeighingReminderNavigationTarget(owner).putInto(this)
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun launch(target: WeighingReminderNavigationTarget) {
        context.startActivity(target.putInto(Intent(context, MainActivity::class.java)).apply {
            action = WeighingReminderNavigationTarget.ACTION_OPEN_PROFILE
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        })
    }

    private fun launchFallback() {
        context.startActivity(Intent(context, MainActivity::class.java).apply {
            action = WeighingReminderNavigationTarget.ACTION_OPEN_PROFILE
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OWNER_UNAVAILABLE, true)
        })
    }

    companion object {
        const val ACTION_SNOOZE = "com.palixander.scalesync.action.SNOOZE_WEIGHING_REMINDER"
        const val ACTION_OPEN = "com.palixander.scalesync.action.OPEN_WEIGHING_REMINDER"
        const val EXTRA_OWNER_UNAVAILABLE = "weighing_reminder_owner_unavailable"
        fun notificationId(id: WeighingReminderId): Int = 0x57000000 xor id.value.hashCode()
    }
}

internal fun weighingReminderNotificationSmallIcon(
    @Suppress("UNUSED_PARAMETER") importance: WeighingReminderImportance,
): Int = R.drawable.ic_notification
