package com.example.huaweimisync

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Rule
import org.junit.Test

class BackupSettingsScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun backupActionsHaveStableAccessibilityTags() {
        compose.setContent {
            SettingsScreen(MainUiState(), SettingsCallbacks(
                onHuaweiAuthorization = {}, onHuaweiPermissionRefresh = {},
                onHealthConnectAuthorization = {}, onHealthConnectAccessManagement = {},
                onManualTest = { _, _ -> }, onManualScan = {}, onReliabilityMode = {},
                openBatterySettings = {}, openApplicationSettings = {},
            ), PaddingValues())
        }
        compose.onNodeWithTag(SettingsScreenTestTags.BackupExport).assertExists()
        compose.onNodeWithTag(SettingsScreenTestTags.BackupMerge).assertExists()
        compose.onNodeWithTag(SettingsScreenTestTags.BackupReplace).assertExists()
    }
}
