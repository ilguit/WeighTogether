package com.palixander.scalesync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

data class AppNotificationChannel(
    val id: String,
    val name: String,
    val importance: Int,
    val description: String? = null,
)

object NotificationChannelRegistry {
    val scaleScanning = AppNotificationChannel(
        id = "scale_scanning",
        name = "Сканирование весов",
        importance = NotificationManager.IMPORTANCE_LOW,
    )
    val pendingMeasurementRouting = AppNotificationChannel(
        id = "pending_measurement_routing",
        name = "Нераспознанные измерения",
        importance = NotificationManager.IMPORTANCE_DEFAULT,
    )
    val successfulMeasurementSaves = AppNotificationChannel(
        id = "successful_measurement_saves",
        name = "Сохранённые измерения",
        importance = NotificationManager.IMPORTANCE_DEFAULT,
        description = "Подтверждения об успешном сохранении измерений",
    )

    val all: List<AppNotificationChannel> = listOf(
        scaleScanning,
        pendingMeasurementRouting,
        successfulMeasurementSaves,
    )

    fun registerAll(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        all.forEach { channel -> register(manager, channel) }
    }

    fun register(manager: NotificationManager, channel: AppNotificationChannel) {
        manager.create(channel)
    }

    private fun NotificationManager.create(channel: AppNotificationChannel) {
        createNotificationChannel(
            NotificationChannel(channel.id, channel.name, channel.importance).apply {
                description = channel.description
            },
        )
    }
}
