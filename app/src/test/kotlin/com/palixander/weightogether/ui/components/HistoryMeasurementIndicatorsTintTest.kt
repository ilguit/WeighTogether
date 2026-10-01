package com.palixander.weightogether.ui.components

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryMeasurementIndicatorsTintTest {
    @Test
    fun indicatorTintKeepsThemeColorAndMakesItSubtle() {
        val themeColor = Color(red = 0.2f, green = 0.4f, blue = 0.6f, alpha = 1f)

        val tint = historyIndicatorTint(themeColor)

        assertEquals(themeColor.red, tint.red, 0f)
        assertEquals(themeColor.green, tint.green, 0f)
        assertEquals(themeColor.blue, tint.blue, 0f)
        assertEquals(0.55f, tint.alpha, 1f / 255f)
    }
}
