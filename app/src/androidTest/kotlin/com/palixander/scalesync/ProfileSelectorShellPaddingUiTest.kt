package com.palixander.scalesync

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.palixander.scalesync.measurements.MeasurementsCallbacks
import com.palixander.scalesync.measurements.MeasurementsDestination
import com.palixander.scalesync.ui.profiles.ProfileSelectionUiState
import com.palixander.scalesync.ui.profiles.ProfileSelectorTestTags
import com.palixander.scalesync.ui.theme.HuaweiDimensions
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

class ProfileSelectorShellPaddingUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun measurementsContentStartsImmediatelyAfterProfileSelector() {
        setShell(AppSection.MEASUREMENTS)

        assertContentStartsAfterSelector("measurements-content")
    }

    @Test
    fun chartsContentStartsImmediatelyAfterProfileSelector() {
        setShell(AppSection.CHARTS)

        assertContentStartsAfterSelector("charts-content")
    }

    @Test
    fun settingsDoesNotShowProfileSelector() {
        setShell(AppSection.SETTINGS)

        composeRule.onNodeWithTag(ProfileSelectorTestTags.Selector).assertDoesNotExist()
        composeRule.onNodeWithTag(SettingsScreenTestTags.List).assertExists()
    }

    @Test
    fun measurementHistoryDoesNotShowProfileSelector() {
        setShell(
            section = AppSection.MEASUREMENTS,
            destination = MeasurementsDestination.HISTORY,
        )

        composeRule.onNodeWithTag(ProfileSelectorTestTags.Selector).assertDoesNotExist()
        composeRule.onNodeWithTag("measurements-content").getUnclippedBoundsInRoot()
    }

    private fun assertContentStartsAfterSelector(contentTag: String) {
        val selectorBottom = composeRule.onNodeWithTag(ProfileSelectorTestTags.Selector)
            .getUnclippedBoundsInRoot().bottom
        val contentTop = composeRule.onNodeWithTag(contentTag)
            .getUnclippedBoundsInRoot().top
        val actualPaddingPx = with(composeRule.density) {
            (contentTop - selectorBottom).toPx().roundToInt()
        }
        val expectedPaddingPx = with(composeRule.density) {
            HuaweiDimensions.CompactContentPadding.roundToPx()
        }

        assertTrue(
            "Expected content padding within 1 px of $expectedPaddingPx px, " +
                "but was $actualPaddingPx px",
            abs(actualPaddingPx - expectedPaddingPx) <= 1,
        )
    }

    private fun setShell(
        section: AppSection,
        destination: MeasurementsDestination = MeasurementsDestination.SUMMARY,
    ) {
        composeRule.setContent {
            ScaleSyncScaffold(
                state = MainUiState(),
                currentSection = section,
                profileSelection = ProfileSelectionUiState(
                    profiles = emptyList(),
                    selectedKey = null,
                    primaryAccountId = null,
                ),
                measurementsDestination = destination,
                measurementsCallbacks = MeasurementsCallbacks.None,
                snackbarHostState = remember { SnackbarHostState() },
                onSectionSelected = {},
                onCloseProfile = {},
                onSaveProfile = {},
                onProfileHeightChanged = {},
                onProfileBirthDateChanged = {},
                onProfileSexChanged = {},
                settingsCallbacks = settingsCallbacks(),
                measurementsContent = { padding ->
                    Box(
                        Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .testTag("measurements-content"),
                    )
                },
                chartsContent = { padding ->
                    Box(
                        Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .testTag("charts-content"),
                    )
                },
            )
        }
    }

    private fun settingsCallbacks() = SettingsCallbacks(
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
