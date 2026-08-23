package com.example.huaweimisync.ble

import com.example.huaweimisync.R
import org.junit.Assert.assertEquals
import org.junit.Test

class ReliabilityScanServiceTest {
    @Test
    fun `foreground notification uses the monochrome app icon`() {
        assertEquals(
            R.drawable.ic_app_monochrome,
            reliabilityScanNotificationSmallIcon(),
        )
    }
}
