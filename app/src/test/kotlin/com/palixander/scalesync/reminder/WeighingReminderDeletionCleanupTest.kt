package com.palixander.scalesync.reminder

import android.app.AlarmManager
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.palixander.scalesync.data.AppDatabase
import com.palixander.scalesync.data.ReminderCallbackKind
import com.palixander.scalesync.data.RoomWeighingReminderRepository
import com.palixander.scalesync.domain.WeighingReminderId
import com.palixander.scalesync.domain.WeighingReminderImportance
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WeighingReminderDeletionCleanupTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val alarms = WeighingReminderAlarmGateway(context)
    private val coordinator = WeighingReminderCoordinator(
        context,
        RoomWeighingReminderRepository(database),
        alarms,
        WeighingReminderCapabilityGateway(context),
    )

    @After
    fun close() = database.close()

    @Test
    fun `deleted owner cleanup cancels notification regular alarm and snooze idempotently`() = runBlocking {
        val id = WeighingReminderId("deleted-schedule")
        val notifications = context.getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel("test", "Test", NotificationManager.IMPORTANCE_DEFAULT))
        notifications.notify(
            WeighingReminderCoordinator.notificationId(id),
            NotificationCompat.Builder(context, "test")
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("Reminder")
                .build(),
        )
        alarms.schedule(id, ReminderCallbackKind.REGULAR, "regular", 10_000, WeighingReminderImportance.REGULAR)
        alarms.schedule(id, ReminderCallbackKind.SNOOZE, "snooze", 20_000, WeighingReminderImportance.REGULAR)

        coordinator.cancelDeleted(listOf(id))
        coordinator.cancelDeleted(listOf(id))

        assertTrue(shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.isEmpty())
        assertNull(shadowOf(notifications).getNotification(WeighingReminderCoordinator.notificationId(id)))
    }
}
