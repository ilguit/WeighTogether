package com.palixander.scalesync

import android.graphics.Bitmap
import android.graphics.Color
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import com.palixander.scalesync.profile.ProfilePhotoStore
import com.palixander.scalesync.ui.accounts.AccountEditorDraft
import com.palixander.scalesync.ui.accounts.AccountEditorScreen
import com.palixander.scalesync.ui.accounts.AccountManagementTestTags
import com.palixander.scalesync.ui.components.ProfilePhotoViewerTestTags
import com.palixander.scalesync.ui.theme.ScaleSyncTheme
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ProfilePhotoViewerUiTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val files = mutableListOf<File>()

    @After fun cleanFixtures() { files.forEach { it.delete() } }

    @Test fun humanPhotoCloseAndBackPreserveUnsavedDraft() = verifyEditor(pet = false)

    @Test fun petPhotoCloseAndBackPreserveUnsavedDraft() = verifyEditor(pet = true)

    @Test fun humanWithoutPhotoHasNoViewerAction() = verifyEmpty(pet = false)

    @Test fun petWithoutPhotoHasNoViewerAction() = verifyEmpty(pet = true)

    @Test fun corruptPhotoOffersCloseAndKeepsDraft() {
        val path = fixturePath()
        File(context.filesDir, path).writeText("not an image")
        showEditor(pet = false, path = path)
        composeRule.onNodeWithTag(AccountManagementTestTags.EditorPhoto).performClick()
        waitFor(ProfilePhotoViewerTestTags.Unavailable)
        composeRule.onNodeWithTag(ProfilePhotoViewerTestTags.Close).performClick()
        composeRule.onNodeWithTag(AccountManagementTestTags.EditorName).assertTextContains("Draft")
    }

    @Test fun missingPhotoOffersUnavailableState() {
        showEditor(pet = true, path = "profile-photos/absent-${UUID.randomUUID()}.png")
        composeRule.onNodeWithTag(PetProfileEditorTestTags.Photo).performClick()
        waitFor(ProfilePhotoViewerTestTags.Unavailable)
    }

    private fun verifyEmpty(pet: Boolean) {
        showEditor(pet, null)
        composeRule.onNodeWithTag(photoTag(pet)).assertHasNoClickAction()
        composeRule.onNodeWithTag(ProfilePhotoViewerTestTags.Viewer).assertDoesNotExist()
    }

    private fun verifyEditor(pet: Boolean) {
        val path = fixturePath()
        // Four saturated quadrants include all corners of a non-square saved image. A circular
        // mask or Crop loses these samples or changes the observed 2:1 colored bounds.
        val source = Bitmap.createBitmap(1600, 800, Bitmap.Config.ARGB_8888)
        for (y in 0 until 800) for (x in 0 until 1600) {
            source.setPixel(x, y, when {
                y < 400 && x < 800 -> Color.RED
                y < 400 -> Color.GREEN
                x < 800 -> Color.BLUE
                else -> Color.YELLOW
            })
        }
        File(context.filesDir, path).outputStream().use { source.compress(Bitmap.CompressFormat.PNG, 100, it) }
        source.recycle()
        showEditor(pet, path)
        val nameTag = if (pet) PetProfileEditorTestTags.NameField else AccountManagementTestTags.EditorName
        composeRule.onNodeWithTag(nameTag).performScrollTo().performTextReplacement("Changed draft")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        composeRule.onNodeWithTag(photoTag(pet)).performScrollTo().performClick()
        waitFor(ProfilePhotoViewerTestTags.Image)
        assertWholeImageAndSaveScreenshot(if (pet) "pet" else "human")
        composeRule.onNodeWithTag(ProfilePhotoViewerTestTags.Close).performClick()
        composeRule.onNodeWithTag(nameTag).performScrollTo().assertTextContains("Changed draft")
        composeRule.onNodeWithTag(photoTag(pet)).performScrollTo().performClick()
        waitFor(ProfilePhotoViewerTestTags.Image)
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(ProfilePhotoViewerTestTags.Viewer).assertDoesNotExist()
        composeRule.onNodeWithTag(nameTag).performScrollTo().assertTextContains("Changed draft")
    }

    private fun showEditor(pet: Boolean, path: String?) {
        var human by mutableStateOf(AccountEditorDraft.add().copy(name = "Draft", photoPath = path))
        var animal by mutableStateOf(PetProfileEditorState(PetProfileDraft.create().copy(displayName = "Draft", photoPath = path)))
        val store = ProfilePhotoStore(context)
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                ScaleSyncTheme {
                    if (pet) PetProfileEditorDialog(
                        state = animal,
                        onAction = { animal = PetProfileReducer.reduce(animal, it) },
                        onSave = { error("Viewing must not save") },
                        onDismiss = { error("Viewing must not dismiss editor") },
                        profilePhotoStore = store,
                    ) else AccountEditorScreen(
                        draft = human,
                        accounts = emptyList(),
                        operationInProgress = false,
                        onDraftChanged = { human = it },
                        onCreate = { error("Viewing must not create") },
                        onUpdate = { error("Viewing must not save") },
                        onDismiss = { error("Viewing must not dismiss editor") },
                        profilePhotoStore = store,
                    )
                }
            }
        }
    }

    private fun assertWholeImageAndSaveScreenshot(name: String) {
        val image = composeRule.onNodeWithTag(ProfilePhotoViewerTestTags.Image).captureToImage().asAndroidBitmap()
        val centerX = image.width / 2
        val centerY = image.height / 2
        val halfHeight = minOf(image.height / 2, image.width / 4)
        val halfWidth = halfHeight * 2
        assertEquals(Color.RED, image.getPixel(centerX - halfWidth * 9 / 10, centerY - halfHeight * 9 / 10))
        assertEquals(Color.GREEN, image.getPixel(centerX + halfWidth * 9 / 10, centerY - halfHeight * 9 / 10))
        assertEquals(Color.BLUE, image.getPixel(centerX - halfWidth * 9 / 10, centerY + halfHeight * 9 / 10))
        assertEquals(Color.YELLOW, image.getPixel(centerX + halfWidth * 9 / 10, centerY + halfHeight * 9 / 10))
        if (image.height > halfHeight * 2 + 4) {
            assertNotEquals(Color.RED, image.getPixel(centerX - halfWidth / 2, centerY - halfHeight - 2))
            assertNotEquals(Color.BLUE, image.getPixel(centerX - halfWidth / 2, centerY + halfHeight + 2))
        } else {
            assertNotEquals(Color.RED, image.getPixel(centerX - halfWidth - 2, centerY - halfHeight / 2))
            assertNotEquals(Color.GREEN, image.getPixel(centerX + halfWidth + 2, centerY - halfHeight / 2))
        }
        val output = File(context.getExternalFilesDir(null), "issue145").apply { mkdirs() }
        composeRule.onNodeWithTag(ProfilePhotoViewerTestTags.Viewer).captureToImage().asAndroidBitmap().let { bitmap ->
            File(output, "$name-viewer-font200.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        assertTrue(files.last().exists())
    }

    private fun fixturePath(): String = "profile-photos/viewer-${UUID.randomUUID()}.png".also {
        files += File(context.filesDir, it).apply { parentFile!!.mkdirs() }
    }

    private fun photoTag(pet: Boolean) = if (pet) PetProfileEditorTestTags.Photo else AccountManagementTestTags.EditorPhoto

    private fun waitFor(tag: String) {
        composeRule.waitUntil(10_000) { composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithTag(tag).assertIsDisplayed()
    }
}
