package com.palixander.weightogether.ble

import com.palixander.weightogether.R
import org.junit.Assert.assertEquals
import org.junit.Test

class ReliabilityScanServiceTest {
    @Test
    fun `foreground notification uses the monochrome app icon`() {
        assertEquals(
            R.drawable.ic_notification,
            reliabilityScanNotificationSmallIcon(),
        )
    }
}
