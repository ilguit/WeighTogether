package com.palixander.scalesync.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import com.palixander.scalesync.core.ReferenceCategory

enum class ReferenceTone {
    VERY_LOW,
    LOW,
    NORMAL,
    GOOD,
    VERY_GOOD,
    HIGH,
    VERY_HIGH,
    UNAVAILABLE,
}

@Immutable
data class ReferenceToneColors(
    val container: Color,
    val content: Color,
)

object ReferencePalette {
    val VeryLow = ReferenceToneColors(Color(0xFFE7F0FF), Color(0xFF234E83))
    val Low = ReferenceToneColors(Color(0xFFEDF5FF), Color(0xFF345E86))
    val Normal = ReferenceToneColors(Color(0xFFDDEEE8), Color(0xFF173A34))
    val Good = ReferenceToneColors(Color(0xFFD4EEE4), Color(0xFF174A3D))
    val VeryGood = ReferenceToneColors(Color(0xFFC5E8D8), Color(0xFF0F4737))
    val High = ReferenceToneColors(Color(0xFFF6E6D9), Color(0xFF4F2A18))
    val VeryHigh = ReferenceToneColors(Color(0xFFFFF0EE), Color(0xFF7E302B))
    val Unavailable = ReferenceToneColors(Color(0xFFE9ECEA), Color(0xFF3E4542))
    val NeutralZoneMarker = Color(0xFF68736F)

    fun colors(tone: ReferenceTone): ReferenceToneColors = when (tone) {
        ReferenceTone.VERY_LOW -> VeryLow
        ReferenceTone.LOW -> Low
        ReferenceTone.NORMAL -> Normal
        ReferenceTone.GOOD -> Good
        ReferenceTone.VERY_GOOD -> VeryGood
        ReferenceTone.HIGH -> High
        ReferenceTone.VERY_HIGH -> VeryHigh
        ReferenceTone.UNAVAILABLE -> Unavailable
    }
}

fun ReferenceCategory.referenceTone(): ReferenceTone = when (this) {
    ReferenceCategory.VERY_LOW -> ReferenceTone.VERY_LOW
    ReferenceCategory.LOW,
    ReferenceCategory.BELOW_NORMAL,
    -> ReferenceTone.LOW
    ReferenceCategory.NORMAL,
    ReferenceCategory.MATCHES,
    -> ReferenceTone.NORMAL
    ReferenceCategory.GOOD,
    ReferenceCategory.YOUNGER,
    -> ReferenceTone.GOOD
    ReferenceCategory.VERY_GOOD -> ReferenceTone.VERY_GOOD
    ReferenceCategory.ABOVE_NORMAL,
    ReferenceCategory.HIGH,
    ReferenceCategory.HIGH_BMI,
    ReferenceCategory.OLDER,
    -> ReferenceTone.HIGH
    ReferenceCategory.VERY_HIGH,
    ReferenceCategory.VERY_HIGH_BMI,
    -> ReferenceTone.VERY_HIGH
}
