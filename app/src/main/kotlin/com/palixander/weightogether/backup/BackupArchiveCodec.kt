package com.palixander.weightogether.backup

import android.graphics.BitmapFactory
import com.google.gson.Gson
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.palixander.weightogether.profile.ProfilePhotoOwner
import com.palixander.weightogether.profile.ProfilePhotoOwnerType
import java.io.Closeable
import java.io.File
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.StringReader
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

const val MAX_BACKUP_ARCHIVE_BYTES = 256 * 1024 * 1024
const val MAX_BACKUP_PHOTO_BYTES = 10 * 1024 * 1024
private const val MAX_PHOTO_INDEX_BYTES = 1024 * 1024
private const val MAX_BACKUP_PHOTOS = MAX_BACKUP_ACCOUNTS + MAX_BACKUP_PETS

/** A private immutable snapshot; close on dismiss, replacement, failure, or after commit. */
class BackupImportSource internal constructor(
    val document: BackupDocumentV1,
    internal val photos: Map<ProfilePhotoOwner, File> = emptyMap(),
    private val directory: File? = null,
) : Closeable {
    @Volatile private var closed = false

    internal fun requireOpen() {
        if (closed) throw BackupException.Invalid("archive", "backup preview is no longer available")
    }

    override fun close() {
        closed = true
        directory?.deleteRecursively()
    }
}

/**
 * Container v1: backup.json is the unchanged portable JSON document; photos.json contains
 * {format:"weigh-together-photos",version:1,photos:[{ownerType:"ACCOUNT"|"PET",ownerId,entry}]}.
 * Each entry is photos/<decimal index>.jpg. IDs are data, never filesystem paths. Every ZIP
 * member must occur once and be referenced; no directories, extras, or duplicate owners.
 */
