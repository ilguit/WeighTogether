package com.palixander.scalesync.reminder

import android.app.AlarmManager
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.palixander.scalesync.data.ReminderCallbackKind
import com.palixander.scalesync.domain.WeighingReminderId
import com.palixander.scalesync.domain.WeighingReminderImportance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
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
    fun `regular reminder uses exact idle alarm when permitted`() {
        val result = gateway.schedule(
            WeighingReminderId("exact"), ReminderCallbackKind.REGULAR, "token", 1_000,
            WeighingReminderImportance.REGULAR,
        )

        assertEquals(ReminderScheduleResult.EXACT, result)
        val alarm = shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.single()
        assertEquals(ShadowAlarmManager.WINDOW_EXACT, alarm.windowLengthMs)
        assertTrue(alarm.allowWhileIdle)
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
}
