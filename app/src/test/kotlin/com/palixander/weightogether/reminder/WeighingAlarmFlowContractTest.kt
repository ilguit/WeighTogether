package com.palixander.weightogether.reminder

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.media.AudioAttributes
import android.net.Uri
import com.palixander.weightogether.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WeighingAlarmFlowContractTest {
    @Test
    fun `alarm screen keeps weigh snooze and system back as distinct actions`() {
        assertEquals(
            setOf(AlarmActivityAction.WEIGH, AlarmActivityAction.SNOOZE, AlarmActivityAction.DISMISS),
            AlarmActivityAction.entries.toSet(),
        )
    }

    @Test
    fun `foreground alarm playback uses alarm audio attributes`() {
        assertEquals(AudioAttributes.USAGE_ALARM, WEIGHING_ALARM_AUDIO_USAGE)
        assertEquals(AudioAttributes.CONTENT_TYPE_SONIFICATION, WEIGHING_ALARM_AUDIO_CONTENT_TYPE)
    }

    @Test
    fun `regular notification opens MainActivity directly`() {
        assertSame(MainActivity::class.java, weighingReminderContentActivity(alarm = false))
    }

    @Test
    fun `alarm notification opens dedicated full screen activity`() {
        assertSame(WeighingReminderOpenActivity::class.java, weighingReminderContentActivity(alarm = true))
    }

    @Test
    fun `weigh action is a visible activity flow rather than a background broadcast`() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<Application>()
        val pendingIntent = PendingIntent.getActivity(
            context,
            17,
            Intent(context, WeighingReminderOpenActivity::class.java).apply {
                action = WeighingReminderCoordinator.ACTION_WEIGH
                putExtra(WeighingReminderCoordinator.EXTRA_PERFORM_WEIGH, true)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val savedIntent = org.robolectric.Shadows.shadowOf(pendingIntent).savedIntent

        assertTrue(pendingIntent.isActivity)
        assertEquals(WeighingReminderCoordinator.ACTION_WEIGH, savedIntent.action)
        assertTrue(savedIntent.getBooleanExtra(WeighingReminderCoordinator.EXTRA_PERFORM_WEIGH, false))
        assertEquals(WeighingReminderOpenActivity::class.java.name, savedIntent.component?.className)
    }

    @Test
    fun `alarm activity reuses its visible instance for notification actions`() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<Application>()
        val info = context.packageManager.getActivityInfo(
            android.content.ComponentName(context, WeighingReminderOpenActivity::class.java),
            0,
        )

        assertEquals(android.content.pm.ActivityInfo.LAUNCH_SINGLE_TOP, info.launchMode)
    }

    @Test
    fun `new occurrence remains actionable when previous action completes`() {
        AlarmActivityAction.entries.forEach { action ->
            val guard = AlarmOccurrenceActionGuard()
            val first = guard.install("schedule-a", "token-a")
            assertTrue("A $action starts", guard.begin(first))

            val second = guard.install("schedule-b", "token-b")
            assertFalse("A $action completion must not finish B", guard.isCurrent(first))
            assertTrue("B $action remains actionable", guard.begin(second))
            assertTrue("B $action completion may finish B", guard.isCurrent(second))
        }
    }

    @Test
    fun `duplicate actions for one occurrence are rejected`() {
        val guard = AlarmOccurrenceActionGuard()
        val presentation = guard.install("schedule", "token")

        assertTrue(guard.begin(presentation))
        assertFalse(guard.begin(presentation))
        assertSame(presentation, guard.install("schedule", "token"))
        assertFalse(guard.begin(presentation))
    }

    @Test
    fun `stop command cannot silence a different occurrence`() {
        assertFalse(shouldStopWeighingAlarm("schedule", "new", "schedule", "old"))
        assertFalse(shouldStopWeighingAlarm("new-schedule", "token", "old-schedule", null))
        assertTrue(shouldStopWeighingAlarm("schedule", "token", "schedule", "token"))
        assertTrue(shouldStopWeighingAlarm("schedule", "token", "schedule", null))
    }

    @Test
    fun `invalid selected sound falls through to distinct system alarm`() {
        val selected = Uri.parse("content://missing/custom-alarm")
        val system = Uri.parse("content://settings/system/alarm_alert")

        assertEquals(listOf(selected, system), alarmSoundCandidates(selected, system))
        assertEquals(listOf(system), alarmSoundCandidates(system, system))
        assertEquals(emptyList<Uri>(), alarmSoundCandidates(null, null))
    }

    @Test
    fun `playback exception uses system sound and exhausted sounds reach tone fallback`() {
        val selected = Uri.parse("content://missing/custom-alarm")
        val system = Uri.parse("content://settings/system/alarm_alert")
        val attempted = mutableListOf<Uri>()

        val played = firstPlayableAlarmSound(alarmSoundCandidates(selected, system)) { candidate ->
            attempted += candidate
            if (candidate == selected) throw SecurityException("permission was revoked")
            "system-player"
        }

        assertEquals("system-player", played)
        assertEquals(listOf(selected, system), attempted)
        assertNull(firstPlayableAlarmSound<String>(listOf(selected)) { throw IllegalStateException("missing") })
    }
}
