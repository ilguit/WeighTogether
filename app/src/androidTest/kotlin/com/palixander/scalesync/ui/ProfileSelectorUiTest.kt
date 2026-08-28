package com.palixander.scalesync.ui

import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.palixander.scalesync.core.Sex
import com.palixander.scalesync.domain.Account
import com.palixander.scalesync.domain.AccountId
import com.palixander.scalesync.domain.AccountProfile
import com.palixander.scalesync.domain.Pet
import com.palixander.scalesync.domain.PetId
import com.palixander.scalesync.domain.PetSpecies
import com.palixander.scalesync.domain.PetWithLatestWeight
import com.palixander.scalesync.ui.icons.HuaweiIcons
import com.palixander.scalesync.ui.profiles.ProfileKey
import com.palixander.scalesync.ui.profiles.ProfilePresentation
import com.palixander.scalesync.ui.profiles.ProfileSelector
import com.palixander.scalesync.ui.profiles.ProfileSelectorTestTags
import com.palixander.scalesync.ui.profiles.buildProfilePresentations
import com.palixander.scalesync.ui.profiles.reconcileProfileSelection
import com.palixander.scalesync.ui.profiles.selectorIcon
import com.palixander.scalesync.ui.theme.ScaleSyncTheme
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
            ScaleSyncTheme {
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
            ScaleSyncTheme {
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
