package com.palixander.weightogether

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import com.palixander.weightogether.backup.BackupImportMode
import com.palixander.weightogether.ui.text.UserFacingUiTextException
import com.palixander.weightogether.ui.text.uiText
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BackupDocumentsTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val uri = Uri.parse("content://backup-test/document/opaque-id.zip")
    private lateinit var provider: BackupProvider
    private lateinit var documents: BackupDocuments

    @Before
    fun setUp() {
        provider = BackupProvider(temporaryFolder.newFile())
        provider.attachInfo(context, ProviderInfo().apply { authority = "backup-test" })
        ShadowContentResolver.registerProviderInternal("backup-test", provider)
        documents = BackupDocuments(context.contentResolver)
    }

    @Test
    fun createUsesCustomMimeAndWtrnTitleWithoutZipExtension() {
        val name = defaultBackupFileName(java.time.LocalDate.of(2026, 10, 7))
        val intent = CreateBackupDocument().createIntent(context, name)

        assertEquals(Intent.ACTION_CREATE_DOCUMENT, intent.action)
        assertTrue(intent.hasCategory(Intent.CATEGORY_OPENABLE))
        assertEquals(BACKUP_ARCHIVE_MIME_TYPE, intent.type)
        assertEquals("weigh-together-backup-2026-10-07.wtrn", intent.getStringExtra(Intent.EXTRA_TITLE))
    }

    @Test
    fun importPickerAcceptsProviderMimeVariantsAndKeepsCancellation() {
        val contract = OpenBackupDocument()
        val intent = contract.createIntent(context, Unit)

        assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent.action)
        assertTrue(intent.hasCategory(Intent.CATEGORY_OPENABLE))
        assertEquals("*/*", intent.type)
        assertEquals(
            setOf(
                "application/json", BACKUP_ARCHIVE_MIME_TYPE, "application/zip",
                "application/x-zip-compressed", "application/octet-stream",
            ),
            intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)?.toSet(),
        )
        assertEquals(uri, contract.parseResult(Activity.RESULT_OK, Intent().setData(uri)))
        assertNull(contract.parseResult(Activity.RESULT_CANCELED, Intent().setData(uri)))
    }

    @Test
    fun importUsesDisplayNameNotOpaqueUriAndAcceptsCaseInsensitiveExtensions() {
        provider.file.writeText("backup data")
        listOf("old.json", "OLD.JSON", "new.wtrn", "NEW.WtRn").forEach { name ->
            provider.displayName = name
            assertEquals("backup data", documents.openImport(uri).bufferedReader().use { it.readText() })
        }
        assertEquals(4, provider.openCount)
    }

    @Test
    fun unsupportedOrUnavailableNamesAreRejectedBeforeOpeningData() {
        listOf("backup.zip", "backup.wtrn.zip", "backup.json.exe", "backup", "backup.wtrn ", ".json", null)
            .forEach { name ->
                provider.displayName = name
                val error = assertThrows(UserFacingUiTextException::class.java) { documents.openImport(uri) }
                assertEquals(uiText(R.string.error_backup_import_extension), error.uiText)
            }
        provider.returnNameColumn = false
        assertThrows(UserFacingUiTextException::class.java) { documents.openImport(uri) }
        assertEquals(0, provider.openCount)
    }

    @Test
    fun exportChecksActualProviderNameBeforeWriting() {
        provider.file.writeText("existing document")
        listOf("backup.wtrn.zip", "backup.zip", "backup.json", null).forEach { name ->
            provider.displayName = name
            val error = assertThrows(UserFacingUiTextException::class.java) { documents.openExport(uri) }
            assertEquals(uiText(R.string.error_backup_export_extension), error.uiText)
        }
        assertEquals(0, provider.openCount)
        assertEquals("existing document", provider.file.readText())
    }

    @Test
    fun exportTruncatesExistingContentAndAcceptsUppercaseWtrn() {
        provider.displayName = "backup.WTRN"
        provider.file.writeText("previous document with a long trailing payload")

        documents.openExport(uri).use { it.write("ZIP".toByteArray()) }

        assertEquals("wt", provider.lastMode)
        assertEquals("ZIP", provider.file.readText())
    }

    @Test
    fun pickerRecreationPreservesBothMergeAndReplaceModes() {
        BackupImportMode.entries.forEach { mode ->
            val original = BackupPickerState().also { it.requestedImportMode = mode }
            val savedState = Bundle().also(original::save)
            val recreated = BackupPickerState().also { it.restore(savedState) }

            assertEquals(mode, recreated.requestedImportMode)
        }
        assertEquals(BackupImportMode.MERGE, BackupPickerState().also { it.restore(null) }.requestedImportMode)
    }

    private class BackupProvider(val file: File) : ContentProvider() {
        var displayName: String? = "backup.wtrn"
        var returnNameColumn = true
        var openCount = 0
        var lastMode: String? = null

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor = MatrixCursor(
            arrayOf(if (returnNameColumn) OpenableColumns.DISPLAY_NAME else "other"),
        ).also { it.addRow(arrayOf(displayName)) }

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            openCount++
            lastMode = mode
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
        }

        override fun onCreate(): Boolean = true
        override fun getType(uri: Uri): String = "application/octet-stream"
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    }
}