class BackupArchiveCodec(
    private val stagingRoot: File,
    private val jsonCodec: BackupJsonCodec = BackupJsonCodec(),
    private val validatePhoto: (File) -> Unit = ::validateBackupPhoto,
    private val archiveByteLimit: Int = MAX_BACKUP_ARCHIVE_BYTES,
    private val photoByteLimit: Int = MAX_BACKUP_PHOTO_BYTES,
) {
    private data class PhotoEntry(val ownerType: String, val ownerId: String, val entry: String)
    private data class PhotoIndex(
        val format: String = "weigh-together-photos",
        val version: Int = 1,
        val photos: List<PhotoEntry>,
    )

    /** Copy files before releasing the profile-reference lock that protected the database snapshot. */
    internal fun snapshot(document: BackupDocumentV1, photos: Map<ProfilePhotoOwner, File>): BackupImportSource {
        val directory = newDirectory()
        try {
            var total = jsonCodec.encode(document).toByteArray(Charsets.UTF_8).size.toLong()
            if (total > MAX_BACKUP_BYTES) throw BackupException.Limits("backup.json", MAX_BACKUP_BYTES)
            if (photos.size > MAX_BACKUP_PHOTOS) throw BackupException.Limits("photos", MAX_BACKUP_PHOTOS)
            val copies = photos.entries.mapIndexed { index, (owner, file) ->
                validateOwner(owner, document)
                val copy = File(directory, "$index.jpg")
                file.inputStream().use { input -> copy.outputStream().use { total += copyBounded(input, it, photoByteLimit) } }
                if (total > archiveByteLimit) throw BackupException.Limits("archive", archiveByteLimit)
                validatePhoto(copy)
                owner to copy
            }.toMap()
            return BackupImportSource(document, copies, directory)
        } catch (failure: Throwable) {
            directory.deleteRecursively()
            throw failure
        }
    }

    /** The SAF stream remains caller-owned. Finishing ZIP writes its central directory. */
    fun write(source: BackupImportSource, output: OutputStream) {
        source.requireOpen()
        try {
            if (source.photos.size > MAX_BACKUP_PHOTOS) throw BackupException.Limits("photos", MAX_BACKUP_PHOTOS)
            val entries = source.photos.keys.mapIndexed { index, owner ->
                PhotoEntry(owner.type.name, owner.id, "photos/$index.jpg")
            }
            val documentBytes = jsonCodec.encode(source.document).toByteArray(Charsets.UTF_8)
            val indexBytes = Gson().toJson(PhotoIndex(photos = entries)).toByteArray(Charsets.UTF_8)
            if (documentBytes.size > MAX_BACKUP_BYTES) throw BackupException.Limits("backup.json", MAX_BACKUP_BYTES)
            if (indexBytes.size > MAX_PHOTO_INDEX_BYTES) throw BackupException.Limits("photos.json", MAX_PHOTO_INDEX_BYTES)
            val expanded = documentBytes.size.toLong() + indexBytes.size + source.photos.values.sumOf { it.length() }
            if (expanded > archiveByteLimit) throw BackupException.Limits("archive", archiveByteLimit)
            val callerOwned = object : FilterOutputStream(output) {
                private var written = 0L
                override fun write(value: Int) {
                    if (++written > archiveByteLimit) throw BackupException.Limits("archive", archiveByteLimit)
                    out.write(value)
                }
                override fun write(bytes: ByteArray, offset: Int, count: Int) {
                    written += count
                    if (written > archiveByteLimit) throw BackupException.Limits("archive", archiveByteLimit)
                    out.write(bytes, offset, count)
                }
                override fun close() = flush()
            }
            ZipOutputStream(callerOwned).use { zip ->
                fun bytes(name: String, bytes: ByteArray) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
                bytes("backup.json", documentBytes)
                bytes("photos.json", indexBytes)
                entries.zip(source.photos.values).forEach { (entry, file) ->
                    zip.putNextEntry(ZipEntry(entry.entry))
                    file.inputStream().use { copyBounded(it, zip, photoByteLimit) }
                    zip.closeEntry()
                }
            }
        } catch (known: BackupException) {
            throw known
        } catch (failure: IOException) {
            throw BackupException.Io(failure)
        }
    }

    fun read(input: InputStream): BackupImportSource {
        val directory = newDirectory()
        try {
            val archive = File(directory, "source.zip")
            archive.outputStream().use { copyBounded(input, it, archiveByteLimit) }
            ZipFile(archive).use { zip ->
                val entries = linkedMapOf<String, ZipEntry>()
                var declaredTotal = 0L
                val enumeration = zip.entries()
                while (enumeration.hasMoreElements()) {
                    val entry = enumeration.nextElement()
                    if (entries.size >= MAX_BACKUP_PHOTOS + 2) throw BackupException.Limits("entries", MAX_BACKUP_PHOTOS + 2)
                    if (entry.isDirectory || !(entry.name in setOf("backup.json", "photos.json") || PHOTO_ENTRY.matches(entry.name))) {
                        throw BackupException.Invalid("archive", "unexpected entry")
                    }
                    if (entries.put(entry.name, entry) != null) throw BackupException.Invalid("archive", "duplicate entry")
                    val limit = when (entry.name) {
                        "backup.json" -> MAX_BACKUP_BYTES
                        "photos.json" -> MAX_PHOTO_INDEX_BYTES
                        else -> photoByteLimit
                    }
                    if (entry.size < 0 || entry.size > limit) throw BackupException.Limits("entry", limit)
                    if (entry.method !in setOf(ZipEntry.STORED, ZipEntry.DEFLATED)) throw BackupException.Invalid("archive", "unsupported compression")
                    declaredTotal += entry.size
                    if (declaredTotal > archiveByteLimit) throw BackupException.Limits("archive", archiveByteLimit)
                }
                var expandedTotal = 0L
                fun extract(name: String, destination: File, limit: Int) {
                    val entry = entries[name] ?: throw BackupException.Invalid("archive", "missing entry")
                    val crc = CRC32()
                    val size = zip.getInputStream(entry).use { source ->
                        destination.outputStream().use { sink -> copyBounded(source, sink, limit, crc) }
                    }
                    expandedTotal += size
                    if (expandedTotal > archiveByteLimit) throw BackupException.Limits("archive", archiveByteLimit)
                    if (size != entry.size || crc.value != entry.crc) throw BackupException.Invalid("archive", "entry checksum mismatch")
                }
                val json = File(directory, "backup.json")
                extract("backup.json", json, MAX_BACKUP_BYTES)
                val document = json.inputStream().use { jsonCodec.decode(readBackupJson(it, MAX_BACKUP_BYTES)) }
                val index = File(directory, "photos.json")
                extract("photos.json", index, MAX_PHOTO_INDEX_BYTES)
                val bindings = index.inputStream().use { decodeIndex(readBackupJson(it, MAX_PHOTO_INDEX_BYTES)) }
                val owners = hashSetOf<ProfilePhotoOwner>()
                val referenced = hashSetOf("backup.json", "photos.json")
                val photos = bindings.mapIndexed { number, binding ->
                    val owner = ProfilePhotoOwner(ProfilePhotoOwnerType.valueOf(binding.ownerType), binding.ownerId)
                    validateOwner(owner, document)
                    if (!owners.add(owner) || !referenced.add(binding.entry)) throw BackupException.Invalid("photos", "duplicate binding")
                    val photo = File(directory, "$number.jpg")
                    extract(binding.entry, photo, photoByteLimit)
                    validatePhoto(photo)
                    owner to photo
                }.toMap()
                if (referenced != entries.keys) throw BackupException.Invalid("archive", "unreferenced entry")
                archive.delete()
                json.delete()
                index.delete()
                return BackupImportSource(document, photos, directory)
            }
        } catch (failure: Throwable) {
            directory.deleteRecursively()
            when (failure) {
                is BackupException -> throw failure
                is IOException, is IllegalArgumentException, is IllegalStateException -> throw BackupException.Corrupt(failure)
                else -> throw failure
            }
        }
    }

    /** Called once at process startup, before any preview/export session can be created. */
    fun clearAbandonedSessions() {
        stagingRoot.listFiles()?.filter { SESSION_NAME.matches(it.name) }?.forEach(File::deleteRecursively)
    }

    private fun newDirectory(): File = File(stagingRoot, "session-${UUID.randomUUID()}").also {
        if (!it.mkdirs()) throw BackupException.Io(IOException("Cannot stage backup"))
    }

    private fun decodeIndex(json: String): List<PhotoEntry> = JsonReader(StringReader(json)).use { reader ->
        reader.strictness = Strictness.STRICT
        val keys = hashSetOf<String>()
        var format: String? = null
        var version: String? = null
        var photos: List<PhotoEntry>? = null
        reader.beginObject()
        while (reader.hasNext()) {
            val name = reader.nextName()
            if (!keys.add(name)) throw BackupException.Invalid("photos.json", "duplicate key")
            when (name) {
                "format" -> format = reader.string()
                "version" -> {
                    if (reader.peek() != JsonToken.NUMBER) throw BackupException.Invalid("photos.json", "invalid version")
                    version = reader.nextString()
                }
                "photos" -> {
                    val values = mutableListOf<PhotoEntry>()
                    reader.beginArray()
                    while (reader.hasNext()) {
                        if (values.size >= MAX_BACKUP_PHOTOS) throw BackupException.Limits("photos", MAX_BACKUP_PHOTOS)
                        val fields = linkedMapOf<String, String>()
                        reader.beginObject()
                        while (reader.hasNext()) {
                            val field = reader.nextName()
                            if (field !in setOf("ownerType", "ownerId", "entry") || fields.put(field, reader.string()) != null) {
                                throw BackupException.Invalid("photos.json", "invalid binding field")
                            }
                        }
                        reader.endObject()
                        if (fields.size != 3 || fields["ownerType"] !in setOf("ACCOUNT", "PET") ||
                            !PHOTO_ENTRY.matches(fields.getValue("entry")) || fields.getValue("ownerId").isBlank()
                        ) throw BackupException.Invalid("photos.json", "invalid binding")
                        values += PhotoEntry(fields.getValue("ownerType"), fields.getValue("ownerId"), fields.getValue("entry"))
                    }
                    reader.endArray()
                    photos = values
                }
                else -> throw BackupException.Invalid("photos.json", "unexpected field")
            }
        }
        reader.endObject()
        if (reader.peek() != JsonToken.END_DOCUMENT || format != "weigh-together-photos" || version != "1" || photos == null) {
            throw BackupException.Invalid("photos.json", "unsupported index")
        }
        requireNotNull(photos)
    }

    private fun JsonReader.string(): String {
        if (peek() != JsonToken.STRING) throw BackupException.Invalid("photos.json", "expected string")
        return nextString()
    }

    private fun validateOwner(owner: ProfilePhotoOwner, document: BackupDocumentV1) {
        val found = when (owner.type) {
            ProfilePhotoOwnerType.ACCOUNT -> document.accounts.any { it.id == owner.id }
            ProfilePhotoOwnerType.PET -> document.pets.any { it.id == owner.id }
        }
        if (!found) throw BackupException.Invalid("photos", "unknown owner")
    }

    companion object {
        private val PHOTO_ENTRY = Regex("photos/(0|[1-9][0-9]{0,3})\\.jpg")
        private val SESSION_NAME = Regex("session-[a-f0-9-]{36}")
    }
}

internal fun copyBounded(input: InputStream, output: OutputStream, limit: Int, crc: CRC32? = null): Long {
    var total = 0L
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        if (count == 0) continue
        total += count
        if (total > limit) throw BackupException.Limits("bytes", limit)
        output.write(buffer, 0, count)
        crc?.update(buffer, 0, count)
    }
    return total
}

internal fun validateBackupPhoto(file: File) {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    // Managed photos are JPEGs with both dimensions at most 1600. Reject large images before decode.
    if (bounds.outMimeType != "image/jpeg" || bounds.outWidth !in 1..1600 || bounds.outHeight !in 1..1600) {
        throw BackupException.Invalid("photo", "invalid image dimensions or format")
    }
    val decoded = BitmapFactory.decodeFile(file.path) ?: throw BackupException.Invalid("photo", "invalid image")
    decoded.recycle()
    // Android decoders may recover truncated JPEGs. A complete managed JPEG always has EOI.
    java.io.RandomAccessFile(file, "r").use {
        if (it.length() < 4) throw BackupException.Invalid("photo", "truncated image")
        it.seek(it.length() - 2)
        if (it.readUnsignedShort() != 0xffd9) throw BackupException.Invalid("photo", "truncated image")
    }
}
