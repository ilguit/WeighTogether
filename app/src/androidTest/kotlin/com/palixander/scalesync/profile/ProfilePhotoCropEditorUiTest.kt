package com.palixander.scalesync.profile

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.down
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.up
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.palixander.scalesync.ui.theme.ScaleSyncTheme
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ProfilePhotoCropEditorUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun controllerCancelClosesEditorKeepsDraftAndDeletesPreparedSource() {
        val store = store()
        val prepared = prepared(store)
        var draftPath: String? = "profile-photos/accounts/existing/photo.jpg"
        lateinit var controller: ProfilePhotoCropController
        composeRule.setContent {
            ScaleSyncTheme {
                controller = rememberProfilePhotoCropController(
                    store,
                    ProfilePhotoOwner(ProfilePhotoOwnerType.ACCOUNT, "cancel-test"),
                    onPhotoReady = { draftPath = it },
                    onError = { error("Unexpected error: $it") },
                )
            }
        }

        composeRule.runOnIdle { controller.open(prepared) }
        composeRule.onNodeWithTag(ProfilePhotoCropTestTags.Editor).assertIsDisplayed()
        composeRule.onNodeWithTag(ProfilePhotoCropTestTags.Cancel).performClick()
        composeRule.onNodeWithTag(ProfilePhotoCropTestTags.Editor).assertDoesNotExist()
        composeRule.runOnIdle {
            assertTrue(draftPath?.contains("existing") == true)
            assertNull(store.restorePrepared(prepared.identifier))
        }
    }

    @Test
    fun controllerConfirmDeliversCroppedPathAndCleansPreparedSource() {
        val store = store()
        val prepared = prepared(store)
        var draftPath: String? = null
        lateinit var controller: ProfilePhotoCropController
        composeRule.setContent {
            ScaleSyncTheme {
                controller = rememberProfilePhotoCropController(
                    store,
                    ProfilePhotoOwner(ProfilePhotoOwnerType.PET, "confirm-test"),
                    onPhotoReady = { draftPath = it },
                    onError = { error("Unexpected error: $it") },
                )
            }
        }

        composeRule.runOnIdle { controller.open(prepared) }
        composeRule.onNodeWithTag(ProfilePhotoCropTestTags.Done).assertIsDisplayed().performClick()
        composeRule.waitUntil(10_000) { draftPath != null }
        composeRule.onNodeWithTag(ProfilePhotoCropTestTags.Editor).assertDoesNotExist()
        composeRule.runOnIdle {
            assertNotNull(draftPath)
            assertTrue(store.resolve(requireNotNull(draftPath)).isFile)
            assertNull(store.restorePrepared(prepared.identifier))
        }
        runBlocking { store.onPhotoDereferenced(requireNotNull(draftPath)) }
    }

    @Test
    fun zoomPanAndEditorSurviveActivityRecreation() {
        val store = store()
        val prepared = prepared(store)
        lateinit var controller: ProfilePhotoCropController
        composeRule.setContent {
            ScaleSyncTheme {
                controller = rememberProfilePhotoCropController(
                    store,
                    ProfilePhotoOwner(ProfilePhotoOwnerType.ACCOUNT, "recreate-test"),
                    onPhotoReady = {},
                    onError = { error("Unexpected error: $it") },
                )
            }
        }
        composeRule.runOnIdle { controller.open(prepared) }
        composeRule.onNodeWithTag(ProfilePhotoCropTestTags.ZoomIn).performClick()
        composeRule.onNodeWithTag(ProfilePhotoCropTestTags.Viewport).performTouchInput {
            swipe(center, center + Offset(60f, 35f), durationMillis = 300)
        }
        val before = stateDescription(ProfilePhotoCropTestTags.Viewport)
        assertFalse(before.contains("позиция 0, 0"))

        composeRule.activityRule.scenario.recreate()

        composeRule.onNodeWithTag(ProfilePhotoCropTestTags.Editor).assertIsDisplayed()
        composeRule.onNodeWithTag(ProfilePhotoCropTestTags.Viewport)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, before))
        composeRule.onNodeWithTag(ProfilePhotoCropTestTags.Cancel).performClick()
    }

    @Test
    fun confirmationPausedAfterRenameSurvivesRecreationAndDeliversExactlyOnce() {
        val enteredHandoff = CompletableDeferred<Unit>()
        val continueHandoff = CompletableDeferred<Unit>()
        val deliveries = AtomicInteger()
        var deliveredPath: String? = null
        val store = ProfilePhotoStore.createForTest(
            context = InstrumentationRegistry.getInstrumentation().targetContext,
            maxDimensionPx = 320,
            afterManagedPhotoCreated = {
                enteredHandoff.complete(Unit)
                continueHandoff.await()
            },
            deleteFile = File::delete,
        )
        val prepared = prepared(store)
        lateinit var controller: ProfilePhotoCropController
        composeRule.setContent {
            ScaleSyncTheme {
                controller = rememberProfilePhotoCropController(
                    store,
                    ProfilePhotoOwner(ProfilePhotoOwnerType.ACCOUNT, "handoff-recreate-test"),
                    onPhotoReady = {
                        deliveries.incrementAndGet()
                        deliveredPath = it
                    },
                    onError = { error("Unexpected error: $it") },
                )
            }
        }

        composeRule.runOnIdle { controller.open(prepared) }
        composeRule.onNodeWithTag(ProfilePhotoCropTestTags.Done).performClick()
        runBlocking { enteredHandoff.await() }
        composeRule.activityRule.scenario.recreate()

        composeRule.onNodeWithTag(ProfilePhotoCropTestTags.Editor).assertIsDisplayed()
        composeRule.onNodeWithTag(ProfilePhotoCropTestTags.Done).assertIsNotEnabled()
        continueHandoff.complete(Unit)
        composeRule.waitUntil(10_000) { deliveries.get() == 1 }
        composeRule.onNodeWithTag(ProfilePhotoCropTestTags.Editor).assertDoesNotExist()
        composeRule.runOnIdle {
            assertTrue(store.resolve(requireNotNull(deliveredPath)).isFile)
            assertNull(store.restorePrepared(prepared.identifier))
            assertTrue(deliveries.get() == 1)
        }
        runBlocking { store.onPhotoDereferenced(requireNotNull(deliveredPath)) }
    }

    @Test
    fun failedDeliveryKeepsPreparedSourceAndAllowsRetry() {
        val store = store()
        val prepared = prepared(store)
        val attempts = AtomicInteger()
        val errors = AtomicInteger()
        var deliveredPath: String? = null
        lateinit var controller: ProfilePhotoCropController
        composeRule.setContent {
            ScaleSyncTheme {
                controller = rememberProfilePhotoCropController(
                    store,
                    ProfilePhotoOwner(ProfilePhotoOwnerType.PET, "delivery-retry-test"),
                    onPhotoReady = { path ->
                        if (attempts.incrementAndGet() == 1) error("draft unavailable")
                        deliveredPath = path
                    },
                    onError = { errors.incrementAndGet() },
                )
            }
        }

        composeRule.runOnIdle { controller.open(prepared) }
        composeRule.onNodeWithTag(ProfilePhotoCropTestTags.Done).performClick()
        composeRule.waitUntil(10_000) { errors.get() == 1 }
        composeRule.onNodeWithTag(ProfilePhotoCropTestTags.Editor).assertIsDisplayed()
        composeRule.onNodeWithTag(ProfilePhotoCropTestTags.Done).assertIsEnabled().performClick()
        composeRule.waitUntil(10_000) { deliveredPath != null }

        composeRule.runOnIdle {
            assertTrue(attempts.get() == 2)
            assertNull(store.restorePrepared(prepared.identifier))
            assertTrue(store.resolve(requireNotNull(deliveredPath)).isFile)
        }
        runBlocking { store.onPhotoDereferenced(requireNotNull(deliveredPath)) }
    }

    @Test
    fun explicitZoomControlsExposeSemanticsAndSliderChangesValue() {
        val store = store()
        val prepared = prepared(store)
        val transform = mutableStateOf(ProfilePhotoCropTransform())
        composeRule.setContent {
            ScaleSyncTheme {
                ProfilePhotoCropEditor(
                    prepared,
                    transform.value,
                    onTransformChanged = { transform.value = it },
                    onCancel = {},
                    onConfirm = {},
                )
            }
        }

        composeRule.onNodeWithTag(ProfilePhotoCropTestTags.ZoomOut)
            .assertContentDescriptionEquals("Уменьшить фото")
        composeRule.onNodeWithTag(ProfilePhotoCropTestTags.ZoomIn)
            .assertContentDescriptionEquals("Увеличить фото")
            .performClick()
        composeRule.runOnIdle { assertTrue(transform.value.zoom > 1f) }
        composeRule.onNodeWithTag(ProfilePhotoCropTestTags.Zoom)
            .assertContentDescriptionEquals("Масштаб фото")
            .performSemanticsAction(SemanticsActions.SetProgress) { set -> set(2.5f) }
        composeRule.runOnIdle { assertTrue(transform.value.zoom == 2.5f) }
        prepared.cancel()
    }

    @Test
    fun longSwipeAppliesEveryMoveWithoutRestartingGestureDetector() {
        val store = store()
        val prepared = prepared(store)
        val transform = mutableStateOf(ProfilePhotoCropTransform(zoom = 2f))
        composeRule.setContent {
            ScaleSyncTheme {
                ProfilePhotoCropEditor(
                    prepared,
                    transform.value,
                    onTransformChanged = { transform.value = it },
                    onCancel = {},
                    onConfirm = {},
                )
            }
        }

        composeRule.onNodeWithTag(ProfilePhotoCropTestTags.Viewport).performTouchInput {
            down(center)
            repeat(10) {
                moveBy(Offset(10f, 0f), delayMillis = 32)
            }
            up()
        }

        composeRule.runOnIdle {
            assertTrue("Expected the full swipe, but panX was ${transform.value.panX}", transform.value.panX < -0.15f)
        }
        prepared.cancel()
    }

    @Test
    fun narrowLargeFontLayoutKeepsActionsReachableWithTouchTargets() {
        val store = store()
        val prepared = prepared(store)
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                ScaleSyncTheme {
                    ProfilePhotoCropEditor(
                        prepared,
                        ProfilePhotoCropTransform(),
                        onTransformChanged = {},
                        onCancel = {},
                        onConfirm = {},
                        modifier = Modifier.width(320.dp),
                    )
                }
            }
        }

        listOf(
            ProfilePhotoCropTestTags.ZoomOut,
            ProfilePhotoCropTestTags.ZoomIn,
            ProfilePhotoCropTestTags.Cancel,
            ProfilePhotoCropTestTags.Done,
        ).forEach { tag ->
            val bounds = composeRule.onNodeWithTag(tag).assertIsDisplayed().getUnclippedBoundsInRoot()
            val width = bounds.right - bounds.left
            val height = bounds.bottom - bounds.top
            assertTrue("$tag width was $width", width >= 48.dp)
            assertTrue("$tag height was $height", height >= 48.dp)
        }
        prepared.cancel()
    }

    private fun stateDescription(tag: String): String {
        var value = ""
        composeRule.onNodeWithTag(tag).assert(
            SemanticsMatcher("capture state description") { node ->
                value = node.config.getOrElse(SemanticsProperties.StateDescription) { "" }
                true
            },
        )
        return value
    }

    private fun store() = ProfilePhotoStore(
        InstrumentationRegistry.getInstrumentation().targetContext,
        maxDimensionPx = 320,
    )

    private fun prepared(store: ProfilePhotoStore): PreparedProfilePhoto {
        val bitmap = Bitmap.createBitmap(240, 160, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.rgb(70, 120, 180))
        }
        val bytes = ByteArrayOutputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output))
            output.toByteArray()
        }
        bitmap.recycle()
        return store.prepare(bytes.inputStream())
    }
}
