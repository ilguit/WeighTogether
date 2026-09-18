package com.palixander.scalesync.profile

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.runBlocking
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
        store = ProfilePhotoStore(context, maxDimensionPx = 64)
    }

    @After
    fun tearDown() {
        File(context.filesDir, "profile-photos").deleteRecursively()
        File(context.cacheDir, "profile-photo-capture").deleteRecursively()
    }

    @Test
    fun `import uses safe owner key and returns normalized managed image`() {
        val owner = ProfilePhotoOwner(ProfilePhotoOwnerType.ACCOUNT, "../../unsafe/account")

        val path = store.import(owner, image(width = 200, height = 100))

        assertTrue(path.startsWith("profile-photos/accounts/"))
        assertFalse(path.contains("unsafe"))
        assertFalse(path.contains(".."))
        val stored = BitmapFactory.decodeFile(store.resolve(path).path)
        assertEquals(64, stored.width)
        assertEquals(32, stored.height)
    }

    @Test
    fun `second import is atomic and keeps old image until lifecycle callback`() = runBlocking {
        val owner = ProfilePhotoOwner(ProfilePhotoOwnerType.PET, "pet-1")
        val first = store.import(owner, image())
        val second = store.import(owner, image())

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
