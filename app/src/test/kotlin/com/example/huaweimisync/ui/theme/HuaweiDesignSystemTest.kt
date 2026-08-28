package com.example.huaweimisync.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.huaweimisync.ui.icons.HuaweiIcons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

class HuaweiDesignSystemTest {
    @Test
    fun `light palette matches redesign contract`() {
        assertEquals(Color(0xFFF5F7F3), HuaweiLightColorScheme.background)
        assertEquals(Color(0xFF19211E), HuaweiLightColorScheme.onBackground)
        assertEquals(Color(0xFF28766B), HuaweiLightColorScheme.primary)
        assertEquals(Color(0xFFDDEEE8), HuaweiLightColorScheme.primaryContainer)
        assertEquals(Color.White, HuaweiLightColorScheme.surface)
        assertEquals(Color(0xFFBCCBC6), HuaweiLightColorScheme.outline)
        assertEquals(Color(0xFFBA4D45), HuaweiLightColorScheme.error)
    }

    @Test
    fun `material components cannot fall back to the baseline purple palette`() {
        assertEquals(HuaweiColors.PrimaryContainer, HuaweiLightColorScheme.inversePrimary)
        assertEquals(HuaweiColors.Primary, HuaweiLightColorScheme.surfaceTint)
        assertEquals(HuaweiColors.InverseSurface, HuaweiLightColorScheme.inverseSurface)
        assertEquals(HuaweiColors.InverseOnSurface, HuaweiLightColorScheme.inverseOnSurface)
        assertEquals(HuaweiColors.Surface, HuaweiLightColorScheme.surfaceBright)
        assertEquals(HuaweiColors.SurfaceDim, HuaweiLightColorScheme.surfaceDim)
        assertEquals(HuaweiColors.SurfaceContainer, HuaweiLightColorScheme.surfaceContainer)
        assertEquals(HuaweiColors.SurfaceContainerHigh, HuaweiLightColorScheme.surfaceContainerHigh)
        assertEquals(HuaweiColors.SurfaceContainerHighest, HuaweiLightColorScheme.surfaceContainerHighest)
        assertEquals(HuaweiColors.SurfaceSubtle, HuaweiLightColorScheme.surfaceContainerLow)
        assertEquals(HuaweiColors.Surface, HuaweiLightColorScheme.surfaceContainerLowest)
        assertEquals(HuaweiColors.PrimaryContainer, HuaweiLightColorScheme.primaryFixed)
        assertEquals(HuaweiColors.SecondaryContainer, HuaweiLightColorScheme.secondaryFixed)
        assertEquals(HuaweiColors.WarningContainer, HuaweiLightColorScheme.tertiaryFixed)
    }

    @Test
    fun `system bar palette retains contrast for light icons`() {
        assertTrue(contrastRatio(Color.White, HuaweiColors.Primary) >= 4.5f)
    }

    @Test
    fun `interactive dimensions retain accessible touch target`() {
        assertEquals(48.dp, HuaweiDimensions.TouchTarget)
        assertEquals(12.dp, HuaweiDimensions.CornerSmall)
        assertEquals(14.dp, HuaweiDimensions.CornerControl)
        assertEquals(18.dp, HuaweiDimensions.CornerSurface)
        assertEquals(24.dp, HuaweiDimensions.CornerLarge)
    }

    @Test
    fun `material shapes map to the four redesign corner sizes`() {
        assertEquals(RoundedCornerShape(12.dp), HuaweiShapes.extraSmall)
        assertEquals(RoundedCornerShape(12.dp), HuaweiShapes.small)
        assertEquals(RoundedCornerShape(14.dp), HuaweiShapes.medium)
        assertEquals(RoundedCornerShape(18.dp), HuaweiShapes.large)
        assertEquals(RoundedCornerShape(24.dp), HuaweiShapes.extraLarge)
    }

    @Test
    fun `typography uses system sans with only redesign weights`() {
        val styles = listOf(
            HuaweiTypography.displayLarge,
            HuaweiTypography.displayMedium,
            HuaweiTypography.displaySmall,
            HuaweiTypography.headlineLarge,
            HuaweiTypography.headlineMedium,
            HuaweiTypography.headlineSmall,
            HuaweiTypography.titleLarge,
            HuaweiTypography.titleMedium,
            HuaweiTypography.titleSmall,
            HuaweiTypography.bodyLarge,
            HuaweiTypography.bodyMedium,
            HuaweiTypography.bodySmall,
            HuaweiTypography.labelLarge,
            HuaweiTypography.labelMedium,
            HuaweiTypography.labelSmall,
        )

        styles.forEach { style ->
            assertEquals(FontFamily.SansSerif, style.fontFamily)
            assertTrue(style.fontWeight == FontWeight.Normal || style.fontWeight == FontWeight.Medium)
        }
        assertEquals(22.sp, HuaweiTypography.titleLarge.fontSize)
        assertEquals(14.sp, HuaweiTypography.bodyMedium.fontSize)
        assertEquals(12.sp, HuaweiTypography.bodySmall.fontSize)
    }

    @Test
    fun `all custom vectors use the shared 24 dp viewport`() {
        val icons = listOf(
            HuaweiIcons.Back,
            HuaweiIcons.More,
            HuaweiIcons.Scale,
            HuaweiIcons.Charts,
            HuaweiIcons.Settings,
            HuaweiIcons.Success,
            HuaweiIcons.Pending,
            HuaweiIcons.Warning,
            HuaweiIcons.LocalDevice,
            HuaweiIcons.ChevronRight,
            HuaweiIcons.ChevronDown,
            HuaweiIcons.Calendar,
            HuaweiIcons.Tune,
            HuaweiIcons.Profile,
            HuaweiIcons.Link,
            HuaweiIcons.Bluetooth,
            HuaweiIcons.Lab,
            HuaweiIcons.Close,
            HuaweiIcons.Health,
            HuaweiIcons.Edit,
            HuaweiIcons.Delete,
            HuaweiIcons.Refresh,
            HuaweiIcons.Users,
            HuaweiIcons.HealthConnect,
            HuaweiIcons.HuaweiHealth,
            HuaweiIcons.Archive,
            HuaweiIcons.Stethoscope,
            HuaweiIcons.History,
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
