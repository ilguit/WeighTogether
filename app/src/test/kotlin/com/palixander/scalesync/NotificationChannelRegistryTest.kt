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
            ),
            NotificationChannelRegistry.all.map { it.id },
        )
    }

    @Test
    fun `existing channel metadata remains stable`() {
        assertEquals("Сканирование весов", NotificationChannelRegistry.scaleScanning.name)
        assertEquals(
            NotificationManager.IMPORTANCE_LOW,
            NotificationChannelRegistry.scaleScanning.importance,
        )
        assertNull(NotificationChannelRegistry.scaleScanning.description)

        assertEquals(
            "Нераспознанные измерения",
            NotificationChannelRegistry.pendingMeasurementRouting.name,
        )
        assertEquals(
            NotificationManager.IMPORTANCE_DEFAULT,
            NotificationChannelRegistry.pendingMeasurementRouting.importance,
        )
        assertNull(NotificationChannelRegistry.pendingMeasurementRouting.description)

        assertEquals(
            "Сохранённые измерения",
            NotificationChannelRegistry.successfulMeasurementSaves.name,
        )
        assertEquals(
            NotificationManager.IMPORTANCE_DEFAULT,
            NotificationChannelRegistry.successfulMeasurementSaves.importance,
        )
        assertEquals(
            "Подтверждения об успешном сохранении измерений",
            NotificationChannelRegistry.successfulMeasurementSaves.description,
        )
    }
}
