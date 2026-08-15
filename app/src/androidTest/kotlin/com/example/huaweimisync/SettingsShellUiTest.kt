package com.example.huaweimisync

import androidx.activity.ComponentActivity
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import com.example.huaweimisync.core.Sex
import com.example.huaweimisync.core.UserProfile
import com.example.huaweimisync.data.AppSettings
import java.time.LocalDate
import org.junit.Rule
import org.junit.Test

class SettingsShellUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun profileRowOpensFullscreenEditorAndBackOrCloseReturnsToSettings() {
        setSettingsShell()

        composeRule.onNodeWithTag(SettingsScreenTestTags.ProfileRow).performClick()

        composeRule.onNodeWithTag(SettingsScreenTestTags.ProfileEditor).assertIsDisplayed()
        composeRule.onNodeWithTag(SettingsScreenTestTags.ProfileRow).assertDoesNotExist()
        composeRule.onNodeWithTag(MainScreenTestTags.BottomNavigation).assertDoesNotExist()
        composeRule.onNodeWithTag(MainScreenTestTags.SnackbarHost).assertExists()

        composeRule.runOnIdle {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }

        composeRule.onNodeWithTag(SettingsScreenTestTags.ProfileEditor).assertDoesNotExist()
        composeRule.onNodeWithTag(SettingsScreenTestTags.ProfileRow).assertIsDisplayed()
        composeRule.onNodeWithTag(MainScreenTestTags.BottomNavigation).assertIsDisplayed()

        composeRule.onNodeWithTag(SettingsScreenTestTags.ProfileRow).performClick()
        composeRule.onNodeWithContentDescription("Закрыть редактор профиля").performClick()

        composeRule.onNodeWithTag(SettingsScreenTestTags.ProfileEditor).assertDoesNotExist()
        composeRule.onNodeWithTag(SettingsScreenTestTags.ProfileRow).assertIsDisplayed()
        composeRule.onNodeWithTag(MainScreenTestTags.BottomNavigation).assertIsDisplayed()
    }

    @Test
    fun invalidProfileSaveKeepsEditorOpenAndShowsError() {
        setSettingsShell()
        composeRule.onNodeWithTag(SettingsScreenTestTags.ProfileRow).performClick()

        composeRule.onNode(hasSetTextAction() and hasText("1988-02-29"))
            .performTextReplacement("29.02.1988")
        composeRule.onNodeWithText("Сохранить профиль").performClick()

        composeRule.onNodeWithTag(SettingsScreenTestTags.ProfileEditor).assertIsDisplayed()
        composeRule.onNodeWithTag(SettingsScreenTestTags.ProfileEditorError).assertIsDisplayed()
        composeRule.onNodeWithText(PROFILE_FORMAT_ERROR_MESSAGE).assertIsDisplayed()
        composeRule.onNodeWithTag(MainScreenTestTags.BottomNavigation).assertDoesNotExist()
        composeRule.onNodeWithTag(MainScreenTestTags.SnackbarHost).assertExists()
    }

    @Test
    fun additionalToggleRevealsExistingActions() {
        setSettingsShell()

        composeRule.onNodeWithTag(SettingsScreenTestTags.AdditionalContent).assertDoesNotExist()
        composeRule.onNodeWithTag(SettingsScreenTestTags.AdditionalToggle)
            .performScrollTo()
            .performClick()

        composeRule.onNodeWithTag(SettingsScreenTestTags.AdditionalContent).assertExists()
        composeRule.onNodeWithText("Отправить тест").assertExists()
        composeRule.onNodeWithText("Повышенная надёжность").assertExists()
        composeRule.onNodeWithText("Батарея").assertExists()
        composeRule.onNodeWithText("Настройки приложения").assertExists()
    }

    private fun setSettingsShell() {
        val profile = UserProfile(
            heightCm = 181.5,
            birthDate = LocalDate.of(1988, 2, 29),
            sex = Sex.FEMALE,
        )
        val eventEmitter = MainUiEventEmitter()
        val editorController = ProfileEditorController(
            saveProfile = {},
            eventEmitter = eventEmitter,
            today = { LocalDate.of(2026, 8, 15) },
        )

        composeRule.setContent {
            val editorState by editorController.state.collectAsState()
            val snackbarHostState = remember { SnackbarHostState() }
            HuaweiMiSyncScaffold(
                state = MainUiState(
                    settings = AppSettings(profile = profile),
                    profileEditor = editorState,
                ),
                currentSection = AppSection.SETTINGS,
                measurementsChrome = MeasurementsChrome(
                    showTopBar = true,
                    showBottomNavigation = true,
                ),
                snackbarHostState = snackbarHostState,
                onSectionSelected = {},
                onCloseProfile = editorController::close,
                onSaveProfile = editorController::save,
                onProfileHeightChanged = editorController::updateHeight,
                onProfileBirthDateChanged = editorController::updateBirthDate,
                onProfileSexChanged = editorController::updateSex,
                settingsCallbacks = settingsCallbacks(
                    onOpenProfile = { editorController.open(profile) },
                ),
                measurementsContent = {},
                chartsContent = {},
            )
        }
    }

    private fun settingsCallbacks(onOpenProfile: () -> Unit) = SettingsCallbacks(
        onOpenProfile = onOpenProfile,
        onHuaweiAuthorization = {},
        onHuaweiPermissionRefresh = {},
        onHealthConnectAuthorization = {},
        onHealthConnectAccessManagement = {},
        onManualTest = { _, _ -> },
        onManualScan = {},
        onReliabilityMode = {},
        openBatterySettings = {},
        openApplicationSettings = {},
    )
}
