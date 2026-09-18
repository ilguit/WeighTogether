package com.palixander.scalesync.profile

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
    fun `cancel removes camera temporary output`() {
        val capture = store.createCapture()
        assertTrue(capture.file.exists())

        capture.cancel()

        assertFalse(capture.file.exists())
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
