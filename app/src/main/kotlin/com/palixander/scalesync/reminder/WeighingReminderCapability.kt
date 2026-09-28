package com.palixander.scalesync.reminder

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.palixander.scalesync.NotificationChannelRegistry
import com.palixander.scalesync.domain.WeighingReminderImportance

data class WeighingReminderCapability(
    val postNotificationsAllowed: Boolean,
    val appNotificationsAllowed: Boolean,
    val regularChannelAllowed: Boolean,
    val alarmChannelAllowed: Boolean,
    val exactAlarmsAllowed: Boolean,
) {
    fun canPublish(importance: WeighingReminderImportance): Boolean =
        issuesFor(setOf(importance)).isEmpty()

    fun issuesFor(importances: Set<WeighingReminderImportance>): List<WeighingReminderCapabilityIssue> = buildList {
        if (importances.isEmpty()) return@buildList
        if (!postNotificationsAllowed) add(WeighingReminderCapabilityIssue.POST_NOTIFICATIONS)
        if (!appNotificationsAllowed) add(WeighingReminderCapabilityIssue.APP_NOTIFICATIONS)
        if (WeighingReminderImportance.REGULAR in importances && !regularChannelAllowed) {
            add(WeighingReminderCapabilityIssue.REGULAR_CHANNEL)
        }
        if (WeighingReminderImportance.ALARM in importances && !alarmChannelAllowed) {
            add(WeighingReminderCapabilityIssue.ALARM_CHANNEL)
        }
        if (WeighingReminderImportance.ALARM in importances && !exactAlarmsAllowed) {
            add(WeighingReminderCapabilityIssue.EXACT_ALARM)
        }
    }
}

enum class WeighingReminderCapabilityIssue {
    POST_NOTIFICATIONS,
    APP_NOTIFICATIONS,
    REGULAR_CHANNEL,
    ALARM_CHANNEL,
    EXACT_ALARM,
}

class WeighingReminderCapabilityGateway(private val context: Context) {
    fun read(): WeighingReminderCapability {
        val notifications = context.getSystemService(NotificationManager::class.java)
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val permissionAllowed = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        fun channelAllowed(id: String): Boolean =
            notifications.getNotificationChannel(id)?.importance != NotificationManager.IMPORTANCE_NONE
        return WeighingReminderCapability(
            postNotificationsAllowed = permissionAllowed,
            appNotificationsAllowed = notifications.areNotificationsEnabled(),
            regularChannelAllowed = channelAllowed(NotificationChannelRegistry.weighingReminders.id),
            alarmChannelAllowed = channelAllowed(NotificationChannelRegistry.weighingAlarms.id),
            exactAlarmsAllowed = Build.VERSION.SDK_INT < 31 || alarmManager.canScheduleExactAlarms(),
        )
    }

    fun settingsIntents(issue: WeighingReminderCapabilityIssue): List<Intent> = buildList {
        when (issue) {
            WeighingReminderCapabilityIssue.EXACT_ALARM -> if (Build.VERSION.SDK_INT >= 31) {
                add(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, "package:${context.packageName}".toUri()))
            }
            WeighingReminderCapabilityIssue.REGULAR_CHANNEL,
            WeighingReminderCapabilityIssue.ALARM_CHANNEL,
            -> add(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                putExtra(
                    Settings.EXTRA_CHANNEL_ID,
                    if (issue == WeighingReminderCapabilityIssue.REGULAR_CHANNEL) {
                        NotificationChannelRegistry.weighingReminders.id
                    } else {
                        NotificationChannelRegistry.weighingAlarms.id
                    },
                )
            })
            WeighingReminderCapabilityIssue.POST_NOTIFICATIONS,
            WeighingReminderCapabilityIssue.APP_NOTIFICATIONS,
            -> Unit
        }
        add(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
        add(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()))
    }

    fun alarmSoundSettingsIntents(): List<Intent> = listOf(
        Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            putExtra(Settings.EXTRA_CHANNEL_ID, NotificationChannelRegistry.weighingAlarms.id)
        },
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()),
    )
}
