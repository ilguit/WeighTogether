package com.palixander.scalesync.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import com.palixander.scalesync.ui.accounts.WeightDeltaEditorState
import com.palixander.scalesync.ui.accounts.WeightDeltaEditorTestTags
import com.palixander.scalesync.ui.accounts.WeightRecognitionSetting
import com.palixander.scalesync.ui.theme.ScaleSyncTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class WeightRecognitionSettingUiTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun completionCommitsWithoutVisibleSaveButtonAndAllowsRetryAfterFailure() {
        var state by mutableStateOf(WeightDeltaEditorState())
        val saved = mutableListOf<Double>()
        composeRule.setContent {
            ScaleSyncTheme {
                WeightRecognitionSetting(
                    state = state,
                    onStateChanged = { state = it },
                    onSave = {
                        saved += it
                        state = state.copy(isSaving = true)
                    },
                    ignoreUnknownMeasurements = false,
                    onIgnoreUnknownMeasurementsChanged = {},
                )
            }
        }

        composeRule.onNodeWithText("Сохранить").assertDoesNotExist()
        composeRule.onNodeWithTag(WeightDeltaEditorTestTags.Input)
            .performTextReplacement("4,5")
        composeRule.onNodeWithTag(WeightDeltaEditorTestTags.Input).performImeAction()
        composeRule.runOnIdle { assertEquals(listOf(4.5), saved) }

        composeRule.runOnIdle { state = state.copy(isSaving = false) }
        composeRule.onNodeWithTag(WeightDeltaEditorTestTags.Input).performClick()
        composeRule.onNodeWithTag(WeightDeltaEditorTestTags.Input).performImeAction()
        composeRule.runOnIdle { assertEquals(listOf(4.5, 4.5), saved) }
    }

    @Test
    fun leavingAValidEditedFieldCommitsButInvalidInputDoesNot() {
        var state by mutableStateOf(WeightDeltaEditorState())
        val saved = mutableListOf<Double>()
        composeRule.setContent {
            ScaleSyncTheme {
                WeightRecognitionSetting(
                    state = state,
                    onStateChanged = { state = it },
                    onSave = { saved += it },
                    ignoreUnknownMeasurements = false,
                    onIgnoreUnknownMeasurementsChanged = {},
                )
            }
        }

        composeRule.onNodeWithTag(WeightDeltaEditorTestTags.Input)
            .performTextReplacement("2,5")
        composeRule.onNodeWithTag(WeightDeltaEditorTestTags.IgnoreUnknown).performClick()
        composeRule.runOnIdle { assertEquals(listOf(2.5), saved) }

        composeRule.onNodeWithTag(WeightDeltaEditorTestTags.Input)
            .performTextReplacement("нет")
        composeRule.onNodeWithTag(WeightDeltaEditorTestTags.IgnoreUnknown).performClick()
        composeRule.runOnIdle { assertEquals(listOf(2.5), saved) }
    }
}
