package com.palixander.scalesync.measurements

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.palixander.scalesync.ui.components.ScaleSyncIconButton
import com.palixander.scalesync.ui.components.ScaleSyncStatusTone
import com.palixander.scalesync.ui.icons.ScaleSyncIcons
import com.palixander.scalesync.ui.theme.ScaleSyncTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SummaryHeaderUiTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun narrowAndLargeTextKeepHistorySeparateFromRightActions() {
        val width = mutableStateOf(320.dp)
        val fontScale = mutableStateOf(1f)
        val tone = mutableStateOf(ScaleSyncStatusTone.Error)
        var historyCalls = 0
        var syncCalls = 0
        var moreCalls = 0
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale.value)) {
                ScaleSyncTheme {
                    Box(Modifier.width(width.value)) {
                        SummaryHeader(
                            date = { Text("12 сентября 2026, 23:59", Modifier.testTag("date")) },
                            history = {
                                TextButton(onClick = { historyCalls++ }, modifier = Modifier.testTag("history")) {
                                    Text("История →")
                                }
                            },
                            actions = {
                                Row {
                                    SummaryStatusAction(ScaleSyncIcons.Warning, "Статус", { syncCalls++ }, tone.value, true, Modifier.testTag("sync"))
                                    ScaleSyncIconButton(ScaleSyncIcons.More, "Действия", { moreCalls++ }, Modifier.testTag("more"))
                                }
                            },
                        )
                    }
                }
            }
        }
        for (testWidth in listOf(320.dp, 200.dp)) {
            for (scale in listOf(1f, 2f)) {
                for (status in ScaleSyncStatusTone.entries) {
                    composeRule.runOnIdle { width.value = testWidth; fontScale.value = scale; tone.value = status }
                    val header = composeRule.onNodeWithTag("summary-header").getUnclippedBoundsInRoot()
                    val date = composeRule.onNodeWithTag("date").getUnclippedBoundsInRoot()
                    val history = composeRule.onNodeWithTag("history").assertIsDisplayed().getUnclippedBoundsInRoot()
                    val sync = composeRule.onNodeWithTag("sync").assertIsDisplayed().getUnclippedBoundsInRoot()
                    val more = composeRule.onNodeWithTag("more").assertIsDisplayed().getUnclippedBoundsInRoot()
                    assertTrue(date.bottom <= history.top)
                    assertTrue(history.right <= sync.left)
                    assertTrue(sync.right <= more.left)
                    assertEquals(header.right, more.right)
                    // Density conversion rounds the 48 dp layout size to whole physical pixels.
                    assertTrue("48 dp status target: $sync", sync.right - sync.left >= 47.5.dp && sync.bottom - sync.top >= 47.5.dp)
                    assertTrue(history.left >= header.left && more.bottom <= header.bottom)
                }
            }
        }
        composeRule.onNodeWithTag("history").performClick()
        composeRule.onNodeWithTag("sync").performClick()
        composeRule.onNodeWithTag("more").performClick()
        composeRule.runOnIdle {
            assertEquals(1, historyCalls)
            assertEquals(1, syncCalls)
            assertEquals(1, moreCalls)
        }
    }
}
