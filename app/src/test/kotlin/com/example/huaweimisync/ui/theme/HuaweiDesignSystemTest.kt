package com.example.huaweimisync.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.huaweimisync.ui.icons.HuaweiIcons
import org.junit.Assert.assertEquals
import org.junit.Test

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
    fun `interactive dimensions retain accessible touch target`() {
        assertEquals(48.dp, HuaweiDimensions.TouchTarget)
        assertEquals(12.dp, HuaweiDimensions.CornerSmall)
        assertEquals(14.dp, HuaweiDimensions.CornerControl)
        assertEquals(18.dp, HuaweiDimensions.CornerSurface)
        assertEquals(24.dp, HuaweiDimensions.CornerLarge)
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
        )

        icons.forEach { icon ->
            assertEquals(24.dp, icon.defaultWidth)
            assertEquals(24.dp, icon.defaultHeight)
            assertEquals(24f, icon.viewportWidth)
            assertEquals(24f, icon.viewportHeight)
        }
    }
}
