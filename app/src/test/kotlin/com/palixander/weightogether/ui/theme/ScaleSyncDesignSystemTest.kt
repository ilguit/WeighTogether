package com.palixander.weightogether.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.palixander.weightogether.ui.icons.ScaleSyncIcons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

class ScaleSyncDesignSystemTest {
    @Test
    fun `light palette matches redesign contract`() {
        assertEquals(Color(0xFFF5F7F3), ScaleSyncLightColorScheme.background)
        assertEquals(Color(0xFF19211E), ScaleSyncLightColorScheme.onBackground)
        assertEquals(Color(0xFF28766B), ScaleSyncLightColorScheme.primary)
        assertEquals(Color(0xFFDDEEE8), ScaleSyncLightColorScheme.primaryContainer)
        assertEquals(Color.White, ScaleSyncLightColorScheme.surface)
        assertEquals(Color(0xFFBCCBC6), ScaleSyncLightColorScheme.outline)
        assertEquals(Color(0xFFBA4D45), ScaleSyncLightColorScheme.error)
    }

    @Test
    fun `material components cannot fall back to the baseline purple palette`() {
        assertEquals(ScaleSyncColors.PrimaryContainer, ScaleSyncLightColorScheme.inversePrimary)
        assertEquals(ScaleSyncColors.Primary, ScaleSyncLightColorScheme.surfaceTint)
        assertEquals(ScaleSyncColors.InverseSurface, ScaleSyncLightColorScheme.inverseSurface)
        assertEquals(ScaleSyncColors.InverseOnSurface, ScaleSyncLightColorScheme.inverseOnSurface)
        assertEquals(ScaleSyncColors.Surface, ScaleSyncLightColorScheme.surfaceBright)
        assertEquals(ScaleSyncColors.SurfaceDim, ScaleSyncLightColorScheme.surfaceDim)
        assertEquals(ScaleSyncColors.SurfaceContainer, ScaleSyncLightColorScheme.surfaceContainer)
        assertEquals(ScaleSyncColors.SurfaceContainerHigh, ScaleSyncLightColorScheme.surfaceContainerHigh)
        assertEquals(ScaleSyncColors.SurfaceContainerHighest, ScaleSyncLightColorScheme.surfaceContainerHighest)
        assertEquals(ScaleSyncColors.SurfaceSubtle, ScaleSyncLightColorScheme.surfaceContainerLow)
        assertEquals(ScaleSyncColors.Surface, ScaleSyncLightColorScheme.surfaceContainerLowest)
        assertEquals(ScaleSyncColors.PrimaryContainer, ScaleSyncLightColorScheme.primaryFixed)
        assertEquals(ScaleSyncColors.SecondaryContainer, ScaleSyncLightColorScheme.secondaryFixed)
        assertEquals(ScaleSyncColors.WarningContainer, ScaleSyncLightColorScheme.tertiaryFixed)
    }

    @Test
    fun `system bar palette retains contrast for light icons`() {
        assertTrue(contrastRatio(Color.White, ScaleSyncColors.Primary) >= 4.5f)
    }

    @Test
    fun `interactive dimensions retain accessible touch target`() {
        assertEquals(48.dp, ScaleSyncDimensions.TouchTarget)
        assertEquals(12.dp, ScaleSyncDimensions.CornerSmall)
        assertEquals(14.dp, ScaleSyncDimensions.CornerControl)
        assertEquals(18.dp, ScaleSyncDimensions.CornerSurface)
        assertEquals(24.dp, ScaleSyncDimensions.CornerLarge)
    }

    @Test
    fun `material shapes map to the four redesign corner sizes`() {
        assertEquals(RoundedCornerShape(12.dp), ScaleSyncShapes.extraSmall)
        assertEquals(RoundedCornerShape(12.dp), ScaleSyncShapes.small)
        assertEquals(RoundedCornerShape(14.dp), ScaleSyncShapes.medium)
        assertEquals(RoundedCornerShape(18.dp), ScaleSyncShapes.large)
        assertEquals(RoundedCornerShape(24.dp), ScaleSyncShapes.extraLarge)
    }

    @Test
    fun `typography uses system sans with only redesign weights`() {
        val styles = listOf(
            ScaleSyncTypography.displayLarge,
            ScaleSyncTypography.displayMedium,
            ScaleSyncTypography.displaySmall,
            ScaleSyncTypography.headlineLarge,
            ScaleSyncTypography.headlineMedium,
            ScaleSyncTypography.headlineSmall,
            ScaleSyncTypography.titleLarge,
            ScaleSyncTypography.titleMedium,
            ScaleSyncTypography.titleSmall,
            ScaleSyncTypography.bodyLarge,
            ScaleSyncTypography.bodyMedium,
            ScaleSyncTypography.bodySmall,
            ScaleSyncTypography.labelLarge,
            ScaleSyncTypography.labelMedium,
            ScaleSyncTypography.labelSmall,
        )

        styles.forEach { style ->
            assertEquals(FontFamily.SansSerif, style.fontFamily)
            assertTrue(style.fontWeight == FontWeight.Normal || style.fontWeight == FontWeight.Medium)
        }
        assertEquals(22.sp, ScaleSyncTypography.titleLarge.fontSize)
        assertEquals(14.sp, ScaleSyncTypography.bodyMedium.fontSize)
        assertEquals(12.sp, ScaleSyncTypography.bodySmall.fontSize)
    }

    @Test
    fun `all custom vectors use the shared 24 dp viewport`() {
        val icons = listOf(
            ScaleSyncIcons.Back,
            ScaleSyncIcons.More,
            ScaleSyncIcons.Scale,
            ScaleSyncIcons.Charts,
            ScaleSyncIcons.Settings,
            ScaleSyncIcons.Success,
            ScaleSyncIcons.Pending,
            ScaleSyncIcons.Warning,
            ScaleSyncIcons.LocalDevice,
            ScaleSyncIcons.ChevronRight,
            ScaleSyncIcons.ChevronDown,
            ScaleSyncIcons.Calendar,
            ScaleSyncIcons.Tune,
            ScaleSyncIcons.Profile,
            ScaleSyncIcons.Link,
            ScaleSyncIcons.Bluetooth,
            ScaleSyncIcons.Lab,
            ScaleSyncIcons.Close,
            ScaleSyncIcons.Health,
            ScaleSyncIcons.Edit,
            ScaleSyncIcons.Delete,
            ScaleSyncIcons.Refresh,
            ScaleSyncIcons.Users,
            ScaleSyncIcons.HealthConnect,
            ScaleSyncIcons.Archive,
            ScaleSyncIcons.Stethoscope,
            ScaleSyncIcons.History,
        )

        icons.forEach { icon ->
            assertEquals(24.dp, icon.defaultWidth)
            assertEquals(24.dp, icon.defaultHeight)
            assertEquals(24f, icon.viewportWidth)
            assertEquals(24f, icon.viewportHeight)
        }
    }

    private fun contrastRatio(first: Color, second: Color): Float {
        val firstLuminance = first.luminance()
        val secondLuminance = second.luminance()
        return (max(firstLuminance, secondLuminance) + 0.05f) /
            (min(firstLuminance, secondLuminance) + 0.05f)
    }
}
