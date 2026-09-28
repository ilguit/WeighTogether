package com.palixander.scalesync

import android.app.NotificationManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationChannelRegistryTest {
    @Test
    fun `registry contains every app notification channel exactly once`() {
        assertEquals(
            listOf(
                "scale_scanning",
                "pending_measurement_routing",
                "successful_measurement_saves",
                "weighing_reminders",
                "weighing_alarms_v2",
            ),
            NotificationChannelRegistry.all.map { it.id },
        )
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

        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, NotificationChannelRegistry.weighingReminders.importance)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, NotificationChannelRegistry.weighingAlarms.importance)
        assertEquals(true, NotificationChannelRegistry.weighingAlarms.alarmSound)
    }
}
