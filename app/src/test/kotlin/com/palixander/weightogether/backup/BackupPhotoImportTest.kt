package com.palixander.weightogether.backup

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.palixander.weightogether.data.AccountEntity
import com.palixander.weightogether.data.AppDatabase
import com.palixander.weightogether.data.AppStateEntity
import com.palixander.weightogether.data.PetEntity
import com.palixander.weightogether.data.PortableProfileSettings
import com.palixander.weightogether.data.ProfilePhotoReferenceCoordinator
import com.palixander.weightogether.data.VersionedPortableProfileSettings
import com.palixander.weightogether.domain.PetSpecies
import com.palixander.weightogether.profile.ProfilePhotoOwner
import com.palixander.weightogether.profile.ProfilePhotoOwnerType
import com.palixander.weightogether.profile.ProfilePhotoStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BackupPhotoImportTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var database: AppDatabase
    private lateinit var store: ProfilePhotoStore
    private lateinit var references: ProfilePhotoReferenceCoordinator
    private lateinit var codec: BackupArchiveCodec
    private lateinit var service: BackupImportService
    private lateinit var gateway: RoomBackupImportGateway
    private val queries = CopyOnWriteArrayList<String>()
    private val settings = VersionedPortableProfileSettings(PortableProfileSettings(null, null, false, null, null), 0)
    private val sessions get() = File(context.cacheDir, "test-backup-sessions")

    @Before
    fun setUp() {
        File(context.filesDir, "profile-photos").deleteRecursively()
        sessions.deleteRecursively()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .setQueryCallback({ sql, _ -> queries += sql }, Executor { it.run() })
            .build()
        store = ProfilePhotoStore(context)
        references = ProfilePhotoReferenceCoordinator(database, store)
        codec = BackupArchiveCodec(sessions)
        service = BackupImportService(archiveCodec = codec)
        gateway = RoomBackupImportGateway(database, { settings }, service, photoReferences = references, photoStore = store)
    }

    @After
    fun tearDown() {
        database.close()
        File(context.filesDir, "profile-photos").deleteRecursively()
        sessions.deleteRecursively()
    }

    @Test
    fun `replace restores distinct people and pet bytes despite same id and deletes old photos`() = runBlocking {
        val oldPhoto = managed(ProfilePhotoOwner(ProfilePhotoOwnerType.ACCOUNT, "old"), image(0xffff0000.toInt()))
        database.accountDao().insert(account("old").copy(photoPath = oldPhoto))
        val accountBytes = image(0xff00ff00.toInt())
        val petBytes = image(0xff0000ff.toInt())
        val source = source(accountBytes = accountBytes, petBytes = petBytes)
        val preview = preview(source)
        BackupImportApplier(gateway, PortableSettingsWriter {}).apply(preview)

        val restoredAccount = database.accountDao().getAll().single()
        val restoredPet = database.petDao().getAllPetsForBackup().single()
        assertEquals("same", restoredAccount.id)
        assertEquals("same", restoredPet.id)
        assertNotEquals(restoredAccount.photoPath, restoredPet.photoPath)
        assertArrayEquals(accountBytes, store.resolve(restoredAccount.photoPath!!).readBytes())
        assertArrayEquals(petBytes, store.resolve(restoredPet.photoPath!!).readBytes())
        assertFalse(store.resolve(oldPhoto).exists())
        assertTrue(sessions.listFiles()!!.isEmpty())
        assertThrows(BackupException.Invalid::class.java) { runBlocking { gateway.stage(preview) } }
        Unit
    }

    @Test
    fun `merge keeps id and name matches including null photos while importing new owner photos`() = runBlocking {
        val localPhoto = managed(ProfilePhotoOwner(ProfilePhotoOwnerType.ACCOUNT, "local"), image(0xffff0000.toInt()))
        database.accountDao().insert(account("local").copy(displayName = "Person", normalizedName = "person", photoPath = localPhoto))
        database.petDao().insertPets(listOf(pet("same")))
        val incoming = source()
        BackupImportApplier(gateway, PortableSettingsWriter {}).apply(preview(incoming, BackupImportMode.MERGE))
        assertEquals(localPhoto, database.accountDao().getAll().single().photoPath)
        assertEquals(null, database.petDao().getAllPetsForBackup().single().photoPath)
        assertEquals(1, managedFiles().size)

        database.petDao().deleteAllPets()
        val newPetSource = source()
        BackupImportApplier(gateway, PortableSettingsWriter {}).apply(preview(newPetSource, BackupImportMode.MERGE))
        assertNotNull(database.petDao().getAllPetsForBackup().single().photoPath)
        assertEquals(localPhoto, database.accountDao().getAll().single().photoPath)
        assertEquals(2, managedFiles().size)
    }

    @Test
    fun `stale preview discards promoted files retains staged photos and refresh can apply`() = runBlocking {
        val incoming = source()
        val initial = preview(incoming)
        database.accountDao().insert(account("concurrent"))
        val stale = assertThrows(BackupPreviewStale::class.java) { runBlocking { gateway.stage(initial) } }
        assertTrue(managedFiles().isEmpty())
        assertEquals(2, incoming.photos.size)
        assertTrue(incoming.photos.values.all(File::exists))
        assertEquals(incoming, stale.refreshedPreview.source)
        BackupImportApplier(gateway, PortableSettingsWriter {}).apply(stale.refreshedPreview)
        assertEquals(2, managedFiles().size)
        assertTrue(sessions.listFiles()!!.isEmpty())
    }

    @Test
    fun `rollback and dismissed preview remove new bytes without changing existing photos`() = runBlocking {
        val localPhoto = managed(ProfilePhotoOwner(ProfilePhotoOwnerType.ACCOUNT, "old"), image(0xffff0000.toInt()))
        database.accountDao().insert(account("old").copy(photoPath = localPhoto))
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_import BEFORE INSERT ON accounts WHEN NEW.id = 'same' BEGIN SELECT RAISE(ABORT, 'test failure'); END",
        )
        val incoming = source()
        val before = managedFiles().map { it.path }.toSet()
        assertThrows(Exception::class.java) { runBlocking { gateway.stage(preview(incoming)) } }
        assertEquals(before, managedFiles().map { it.path }.toSet())
        assertEquals(localPhoto, database.accountDao().getAll().single().photoPath)
        assertEquals(null, gateway.pendingRecovery())
        incoming.close()
        assertTrue(sessions.listFiles()!!.isEmpty())
    }

    @Test
    fun `settings failure and cancellation after database commit preserve photos for startup recovery`() = runBlocking {
        val first = source()
        val failure = BackupImportApplier(gateway, PortableSettingsWriter { throw IOException("settings") })
            .apply(preview(first))
        assertTrue(failure is BackupImportApplyResult.CompletedPendingRecovery)
        assertEquals(2, managedFiles().size)
        assertNotNull(gateway.pendingRecovery())
        BackupImportApplier(gateway, PortableSettingsWriter {}).recoverPendingImport()
        assertEquals(null, gateway.pendingRecovery())

        val next = source()
        assertThrows(CancellationException::class.java) {
            runBlocking {
                BackupImportApplier(gateway, PortableSettingsWriter { throw CancellationException("cancelled") })
                    .apply(preview(next))
            }
        }
        assertEquals(2, managedFiles().size)
        assertNotNull(gateway.pendingRecovery())
        assertTrue(sessions.listFiles()!!.isEmpty())
        BackupImportApplier(gateway, PortableSettingsWriter {}).recoverPendingImport()
        assertEquals(null, gateway.pendingRecovery())
    }

    @Test
    fun `restart retries cleanup of old photos recorded atomically with replacement`() = runBlocking {
        // Use a lifecycle that cannot delete yet to simulate process loss after the database commit.
        val oldFile = File(context.filesDir, "profile-photos/accounts/original/photo.jpg").apply {
            parentFile!!.mkdirs()
            writeBytes(image(0xffff0000.toInt()))
        }
        val oldPath = oldFile.relativeTo(context.filesDir).invariantSeparatorsPath
        database.accountDao().insert(account("old").copy(photoPath = oldPath))
        val failedCleanup = ProfilePhotoReferenceCoordinator(database) { throw IOException("cleanup unavailable") }
        val interrupted = RoomBackupImportGateway(database, { settings }, service, photoReferences = failedCleanup, photoStore = store)
        val incoming = source()
        assertThrows(IOException::class.java) { runBlocking { interrupted.stage(preview(incoming)) } }
        assertEquals("same", database.accountDao().getAll().single().id)
        assertTrue(oldFile.exists())
        assertNotNull(gateway.pendingRecovery())
        // A new coordinator has no in-memory deletion candidates. Only the Room checkpoint survives.
        val restarted = RoomBackupImportGateway(database, { settings }, service,
            photoReferences = ProfilePhotoReferenceCoordinator(database, store), photoStore = store)
        BackupImportApplier(restarted, PortableSettingsWriter {}).recoverPendingImport()
        assertFalse(oldFile.exists())
        assertEquals(2, managedFiles().size)
        assertEquals(null, restarted.pendingRecovery())
    }

    @Test
    fun `fresh import can still replace an unrecognizable historical checkpoint`() = runBlocking {
        database.backupImportCheckpointDao().replace(
            com.palixander.weightogether.data.BackupImportCheckpointEntity(
                operationId = java.util.UUID.randomUUID().toString(),
                sweepNeeded = "true",
                targetSettingsJson = "unrecognizable historical payload",
            ),
        )
        assertEquals(null, gateway.pendingRecovery())
        BackupImportApplier(gateway, PortableSettingsWriter {}).apply(preview(source()))
        assertEquals(2, managedFiles().size)
        assertEquals(null, gateway.pendingRecovery())
    }

    @Test
    fun `startup cleanup reads only photo paths and preserves people pet and shared photos`() = runBlocking {
        BackupImportApplier(gateway, PortableSettingsWriter {}).apply(preview(source()))
        val shared = managed(ProfilePhotoOwner(ProfilePhotoOwnerType.ACCOUNT, "shared"), image(0))
        database.accountDao().insert(account("shared").copy(normalizedName = "shared", photoPath = shared))
        database.petDao().insertPet(pet("shared").copy(normalizedName = "shared", photoPath = shared))
        database.accountDao().insert(account("no-photo").copy(normalizedName = "no-photo"))
        database.petDao().insertPet(pet("no-photo").copy(normalizedName = "no-photo"))
        val retained = database.accountDao().getAll().mapNotNull { it.photoPath }.toSet() +
            database.petDao().getAllPetsForBackup().mapNotNull { it.photoPath }
        val orphan = managed(ProfilePhotoOwner(ProfilePhotoOwnerType.PET, "orphan"), image(0))
        val editor = File(context.filesDir, "profile-photos/accounts/editor/draft.jpg").apply {
            parentFile!!.mkdirs()
            writeBytes(image(0))
        }
        val pending = source()
        queries.clear()
        cleanupBackupPhotosAtStartup(database, codec, references, store)

        val dataReads = queries.filter { it.startsWith("SELECT", ignoreCase = true) && !it.contains("room_") }
        assertEquals(
            listOf(
                "SELECT photoPath FROM accounts WHERE photoPath IS NOT NULL",
                "SELECT photoPath FROM pets WHERE photoPath IS NOT NULL",
            ),
            dataReads,
        )
        assertEquals(3, retained.size)
        assertFalse(store.resolve(orphan).exists())
        assertTrue(editor.exists())
        assertTrue(retained.all { store.resolve(it).isFile })
        assertTrue(sessions.listFiles()!!.isEmpty())
        pending.close()
    }

    @Test
    fun `export holds reference lock until photo snapshot copied and archive bytes stay independent`() = runBlocking {
        val photoBytes = image(0xff00ff00.toInt())
        val photo = managed(ProfilePhotoOwner(ProfilePhotoOwnerType.ACCOUNT, "same"), photoBytes)
        database.accountDao().insert(account("same").copy(photoPath = photo))
        val read = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val source = BackupSnapshotSource {
            val snapshot = RoomBackupSnapshotSource(database).readSnapshot()
            read.complete(Unit)
            resume.await()
            snapshot
        }
        val export = BackupExportService(source, { settings.settings }, archiveCodec = codec, photoStore = store, photoReferences = references)
        val output = ByteArrayOutputStream()
        val job = async { export.writeTo(output) }
        read.await()
        val removal = async {
            references.mutate(emptySet()) {
                database.accountDao().deleteAll()
                com.palixander.weightogether.data.ProfilePhotoMutation(Unit, setOf(photo))
            }
        }
        yield()
        assertFalse(removal.isCompleted)
        resume.complete(Unit)
        job.await()
        removal.await()
        assertFalse(store.resolve(photo).exists())
        service.readSource(output.toByteArray().inputStream()).use {
            assertArrayEquals(photoBytes, it.photos.values.single().readBytes())
            assertEquals("same", it.document.accounts.single().id)
        }
    }

    @Test
    fun `invalid truncated and oversized jpeg images are rejected before preview`() {
        val invalid = File(context.cacheDir, "invalid-photo.jpg")
        for (bytes in listOf("garbage".toByteArray(), image(0).dropLast(2).toByteArray(), image(0, 1601))) {
            invalid.writeBytes(bytes)
            assertThrows(BackupException.Invalid::class.java) { validateBackupPhoto(invalid) }
        }
        invalid.delete()
        assertTrue(managedFiles().isEmpty())
    }

    @Test
    fun `old JSON replacement still clears photos and archive without photos is valid`() = runBlocking {
        BackupImportApplier(gateway, PortableSettingsWriter {}).apply(preview(source()))
        val legacy = service.readSource(BackupJsonCodec().encode(document()).byteInputStream())
        BackupImportApplier(gateway, PortableSettingsWriter {}).apply(preview(legacy))
        assertTrue(managedFiles().isEmpty())
        assertEquals(null, database.accountDao().getAll().single().photoPath)
        val archive = ByteArrayOutputStream()
        codec.snapshot(document(), emptyMap()).use { codec.write(it, archive) }
        service.readSource(archive.toByteArray().inputStream()).use { assertTrue(it.photos.isEmpty()) }
    }

    private suspend fun preview(source: BackupImportSource, mode: BackupImportMode = BackupImportMode.REPLACE) =
        service.preview(source, RoomBackupSnapshotSource(database).readSnapshot(), settings, mode)

    private fun source(accountBytes: ByteArray = image(0xff00ff00.toInt()), petBytes: ByteArray = image(0xff0000ff.toInt())): BackupImportSource {
        val accountFile = File(context.cacheDir, "account-input.jpg").apply { writeBytes(accountBytes) }
        val petFile = File(context.cacheDir, "pet-input.jpg").apply { writeBytes(petBytes) }
        val photos = mapOf(
            ProfilePhotoOwner(ProfilePhotoOwnerType.ACCOUNT, "same") to accountFile,
            ProfilePhotoOwner(ProfilePhotoOwnerType.PET, "same") to petFile,
        )
        val output = ByteArrayOutputStream()
        codec.snapshot(document(), photos).use { codec.write(it, output) }
        accountFile.delete()
        petFile.delete()
        return service.readSource(output.toByteArray().inputStream())
    }

    private fun managed(owner: ProfilePhotoOwner, bytes: ByteArray): String {
        val file = File(context.cacheDir, "managed-input.jpg").apply { writeBytes(bytes) }
        return store.importBackupPhoto(owner, file).also { file.delete() }
    }

    private fun managedFiles() = File(context.filesDir, "profile-photos").walkTopDown().filter { it.isFile }.toList()

    private fun image(color: Int, width: Int = 24): ByteArray {
        val bitmap = Bitmap.createBitmap(width, 24, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
            .also { bitmap.recycle() }
    }

    private fun account(id: String) = AccountEntity(id, "Person", "person", null, null, null, false, 1, 2)
    private fun pet(id: String) = PetEntity(id, "Pet", "pet", PetSpecies.CAT, 1, 2)
    private fun document() = BackupDocumentV1(
        exportedAt = "2026-10-07T00:00:00Z",
        accounts = listOf(BackupAccountV1("same", "Person", "person", BackupAccountProfileV1(null, null, null, false), 1, 2)),
        appState = BackupAppStateV1("same", 1.0, false),
        measurements = emptyList(),
        settings = BackupSettingsV1(null, null, false, null, null),
        pets = listOf(BackupPetV2("same", "Pet", "pet", PetSpecies.CAT, 1, 2)),
    )
}
