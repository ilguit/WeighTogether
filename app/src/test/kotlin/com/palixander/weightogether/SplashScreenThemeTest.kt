package com.palixander.weightogether

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.util.TypedValue
import android.view.ContextThemeWrapper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 31, 35], application = Application::class)
class SplashScreenThemeTest {
    @Test
    @Config(qualifiers = "night")
    fun nightModeUsesTheSameDedicatedSplashAndReturnsToAppTheme() {
        launcherThemeUsesDedicatedSplashAndReturnsToAppTheme()
    }

    @Test
    fun launcherThemeUsesDedicatedSplashAndReturnsToAppTheme() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val activity = context.packageManager.getActivityInfo(
            ComponentName(context, MainActivity::class.java),
            0,
        )
        assertEquals(R.style.Theme_ScaleSync_Starting, activity.themeResource)
        val theme = ContextThemeWrapper(context, activity.themeResource).theme

        fun attributeResource(attribute: Int): Int {
            val value = TypedValue()
            assertTrue(theme.resolveAttribute(attribute, value, true))
            return value.resourceId
        }

        assertEquals(
            R.color.scalesync_background,
            attributeResource(androidx.core.splashscreen.R.attr.windowSplashScreenBackground),
        )
        val icon = attributeResource(androidx.core.splashscreen.R.attr.windowSplashScreenAnimatedIcon)
        assertEquals(R.drawable.ic_weigh_together_splash, icon)
        assertNotNull(context.getDrawable(icon))
        assertEquals(
            R.style.Theme_ScaleSync,
            attributeResource(androidx.core.splashscreen.R.attr.postSplashScreenTheme),
        )
    }
}
