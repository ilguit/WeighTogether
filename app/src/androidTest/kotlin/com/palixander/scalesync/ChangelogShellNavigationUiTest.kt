package com.palixander.scalesync

import androidx.activity.ComponentActivity
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.palixander.scalesync.changelog.ChangelogScreenTestTags
import com.palixander.scalesync.measurements.MeasurementsCallbacks
import com.palixander.scalesync.measurements.MeasurementsDestination
import org.junit.Rule
import org.junit.Test

class ChangelogShellNavigationUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun settingsRowOpensChangelogWithOwnedChrome() {
        setSettingsShell()

        openChangelog()

        composeRule.onNodeWithTag(ChangelogScreenTestTags.List).assertIsDisplayed()
        composeRule.onNodeWithText("История изменений").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Вернуться к настройкам").assertIsDisplayed()
        composeRule.onNodeWithTag(MainScreenTestTags.BottomNavigation).assertDoesNotExist()
    }

    @Test
    fun toolbarBackRestoresSettingsAndRootChrome() {
        setSettingsShell()
        openChangelog()

        composeRule.onNodeWithContentDescription("Вернуться к настройкам").performClick()

        assertSettingsChromeRestored()
    }

    @Test
    fun systemBackRestoresSettingsAndRootChrome() {
        setSettingsShell()
        openChangelog()

        composeRule.runOnIdle {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }

        assertSettingsChromeRestored()
    }

    private fun openChangelog() {
        composeRule.onNodeWithTag(SettingsScreenTestTags.List).performScrollToNode(
            hasTestTag(SettingsScreenTestTags.ChangelogRow),
        )
        composeRule.onNodeWithTag(SettingsScreenTestTags.ChangelogRow).performClick()
    }

    private fun assertSettingsChromeRestored() {
        composeRule.onNodeWithTag(ChangelogScreenTestTags.List).assertDoesNotExist()
        composeRule.onNodeWithTag(MainScreenTestTags.TopBarTitle)
            .assertTextEquals("Настройки").assertIsDisplayed()
        composeRule.onNodeWithTag(SettingsScreenTestTags.List).assertIsDisplayed()
        composeRule.onNodeWithTag(MainScreenTestTags.BottomNavigation).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Вернуться к настройкам").assertDoesNotExist()
    }

    private fun setSettingsShell() {
        val destination = mutableStateOf(AppDestination.ROOT)
        composeRule.setContent {
            ScaleSyncScaffold(
                state = MainUiState(),
                currentSection = AppSection.SETTINGS,
                currentDestination = destination.value,
                measurementsDestination = MeasurementsDestination.SUMMARY,
                measurementsCallbacks = MeasurementsCallbacks.None,
                snackbarHostState = remember { SnackbarHostState() },
                onSectionSelected = {},
                onDestinationChanged = { destination.value = it },
                onCloseProfile = {},
                onSaveProfile = {},
                onProfileHeightChanged = {},
                onProfileBirthDateChanged = {},
                onProfileSexChanged = {},
                settingsCallbacks = settingsCallbacks(
                    onOpenChangelog = { destination.value = AppDestination.CHANGELOG },
                ),
                measurementsContent = { _, _ -> },
                chartsContent = {},
            )
        }
    }

    private fun settingsCallbacks(onOpenChangelog: () -> Unit) = SettingsCallbacks(
        onOpenChangelog = onOpenChangelog,
        onHealthConnectAuthorization = {},
        onHealthConnectAccessManagement = {},
        onManualScan = {},
        onReliabilityMode = {},
        openBatterySettings = {},
        openApplicationSettings = {},
    )
}
