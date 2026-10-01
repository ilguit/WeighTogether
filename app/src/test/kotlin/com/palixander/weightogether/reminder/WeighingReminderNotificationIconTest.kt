package com.palixander.weightogether.reminder

import com.palixander.weightogether.R
import com.palixander.weightogether.domain.WeighingReminderImportance
import org.junit.Assert.assertEquals
import org.junit.Test

class WeighingReminderNotificationIconTest {
    @Test
    fun `regular and alarm reminders use the monochrome app icon`() {
        WeighingReminderImportance.entries.forEach { importance ->
            assertEquals(
                R.drawable.ic_notification,
                weighingReminderNotificationSmallIcon(importance),
            )
        }
    }
}
