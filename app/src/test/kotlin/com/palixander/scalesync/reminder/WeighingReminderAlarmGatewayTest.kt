package com.palixander.scalesync.reminder

import android.app.AlarmManager
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.palixander.scalesync.data.ReminderCallbackKind
import com.palixander.scalesync.domain.WeighingReminderId
import com.palixander.scalesync.domain.WeighingReminderImportance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WeighingReminderAlarmGatewayTest {
    private lateinit var context: Context
    private lateinit var gateway: WeighingReminderAlarmGateway

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        gateway = WeighingReminderAlarmGateway(context)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    @Test
    fun `same schedule and callback replace existing immutable pending intent`() {
        val id = WeighingReminderId("schedule")
        gateway.schedule(id, ReminderCallbackKind.REGULAR, "first", 1_000, WeighingReminderImportance.REGULAR)
        gateway.schedule(id, ReminderCallbackKind.REGULAR, "second", 2_000, WeighingReminderImportance.REGULAR)

        val alarms = shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms
        assertEquals(1, alarms.size)
        assertEquals(2_000, alarms.single().triggerAtTime)
        assertTrue(requireNotNull(alarms.single().operation).isImmutable)
    }

    @Test
    fun `alarm uses alarm clock info with show intent`() {
        val id = WeighingReminderId("schedule")
        gateway.schedule(id, ReminderCallbackKind.REGULAR, "regular", 1_000, WeighingReminderImportance.ALARM)
        gateway.schedule(id, ReminderCallbackKind.SNOOZE, "snooze", 2_000, WeighingReminderImportance.ALARM)

        val alarms = shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms
        assertEquals(2, alarms.size)
        assertTrue(alarms.all { it.windowLengthMs == ShadowAlarmManager.WINDOW_EXACT })
        assertTrue(alarms.all { it.type == AlarmManager.RTC_WAKEUP })
        assertTrue(alarms.all { it.showIntent != null })
    }

    @Test
    fun `regular reminder uses user visible alarm clock transport when exact is permitted`() {
        val result = gateway.schedule(
            WeighingReminderId("exact"), ReminderCallbackKind.REGULAR, "token", 1_000,
            WeighingReminderImportance.REGULAR,
        )

        assertEquals(ReminderScheduleResult.EXACT, result)
        val alarm = shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.single()
        assertEquals(ShadowAlarmManager.WINDOW_EXACT, alarm.windowLengthMs)
        assertEquals(AlarmManager.RTC_WAKEUP, alarm.type)
        assertTrue(alarm.showIntent != null)
    }

    @Test
    fun `regular reminder falls back to inexact idle alarm without exact capability`() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)

        val result = gateway.schedule(
            WeighingReminderId("inexact"), ReminderCallbackKind.REGULAR, "token", 1_000,
            WeighingReminderImportance.REGULAR,
        )

        assertEquals(ReminderScheduleResult.INEXACT, result)
        val alarm = shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.single()
        assertTrue(alarm.windowLengthMs != ShadowAlarmManager.WINDOW_EXACT)
        assertTrue(alarm.allowWhileIdle)
    }

    @Test
    fun `cancel unknown removes both callback identities`() {
        val id = WeighingReminderId("deleted")
        gateway.schedule(id, ReminderCallbackKind.REGULAR, "regular", 1_000, WeighingReminderImportance.REGULAR)
        gateway.schedule(id, ReminderCallbackKind.SNOOZE, "snooze", 2_000, WeighingReminderImportance.REGULAR)

        assertEquals(setOf(id), gateway.cancelUnknown(emptySet()))
        assertTrue(shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.isEmpty())
    }

    @Test
    fun `successful snooze scheduling does not hide regular scheduling failure`() {
        val id = WeighingReminderId("partially-failed")
        context.getSharedPreferences("weighing_reminder_alarm_registry", Context.MODE_PRIVATE)
            .edit()
            .putStringSet("failures", setOf("${id.value}|${ReminderCallbackKind.REGULAR.name}"))
            .commit()

        gateway.schedule(id, ReminderCallbackKind.SNOOZE, "snooze", 2_000, WeighingReminderImportance.REGULAR)

        assertTrue(gateway.hasSchedulingFailure(id))
    }

    @Test
    fun `cancel unknown clears orphan scheduling failures`() {
        val id = WeighingReminderId("deleted-failure")
        context.getSharedPreferences("weighing_reminder_alarm_registry", Context.MODE_PRIVATE)
            .edit()
            .putStringSet("failures", setOf("${id.value}|${ReminderCallbackKind.REGULAR.name}"))
            .commit()

        assertEquals(setOf(id), gateway.cancelUnknown(emptySet()))
        assertFalse(gateway.hasAnySchedulingFailure())
    }

    @Test
    fun `failure lookup is scoped to current owner schedule ids`() {
        val firstOwner = WeighingReminderId("first-owner")
        val secondOwner = WeighingReminderId("second-owner")
        context.getSharedPreferences("weighing_reminder_alarm_registry", Context.MODE_PRIVATE)
            .edit()
            .putStringSet("failures", setOf("${secondOwner.value}|${ReminderCallbackKind.REGULAR.name}"))
            .commit()

        assertFalse(gateway.hasSchedulingFailure(listOf(firstOwner)))
        assertTrue(gateway.hasSchedulingFailure(listOf(secondOwner)))
        assertFalse(gateway.hasSchedulingFailure(emptyList()))
    }
}
