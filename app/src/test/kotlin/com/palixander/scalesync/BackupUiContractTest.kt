package com.palixander.scalesync

import com.palixander.scalesync.backup.BackupImportMode
import com.palixander.scalesync.backup.BackupImportCounts
import com.palixander.scalesync.backup.BackupImportPreview
import com.palixander.scalesync.backup.BackupDatabaseSnapshot
import com.palixander.scalesync.backup.BackupAppStateV1
import com.palixander.scalesync.backup.BackupDocumentV1
import com.palixander.scalesync.backup.BackupImportBaselineToken
import com.palixander.scalesync.backup.BackupSettingsV1
import com.palixander.scalesync.data.AppStateEntity
import com.palixander.scalesync.data.PortableProfileSettings
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupUiContractTest {
    @Test
    fun `backup filename contains ISO date`() {
        assertEquals("scalesync-backup-2026-08-25.json", defaultBackupFileName(LocalDate.of(2026, 8, 25)))
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
