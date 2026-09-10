package com.palixander.scalesync.ui

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.palixander.scalesync.core.Sex
import com.palixander.scalesync.domain.Account
import com.palixander.scalesync.domain.AccountId
import com.palixander.scalesync.domain.AccountProfile
import com.palixander.scalesync.domain.Pet
import com.palixander.scalesync.domain.PetId
import com.palixander.scalesync.domain.PetSpecies
import com.palixander.scalesync.domain.PetWithLatestWeight
import com.palixander.scalesync.ui.accounts.AccountManagementCallbacks
import com.palixander.scalesync.ui.accounts.AccountManagementSection
import com.palixander.scalesync.ui.accounts.AccountManagementTestTags
import com.palixander.scalesync.ui.accounts.AccountManagementUiState
import com.palixander.scalesync.ui.theme.ScaleSyncTheme
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class UnifiedProfileManagementUiTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun mixedListShowsTypedRowsAndDispatchesAllowedActions() {
        val human = account()
        val pet = pet()
        val actions = mutableListOf<String>()
        composeRule.setContent {
            ScaleSyncTheme {
                AccountManagementSection(
                    state = AccountManagementUiState(listOf(human), human.id),
                    callbacks = AccountManagementCallbacks.None.copy(onAction = { actions += "human" }),
                    pets = listOf(pet),
                    onEditPet = { actions += "pet-edit" },
                    onDeletePet = { actions += "pet-delete" },
                    petSpeciesLabel = { "Кошка" },
                )
            }
        }

        composeRule.onNodeWithText("Профили").assertExists()
        composeRule.onNodeWithText("Питомцы").assertExists()
        composeRule.onNodeWithTag(AccountManagementTestTags.PeopleGroup).assertExists()
        composeRule.onNodeWithTag(AccountManagementTestTags.PetsGroup).assertExists()
        composeRule.onNodeWithText("Человек").assertDoesNotExist()
        composeRule.onNodeWithText("Питомец · Кошка").assertDoesNotExist()
        composeRule.onNodeWithTag(AccountManagementTestTags.humanMakePrimary(human.id)).assertDoesNotExist()
        composeRule.onNodeWithTag(AccountManagementTestTags.petRow(pet.pet.id)).performClick()
        composeRule.onNodeWithTag(AccountManagementTestTags.petMenu(pet.pet.id)).performClick()
        composeRule.onNodeWithTag(AccountManagementTestTags.petDelete(pet.pet.id)).performClick()
        composeRule.runOnIdle { assertEquals(listOf("pet-edit", "pet-delete"), actions) }
    }

    @Test
    fun humanOnlyExposesPrimaryAction() {
        val human = account()
        composeRule.setContent {
            ScaleSyncTheme {
                AccountManagementSection(
                    state = AccountManagementUiState(listOf(human), null),
                    callbacks = AccountManagementCallbacks.None,
                )
            }
        }
        composeRule.onNodeWithTag(AccountManagementTestTags.row(human.id)).assertExists()
        composeRule.onNodeWithTag(AccountManagementTestTags.humanMenu(human.id)).performClick()
        composeRule.onNodeWithTag(AccountManagementTestTags.humanMakePrimary(human.id)).assertExists()
    }

    @Test
    fun petOnlyHasNoEmptyPlaceholder() {
        val pet = pet()
        composeRule.setContent {
            ScaleSyncTheme {
                AccountManagementSection(
                    state = AccountManagementUiState(),
                    callbacks = AccountManagementCallbacks.None,
                    pets = listOf(pet),
                )
            }
        }
        composeRule.onNodeWithTag(AccountManagementTestTags.petRow(pet.pet.id)).assertExists()
        composeRule.onNodeWithTag(AccountManagementTestTags.Empty).assertDoesNotExist()
    }

    @Test
    fun emptyStateHasStableTag() {
        composeRule.setContent {
            ScaleSyncTheme {
                AccountManagementSection(AccountManagementUiState(), AccountManagementCallbacks.None)
            }
        }
        composeRule.onNodeWithTag(AccountManagementTestTags.Empty).assertExists()
    }

    private fun account() = Account(
        id = AccountId("human"),
        displayName = "Анна",
        profile = AccountProfile.Complete(170.0, LocalDate.of(1990, 1, 1), Sex.FEMALE),
        createdAt = NOW,
        updatedAt = NOW,
    )

    private fun pet() = PetWithLatestWeight(
        Pet(PetId("pet"), "Барсик", PetSpecies.CAT, createdAt = NOW, updatedAt = NOW),
        null,
    )

    private companion object { val NOW: Instant = Instant.parse("2026-01-01T00:00:00Z") }
}
