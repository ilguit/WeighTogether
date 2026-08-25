package com.example.huaweimisync

import com.example.huaweimisync.backup.BackupImportMode
import com.example.huaweimisync.backup.BackupImportCounts
import com.example.huaweimisync.backup.BackupImportPreview
import com.example.huaweimisync.backup.BackupDatabaseSnapshot
import com.example.huaweimisync.data.AppStateEntity
import com.example.huaweimisync.data.PortableProfileSettings
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
        )
        val state = BackupUiState(preview = preview)

        assertEquals(BackupImportMode.REPLACE, state.preview?.mode)
        assertFalse(state.replaceConfirmationRequested)
        assertTrue(state.copy(replaceConfirmationRequested = true).replaceConfirmationRequested)
    }
}
