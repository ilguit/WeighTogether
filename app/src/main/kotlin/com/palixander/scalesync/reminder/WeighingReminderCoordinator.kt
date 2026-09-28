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
import com.palixander.scalesync.data.ReminderOccurrenceStatus
import com.palixander.scalesync.data.ReminderSnoozeStatus
import com.palixander.scalesync.data.RoomWeighingReminderRepository
import com.palixander.scalesync.data.SaveWeighingReminderResult
import com.palixander.scalesync.data.WeighingReminderDraft
import com.palixander.scalesync.data.WeighingReminderSnapshot
import com.palixander.scalesync.domain.WeighingReminderId
import com.palixander.scalesync.domain.WeighingReminderImportance
import com.palixander.scalesync.domain.WeighingReminderOwner
import java.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    private val startAlarmSound: (
        String,
        String,
        String?,
        Int,
        android.app.Notification,
        android.app.Notification,
    ) -> Boolean =
        { scheduleId, token, soundUri, notificationId, notification, fallbackNotification ->
            WeighingAlarmSoundService.start(
                context,
                scheduleId,
                token,
                soundUri,
                notificationId,
                notification,
                fallbackNotification,
            )
        },
    private val scheduleAlarm: (
        WeighingReminderId,
        ReminderCallbackKind,
        String,
        Long,
        WeighingReminderImportance,
    ) -> ReminderScheduleResult = alarmGateway::schedule,
) {
    private val notifications = context.getSystemService(NotificationManager::class.java)

    suspend fun reconcile() = runtimeMutex.withLock { reconcileUnlocked() }

    suspend fun save(
        id: WeighingReminderId?,
        draft: WeighingReminderDraft,
    ): SaveWeighingReminderResult = runtimeMutex.withLock {
        val result = if (id == null) repository.create(draft) else repository.update(id, draft)
        if (result is SaveWeighingReminderResult.Saved) reconcileUnlocked()
        result
    }

    suspend fun setEnabled(id: WeighingReminderId, enabled: Boolean): SaveWeighingReminderResult =
        runtimeMutex.withLock {
            repository.setEnabled(id, enabled).also { reconcileUnlocked() }
        }

    suspend fun delete(id: WeighingReminderId) = runtimeMutex.withLock {
        repository.delete(id).also { reconcileUnlocked() }
    }

    private suspend fun reconcileUnlocked() {
        val snapshots = repository.snapshotEnabled()
        alarmGateway.cancelUnknown(snapshots.mapTo(mutableSetOf()) { it.schedule.id.value })
            .forEach(::cancelNotificationCopies)
        val capability = capabilityGateway.read()
        snapshots.forEach { snapshot ->
            val id = snapshot.schedule.id
            if (!capability.canPublish(snapshot.schedule.importance)) {
                WeighingAlarmSoundService.stop(context, id.value)
                cancelNotificationCopies(id)
                alarmGateway.cancel(id, ReminderCallbackKind.REGULAR)
                alarmGateway.cancel(id, ReminderCallbackKind.SNOOZE)
                repository.invalidateRuntime(id)
                return@forEach
            }
            if (snapshot.activeOccurrenceToken == null) cancelNotificationCopies(id)
            scheduleNextRegularUnlocked(snapshot)
            val snoozeDue = snapshot.snoozeDueEpochMillis
            val snoozeToken = snapshot.snoozeOccurrenceToken
            if (snapshot.snoozeStatus == ReminderSnoozeStatus.SCHEDULED &&
                snoozeDue != null && snoozeToken != null && snoozeDue > clock.millis()
            ) {
                val result = scheduleAlarm(
                    id,
                    ReminderCallbackKind.SNOOZE,
                    snoozeToken,
                    snoozeDue,
                    snapshot.schedule.importance,
                )
                if (result == ReminderScheduleResult.FAILED) repository.invalidateRuntime(id)
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
    suspend fun cancelDeleted(scheduleIds: Iterable<WeighingReminderId>) = runtimeMutex.withLock {
        scheduleIds.forEach { id ->
            WeighingAlarmSoundService.stop(context, id.value)
            alarmGateway.cancel(id, ReminderCallbackKind.REGULAR)
            alarmGateway.cancel(id, ReminderCallbackKind.SNOOZE)
            cancelNotificationCopies(id)
        }
    }

    suspend fun onFire(id: WeighingReminderId, kind: ReminderCallbackKind, token: String): Unit = runtimeMutex.withLock {
        if (repository.claimDue(id, kind, token) !is ReminderClaimResult.Publish) return@withLock
        val snapshot = repository.snapshot(id) ?: return@withLock
        scheduleNextRegularUnlocked(id)
        if (!capabilityGateway.read().canPublish(snapshot.schedule.importance)) {
            repository.invalidateRuntime(id)
            alarmGateway.cancel(id, ReminderCallbackKind.REGULAR)
            alarmGateway.cancel(id, ReminderCallbackKind.SNOOZE)
            return@withLock
        }
        val name = repository.ownerDisplayName(snapshot.schedule.owner) ?: return@withLock
        publish(snapshot.schedule, token, name)
    }

    suspend fun onSnooze(id: WeighingReminderId, token: String): Unit = runtimeMutex.withLock {
        val snapshot = repository.snapshot(id) ?: return@withLock
        val due = clock.millis() + 10.minutes.inWholeMilliseconds
        val snoozeToken = repository.snooze(id, token, due) ?: return@withLock
        WeighingAlarmSoundService.stop(context, id.value, token)
        cancelNotificationCopies(id)
        alarmGateway.cancel(id, ReminderCallbackKind.SNOOZE)
        if (scheduleAlarm(id, ReminderCallbackKind.SNOOZE, snoozeToken, due, snapshot.schedule.importance) == ReminderScheduleResult.FAILED) {
            repository.invalidateRuntime(id)
        }
    }

    suspend fun onContentTap(id: WeighingReminderId, token: String) = runtimeMutex.withLock {
        val snapshot = repository.snapshot(id)
        if (!onContentOpenedUnlocked(id, token)) return@withLock
        snapshot?.let { launch(WeighingReminderNavigationTarget(it.schedule.owner)) } ?: launchFallback()
    }

    /** Consumes an occurrence opened directly by MainActivity without launching a second task. */
    suspend fun onContentOpened(id: WeighingReminderId, token: String): Boolean = runtimeMutex.withLock {
        onContentOpenedUnlocked(id, token)
    }

    private suspend fun onContentOpenedUnlocked(id: WeighingReminderId, token: String): Boolean {
        val snapshot = repository.snapshot(id)
        if (snapshot != null && !repository.consumeAction(id, token)) return false
        WeighingAlarmSoundService.stop(context, id.value, token)
        cancelNotificationCopies(id)
        alarmGateway.cancel(id, ReminderCallbackKind.SNOOZE)
        return true
    }

    private suspend fun scheduleNextRegularUnlocked(id: WeighingReminderId) {
        val snapshot = repository.snapshot(id) ?: return
        scheduleNextRegularUnlocked(snapshot)
    }

    private suspend fun scheduleNextRegularUnlocked(
        snapshot: WeighingReminderSnapshot,
    ) {
        val schedule = snapshot.schedule
        val existingToken = snapshot.regularOccurrenceToken
        val existingDue = snapshot.regularDueEpochMillis
        if (snapshot.regularStatus == ReminderOccurrenceStatus.SCHEDULED &&
            existingToken != null && existingDue != null
        ) {
            if (scheduleAlarm(
                    schedule.id,
                    ReminderCallbackKind.REGULAR,
                    existingToken,
                    existingDue,
                    schedule.importance,
                ) == ReminderScheduleResult.FAILED
            ) {
                repository.discardRegularOccurrence(schedule.id, existingToken)
            }
            return
        }
        val due = nextReminderInstant(schedule, clock).toEpochMilli()
        val token = repository.prepareRegularOccurrence(schedule.id, due) ?: return
        alarmGateway.cancel(schedule.id, ReminderCallbackKind.REGULAR)
        if (scheduleAlarm(schedule.id, ReminderCallbackKind.REGULAR, token, due, schedule.importance) == ReminderScheduleResult.FAILED) {
            repository.discardRegularOccurrence(schedule.id, token)
        }
    }

    suspend fun onStop(id: WeighingReminderId, token: String): WeighingReminderNavigationTarget? = runtimeMutex.withLock {
        val snapshot = repository.snapshot(id) ?: run {
            WeighingAlarmSoundService.stop(context, id.value, token)
            cancelNotificationCopies(id)
            return null
        }
        if (!repository.consumeAction(id, token)) return null
        WeighingAlarmSoundService.stop(context, id.value, token)
        cancelNotificationCopies(id)
        alarmGateway.cancel(id, ReminderCallbackKind.SNOOZE)
        return WeighingReminderNavigationTarget(snapshot.schedule.owner)
    }

    private fun publish(
        schedule: com.palixander.scalesync.domain.WeighingReminderSchedule,
        token: String,
        name: String,
    ) {
        val importance = schedule.importance
        val id = schedule.id
        val owner = schedule.owner
        val presentation = weighingReminderNotificationPresentation(importance)
        val openIntent = contentIntent(id, token, owner, importance == WeighingReminderImportance.ALARM, name)
        fun buildNotification(channel: String) = NotificationCompat.Builder(context, channel)
            .setSmallIcon(weighingReminderNotificationSmallIcon(importance))
            .setContentTitle(context.getString(presentation.titleRes, name))
            .setContentText(context.getString(presentation.textRes))
            .setCategory(presentation.category)
            .setAutoCancel(presentation.autoCancel)
            .setOngoing(presentation.ongoing)
            .setContentIntent(openIntent)
            .setPriority(
                if (importance == WeighingReminderImportance.ALARM) {
                    NotificationCompat.PRIORITY_MAX
                } else {
                    NotificationCompat.PRIORITY_HIGH
                },
            )
            .addAction(
                0,
                context.getString(R.string.weighing_reminder_snooze),
                actionIntent(ACTION_SNOOZE, id, token, owner),
            )
            .apply {
                if (importance == WeighingReminderImportance.ALARM) {
                    setFullScreenIntent(openIntent, true)
                }
            }
            .apply {
                if (importance == WeighingReminderImportance.ALARM) addAction(
                    0,
                    context.getString(R.string.weighing_reminder_weigh),
                    weighIntent(id, token, owner, name),
                )
            }
            .build()

        if (importance == WeighingReminderImportance.ALARM) {
            val notificationId = notificationId(id)
            val notification = buildNotification(NotificationChannelRegistry.weighingAlarms.id)
            val fallbackNotification = buildNotification(NotificationChannelRegistry.weighingAlarmFallback.id)
            val started = startAlarmSound(
                id.value,
                token,
                schedule.alarmSoundUri,
                notificationId,
                notification,
                fallbackNotification,
            )
            if (!started) notifications.notify(notificationId, fallbackNotification)
        } else {
            notifications.notify(
                notificationId(id),
                buildNotification(NotificationChannelRegistry.weighingReminders.id),
            )
        }
    }

    private fun cancelNotificationCopies(id: WeighingReminderId) {
        notifications.cancel(notificationId(id))
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

    private fun weighIntent(
        id: WeighingReminderId,
        token: String,
        owner: WeighingReminderOwner,
        ownerName: String,
    ): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, WeighingReminderOpenActivity::class.java).apply {
            action = ACTION_WEIGH
            data = Uri.parse("scalesync://weighing-reminder-action/${Uri.encode(id.value)}/weigh")
            putExtra(WeighingReminderAlarmGateway.EXTRA_SCHEDULE_ID, id.value)
            putExtra(WeighingReminderAlarmGateway.EXTRA_OCCURRENCE_TOKEN, token)
            putExtra(EXTRA_OWNER_NAME, ownerName)
            putExtra(EXTRA_PERFORM_WEIGH, true)
            WeighingReminderNavigationTarget(owner).putInto(this)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun contentIntent(
        id: WeighingReminderId,
        token: String,
        owner: WeighingReminderOwner,
        alarm: Boolean,
        ownerName: String,
    ): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, weighingReminderContentActivity(alarm)).apply {
            action = if (alarm) ACTION_OPEN else WeighingReminderNavigationTarget.ACTION_OPEN_PROFILE
            data = Uri.parse("scalesync://weighing-reminder-action/${Uri.encode(id.value)}/open")
            putExtra(WeighingReminderAlarmGateway.EXTRA_SCHEDULE_ID, id.value)
            putExtra(WeighingReminderAlarmGateway.EXTRA_OCCURRENCE_TOKEN, token)
            putExtra(EXTRA_ALARM, alarm)
            putExtra(EXTRA_OWNER_NAME, ownerName)
            WeighingReminderNavigationTarget(owner).putInto(this)
            if (!alarm) flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
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
        private val runtimeMutex = Mutex()
        const val ACTION_SNOOZE = "com.palixander.scalesync.action.SNOOZE_WEIGHING_REMINDER"
        const val ACTION_STOP = "com.palixander.scalesync.action.STOP_WEIGHING_REMINDER"
        const val ACTION_WEIGH = "com.palixander.scalesync.action.WEIGH_FROM_WEIGHING_REMINDER"
        const val ACTION_OPEN = "com.palixander.scalesync.action.OPEN_WEIGHING_REMINDER"
        const val EXTRA_OWNER_UNAVAILABLE = "weighing_reminder_owner_unavailable"
        const val EXTRA_ALARM = "weighing_reminder_alarm"
        const val EXTRA_OWNER_NAME = "weighing_reminder_owner_name"
        const val EXTRA_PERFORM_WEIGH = "weighing_reminder_perform_weigh"
        fun notificationId(id: WeighingReminderId): Int = 0x57000000 xor id.value.hashCode()
    }
}

internal fun weighingReminderContentActivity(alarm: Boolean): Class<*> =
    if (alarm) WeighingReminderOpenActivity::class.java else MainActivity::class.java

internal fun weighingReminderNotificationSmallIcon(
    @Suppress("UNUSED_PARAMETER") importance: WeighingReminderImportance,
): Int = R.drawable.ic_notification

internal data class WeighingReminderNotificationPresentation(
    val titleRes: Int,
    val textRes: Int,
    val category: String,
    val ongoing: Boolean,
    val autoCancel: Boolean,
)

internal fun weighingReminderNotificationPresentation(importance: WeighingReminderImportance) =
    if (importance == WeighingReminderImportance.ALARM) {
        WeighingReminderNotificationPresentation(
            R.string.weighing_alarm_notification_title,
            R.string.weighing_alarm_notification_text,
            NotificationCompat.CATEGORY_ALARM,
            ongoing = true,
            autoCancel = false,
        )
    } else {
        WeighingReminderNotificationPresentation(
            R.string.weighing_reminder_notification_title,
            R.string.weighing_reminder_notification_text,
            NotificationCompat.CATEGORY_REMINDER,
            ongoing = false,
            autoCancel = true,
        )
    }
