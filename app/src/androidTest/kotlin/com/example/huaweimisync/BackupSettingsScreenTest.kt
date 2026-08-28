package com.example.huaweimisync

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.huaweimisync.backup.BackupAppStateV1
import com.example.huaweimisync.backup.BackupDatabaseSnapshot
import com.example.huaweimisync.backup.BackupDocumentV1
import com.example.huaweimisync.backup.BackupImportBaselineToken
import com.example.huaweimisync.backup.BackupImportCounts
import com.example.huaweimisync.backup.BackupImportMode
import com.example.huaweimisync.backup.BackupImportPreview
import com.example.huaweimisync.backup.BackupSettingsV1
import com.example.huaweimisync.data.AppStateEntity
import com.example.huaweimisync.data.PortableProfileSettings
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
        compose.onNodeWithTag(SettingsScreenTestTags.BackupSection).performClick()
        compose.onNodeWithTag(SettingsScreenTestTags.BackupExport).assertExists()
        compose.onNodeWithTag(SettingsScreenTestTags.BackupMerge).assertExists()
        compose.onNodeWithTag(SettingsScreenTestTags.BackupReplace).assertExists()
    }

    @Test
    fun replaceConfirmationNamesHumanAndPetData() {
        compose.setContent {
            SettingsScreen(
                MainUiState(
                    backup = BackupUiState(
                        preview = replacePreview(),
                        replaceConfirmationRequested = true,
                    ),
                ),
                callbacks(),
                PaddingValues(),
            )
        }

        compose.onNodeWithTag(SettingsScreenTestTags.BackupDialog).assertExists()
        compose.onNodeWithText(BACKUP_REPLACE_WARNING).assertExists()
    }

    private fun replacePreview(): BackupImportPreview {
        val settings = PortableProfileSettings(null, null, false, null, null)
        val database = BackupDatabaseSnapshot(emptyList(), AppStateEntity(), emptyList())
        return BackupImportPreview(
            mode = BackupImportMode.REPLACE,
            counts = BackupImportCounts(0, 0, 0, 0, 0, 0),
            result = database,
            settings = settings,
            sourceDocument = BackupDocumentV1(
                exportedAt = "2026-08-26T00:00:00Z",
                accounts = emptyList(),
                appState = BackupAppStateV1(null, 0.0, false),
                measurements = emptyList(),
                settings = BackupSettingsV1(null, null, false, null, null),
            ),
            baselineToken = BackupImportBaselineToken(database, settings, 0L),
        )
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
