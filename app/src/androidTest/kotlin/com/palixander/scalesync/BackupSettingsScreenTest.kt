package com.palixander.scalesync

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.palixander.scalesync.backup.BackupAppStateV1
import com.palixander.scalesync.backup.BackupDatabaseSnapshot
import com.palixander.scalesync.backup.BackupDocumentV1
import com.palixander.scalesync.backup.BackupImportBaselineToken
import com.palixander.scalesync.backup.BackupImportCounts
import com.palixander.scalesync.backup.BackupImportMode
import com.palixander.scalesync.backup.BackupImportPreview
import com.palixander.scalesync.backup.BackupSettingsV1
import com.palixander.scalesync.data.AppStateEntity
import com.palixander.scalesync.data.PortableProfileSettings
import org.junit.Rule
import org.junit.Test

class BackupSettingsScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun backupActionsHaveStableAccessibilityTags() {
        compose.setContent {
            val destination = remember { mutableStateOf(SettingsDestination.ROOT) }
            SettingsScreen(MainUiState(), SettingsCallbacks(
                onHuaweiAuthorization = {}, onHuaweiPermissionRefresh = {},
                onHealthConnectAuthorization = {}, onHealthConnectAccessManagement = {},
                onManualScan = {}, onReliabilityMode = {},
                openBatterySettings = {}, openApplicationSettings = {},
            ), PaddingValues(), destination.value, { destination.value = it })
        }
        compose.onNodeWithTag(SettingsScreenTestTags.BackupRow).performClick()
        compose.onNodeWithTag(SettingsScreenTestTags.BackupExport).assertExists()
        compose.onNodeWithTag(SettingsScreenTestTags.BackupMerge).assertExists()
        compose.onNodeWithTag(SettingsScreenTestTags.BackupReplace).assertExists()
        compose.onNodeWithTag(SettingsScreenTestTags.BackupSaveGroup).assertExists()
        compose.onNodeWithTag(SettingsScreenTestTags.BackupRestoreGroup).assertExists()
        compose.onNodeWithTag(SettingsScreenTestTags.BackupRestoreDivider).assertExists()
    }

    @Test
    fun busyBackupKeepsGroupsAndDisablesEveryAction() {
        compose.setContent {
            SettingsScreen(
                MainUiState(backup = BackupUiState(inProgress = true)),
                callbacks(),
                PaddingValues(),
                destination = SettingsDestination.BACKUP,
            )
        }

        compose.onNodeWithTag(SettingsScreenTestTags.BackupSaveGroup).assertExists()
        compose.onNodeWithTag(SettingsScreenTestTags.BackupRestoreGroup).assertExists()
        compose.onNodeWithTag(SettingsScreenTestTags.BackupExport).assertIsNotEnabled()
        compose.onNodeWithTag(SettingsScreenTestTags.BackupMerge).assertIsNotEnabled()
        compose.onNodeWithTag(SettingsScreenTestTags.BackupReplace).assertIsNotEnabled()
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
                destination = SettingsDestination.BACKUP,
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
        onManualScan = {},
        onReliabilityMode = {},
        openBatterySettings = {},
        openApplicationSettings = {},
    )
}
