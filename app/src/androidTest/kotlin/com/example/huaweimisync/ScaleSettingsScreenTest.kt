package com.example.huaweimisync

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.example.huaweimisync.data.AppSettings
import org.junit.Rule
import org.junit.Test

class ScaleSettingsScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun checkingShowsProgressWithoutStopOrDestructiveAction() {
        show(MainUiState(settings = selectedScale(), scanning = true))
        compose.onNodeWithTag(SettingsScreenTestTags.ScaleStatus).assertTextContains("Идёт поиск")
        compose.onNodeWithTag(SettingsScreenTestTags.ScaleProgress).assertExists()
        compose.onNodeWithTag(SettingsScreenTestTags.ScaleAction)
            .assertTextContains("Поиск…")
            .assertIsNotEnabled()
        compose.onNodeWithTag(SettingsScreenTestTags.ForgetScale).assertDoesNotExist()
    }

    @Test fun readyEnablesSelectionAndShowsDestructiveAction() {
        show(MainUiState(settings = selectedScale()))
        compose.onNodeWithTag(SettingsScreenTestTags.ScaleStatus).assertTextContains("MIBFS · AA:BB")
        compose.onNodeWithTag(SettingsScreenTestTags.ScaleAction).assertIsEnabled()
        compose.onNodeWithTag(SettingsScreenTestTags.ForgetScale).assertExists()
    }

    @Test fun unavailablePermissionShowsRecoveryAndHidesDestructiveAction() {
        show(MainUiState(settings = selectedScale(), scaleAvailability = ScaleAvailability.PERMISSION_REQUIRED))
        compose.onNodeWithTag(SettingsScreenTestTags.ScaleStatus).assertTextContains("Нет разрешения")
        compose.onNodeWithTag(SettingsScreenTestTags.ScaleAction).assertTextContains("Открыть настройки")
        compose.onNodeWithTag(SettingsScreenTestTags.ForgetScale).assertDoesNotExist()
    }

    @Test fun errorOffersRetryAndHidesDestructiveAction() {
        show(MainUiState(settings = selectedScale(), scaleScanError = "Ошибка поиска"))
        compose.onNodeWithTag(SettingsScreenTestTags.ScaleAction).assertTextContains("Повторить")
        compose.onNodeWithTag(SettingsScreenTestTags.ForgetScale).assertDoesNotExist()
    }

    private fun show(state: MainUiState) = compose.setContent {
        SettingsScreen(state, callbacks(), PaddingValues(), destination = SettingsDestination.SCALE)
    }

    private fun selectedScale() = AppSettings(scaleAddress = "AA:BB", scaleName = "MIBFS")

    private fun callbacks() = SettingsCallbacks(
        onHuaweiAuthorization = {}, onHuaweiPermissionRefresh = {},
        onHealthConnectAuthorization = {}, onHealthConnectAccessManagement = {},
        onManualTest = { _, _ -> }, onManualScan = {}, onReliabilityMode = {},
        openBatterySettings = {}, openApplicationSettings = {},
    )
}
