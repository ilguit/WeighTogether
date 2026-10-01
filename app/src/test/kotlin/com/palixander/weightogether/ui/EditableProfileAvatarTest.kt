package com.palixander.weightogether.ui

import android.app.Application
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import com.palixander.weightogether.ui.components.EditableProfileAvatar
import com.palixander.weightogether.ui.components.ProfilePhotoViewerTestTags
import com.palixander.weightogether.ui.icons.ScaleSyncIcons
import com.palixander.weightogether.ui.theme.ScaleSyncTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class EditableProfileAvatarTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun missingPhotoOpensArtworkAndCanBeClosedAndReopened() {
        showAvatar(null)
        composeRule.onNodeWithTag("avatar").performClick()
        composeRule.onNodeWithTag(ProfilePhotoViewerTestTags.SplashArtwork).assertIsDisplayed()
        composeRule.onNodeWithTag(ProfilePhotoViewerTestTags.Unavailable).assertDoesNotExist()
        composeRule.onNodeWithTag(ProfilePhotoViewerTestTags.Close).performClick()
        composeRule.onNodeWithTag(ProfilePhotoViewerTestTags.Viewer).assertDoesNotExist()
        composeRule.onNodeWithTag("avatar").performClick()
        composeRule.onNodeWithTag(ProfilePhotoViewerTestTags.SplashArtwork).assertIsDisplayed()
    }

    @Test
    fun unavailableAssignedPhotoStillUsesPhotoViewer() {
        showAvatar("profile-photos/missing.png")
        composeRule.onNodeWithTag("avatar").performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag(ProfilePhotoViewerTestTags.Unavailable)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(ProfilePhotoViewerTestTags.SplashArtwork).assertDoesNotExist()
        composeRule.onNodeWithTag(ProfilePhotoViewerTestTags.Close).performClick()
        composeRule.onNodeWithTag(ProfilePhotoViewerTestTags.Viewer).assertDoesNotExist()
    }

    private fun showAvatar(path: String?) {
        composeRule.setContent {
            ScaleSyncTheme {
                EditableProfileAvatar(
                    photoPath = path,
                    fallbackIcon = ScaleSyncIcons.Profile,
                    contentDescription = "Profile",
                    store = null,
                    modifier = Modifier.testTag("avatar"),
                )
            }
        }
    }
}
