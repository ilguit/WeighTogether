package com.palixander.scalesync.reminder

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.palixander.scalesync.NotificationChannelRegistry
import com.palixander.scalesync.data.AccountEntity
import com.palixander.scalesync.data.AppDatabase
import com.palixander.scalesync.data.ReminderCallbackKind
import com.palixander.scalesync.data.RoomWeighingReminderRepository
import com.palixander.scalesync.data.WeighingReminderDraft
import com.palixander.scalesync.domain.AccountId
import com.palixander.scalesync.domain.WeighingReminderId
import com.palixander.scalesync.domain.WeighingReminderImportance
import com.palixander.scalesync.domain.WeighingReminderOwner
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class WeighingReminderCoordinatorFailureTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val ids = ArrayDeque(listOf("schedule", "active", "next", "snooze"))
    private val repository = RoomWeighingReminderRepository(database, newId = { ids.removeFirst() })
    private val coordinator = WeighingReminderCoordinator(
        context,
        repository,
        WeighingReminderAlarmGateway(context),
        WeighingReminderCapabilityGateway(context),
        Clock.fixed(Instant.parse("2026-09-28T08:00:00Z"), ZoneOffset.UTC),
    ) { _, kind, _, _, _ ->
        if (kind == ReminderCallbackKind.REGULAR) ReminderScheduleResult.FAILED
        else ReminderScheduleResult.EXACT
    }

    @After
    fun close() = database.close()

    @Test
    fun `regular notification remains tappable exactly once when next scheduling fails`() = runBlocking {
        val (id, active) = createClaimable(WeighingReminderImportance.REGULAR)

        coordinator.onFire(id, ReminderCallbackKind.REGULAR, active)

        assertNotNull(notification(id))
        assertEquals(active, repository.snapshot(id)?.activeOccurrenceToken)
        coordinator.onContentTap(id, active)
        assertNull(notification(id))
        assertFalse(repository.consumeAction(id, active))
    }

    @Test
    fun `alarm notification remains snoozable exactly once when next scheduling fails`() = runBlocking {
        val (id, active) = createClaimable(WeighingReminderImportance.ALARM)

        coordinator.onFire(id, ReminderCallbackKind.REGULAR, active)

        val published = requireNotNull(notification(id))
        assertTrue(published.flags and Notification.FLAG_ONGOING_EVENT != 0)
        coordinator.onSnooze(id, active)
        val snooze = repository.snapshot(id)?.snoozeOccurrenceToken
        assertNotNull(snooze)
        assertNull(notification(id))
        coordinator.onSnooze(id, active)
        assertEquals(snooze, repository.snapshot(id)?.snoozeOccurrenceToken)
    }

    @Test
    fun `ongoing alarm is dismissible by its content action after next scheduling fails`() = runBlocking {
        val (id, active) = createClaimable(WeighingReminderImportance.ALARM)

        coordinator.onFire(id, ReminderCallbackKind.REGULAR, active)
        assertTrue(requireNotNull(notification(id)).flags and Notification.FLAG_ONGOING_EVENT != 0)

        coordinator.onContentTap(id, active)
        assertNull(notification(id))
        assertFalse(repository.consumeAction(id, active))
    }

    private suspend fun createClaimable(
        importance: WeighingReminderImportance,
    ): Pair<WeighingReminderId, String> {
        database.accountDao().insert(
            AccountEntity("account", "Alice", "alice", null, null, null, false, 1, 1),
        )
        repository.create(
            WeighingReminderDraft(
                WeighingReminderOwner.Account(AccountId("account")),
                LocalTime.of(9, 0),
                DayOfWeek.entries.toSet(),
                importance,
            ),
        )
        NotificationChannelRegistry.registerAll(context)
        val id = WeighingReminderId("schedule")
        return id to requireNotNull(repository.prepareRegularOccurrence(id, 1_000))
    }

    private fun notification(id: WeighingReminderId): Notification? =
        shadowOf(context.getSystemService(NotificationManager::class.java))
            .getNotification(WeighingReminderCoordinator.notificationId(id))
}
