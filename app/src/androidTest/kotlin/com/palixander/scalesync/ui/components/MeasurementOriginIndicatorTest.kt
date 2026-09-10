package com.palixander.scalesync.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.palixander.scalesync.domain.MeasurementOrigin
import com.palixander.scalesync.ui.theme.ScaleSyncTheme
import org.junit.Rule
import org.junit.Test

class MeasurementOriginIndicatorTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun knownOriginsHaveAccessibleExplanationsAndRestoreFocus() {
        compose.setContent {
            ScaleSyncTheme {
                Column {
                    MeasurementOriginIndicator(MeasurementOrigin.MANUAL, Modifier.testTag("manual"))
                    MeasurementOriginIndicator(MeasurementOrigin.LEGACY, Modifier.testTag("legacy"))
                    MeasurementOriginIndicator(MeasurementOrigin.SCALE, Modifier.testTag("scale"))
                }
            }
        }
        compose.onNodeWithTag("legacy").assertDoesNotExist()
        compose.onNodeWithContentDescription("Источник: ручной ввод").assertHasClickAction().performClick()
        compose.onNodeWithText("Измерение введено вручную").assertIsDisplayed()
        compose.onNodeWithTag("measurement-origin-dismiss").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("manual").assertIsFocused()

        compose.onNodeWithContentDescription("Источник: весы").assertHasClickAction().performClick()
        compose.onNodeWithText("Измерение получено с весов").assertIsDisplayed()
        compose.onNodeWithTag("measurement-origin-dismiss").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("scale").assertIsFocused()
    }
}
