package com.palixander.scalesync.reminder

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.palixander.scalesync.NotificationChannelRegistry
import com.palixander.scalesync.data.AccountEntity
import com.palixander.scalesync.data.AppDatabase
import com.palixander.scalesync.data.ReminderCallbackKind
import com.palixander.scalesync.data.ReminderOccurrenceStatus
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class WeighingReminderCoordinatorConcurrencyTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val generatedIds = ArrayDeque(listOf("schedule", "due-token", "next-token", "edited-token", "unused"))
    private val repository = RoomWeighingReminderRepository(database, newId = { generatedIds.removeFirst() })

    @After
    fun close() = database.close()

    @Test
    fun `startup reconcile first preserves delivered token until receiver claims it`() = runBlocking {
        val id = createScheduledReminder()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var calls = 0
        val coordinator = coordinator { _, _, _, _, _ ->
            calls++
            if (calls == 1) {
                entered.countDown()
                assertTrue(release.await(5, TimeUnit.SECONDS))
            }
            ReminderScheduleResult.EXACT
        }

        val reconcile = async(Dispatchers.Default) { coordinator.reconcile() }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val fire = async(Dispatchers.Default) {
            coordinator.onFire(id, ReminderCallbackKind.REGULAR, "due-token")
        }
        release.countDown()
        withTimeout(5_000) {
            reconcile.await()
            fire.await()
        }

        val snapshot = requireNotNull(repository.snapshot(id))
        assertEquals("due-token", snapshot.activeOccurrenceToken)
        assertEquals(ReminderOccurrenceStatus.SCHEDULED, snapshot.regularStatus)
        assertEquals("next-token", snapshot.regularOccurrenceToken)
        assertNotNull(notification(id))
    }

    @Test
    fun `receiver first and queued startup reconcile keep next occurrence token`() = runBlocking {
        val id = createScheduledReminder()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var calls = 0
        val coordinator = coordinator { _, _, _, _, _ ->
            calls++
            if (calls == 1) {
                entered.countDown()
                assertTrue(release.await(5, TimeUnit.SECONDS))
            }
            ReminderScheduleResult.EXACT
        }

        val fire = async(Dispatchers.Default) {
            coordinator.onFire(id, ReminderCallbackKind.REGULAR, "due-token")
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val reconcile = async(Dispatchers.Default) { coordinator.reconcile() }
        release.countDown()
        withTimeout(5_000) {
            fire.await()
            reconcile.await()
        }

        val snapshot = requireNotNull(repository.snapshot(id))
        assertEquals("due-token", snapshot.activeOccurrenceToken)
        assertEquals("next-token", snapshot.regularOccurrenceToken)
        assertEquals(2, calls)
        assertNotNull(notification(id))
    }

    @Test
    fun `concurrent reconciles reuse one database occurrence`() = runBlocking {
        val id = createScheduledReminder()
        val seenTokens = mutableListOf<String>()
        val coordinator = coordinator { _, _, token, _, _ ->
            synchronized(seenTokens) { seenTokens += token }
            ReminderScheduleResult.EXACT
        }

        withTimeout(5_000) {
            val first = async(Dispatchers.Default) { coordinator.reconcile() }
            val second = async(Dispatchers.Default) { coordinator.reconcile() }
            first.await()
            second.await()
        }

        assertEquals(listOf("due-token", "due-token"), seenTokens)
        assertEquals("due-token", repository.snapshot(id)?.regularOccurrenceToken)
        assertNotEquals("next-token", repository.snapshot(id)?.regularOccurrenceToken)
    }

    @Test
    fun `edit queued behind due delivery removes stale presentation and schedules edited occurrence`() = runBlocking {
        val id = createScheduledReminder()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var calls = 0
        val coordinator = coordinator { _, _, _, _, _ ->
            calls++
            if (calls == 1) {
                entered.countDown()
                assertTrue(release.await(5, TimeUnit.SECONDS))
            }
            ReminderScheduleResult.EXACT
        }
        val editedDraft = WeighingReminderDraft(
            WeighingReminderOwner.Account(AccountId("account")),
            LocalTime.of(10, 0),
            DayOfWeek.entries.toSet(),
            WeighingReminderImportance.REGULAR,
        )

        val fire = async(Dispatchers.Default) {
            coordinator.onFire(id, ReminderCallbackKind.REGULAR, "due-token")
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val edit = async(Dispatchers.Default) { coordinator.save(id, editedDraft) }
        release.countDown()
        withTimeout(5_000) {
            fire.await()
            edit.await()
        }

        val snapshot = requireNotNull(repository.snapshot(id))
        assertEquals(LocalTime.of(10, 0), snapshot.schedule.time)
        assertEquals(null, snapshot.activeOccurrenceToken)
        assertEquals("edited-token", snapshot.regularOccurrenceToken)
        assertEquals(null, notification(id))
    }

    private suspend fun createScheduledReminder(): WeighingReminderId {
        database.accountDao().insert(
            AccountEntity("account", "Alice", "alice", null, null, null, false, 1, 1),
        )
        repository.create(
            WeighingReminderDraft(
                WeighingReminderOwner.Account(AccountId("account")),
                LocalTime.of(9, 0),
                DayOfWeek.entries.toSet(),
                WeighingReminderImportance.REGULAR,
            ),
        )
        NotificationChannelRegistry.registerAll(context)
        return WeighingReminderId("schedule").also {
            repository.prepareRegularOccurrence(it, 1_000)
        }
    }

    private fun coordinator(
        schedule: (WeighingReminderId, ReminderCallbackKind, String, Long, WeighingReminderImportance) -> ReminderScheduleResult,
    ) = WeighingReminderCoordinator(
        context = context,
        repository = repository,
        alarmGateway = WeighingReminderAlarmGateway(context),
        capabilityGateway = WeighingReminderCapabilityGateway(context),
        clock = Clock.fixed(Instant.parse("2026-09-28T08:00:00Z"), ZoneOffset.UTC),
        scheduleAlarm = schedule,
    )

    private fun notification(id: WeighingReminderId) =
        shadowOf(context.getSystemService(NotificationManager::class.java))
            .getNotification(WeighingReminderCoordinator.notificationId(id))
}
