package com.palixander.weightogether.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.palixander.weightogether.core.Sex
import com.palixander.weightogether.domain.Account
import com.palixander.weightogether.domain.AccountId
import com.palixander.weightogether.domain.AccountProfile
import com.palixander.weightogether.domain.Pet
import com.palixander.weightogether.domain.PetId
import com.palixander.weightogether.domain.PetWithLatestWeight
import com.palixander.weightogether.ProfileMeasurementSelectionEffect
import com.palixander.weightogether.ui.accounts.AccountSelectorUiState
import com.palixander.weightogether.ui.profiles.ProfileKey
import com.palixander.weightogether.ui.profiles.ProfileNavigationState
import com.palixander.weightogether.ui.profiles.ProfileSelector
import com.palixander.weightogether.ui.profiles.ProfileSelectorTestTags
import com.palixander.weightogether.ui.profiles.buildProfilePresentations
import com.palixander.weightogether.ui.profiles.reconcileProfileNavigation
import com.palixander.weightogether.ui.profiles.reconcileProfileSelection
import com.palixander.weightogether.ui.theme.ScaleSyncTheme
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ProfileNavigationRestorationUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun restoredPetSurvivesInitialEmptyLoadThenMissingPetFallsBack() {
        val tester = StateRestorationTester(composeRule)
        val exactPetId = PetId("pet:restored/exact")
        val primary = account("primary")
        val pet = pet(exactPetId)
        val profilesLoaded = mutableStateOf(true)
        val accounts = mutableStateOf(listOf(primary))
        val pets = mutableStateOf(listOf(pet))
        var navigation = ProfileNavigationState()

        tester.setContent {
            var savedNavigation by rememberSaveable(stateSaver = ProfileNavigationState.Saver) {
                mutableStateOf(ProfileNavigationState())
            }
            val profiles = buildProfilePresentations(accounts.value, pets.value)
            val selection = if (profilesLoaded.value) {
                reconcileProfileSelection(profiles, savedNavigation.selectedKey, primary.id)
            } else {
                null
            }
            LaunchedEffect(selection?.selectedKey, selection?.fallback) {
                selection?.let {
                    savedNavigation = reconcileProfileNavigation(
                        savedNavigation,
                        it,
                        profilesLoaded.value,
                    )
                }
            }
            navigation = savedNavigation
            ScaleSyncTheme {
                selection?.let {
                    ProfileSelector(
                        state = it,
                        onProfileSelected = { key -> savedNavigation = savedNavigation.select(key) },
                    )
                }
            }
        }

        composeRule.onNodeWithTag(ProfileSelectorTestTags.pet(exactPetId.value)).performClick()
        composeRule.runOnIdle { assertEquals(ProfileKey.Pet(exactPetId), navigation.selectedKey) }

        composeRule.runOnIdle {
            profilesLoaded.value = false
            accounts.value = emptyList()
            pets.value = emptyList()
        }
        tester.emulateSavedInstanceStateRestore()
        composeRule.runOnIdle { assertEquals(ProfileKey.Pet(exactPetId), navigation.selectedKey) }

        composeRule.runOnIdle {
            accounts.value = listOf(primary)
            pets.value = listOf(pet)
            profilesLoaded.value = true
        }
        composeRule.onNodeWithTag(ProfileSelectorTestTags.pet(exactPetId.value)).assertIsSelected()
        composeRule.runOnIdle { assertEquals(ProfileKey.Pet(exactPetId), navigation.selectedKey) }

        composeRule.runOnIdle { pets.value = emptyList() }
        composeRule.onNodeWithTag(ProfileSelectorTestTags.human(primary.id.value)).assertIsSelected()
        composeRule.runOnIdle { assertEquals(ProfileKey.Human(primary.id), navigation.selectedKey) }
    }

    @Test
    fun restoredHumanSynchronizesAfterMeasurementAccountsBecomeReady() {
        val human = account("restored-human")
        val measurementSelector = mutableStateOf(AccountSelectorUiState(emptyList(), null, human.id))
        var selectionAttempts = 0

        composeRule.setContent {
            var navigation by rememberSaveable(stateSaver = ProfileNavigationState.Saver) {
                mutableStateOf(ProfileNavigationState().select(ProfileKey.Human(human.id)))
            }
            val selection = reconcileProfileSelection(
                profiles = buildProfilePresentations(listOf(human), emptyList()),
                requestedKey = navigation.selectedKey,
                primaryAccountId = human.id,
            )
            ProfileMeasurementSelectionEffect(
                navigation = navigation,
                selection = selection,
                profilesLoaded = true,
                accountSelector = measurementSelector.value,
                onNavigationChanged = { navigation = it },
                onAccountSelected = { accountId ->
                    selectionAttempts += 1
                    measurementSelector.value = measurementSelector.value.copy(selectedAccountId = accountId)
                },
            )
        }

        composeRule.runOnIdle {
            assertEquals(0, selectionAttempts)
            measurementSelector.value = measurementSelector.value.copy(accounts = listOf(human))
        }
        composeRule.runOnIdle {
            assertEquals(human.id, measurementSelector.value.selectedAccountId)
            assertEquals(1, selectionAttempts)
        }
        composeRule.waitForIdle()
        assertEquals(1, selectionAttempts)
    }

    private fun account(id: String) = Account(
        id = AccountId(id),
        displayName = id,
        profile = AccountProfile.Complete(170.0, LocalDate.of(1990, 1, 1), Sex.MALE),
        createdAt = NOW,
        updatedAt = NOW,
    )

    private fun pet(id: PetId) = PetWithLatestWeight(
        pet = Pet(id, id.value, createdAt = NOW, updatedAt = NOW),
        latestMeasurement = null,
    )

    private companion object {
        val NOW: Instant = Instant.parse("2026-01-01T00:00:00Z")
    }
}
