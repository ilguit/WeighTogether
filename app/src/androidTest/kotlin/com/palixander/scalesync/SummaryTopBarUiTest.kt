package com.palixander.scalesync

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.palixander.scalesync.domain.Account
import com.palixander.scalesync.domain.AccountId
import com.palixander.scalesync.domain.AccountProfile
import com.palixander.scalesync.domain.Pet
import com.palixander.scalesync.domain.PetId
import com.palixander.scalesync.domain.PetWithLatestWeight
import com.palixander.scalesync.ui.profiles.ProfilePresentation
import com.palixander.scalesync.ui.profiles.ProfileSelectionUiState
import com.palixander.scalesync.ui.theme.ScaleSyncTheme
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SummaryTopBarUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun dropdownRestoresExpansionAndSelectsOnlyHumanWithoutChangingPrimary() {
        val first = human("first", "Первый человек")
        val second = human("second", "Человек с длинным именем для проверки")
        val pet = ProfilePresentation.Pet(
            PetWithLatestWeight(Pet(PetId("pet"), "Тестовый питомец", createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH), null),
        )
        val selection = mutableStateOf(ProfileSelectionUiState(listOf(first, pet, second), first.key, first.account.id))
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent {
            ScaleSyncTheme {
                SummaryTopBar(selection.value, { selection.value = selection.value.copy(selectedKey = it) }) {}
            }
        }
        composeRule.onNodeWithTag("summary-profile-icon", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag(SummaryTopBarTestTags.Profile).performClick()
        composeRule.onNodeWithTag(SummaryTopBarTestTags.human("first")).assertIsSelected()
        composeRule.onNodeWithText("Тестовый питомец").assertDoesNotExist()
        restoration.emulateSavedInstanceStateRestore()
        composeRule.onNodeWithTag(SummaryTopBarTestTags.human("second")).assertIsDisplayed().performClick()
        composeRule.onNodeWithTag(SummaryTopBarTestTags.Profile)
            .assertContentDescriptionEquals("Выбор профиля: ${second.displayName}")
        composeRule.onNodeWithTag(SummaryTopBarTestTags.human("second")).assertDoesNotExist()
        composeRule.runOnIdle {
            assertEquals(second.key, selection.value.selectedKey)
            assertEquals(first.account.id, selection.value.primaryAccountId)
        }
    }

    @Test
    fun loadingDoesNotOfferSelectionAndReconciledProfileReplacesOldName() {
        val selection = mutableStateOf<ProfileSelectionUiState?>(null)
        val first = human("first", "Первый человек")
        composeRule.setContent {
            ScaleSyncTheme { SummaryTopBar(selection.value, {}) {} }
        }
        composeRule.onNodeWithTag(SummaryTopBarTestTags.Profile)
            .assertIsNotEnabled().assertContentDescriptionEquals("Выбор профиля: Загрузка профилей…")
        composeRule.runOnIdle { selection.value = ProfileSelectionUiState(listOf(first), first.key, first.account.id) }
        composeRule.onNodeWithTag(SummaryTopBarTestTags.Profile).assertContentDescriptionEquals("Выбор профиля: Первый человек")
        composeRule.runOnIdle { selection.value = ProfileSelectionUiState(emptyList(), null, null) }
        composeRule.onNodeWithTag(SummaryTopBarTestTags.Profile)
            .assertIsNotEnabled().assertContentDescriptionEquals("Выбор профиля: Выберите профиль")
    }

    @Test
    fun profileAndActionsHaveSixteenDpHorizontalAndEightDpVerticalInsetsAfterStatusBar() {
        var statusBarHeight = 0.dp
        val first = human("first", "Первый")
        composeRule.setContent {
            val density = LocalDensity.current
            statusBarHeight = with(density) { WindowInsets.statusBars.getTop(density).toDp() }
            ScaleSyncTheme {
                Box(Modifier.fillMaxWidth().testTag("top-bar-container")) {
                    SummaryTopBar(ProfileSelectionUiState(listOf(first), first.key, first.account.id), {}) {
                        Box(Modifier.size(48.dp).testTag("top-bar-action"))
                    }
                }
            }
        }
        val container = composeRule.onNodeWithTag("top-bar-container").getUnclippedBoundsInRoot()
        val profile = composeRule.onNodeWithTag(SummaryTopBarTestTags.Profile).getUnclippedBoundsInRoot()
        val action = composeRule.onNodeWithTag("top-bar-action").getUnclippedBoundsInRoot()
        assertEquals(16.dp, profile.left - container.left)
        assertEquals(16.dp, container.right - action.right)
        assertEquals(statusBarHeight + 8.dp, profile.top - container.top)
        assertEquals(8.dp, container.bottom - profile.bottom)
    }

    private fun human(id: String, name: String) = ProfilePresentation.Human(
        Account(AccountId(id), name, profile = AccountProfile.IncompleteRecovery(), createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH),
    )
}
