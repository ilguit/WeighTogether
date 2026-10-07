package com.palixander.weightogether

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.palixander.weightogether.backup.BackupImportMode
import com.palixander.weightogether.ui.theme.ScaleSyncTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BackupSettingsTest {
    @get:Rule val composeRule = createComposeRule()
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun backupScreenExplainsFormatsAndRoutesDistinctImportModes() {
        val requestedModes = mutableListOf<BackupImportMode>()
        var exports = 0
        composeRule.setContent {
            ScaleSyncTheme {
                SettingsScreen(
                    state = MainUiState(),
                    callbacks = SettingsCallbacks(
                        onHealthConnectAuthorization = {},
                        onHealthConnectAccessManagement = {},
                        onManualScan = {},
                        onReliabilityMode = {},
                        openBatterySettings = {},
                        openApplicationSettings = {},
                        onExportBackup = { exports++ },
                        onImportBackup = { requestedModes += it },
                    ),
                    contentPadding = PaddingValues(),
                    destination = SettingsDestination.BACKUP,
                )
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.settings_backup_intro)).assertIsDisplayed()
        composeRule.onNodeWithTag(SettingsScreenTestTags.BackupExport).performScrollTo().performClick()
        composeRule.onNodeWithTag(SettingsScreenTestTags.BackupMerge).performScrollTo().performClick()
        composeRule.onNodeWithTag(SettingsScreenTestTags.BackupReplace).performScrollTo().performClick()

        assertEquals(1, exports)
        assertEquals(listOf(BackupImportMode.MERGE, BackupImportMode.REPLACE), requestedModes)
    }
}
