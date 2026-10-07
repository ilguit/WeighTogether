package com.palixander.weightogether

import com.palixander.weightogether.backup.BackupImportMode
import com.palixander.weightogether.backup.BackupImportCounts
import com.palixander.weightogether.backup.BackupImportPreview
import com.palixander.weightogether.backup.BackupDatabaseSnapshot
import com.palixander.weightogether.backup.BackupAppStateV1
import com.palixander.weightogether.backup.BackupDocumentV1
import com.palixander.weightogether.backup.BackupImportBaselineToken
import com.palixander.weightogether.backup.BackupSettingsV1
import com.palixander.weightogether.data.AppStateEntity
import com.palixander.weightogether.data.PortableProfileSettings
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupUiContractTest {
    @Test
    fun `backup filename contains ISO date`() {
        assertEquals("weigh-together-backup-2026-08-25.wtrn", defaultBackupFileName(LocalDate.of(2026, 8, 25)))
    }

    @Test
    fun `replace preview remains explicit in UI state`() {
        val preview = BackupImportPreview(
            mode = BackupImportMode.REPLACE,
            counts = BackupImportCounts(0, 0, 2, 0, 0, 12),
            result = BackupDatabaseSnapshot(emptyList(), AppStateEntity(), emptyList()),
            settings = PortableProfileSettings(null, null, false, null, null),
            sourceDocument = BackupDocumentV1(
                exportedAt = "2026-08-25T00:00:00Z",
                accounts = emptyList(),
                appState = BackupAppStateV1(null, 0.0, false),
                measurements = emptyList(),
                settings = BackupSettingsV1(null, null, false, null, null),
            ),
            baselineToken = BackupImportBaselineToken(
                BackupDatabaseSnapshot(emptyList(), AppStateEntity(), emptyList()),
                PortableProfileSettings(null, null, false, null, null),
                0L,
            ),
        )
        val state = BackupUiState(preview = preview)

        assertEquals(BackupImportMode.REPLACE, state.preview?.mode)
        assertFalse(state.replaceConfirmationRequested)
        assertTrue(state.copy(replaceConfirmationRequested = true).replaceConfirmationRequested)
    }
}
