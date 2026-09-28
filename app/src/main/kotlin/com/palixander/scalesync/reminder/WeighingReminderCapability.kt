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
    val notificationsAllowed: Boolean,
    val regularChannelAllowed: Boolean,
    val alarmChannelAllowed: Boolean,
    val exactAlarmsAllowed: Boolean,
) {
    fun canPublish(importance: WeighingReminderImportance): Boolean = notificationsAllowed && when (importance) {
        WeighingReminderImportance.REGULAR -> regularChannelAllowed
        WeighingReminderImportance.ALARM -> alarmChannelAllowed && exactAlarmsAllowed
    }
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
            notificationsAllowed = permissionAllowed && notifications.areNotificationsEnabled(),
            regularChannelAllowed = channelAllowed(NotificationChannelRegistry.weighingReminders.id),
            alarmChannelAllowed = channelAllowed(NotificationChannelRegistry.weighingAlarms.id),
            exactAlarmsAllowed = Build.VERSION.SDK_INT < 31 || alarmManager.canScheduleExactAlarms(),
        )
    }

    fun settingsIntents(importance: WeighingReminderImportance): List<Intent> {
        val channel = when (importance) {
            WeighingReminderImportance.REGULAR -> NotificationChannelRegistry.weighingReminders.id
            WeighingReminderImportance.ALARM -> NotificationChannelRegistry.weighingAlarms.id
        }
        return buildList {
            if (importance == WeighingReminderImportance.ALARM && Build.VERSION.SDK_INT >= 31) {
                add(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, "package:${context.packageName}".toUri()))
            }
            add(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                putExtra(Settings.EXTRA_CHANNEL_ID, channel)
            })
            add(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            add(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()))
        }
    }
}
