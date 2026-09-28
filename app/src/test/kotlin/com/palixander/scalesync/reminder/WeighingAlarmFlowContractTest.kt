package com.palixander.scalesync.reminder

import android.app.Application
import android.media.AudioAttributes
import android.net.Uri
import com.palixander.scalesync.MainActivity
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
