package com.palixander.scalesync

import androidx.activity.ComponentActivity
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

    private fun human(id: String, name: String) = ProfilePresentation.Human(
        Account(AccountId(id), name, profile = AccountProfile.IncompleteRecovery(), createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH),
    )
}
