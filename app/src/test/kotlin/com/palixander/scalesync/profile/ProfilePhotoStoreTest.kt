package com.palixander.scalesync.profile

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
class ProfilePhotoStoreTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var store: ProfilePhotoStore

    @Before
    fun setUp() {
        // FileProvider keeps a process-wide path cache while Robolectric gives each test a new
        // data directory. Clear it so camera paths are resolved against this test's context.
        FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }
            .get(null)
            .let { it as MutableMap<*, *> }
            .clear()
        File(context.filesDir, "profile-photos").deleteRecursively()
        File(context.cacheDir, "profile-photo-capture").deleteRecursively()
        File(context.cacheDir, "profile-photo-prepared").deleteRecursively()
        store = ProfilePhotoStore(context, maxDimensionPx = 64)
    }

    @After
    fun tearDown() {
        File(context.filesDir, "profile-photos").deleteRecursively()
        File(context.cacheDir, "profile-photo-capture").deleteRecursively()
        File(context.cacheDir, "profile-photo-prepared").deleteRecursively()
    }

    @Test
    fun `confirmed crop uses safe owner key and returns square managed image`() = runBlocking {
        val owner = ProfilePhotoOwner(ProfilePhotoOwnerType.ACCOUNT, "../../unsafe/account")

        val prepared = store.prepare(image(width = 200, height = 100))
        var path = ""
        store.confirmCrop(owner, prepared, ProfilePhotoCropTransform()) { path = it }

        assertTrue(path.startsWith("profile-photos/accounts/"))
        assertFalse(path.contains("unsafe"))
        assertFalse(path.contains(".."))
        val stored = BitmapFactory.decodeFile(store.resolve(path).path)
        assertEquals(32, stored.width)
        assertEquals(32, stored.height)
        assertFalse(prepared.file.exists())
    }

    @Test
    fun `second import is atomic and keeps old image until lifecycle callback`() = runBlocking {
        val owner = ProfilePhotoOwner(ProfilePhotoOwnerType.PET, "pet-1")
        var first = ""
        store.prepare(image()).let {
            store.confirmCrop(owner, it, ProfilePhotoCropTransform()) { path -> first = path }
        }
        var second = ""
        store.prepare(image()).let {
            store.confirmCrop(owner, it, ProfilePhotoCropTransform()) { path -> second = path }
        }

        assertNotEquals(first, second)
        assertTrue(store.resolve(first).isFile)
        assertTrue(store.resolve(second).isFile)

        store.onPhotoDereferenced(first)

        assertFalse(store.resolve(first).exists())
        assertTrue(store.resolve(second).isFile)
    }

    @Test
    fun `dereferencing an absent managed photo is idempotent`() = runBlocking {
        val missingPath = "profile-photos/accounts/missing/photo.jpg"

        store.onPhotoDereferenced(missingPath)
        store.onPhotoDereferenced(missingPath)

        assertFalse(store.resolve(missingPath).exists())
    }

    @Test
    fun `cancel removes camera temporary output`() {
        val capture = store.createCapture()
        assertTrue(capture.file.exists())

        capture.cancel()

        assertFalse(capture.file.exists())
    }

    @Test
    fun `capture identifier restores camera output after state recreation`() {
        val capture = store.createCapture()

        val restored = ProfilePhotoStore(context, maxDimensionPx = 64)
            .restoreCapture(capture.identifier)

        assertEquals(capture.identifier, restored?.identifier)
        assertEquals(capture.file.canonicalFile, restored?.file?.canonicalFile)
    }

    @Test
    fun `prepared identifier restores normalized source after recreation`() {
        val prepared = store.prepare(image(width = 200, height = 100))

        val restored = ProfilePhotoStore(context, maxDimensionPx = 64)
            .restorePrepared(prepared.identifier)

        assertEquals(prepared.identifier, restored?.identifier)
        assertEquals(64, restored?.width)
        assertEquals(32, restored?.height)
        assertEquals(prepared.file.canonicalFile, restored?.file?.canonicalFile)
    }

    @Test
    fun `prepare applies EXIF rotation before exposing dimensions`() {
        val source = File(context.cacheDir, "orientation-source.jpg")
        Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888).also { bitmap ->
            source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            bitmap.recycle()
        }
        ExifInterface(source).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }

        val prepared = source.inputStream().use(store::prepare)

        assertEquals(20, prepared.width)
        assertEquals(40, prepared.height)
        source.delete()
    }

    @Test
    fun `completed camera capture becomes prepared and deletes capture output`() {
        val capture = store.createCapture()
        image(width = 30, height = 20).use { input ->
            capture.file.outputStream().use(input::copyTo)
        }

        val prepared = runBlocking { store.prepareCapture(capture) }

        assertFalse(capture.file.exists())
        assertTrue(prepared.file.exists())
        assertEquals(30, prepared.width)
        assertEquals(20, prepared.height)
    }

    @Test
    fun `cancel prepared removes source without creating managed photo`() {
        val prepared = store.prepare(image())

        prepared.cancel()

        assertFalse(prepared.file.exists())
        assertFalse(File(context.filesDir, "profile-photos").exists())
    }

    @Test
    fun `prepared restoration rejects traversal and missing files`() {
        assertEquals(null, store.restorePrepared("../prepared-unsafe.jpg"))
        assertEquals(null, store.restorePrepared("prepared-not-a-uuid.jpg"))
        assertEquals(null, store.restorePrepared("prepared-00000000-0000-0000-0000-000000000000.jpg"))
    }

    @Test
    fun `preparing another source removes stale prepared output but preserves recent output`() {
        val stale = store.prepare(image())
        stale.file.setLastModified(System.currentTimeMillis() - 25L * 60 * 60 * 1_000)
        val recent = store.prepare(image())
        val next = store.prepare(image())

        assertFalse(stale.file.exists())
        assertTrue(recent.file.exists())
        assertTrue(next.file.exists())
    }

    @Test
    fun `confirmed transform crops requested source region`() = runBlocking {
        val pixels = IntArray(120 * 60) { index ->
            if (index % 120 < 60) android.graphics.Color.BLUE else android.graphics.Color.RED
        }
        val source = Bitmap.createBitmap(pixels, 120, 60, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream()
        source.compress(Bitmap.CompressFormat.PNG, 100, bytes)
        source.recycle()
        val prepared = store.prepare(ByteArrayInputStream(bytes.toByteArray()))

        var path = ""
        store.confirmCrop(
            ProfilePhotoOwner(ProfilePhotoOwnerType.ACCOUNT, "account"),
            prepared,
            ProfilePhotoCropTransform(panX = 1f),
        ) { path = it }

        val cropped = BitmapFactory.decodeFile(store.resolve(path).path)
        assertEquals(cropped.width, cropped.height)
        val pixel = cropped.getPixel(cropped.width / 2, cropped.height / 2)
        assertTrue(
            "Expected red crop but center was #${Integer.toHexString(pixel)}",
            android.graphics.Color.red(pixel) > android.graphics.Color.blue(pixel),
        )
        cropped.recycle()
    }

    @Test
    fun `cancellation after managed rename still hands path to draft atomically`() = runBlocking {
        val enteredHandoff = CompletableDeferred<Unit>()
        val continueHandoff = CompletableDeferred<Unit>()
        val delivered = AtomicReference<String?>()
        store = ProfilePhotoStore.createForTest(
            context = context,
            maxDimensionPx = 64,
            deleteFile = File::delete,
            afterManagedPhotoCreated = {
                enteredHandoff.complete(Unit)
                continueHandoff.await()
            },
        )
        val prepared = store.prepare(image())

        val confirmation = launch {
            store.confirmCrop(
                ProfilePhotoOwner(ProfilePhotoOwnerType.ACCOUNT, "account"),
                prepared,
                ProfilePhotoCropTransform(),
            ) { delivered.set(it) }
        }
        enteredHandoff.await()
        confirmation.cancel()
        continueHandoff.complete(Unit)
        confirmation.join()

        val path = delivered.get()
        assertTrue("The managed path must be handed off despite cancellation", path != null)
        assertTrue(store.resolve(checkNotNull(path)).isFile)
        assertFalse("A delivered crop must consume its prepared source", prepared.file.exists())
    }

    @Test
    fun `failed crop handoff deletes managed output and preserves prepared source`() = runBlocking {
        val prepared = store.prepare(image())

        try {
            store.confirmCrop(
                ProfilePhotoOwner(ProfilePhotoOwnerType.PET, "pet"),
                prepared,
                ProfilePhotoCropTransform(),
            ) { throw IllegalStateException("draft unavailable") }
            throw AssertionError("Expected handoff failure")
        } catch (expected: IllegalStateException) {
            assertEquals("draft unavailable", expected.message)
        }

        assertTrue(prepared.file.isFile)
        val managed = File(context.filesDir, "profile-photos")
            .walkTopDown()
            .filter(File::isFile)
            .toList()
        assertTrue("Failed handoff must not leave a managed orphan: $managed", managed.isEmpty())
    }

    @Test
    fun `permanent owner close after managed rename completes and removes undelivered output`() = runBlocking {
        val enteredHandoff = CompletableDeferred<Unit>()
        val continueHandoff = CompletableDeferred<Unit>()
        val targets = ProfilePhotoDeliveryTargets()
        store = ProfilePhotoStore.createForTest(
            context = context,
            maxDimensionPx = 64,
            deleteFile = File::delete,
            afterManagedPhotoCreated = {
                enteredHandoff.complete(Unit)
                continueHandoff.await()
            },
        )
        val prepared = store.prepare(image())
        val confirmation = async {
            runCatching {
                store.confirmCrop(
                    ProfilePhotoOwner(ProfilePhotoOwnerType.PET, "closed-owner"),
                    prepared,
                    ProfilePhotoCropTransform(),
                ) { path -> targets.deliver(kotlinx.coroutines.Dispatchers.Unconfined, path) }
            }.exceptionOrNull()
        }

        enteredHandoff.await()
        targets.close()
        continueHandoff.complete(Unit)
        val failure = withTimeout(5_000) { confirmation.await() }

        assertTrue(failure is ProfilePhotoDeliveryClosedException)
        assertTrue("An undelivered crop keeps its prepared source for TTL cleanup", prepared.file.isFile)
        val managed = File(context.filesDir, "profile-photos")
            .walkTopDown()
            .filter(File::isFile)
            .toList()
        assertTrue("Closed delivery must not leave a managed orphan: $managed", managed.isEmpty())
    }

    @Test
    fun `capture restoration rejects paths outside managed capture directory`() {
        assertEquals(null, store.restoreCapture("../capture-unsafe.jpg"))
        assertEquals(null, store.restoreCapture("profile-photos/accounts/photo.jpg"))
        assertEquals(null, store.restoreCapture("capture-missing.jpg"))
    }

    @Test
    fun `creating capture removes abandoned stale output but preserves recent output`() {
        val stale = store.createCapture()
        stale.file.setLastModified(System.currentTimeMillis() - 25L * 60 * 60 * 1_000)
        val recent = store.createCapture()

        assertFalse(stale.file.exists())
        assertTrue(recent.file.exists())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `resolve rejects a non photo managed relative path`() {
        store.resolve("other/file.jpg")
    }

    private fun image(width: Int = 10, height: Int = 10): ByteArrayInputStream {
        val bytes = ByteArrayOutputStream()
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            bitmap.eraseColor(android.graphics.Color.BLUE)
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes)
            bitmap.recycle()
        }
        return ByteArrayInputStream(bytes.toByteArray())
    }
}
