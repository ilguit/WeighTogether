package com.palixander.scalesync.ui

import androidx.compose.ui.test.getUnclippedBoundsInRoot
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
        composeRule.onNodeWithText("♀").assertExists()
        composeRule.onNodeWithText("🐱").assertExists()
        composeRule.onNodeWithText("Кошка").assertDoesNotExist()
        composeRule.onNodeWithTag(AccountManagementTestTags.humanMakePrimary(human.id)).assertDoesNotExist()
        composeRule.onNodeWithTag(AccountManagementTestTags.petEdit(pet.pet.id)).performClick()
        composeRule.onNodeWithText("Изменить").performClick()
        composeRule.onNodeWithTag(AccountManagementTestTags.petEdit(pet.pet.id)).performClick()
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
        composeRule.onNodeWithTag(AccountManagementTestTags.humanEdit(human.id)).performClick()
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
        composeRule.onNodeWithText("Добавить профиль").assertExists()
        composeRule.onNodeWithText("Добавить питомца").assertExists()
    }

    @Test
    fun primaryIsFirstAndOtherProfilesAndPetsAreSortedCaseInsensitively() {
        val primary = account("primary", "Яна")
        val anna = account("anna", "анна")
        val boris = account("boris", "Борис")
        val zebra = pet("zebra", "яша")
        val alpha = pet("alpha", "Альфа")
        composeRule.setContent {
            ScaleSyncTheme {
                AccountManagementSection(
                    state = AccountManagementUiState(listOf(boris, primary, anna), primary.id),
                    callbacks = AccountManagementCallbacks.None,
                    pets = listOf(zebra, alpha),
                )
            }
        }

        val people = listOf(primary, anna, boris).map {
            composeRule.onNodeWithTag(AccountManagementTestTags.row(it.id)).getUnclippedBoundsInRoot().top
        }
        val pets = listOf(alpha, zebra).map {
            composeRule.onNodeWithTag(AccountManagementTestTags.petRow(it.pet.id)).getUnclippedBoundsInRoot().top
        }
        assertEquals(people.sorted(), people)
        assertEquals(pets.sorted(), pets)
    }

    private fun account(id: String = "human", name: String = "Анна") = Account(
        id = AccountId(id),
        displayName = name,
        profile = AccountProfile.Complete(170.0, LocalDate.of(1990, 1, 1), Sex.FEMALE),
        createdAt = NOW,
        updatedAt = NOW,
    )

    private fun pet(id: String = "pet", name: String = "Барсик") = PetWithLatestWeight(
        Pet(PetId(id), name, PetSpecies.CAT, createdAt = NOW, updatedAt = NOW),
        null,
    )

    private companion object { val NOW: Instant = Instant.parse("2026-01-01T00:00:00Z") }
}
