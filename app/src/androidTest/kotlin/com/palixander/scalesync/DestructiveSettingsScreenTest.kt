package com.palixander.scalesync

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.palixander.scalesync.data.AppSettings
import org.junit.Rule
import org.junit.Test

class DestructiveSettingsScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun selectedScaleShowsSeparatedDestructiveActionAndRequiresConfirmation() {
        compose.setContent {
            SettingsScreen(
                state = MainUiState(
                    settings = AppSettings(
                        scaleAddress = "AA:BB:CC:DD:EE:FF",
                        scaleName = "MIBFS",
                    ),
                ),
                callbacks = callbacks(),
                contentPadding = PaddingValues(),
                destination = SettingsDestination.SCALE,
            )
        }

        compose.onNodeWithTag(SettingsScreenTestTags.ForgetScale).performClick()
        compose.onNodeWithTag(SettingsScreenTestTags.DestructiveDialog).assertExists()
        compose.onNodeWithTag(SettingsScreenTestTags.DestructiveConfirm).assertExists()
    }

    @Test
    fun noConnectedTargetHidesDestructiveSection() {
        compose.setContent {
            SettingsScreen(
                MainUiState(),
                callbacks(),
                PaddingValues(),
                destination = SettingsDestination.SCALE,
            )
        }

        compose.onNodeWithTag(SettingsScreenTestTags.DestructiveSection).assertDoesNotExist()
        compose.onNodeWithTag(SettingsScreenTestTags.ForgetScale).assertDoesNotExist()
    }

    private fun callbacks() = SettingsCallbacks(
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
