package com.palixander.weightogether.ui.theme

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
            ReferencePalette.VeryLow to Triple(0xFFE7F0FFL, 0xFF234E83L, 0xFF397BE5L),
            ReferencePalette.Low to Triple(0xFFEDF5FFL, 0xFF345E86L, 0xFF65A0EEL),
            ReferencePalette.Normal to Triple(0xFFDDEEE8L, 0xFF173A34L, 0xFF45A98BL),
            ReferencePalette.Good to Triple(0xFFD4EEE4L, 0xFF174A3DL, 0xFF20A574L),
            ReferencePalette.VeryGood to Triple(0xFFC5E8D8L, 0xFF0F4737L, 0xFF07885AL),
            ReferencePalette.High to Triple(0xFFF6E6D9L, 0xFF4F2A18L, 0xFFE8873DL),
            ReferencePalette.VeryHigh to Triple(0xFFFFF0EEL, 0xFF7E302BL, 0xFFE0524AL),
            ReferencePalette.Unavailable to Triple(0xFFE9ECEAL, 0xFF3E4542L, 0xFF7A8782L),
        )

        expected.forEach { (actual, colors) ->
            assertEquals(colors.first, actual.container.toArgb().toLong() and 0xffffffffL)
            assertEquals(colors.second, actual.content.toArgb().toLong() and 0xffffffffL)
            assertEquals(colors.third, actual.scale.toArgb().toLong() and 0xffffffffL)
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

    @Test
    fun everyToneUsesASeparateSaturatedScaleColor() {
        ReferenceTone.entries.forEach { tone ->
            val colors = ReferencePalette.colors(tone)
            assertTrue("$tone scale differs from container", colors.scale != colors.container)
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
