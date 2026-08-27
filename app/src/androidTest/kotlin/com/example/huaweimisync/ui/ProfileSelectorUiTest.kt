package com.example.huaweimisync.ui

import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.example.huaweimisync.core.Sex
import com.example.huaweimisync.domain.Account
import com.example.huaweimisync.domain.AccountId
import com.example.huaweimisync.domain.AccountProfile
import com.example.huaweimisync.domain.Pet
import com.example.huaweimisync.domain.PetId
import com.example.huaweimisync.domain.PetSpecies
import com.example.huaweimisync.domain.PetWithLatestWeight
import com.example.huaweimisync.ui.icons.HuaweiIcons
import com.example.huaweimisync.ui.profiles.ProfileKey
import com.example.huaweimisync.ui.profiles.ProfilePresentation
import com.example.huaweimisync.ui.profiles.ProfileSelector
import com.example.huaweimisync.ui.profiles.ProfileSelectorTestTags
import com.example.huaweimisync.ui.profiles.buildProfilePresentations
import com.example.huaweimisync.ui.profiles.reconcileProfileSelection
import com.example.huaweimisync.ui.profiles.selectorIcon
import com.example.huaweimisync.ui.theme.HuaweiMiSyncTheme
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ProfileSelectorUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun typedOptionsExposeKindAndDispatchExactPetId() {
        val account = account("same")
        val pet = pet("same")
        val state = reconcileProfileSelection(
            profiles = buildProfilePresentations(listOf(account), listOf(pet)),
            requestedKey = ProfileKey.Pet(pet.pet.id),
            primaryAccountId = account.id,
        )
        var selected: ProfileKey? = null
        composeRule.setContent {
            HuaweiMiSyncTheme {
                ProfileSelector(state = state, onProfileSelected = { selected = it })
            }
        }

        composeRule.onNodeWithContentDescription("same, человек").assertExists()
        composeRule.onNodeWithTag(ProfileSelectorTestTags.pet("same"))
            .assertIsSelected()
            .performClick()
        composeRule.runOnIdle { assertEquals(ProfileKey.Pet(PetId("same")), selected) }
    }

    @Test
    fun petIconsFollowSpeciesAndUnspecifiedUsesSafeProfileFallback() {
        assertEquals(HuaweiIcons.Cat, ProfilePresentation.Pet(pet("cat", PetSpecies.CAT)).selectorIcon())
        assertEquals(HuaweiIcons.Dog, ProfilePresentation.Pet(pet("dog", PetSpecies.DOG)).selectorIcon())
        assertEquals(
            HuaweiIcons.Profile,
            ProfilePresentation.Pet(pet("legacy", PetSpecies.UNSPECIFIED)).selectorIcon(),
        )
    }

    @Test
    fun largeProfileListStaysOnOneRowAndFarProfileCanBeSelected() {
        val accounts = List(20) { index -> account("account-$index") }
        val state = reconcileProfileSelection(
            profiles = buildProfilePresentations(accounts, emptyList()),
            requestedKey = ProfileKey.Human(accounts.first().id),
            primaryAccountId = accounts.first().id,
        )
        var selected: ProfileKey? = null
        composeRule.setContent {
            HuaweiMiSyncTheme {
                ProfileSelector(state = state, onProfileSelected = { selected = it })
            }
        }

        val first = composeRule.onNodeWithTag(ProfileSelectorTestTags.human("account-0"))
        val last = composeRule.onNodeWithTag(ProfileSelectorTestTags.human("account-19"))
        val firstTop = first.getUnclippedBoundsInRoot().top

        last.performScrollTo().assertIsDisplayed()
        val lastTop = last.getUnclippedBoundsInRoot().top
        assertEquals(firstTop, lastTop)
        last.performClick()

        composeRule.runOnIdle {
            assertEquals(ProfileKey.Human(AccountId("account-19")), selected)
        }
    }

    private fun account(id: String) = Account(
        id = AccountId(id),
        displayName = id,
        profile = AccountProfile.Complete(170.0, LocalDate.of(1990, 1, 1), Sex.MALE),
        createdAt = NOW,
        updatedAt = NOW,
    )

    private fun pet(id: String, species: PetSpecies = PetSpecies.CAT) = PetWithLatestWeight(
        pet = Pet(PetId(id), id, species = species, createdAt = NOW, updatedAt = NOW),
        latestMeasurement = null,
    )

    private companion object {
        val NOW: Instant = Instant.parse("2026-01-01T00:00:00Z")
    }
}
