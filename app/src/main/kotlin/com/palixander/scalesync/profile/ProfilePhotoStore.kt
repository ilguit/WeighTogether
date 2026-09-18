package com.palixander.scalesync.profile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import com.palixander.scalesync.data.ProfilePhotoLifecycle
import com.palixander.scalesync.domain.validateManagedProfilePhotoPath
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

enum class ProfilePhotoOwnerType(val directoryName: String) {
    ACCOUNT("accounts"),
    PET("pets"),
}

data class ProfilePhotoOwner(
    val type: ProfilePhotoOwnerType,
    val id: String,
) {
    init {
        require(id.isNotBlank()) { "Profile photo owner id must not be blank" }
    }
}

/** App-private profile photo storage. Returned paths are relative to [Context.getFilesDir]. */
class ProfilePhotoStore private constructor(
    context: Context,
    private val maxDimensionPx: Int,
    private val deleteFile: (File) -> Boolean,
) : ProfilePhotoLifecycle {
    private val appContext = context.applicationContext
    private val filesRoot = appContext.filesDir.canonicalFile
    private val captureRoot = File(appContext.cacheDir, CAPTURE_DIRECTORY)

    init {
        require(maxDimensionPx > 0)
    }

    constructor(
        context: Context,
        maxDimensionPx: Int = DEFAULT_MAX_DIMENSION_PX,
    ) : this(context, maxDimensionPx, File::delete)

    fun resolve(photoPath: String): File {
        validateManagedProfilePhotoPath(photoPath)
        require(photoPath.startsWith("$PHOTO_DIRECTORY/")) { "Path is not a managed profile photo" }
        val resolved = File(filesRoot, photoPath).canonicalFile
        require(resolved.toPath().startsWith(filesRoot.toPath())) { "Path escapes app storage" }
        return resolved
    }

    suspend fun import(owner: ProfilePhotoOwner, source: Uri): String = withContext(Dispatchers.IO) {
        try {
            appContext.contentResolver.openInputStream(source)?.use { input ->
                import(owner, input)
            } ?: throw ProfilePhotoException(ProfilePhotoError.UNREADABLE_SOURCE)
        } catch (known: ProfilePhotoException) {
            throw known
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            throw ProfilePhotoException(ProfilePhotoError.UNREADABLE_SOURCE, failure)
        }
    }

    internal fun import(owner: ProfilePhotoOwner, input: InputStream): String {
        val ownerDirectory = File(
            filesRoot,
            "$PHOTO_DIRECTORY/${owner.type.directoryName}/${owner.id.safeStorageKey()}",
        )
        check(ownerDirectory.mkdirs() || ownerDirectory.isDirectory) { "Cannot create profile photo directory" }

        val sourceFile = File.createTempFile("source-", ".tmp", ownerDirectory)
        val outputFile = File(ownerDirectory, "${UUID.randomUUID()}.jpg")
        val stagingFile = File(ownerDirectory, ".${outputFile.name}.tmp")
        try {
            sourceFile.outputStream().use { output -> input.copyTo(output) }
            val bitmap = decodeNormalized(sourceFile)
            try {
                stagingFile.outputStream().buffered().use { output ->
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) {
                        throw ProfilePhotoException(ProfilePhotoError.PROCESSING_FAILED)
                    }
                }
            } finally {
                bitmap.recycle()
            }
            if (!stagingFile.renameTo(outputFile)) {
                throw ProfilePhotoException(ProfilePhotoError.PROCESSING_FAILED)
            }
            return outputFile.relativeTo(filesRoot).invariantSeparatorsPath
        } catch (failure: ProfilePhotoException) {
            throw failure
        } catch (failure: Exception) {
            throw ProfilePhotoException(ProfilePhotoError.PROCESSING_FAILED, failure)
        } finally {
            sourceFile.delete()
            stagingFile.delete()
        }
    }

    fun createCapture(): PendingProfilePhotoCapture {
        check(captureRoot.mkdirs() || captureRoot.isDirectory) { "Cannot create capture directory" }
        deleteStaleCaptures()
        val file = File.createTempFile("capture-", ".jpg", captureRoot)
        val uri = FileProvider.getUriForFile(
            appContext,
            "${appContext.packageName}.profilephotos.fileprovider",
            file,
        )
        return PendingProfilePhotoCapture(uri, file.name, file)
    }

    /** Restores an outstanding camera destination after the activity or process was recreated. */
    fun restoreCapture(identifier: String): PendingProfilePhotoCapture? {
        if (!CAPTURE_FILE_PATTERN.matches(identifier)) return null
        val file = File(captureRoot, identifier).canonicalFile
        if (file.parentFile != captureRoot.canonicalFile || !file.isFile) return null
        val uri = FileProvider.getUriForFile(
            appContext,
            "${appContext.packageName}.profilephotos.fileprovider",
            file,
        )
        return PendingProfilePhotoCapture(uri, identifier, file)
    }

    suspend fun completeCapture(owner: ProfilePhotoOwner, capture: PendingProfilePhotoCapture): String =
        withContext(Dispatchers.IO) {
            try {
                FileInputStream(capture.file).use { import(owner, it) }
            } finally {
                capture.cancel()
            }
        }

    override suspend fun onPhotoDereferenced(photoPath: String) {
        val photo = resolve(photoPath)
        if (photo.exists() && !deleteFile(photo) && photo.exists()) {
            throw IOException("Cannot delete managed profile photo: $photoPath")
        }
        removeEmptyParents(photo.parentFile)
    }

    private fun removeEmptyParents(start: File?) {
        val stop = File(filesRoot, PHOTO_DIRECTORY)
        var directory = start
        while (directory != null && directory != stop && directory.list()?.isEmpty() == true) {
            if (!directory.delete()) return
            directory = directory.parentFile
        }
    }

    private fun decodeNormalized(source: File): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw ProfilePhotoException(ProfilePhotoError.INVALID_IMAGE)
        }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDimensionPx) sample *= 2
        val decoded = BitmapFactory.decodeFile(
            source.path,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: throw ProfilePhotoException(ProfilePhotoError.INVALID_IMAGE)

        val orientation = runCatching {
            ExifInterface(source).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = orientationMatrix(orientation)
        val oriented = if (!matrix.isIdentity) {
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true).also {
                if (it !== decoded) decoded.recycle()
            }
        } else {
            decoded
        }
        if (max(oriented.width, oriented.height) <= maxDimensionPx) return oriented
        val scale = maxDimensionPx.toFloat() / max(oriented.width, oriented.height)
        return Bitmap.createScaledBitmap(
            oriented,
            (oriented.width * scale).toInt().coerceAtLeast(1),
            (oriented.height * scale).toInt().coerceAtLeast(1),
            true,
        ).also { if (it !== oriented) oriented.recycle() }
    }

    private fun orientationMatrix(orientation: Int) = Matrix().apply {
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
        }
    }

    companion object {
        const val DEFAULT_MAX_DIMENSION_PX = 1_600
        private const val JPEG_QUALITY = 90
        private const val PHOTO_DIRECTORY = "profile-photos"
        private const val CAPTURE_DIRECTORY = "profile-photo-capture"
        private const val STALE_CAPTURE_AGE_MILLIS = 24 * 60 * 60 * 1_000L
        private val CAPTURE_FILE_PATTERN = Regex("capture-[A-Za-z0-9._-]+\\.jpg")

        internal fun createForTest(
            context: Context,
            maxDimensionPx: Int = DEFAULT_MAX_DIMENSION_PX,
            deleteFile: (File) -> Boolean,
        ) = ProfilePhotoStore(context, maxDimensionPx, deleteFile)
    }

    private fun deleteStaleCaptures(nowMillis: Long = System.currentTimeMillis()) {
        captureRoot.listFiles()?.forEach { file ->
            if (file.isFile && nowMillis - file.lastModified() >= STALE_CAPTURE_AGE_MILLIS) {
                file.delete()
            }
        }
    }
}

class PendingProfilePhotoCapture internal constructor(
    val uri: Uri,
    val identifier: String,
    internal val file: File,
) {
    fun cancel() {
        file.delete()
    }
}

enum class ProfilePhotoError {
    UNREADABLE_SOURCE,
    INVALID_IMAGE,
    PROCESSING_FAILED,
}

class ProfilePhotoException(
    val error: ProfilePhotoError,
    cause: Throwable? = null,
) : Exception(error.name, cause)

private fun String.safeStorageKey(): String =
    MessageDigest.getInstance("SHA-256")
        .digest(toByteArray(Charsets.UTF_8))
        .take(16)
        .joinToString("") { "%02x".format(it) }
