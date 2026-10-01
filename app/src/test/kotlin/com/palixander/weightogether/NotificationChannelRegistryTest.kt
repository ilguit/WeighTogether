package com.palixander.weightogether

import android.app.NotificationManager
import android.app.Application
import android.content.Context
import android.media.AudioAttributes
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class NotificationChannelRegistryTest {
    @Test
    fun `registry contains every app notification channel exactly once`() {
        assertEquals(
            listOf(
                "scale_scanning",
                "pending_measurement_routing",
                "successful_measurement_saves",
                "weighing_reminders_v2",
                "weighing_alarms_v3",
                "weighing_alarm_fallback_v1",
            ),
            NotificationChannelRegistry.all.map { it.id },
        )
    }

    @Test
    fun `regular reminder channel is audible high importance and vibrates`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = context.getSystemService(NotificationManager::class.java)

        NotificationChannelRegistry.register(context, manager, NotificationChannelRegistry.weighingReminders)

        val channel = manager.getNotificationChannel(NotificationChannelRegistry.weighingReminders.id)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
        assertEquals(AudioAttributes.USAGE_NOTIFICATION, channel.audioAttributes?.usage)
        assertEquals(true, channel.shouldVibrate())
    }

    @Test
    fun `alarm fallback channel is audible when sound service cannot start`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = context.getSystemService(NotificationManager::class.java)

        NotificationChannelRegistry.register(context, manager, NotificationChannelRegistry.weighingAlarmFallback)

        val channel = manager.getNotificationChannel(NotificationChannelRegistry.weighingAlarmFallback.id)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
        assertEquals(AudioAttributes.USAGE_ALARM, channel.audioAttributes?.usage)
        assertEquals(false, NotificationChannelRegistry.weighingAlarmFallback.silent)
        assertEquals(true, NotificationChannelRegistry.weighingAlarmFallback.alarmSound)
    }

    @Test
    fun `alarm channel stays silent because foreground service owns playback`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = context.getSystemService(NotificationManager::class.java)

        NotificationChannelRegistry.register(context, manager, NotificationChannelRegistry.weighingAlarms)

        val channel = manager.getNotificationChannel(NotificationChannelRegistry.weighingAlarms.id)
        assertNull(channel.sound)
        assertNull(channel.audioAttributes)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
    }

    @Test
    fun `existing channel metadata remains stable`() {
        assertEquals(R.string.notification_channel_scale_scanning, NotificationChannelRegistry.scaleScanning.nameRes)
        assertEquals(
            NotificationManager.IMPORTANCE_LOW,
            NotificationChannelRegistry.scaleScanning.importance,
        )
        assertNull(NotificationChannelRegistry.scaleScanning.descriptionRes)

        assertEquals(
            R.string.notification_channel_pending_measurements,
            NotificationChannelRegistry.pendingMeasurementRouting.nameRes,
        )
        assertEquals(
            NotificationManager.IMPORTANCE_DEFAULT,
            NotificationChannelRegistry.pendingMeasurementRouting.importance,
        )
        assertNull(NotificationChannelRegistry.pendingMeasurementRouting.descriptionRes)

        assertEquals(
            R.string.notification_channel_saved_measurements,
            NotificationChannelRegistry.successfulMeasurementSaves.nameRes,
        )
        assertEquals(
            NotificationManager.IMPORTANCE_DEFAULT,
            NotificationChannelRegistry.successfulMeasurementSaves.importance,
        )
        assertEquals(
            R.string.notification_channel_saved_measurements_description,
            NotificationChannelRegistry.successfulMeasurementSaves.descriptionRes,
        )

        assertEquals(NotificationManager.IMPORTANCE_HIGH, NotificationChannelRegistry.weighingReminders.importance)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, NotificationChannelRegistry.weighingAlarms.importance)
        assertEquals(false, NotificationChannelRegistry.weighingAlarms.alarmSound)
        assertEquals(true, NotificationChannelRegistry.weighingAlarms.silent)
    }
}
