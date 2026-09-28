package com.palixander.scalesync.reminder

import androidx.core.app.NotificationCompat
import com.palixander.scalesync.R
import com.palixander.scalesync.domain.WeighingReminderImportance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeighingReminderNotificationPresentationTest {
    @Test
    fun `alarm is visibly distinct persistent alarm notification`() {
        val presentation = weighingReminderNotificationPresentation(WeighingReminderImportance.ALARM)

        assertEquals(NotificationCompat.CATEGORY_ALARM, presentation.category)
        assertEquals(R.string.weighing_alarm_notification_title, presentation.titleRes)
        assertEquals(R.string.weighing_alarm_notification_text, presentation.textRes)
        assertTrue(presentation.ongoing)
        assertFalse(presentation.autoCancel)
    }

    @Test
    fun `regular is dismissible reminder notification`() {
        val presentation = weighingReminderNotificationPresentation(WeighingReminderImportance.REGULAR)

        assertEquals(NotificationCompat.CATEGORY_REMINDER, presentation.category)
        assertEquals(R.string.weighing_reminder_notification_title, presentation.titleRes)
        assertFalse(presentation.ongoing)
        assertTrue(presentation.autoCancel)
    }
}
