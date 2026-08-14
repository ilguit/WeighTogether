package com.example.huaweimisync

import org.junit.Assert.assertEquals
import org.junit.Test

class AppSectionTest {
    @Test
    fun `sections have the expected order`() {
        assertEquals(
            listOf(AppSection.MEASUREMENTS, AppSection.CHARTS, AppSection.SETTINGS),
            AppSection.entries,
        )
    }

    @Test
    fun `measurements is the default section`() {
        assertEquals(AppSection.MEASUREMENTS, defaultAppSection)
    }

    @Test
    fun `settings section has settings presentation`() {
        assertEquals("Настройки", AppSection.SETTINGS.title)
        assertEquals("⚙", AppSection.SETTINGS.icon)
    }
}
