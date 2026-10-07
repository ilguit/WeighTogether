package com.palixander.weightogether

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import com.palixander.weightogether.backup.BackupImportMode
import com.palixander.weightogether.ui.text.UserFacingUiTextException
import com.palixander.weightogether.ui.text.uiText
import java.io.InputStream
import java.io.OutputStream

// An unknown custom MIME has no system .zip mapping, so DocumentsUI keeps the .wtrn title.
internal const val BACKUP_ARCHIVE_MIME_TYPE = "application/vnd.weigh-together.backup"

internal class CreateBackupDocument : ActivityResultContracts.CreateDocument(BACKUP_ARCHIVE_MIME_TYPE) {
    override fun createIntent(context: Context, input: String): Intent =
        super.createIntent(context, input).addCategory(Intent.CATEGORY_OPENABLE)
}

internal class OpenBackupDocument : ActivityResultContract<Unit, Uri?>() {
    private val delegate = ActivityResultContracts.OpenDocument()

    override fun createIntent(context: Context, input: Unit): Intent = delegate.createIntent(
        context,
        arrayOf(
            "application/json",
            BACKUP_ARCHIVE_MIME_TYPE,
            "application/zip",
            "application/x-zip-compressed",
            "application/octet-stream",
        ),
    ).addCategory(Intent.CATEGORY_OPENABLE)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? = delegate.parseResult(resultCode, intent)
}

/** A document provider's display name is authoritative; URI paths often contain only opaque IDs. */
internal class BackupDocuments(private val resolver: ContentResolver) {
    fun openExport(uri: Uri): OutputStream {
        if (!displayName(uri).hasExtension("wtrn")) {
            throw UserFacingUiTextException(uiText(R.string.error_backup_export_extension))
        }
        return resolver.openOutputStream(uri, "wt")
            ?: throw UserFacingUiTextException(uiText(R.string.error_open_selected_file))
    }

    fun openImport(uri: Uri): InputStream {
        val name = displayName(uri)
        if (!name.hasExtension("json") && !name.hasExtension("wtrn")) {
            throw UserFacingUiTextException(uiText(R.string.error_backup_import_extension))
        }
        return resolver.openInputStream(uri)
            ?: throw UserFacingUiTextException(uiText(R.string.error_open_selected_file))
    }

    private fun displayName(uri: Uri): String? = resolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME),
        null,
        null,
        null,
    )?.use { cursor ->
        val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
    }

    private fun String?.hasExtension(extension: String): Boolean =
        this != null && length > extension.length + 1 && endsWith(".$extension", ignoreCase = true)
}

/** Keep the selected destructive/non-destructive mode across recreation while SAF is open. */
internal class BackupPickerState {
    var requestedImportMode = BackupImportMode.MERGE

    fun restore(savedInstanceState: Bundle?) {
        requestedImportMode = BackupImportMode.entries.firstOrNull {
            it.name == savedInstanceState?.getString(IMPORT_MODE_KEY)
        } ?: BackupImportMode.MERGE
    }

    fun save(outState: Bundle) {
        outState.putString(IMPORT_MODE_KEY, requestedImportMode.name)
    }

    private companion object {
        const val IMPORT_MODE_KEY = "backup_import_mode"
    }
}
