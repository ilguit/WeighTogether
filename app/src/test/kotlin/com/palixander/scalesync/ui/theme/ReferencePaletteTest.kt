package com.palixander.scalesync.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReferencePaletteTest {
    @Test
    fun paletteUsesTheExactApprovedLightTokens() {
        val expected = listOf(
            ReferencePalette.VeryLow to (0xFFE7F0FFL to 0xFF234E83L),
            ReferencePalette.Low to (0xFFEDF5FFL to 0xFF345E86L),
            ReferencePalette.Normal to (0xFFDDEEE8L to 0xFF173A34L),
            ReferencePalette.Good to (0xFFD4EEE4L to 0xFF174A3DL),
            ReferencePalette.VeryGood to (0xFFC5E8D8L to 0xFF0F4737L),
            ReferencePalette.High to (0xFFF6E6D9L to 0xFF4F2A18L),
            ReferencePalette.VeryHigh to (0xFFFFF0EEL to 0xFF7E302BL),
            ReferencePalette.Unavailable to (0xFFE9ECEAL to 0xFF3E4542L),
        )

        expected.forEach { (actual, colors) ->
            assertEquals(colors.first, actual.container.toArgb().toLong() and 0xffffffffL)
            assertEquals(colors.second, actual.content.toArgb().toLong() and 0xffffffffL)
        }
        assertEquals(0xFF68736FL, ReferencePalette.NeutralZoneMarker.toArgb().toLong() and 0xffffffffL)
    }

    @Test
    fun everyTextPairExceedsWcagAaContrast() {
        ReferenceTone.entries.forEach { tone ->
            val colors = ReferencePalette.colors(tone)
            assertTrue("$tone contrast", contrast(colors.container, colors.content) >= 4.5)
        }
    }

    private fun contrast(first: Color, second: Color): Double {
        val one = luminance(first)
        val two = luminance(second)
        return (max(one, two) + 0.05) / (min(one, two) + 0.05)
    }

    private fun luminance(color: Color): Double {
        fun channel(value: Float): Double {
            val normalized = value.toDouble()
            return if (normalized <= 0.03928) normalized / 12.92 else ((normalized + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
    }
}
