package com.palixander.weightogether.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.palixander.weightogether.domain.MeasurementOrigin
import com.palixander.weightogether.ui.theme.ScaleSyncTheme
import org.junit.Rule
import org.junit.Test

class ManualOriginIndicatorTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun onlyManualOriginHasAccessibleExplanationAndRestoresFocus() {
        compose.setContent {
            ScaleSyncTheme {
                Column {
                    ManualOriginIndicator(MeasurementOrigin.MANUAL, Modifier.testTag("manual"))
                    ManualOriginIndicator(MeasurementOrigin.LEGACY, Modifier.testTag("legacy"))
                    ManualOriginIndicator(MeasurementOrigin.SCALE, Modifier.testTag("scale"))
                }
            }
        }
        compose.onNodeWithTag("legacy").assertDoesNotExist()
        compose.onNodeWithTag("scale").assertDoesNotExist()
        compose.onNodeWithContentDescription("Введено вручную").assertHasClickAction().performClick()
        compose.onNodeWithText("Введено вручную").assertIsDisplayed()
        compose.onNodeWithTag("manual-origin-dismiss").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("manual").assertIsFocused()
    }
}
