package com.palixander.scalesync.reminder

import android.media.AudioAttributes
import com.palixander.scalesync.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
