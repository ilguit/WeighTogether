package com.palixander.weightogether.backup

import com.palixander.weightogether.domain.PetSpecies
import com.palixander.weightogether.profile.ProfilePhotoOwner
import com.palixander.weightogether.profile.ProfilePhotoOwnerType
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BackupArchiveCodecTest {
    @get:Rule val folder = TemporaryFolder()
    private val staging get() = File(folder.root, "staging")
    private val codec get() = BackupArchiveCodec(staging, validatePhoto = { require(it.readText() == "photo") })

    @Test
    fun `archive round trips same ids in different owner types and cleans session on close`() {
        val file = folder.newFile().apply { writeText("photo") }
        val document = document()
        val photos = ProfilePhotoOwnerType.entries.associate { ProfilePhotoOwner(it, "same") to file }
        val bytes = ByteArrayOutputStream()
        codec.snapshot(document, photos).use { codec.write(it, bytes) }
        assertTrue(staging.listFiles()!!.isEmpty())
        codec.read(bytes.toByteArray().inputStream()).use {
            assertEquals(document, it.document)
            assertEquals(photos.keys, it.photos.keys)
            assertEquals(listOf("photo", "photo"), it.photos.values.map(File::readText))
        }
        assertTrue(staging.listFiles()!!.isEmpty())
    }

    @Test
    fun `legacy json versions remain readable and archive content is auto detected`() {
        val service = BackupImportService(archiveCodec = codec)
        for (version in 1..BACKUP_SCHEMA_VERSION) {
            val document = document().copy(schemaVersion = version, pets = if (version == 1) emptyList() else document().pets)
            service.readSource(BackupJsonCodec().encode(document).byteInputStream()).use {
                assertEquals(version, it.document.schemaVersion)
                assertTrue(it.photos.isEmpty())
            }
        }
        service.readSource(archive(index()).inputStream()).use { assertEquals(document(), it.document) }
    }

    @Test
    fun `truncated central directory corrupt crc and malformed zip are rejected and cleaned`() {
        val complete = archive(index())
        assertRejected(complete.copyOf(complete.size - 22))
        assertRejected(byteArrayOf(0x50, 0x4b, 0x03, 0x04))
        val stored = archive(index(), stored = true)
        val target = "weigh-together-photos".toByteArray()
        val position = stored.indices.first { start -> start + target.size <= stored.size && stored.copyOfRange(start, start + target.size).contentEquals(target) }
        stored[position] = 'x'.code.toByte()
        assertRejected(stored)
    }

    @Test
    fun `unsafe unknown unreferenced and duplicate zip members are rejected`() {
        listOf("../outside.jpg", "/absolute.jpg", "photos/../0.jpg", "photos\\0.jpg", "photos/", "other.json", "photos/0.jpg").forEach {
            assertRejected(archive(index(), mapOf(it to "photo".toByteArray())))
        }
        // ZipOutputStream forbids duplicate names. Rename an equal-length extra entry in both headers.
        val duplicate = archive(index(), mapOf("xxxxxx.json" to byteArrayOf())).toString(Charsets.ISO_8859_1)
            .replace("xxxxxx.json", "backup.json").toByteArray(Charsets.ISO_8859_1)
        assertRejected(duplicate)
    }

    @Test
    fun `invalid photo bindings cannot reach a preview`() {
        val valid = """{"ownerType":"ACCOUNT","ownerId":"same","entry":"photos/0.jpg"}"""
        listOf(
            index(valid, valid),
            index(valid.replace("same", "missing")),
            index(valid.replace("ACCOUNT", "UNKNOWN")),
            index(valid.replace("photos/0.jpg", "../0.jpg")),
            index(valid.replace("\"ownerId\":\"same\"", "\"ownerId\":\"same\",\"ownerId\":\"same\"")),
            index(valid).replace("\"version\":1", "\"version\":1,\"version\":1"),
            index(valid).replace("\"version\":1", "\"version\":2"),
        ).forEach { assertRejected(archive(it, mapOf("photos/0.jpg" to "photo".toByteArray()))) }
        assertRejected(archive(index(valid)))
        assertRejected(archive(index(valid), mapOf("photos/0.jpg" to "corrupt".toByteArray())))
    }

    @Test
    fun `compressed expanded per image and output limits are enforced`() {
        val valid = """{"ownerType":"ACCOUNT","ownerId":"same","entry":"photos/0.jpg"}"""
        val payload = archive(index(valid), mapOf("photos/0.jpg" to ByteArray(8192)))
        val expandedLimit = BackupArchiveCodec(staging, validatePhoto = {}, archiveByteLimit = 4096)
        assertThrows(BackupException.Limits::class.java) { expandedLimit.read(payload.inputStream()) }
        val imageLimit = BackupArchiveCodec(staging, validatePhoto = {}, photoByteLimit = 4)
        assertThrows(BackupException.Limits::class.java) { imageLimit.read(archive(index(valid), mapOf("photos/0.jpg" to "photo".toByteArray())).inputStream()) }
        val compressedLimit = BackupArchiveCodec(staging, validatePhoto = {}, archiveByteLimit = 20)
        assertThrows(BackupException.Limits::class.java) { compressedLimit.read(archive(index()).inputStream()) }
        val outputLimit = BackupArchiveCodec(staging, validatePhoto = {}, archiveByteLimit = 100)
        assertThrows(BackupException.Limits::class.java) { outputLimit.write(BackupImportSource(document()), ByteArrayOutputStream()) }
        assertTrue(staging.listFiles()!!.isEmpty())
    }

    @Test
    fun `closing source prevents reuse and abandoned cleanup targets only sessions`() {
        val file = folder.newFile().apply { writeText("photo") }
        val source = codec.snapshot(document(), mapOf(ProfilePhotoOwner(ProfilePhotoOwnerType.ACCOUNT, "same") to file))
        source.close()
        assertThrows(BackupException.Invalid::class.java) { codec.write(source, ByteArrayOutputStream()) }
        val abandoned = codec.snapshot(document(), emptyMap())
        val other = File(staging, "unrelated").apply { mkdirs() }
        codec.clearAbandonedSessions()
        assertTrue(other.exists())
        assertEquals(listOf("unrelated"), staging.list()!!.toList())
        abandoned.close()
        assertFalse(file.name.startsWith("session-"))
    }

    private fun assertRejected(bytes: ByteArray) {
        assertThrows(BackupException::class.java) { codec.read(bytes.inputStream()) }
        assertTrue(staging.listFiles()!!.isEmpty())
    }

    private fun archive(index: String, extra: Map<String, ByteArray> = emptyMap(), stored: Boolean = false): ByteArray =
        ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                (mapOf("backup.json" to BackupJsonCodec().encode(document()).toByteArray(), "photos.json" to index.toByteArray()) + extra).forEach { (name, bytes) ->
                    val entry = ZipEntry(name)
                    if (stored) {
                        entry.method = ZipEntry.STORED
                        entry.size = bytes.size.toLong()
                        entry.crc = java.util.zip.CRC32().apply { update(bytes) }.value
                    }
                    zip.putNextEntry(entry)
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
        }.toByteArray()

    private fun index(vararg entries: String) =
        """{"format":"weigh-together-photos","version":1,"photos":[${entries.joinToString()}]}"""

    private fun document() = BackupDocumentV1(
        exportedAt = "2026-10-07T00:00:00Z",
        accounts = listOf(BackupAccountV1("same", "Person", "person", BackupAccountProfileV1(null, null, null, false), 1, 2)),
        appState = BackupAppStateV1("same", 1.0, false),
        measurements = emptyList(),
        settings = BackupSettingsV1(null, null, false, null, null),
        pets = listOf(BackupPetV2("same", "Pet", "pet", PetSpecies.CAT, 1, 2)),
    )
}
