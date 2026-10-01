package com.palixander.weightogether

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.palixander.weightogether.core.Sex
import com.palixander.weightogether.ui.text.UiText
import com.palixander.weightogether.ui.theme.ScaleSyncTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ProfileEditorScreenUiTest {
    @get:Rule val composeRule = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun normalEditorForwardsFieldChangesSexSelectionAndSave() {
        var state by mutableStateOf(fixture())
        val heights = mutableListOf<String>()
        val dates = mutableListOf<String>()
        val sexes = mutableListOf<Sex>()
        var saves = 0
        composeRule.setContent {
            ScaleSyncTheme {
                Column {
                    ProfileEditorScreen(
                        state = state,
                        onHeightChanged = { heights += it; state = state.copy(height = it) },
                        onBirthDateChanged = { dates += it; state = state.copy(birthDate = it) },
                        onSexChanged = { sexes += it; state = state.copy(sex = it) },
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.weight(1f),
                    )
                    ProfileEditorSaveBar(onSave = { saves++ })
                }
            }
        }
        composeRule.onNodeWithTag(SettingsScreenTestTags.ProfileEditor).assertIsDisplayed()
        composeRule.onNodeWithTag(SettingsScreenTestTags.ProfileEditorError).assertDoesNotExist()
        saveScreenshot("normal")
        composeRule.onNodeWithText(context.getString(R.string.settings_height_label))
            .performScrollTo().performTextReplacement("172")
        composeRule.onNodeWithText(context.getString(R.string.settings_birth_date_label))
            .performScrollTo().performTextReplacement("02.03.1990")
        composeRule.onNodeWithText(context.getString(R.string.settings_sex_male))
            .performScrollTo().assertIsSelected()
        composeRule.onNodeWithText(context.getString(R.string.settings_sex_female))
            .assertIsNotSelected().performClick().assertIsSelected()
        composeRule.onNodeWithText(context.getString(R.string.settings_sex_male)).assertIsNotSelected()
        composeRule.onNodeWithText(context.getString(R.string.settings_save_profile)).performClick()
        composeRule.runOnIdle {
            assertEquals(listOf("172"), heights)
            assertEquals(listOf("02.03.1990"), dates)
            assertEquals(listOf(Sex.FEMALE), sexes)
            assertEquals(1, saves)
        }
    }

    @Test
    fun errorEditorKeepsFieldsAndSaveReachableAtNarrowWidthAndLargeFont() {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                ScaleSyncTheme {
                    Column(Modifier.width(320.dp).fillMaxHeight()) {
                        ProfileEditorScreen(
                            state = fixture().copy(errorMessage = UiText.Raw("Fixture validation error")),
                            onHeightChanged = {},
                            onBirthDateChanged = {},
                            onSexChanged = {},
                            contentPadding = PaddingValues(0.dp),
                            modifier = Modifier.weight(1f),
                        )
                        ProfileEditorSaveBar(onSave = {})
                    }
                }
            }
        }
        composeRule.onNodeWithTag(SettingsScreenTestTags.ProfileEditorError)
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Fixture validation error").assertIsDisplayed()
        saveScreenshot("error-narrow-large-font")
        composeRule.onNodeWithText(context.getString(R.string.settings_height_label))
            .performScrollTo().assertIsDisplayed().assertTextContains("170")
        composeRule.onNodeWithText(context.getString(R.string.settings_birth_date_label))
            .performScrollTo().assertIsDisplayed().assertTextContains("01.01.1990")
        composeRule.onNodeWithText(context.getString(R.string.settings_sex_female))
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.settings_save_profile)).assertIsDisplayed()
        saveScreenshot("fields-narrow-large-font")
    }

    private fun fixture() = ProfileEditorUiState(
        isOpen = true,
        height = "170",
        birthDate = "01.01.1990",
        sex = Sex.MALE,
    )

    private fun saveScreenshot(name: String) {
        val output = File(context.getExternalFilesDir(null), "issue142").apply { mkdirs() }
        val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
        File(output, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
