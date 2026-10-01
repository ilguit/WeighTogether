package com.palixander.weightogether

import android.util.TypedValue
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityLaunchTest {
    @Test
    fun activityReachesResumedWithoutAccessingViewModelBeforeOnCreate() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
        }
    }

    @Test
    fun launchAndRecreationRestoreAppThemeAfterSplash() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            fun assertAppTheme() {
                assertEquals(Lifecycle.State.RESUMED, scenario.state)
                scenario.onActivity { activity ->
                    val accent = TypedValue()
                    assertTrue(activity.theme.resolveAttribute(android.R.attr.colorAccent, accent, true))
                    assertEquals(activity.getColor(R.color.scalesync_primary), accent.data)
                    val background = TypedValue()
                    assertTrue(activity.theme.resolveAttribute(android.R.attr.windowBackground, background, true))
                    assertEquals(R.color.scalesync_background, background.resourceId)
                }
            }

            assertAppTheme()
            scenario.recreate()
            assertAppTheme()
        }
    }
}
