package com.palixander.scalesync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.annotation.StringRes

data class AppNotificationChannel(
    val id: String,
    @param:StringRes val nameRes: Int,
    val importance: Int,
    @param:StringRes val descriptionRes: Int? = null,
    val alarmSound: Boolean = false,
    val notificationSound: Boolean = false,
    val silent: Boolean = false,
)

object NotificationChannelRegistry {
    val scaleScanning = AppNotificationChannel(
        id = "scale_scanning",
        nameRes = R.string.notification_channel_scale_scanning,
        importance = NotificationManager.IMPORTANCE_LOW,
    )
    val pendingMeasurementRouting = AppNotificationChannel(
        id = "pending_measurement_routing",
        nameRes = R.string.notification_channel_pending_measurements,
        importance = NotificationManager.IMPORTANCE_DEFAULT,
    )
    val successfulMeasurementSaves = AppNotificationChannel(
        id = "successful_measurement_saves",
        nameRes = R.string.notification_channel_saved_measurements,
        importance = NotificationManager.IMPORTANCE_DEFAULT,
        descriptionRes = R.string.notification_channel_saved_measurements_description,
    )
    val weighingReminders = AppNotificationChannel(
        // Importance and sound are immutable after first creation. Version the channel so
        // installs that already created the old DEFAULT channel receive heads-up reminders.
        id = "weighing_reminders_v2",
        nameRes = R.string.notification_channel_weighing_reminders,
        importance = NotificationManager.IMPORTANCE_HIGH,
        notificationSound = true,
    )
    val weighingAlarms = AppNotificationChannel(
        // Channel properties are immutable after creation; use a versioned id for the alarm semantics.
        id = "weighing_alarms_v3",
        nameRes = R.string.notification_channel_weighing_alarms,
        importance = NotificationManager.IMPORTANCE_HIGH,
        silent = true,
    )
    val weighingAlarmFallback = AppNotificationChannel(
        // Used only when Android refuses to start the sound foreground service.
        id = "weighing_alarm_fallback_v1",
        nameRes = R.string.notification_channel_weighing_alarms,
        importance = NotificationManager.IMPORTANCE_HIGH,
        alarmSound = true,
    )

    val all: List<AppNotificationChannel> = listOf(
        scaleScanning,
        pendingMeasurementRouting,
        successfulMeasurementSaves,
        weighingReminders,
        weighingAlarms,
        weighingAlarmFallback,
    )

    fun registerAll(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        all.forEach { channel -> register(context, manager, channel) }
    }

    fun register(context: Context, manager: NotificationManager, channel: AppNotificationChannel) {
        manager.create(context, channel)
    }

    private fun NotificationManager.create(context: Context, channel: AppNotificationChannel) {
        createNotificationChannel(
            NotificationChannel(
                channel.id,
                context.getString(channel.nameRes),
                channel.importance,
            ).apply {
                description = channel.descriptionRes?.let(context::getString)
                if (channel.alarmSound) {
                    setSound(
                        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build(),
                    )
                } else if (channel.notificationSound) {
                    setSound(
                        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build(),
                    )
                    enableVibration(true)
                } else if (channel.silent) {
                    setSound(null, null)
                }
            },
        )
    }
}
