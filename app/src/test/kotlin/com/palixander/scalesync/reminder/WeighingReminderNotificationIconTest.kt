package com.palixander.scalesync.reminder

import com.palixander.scalesync.R
import com.palixander.scalesync.domain.WeighingReminderImportance
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
