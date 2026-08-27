package com.example.huaweimisync.ui

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
import com.example.huaweimisync.core.Sex
import com.example.huaweimisync.domain.Account
import com.example.huaweimisync.domain.AccountId
import com.example.huaweimisync.domain.AccountProfile
import com.example.huaweimisync.domain.Pet
import com.example.huaweimisync.domain.PetId
import com.example.huaweimisync.domain.PetWithLatestWeight
import com.example.huaweimisync.ui.profiles.ProfileKey
import com.example.huaweimisync.ui.profiles.ProfileNavigationState
import com.example.huaweimisync.ui.profiles.ProfileSelector
import com.example.huaweimisync.ui.profiles.ProfileSelectorTestTags
import com.example.huaweimisync.ui.profiles.buildProfilePresentations
import com.example.huaweimisync.ui.profiles.reconcileProfileNavigation
import com.example.huaweimisync.ui.profiles.reconcileProfileSelection
import com.example.huaweimisync.ui.theme.HuaweiMiSyncTheme
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
            HuaweiMiSyncTheme {
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
