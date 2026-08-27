package com.example.huaweimisync.ui.profiles

import com.example.huaweimisync.core.Sex
import com.example.huaweimisync.domain.Account
import com.example.huaweimisync.domain.AccountId
import com.example.huaweimisync.domain.AccountProfile
import com.example.huaweimisync.domain.Pet
import com.example.huaweimisync.domain.PetId
import com.example.huaweimisync.domain.PetWithLatestWeight
import java.time.Instant
import java.time.LocalDate
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfilePresentationContractsTest {
    @Test
    fun mergedProfilesUseRootAlphabeticalOrderThenHumanKindAndTypedId() {
        val originalLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            val profiles = buildProfilePresentations(
                accounts = listOf(account("z-human", "Zed"), account("b-human", "iris"), account("a-human", "IRIS")),
                pets = listOf(pet("a-pet", "Iris"), pet("z-pet", "alice")),
            )

            assertEquals(
                listOf(
                    ProfileKey.Pet(PetId("z-pet")),
                    ProfileKey.Human(AccountId("a-human")),
                    ProfileKey.Human(AccountId("b-human")),
                    ProfileKey.Pet(PetId("a-pet")),
                    ProfileKey.Human(AccountId("z-human")),
                ),
                profiles.map { it.key },
            )
        } finally {
            Locale.setDefault(originalLocale)
        }
    }

    @Test
    fun equalRawIdsRemainDistinctTypedSelections() {
        val profiles = buildProfilePresentations(
            accounts = listOf(account("same", "Human")),
            pets = listOf(pet("same", "Pet")),
        )

        val selected = reconcileProfileSelection(
            profiles = profiles,
            requestedKey = ProfileKey.Pet(PetId("same")),
            primaryAccountId = AccountId("same"),
        )

        assertEquals(ProfileKey.Pet(PetId("same")), selected.selectedKey)
        assertTrue(selected.selectedProfile is ProfilePresentation.Pet)
        assertEquals(ProfileSelectionFallback.NONE, selected.fallback)
    }

    @Test
    fun removedPetFallsBackOnlyToValidPrimaryHuman() {
        val primary = account("primary", "Primary")
        val state = reconcileProfileSelection(
            profiles = buildProfilePresentations(listOf(primary), listOf(pet("remaining", "Pet"))),
            requestedKey = ProfileKey.Pet(PetId("removed")),
            primaryAccountId = primary.id,
        )

        assertEquals(ProfileKey.Human(primary.id), state.selectedKey)
        assertEquals(primary.id, state.primaryAccountId)
        assertEquals(ProfileSelectionFallback.SELECTED_PROFILE_UNAVAILABLE, state.fallback)
    }

    @Test
    fun unavailablePrimaryNeverPromotesPetOrFirstHuman() {
        val profiles = buildProfilePresentations(
            accounts = listOf(account("other", "Other")),
            pets = listOf(pet("pet", "Pet")),
        )
        val state = reconcileProfileSelection(
            profiles = profiles,
            requestedKey = ProfileKey.Pet(PetId("removed")),
            primaryAccountId = AccountId("missing"),
        )

        assertNull(state.selectedKey)
        assertNull(state.selectedProfile)
        assertNull(state.primaryAccountId)
        assertEquals(ProfileSelectionFallback.SELECTED_PROFILE_UNAVAILABLE, state.fallback)
    }

    @Test
    fun noRequestedSelectionAndUnavailablePrimaryHasExplicitFallback() {
        val state = reconcileProfileSelection(
            profiles = buildProfilePresentations(emptyList(), listOf(pet("pet", "Pet"))),
            requestedKey = null,
            primaryAccountId = AccountId("missing"),
        )

        assertNull(state.selectedKey)
        assertEquals(ProfileSelectionFallback.PRIMARY_ACCOUNT_UNAVAILABLE, state.fallback)
    }

    @Test
    fun emptySourcesHaveNoProfilesFallback() {
        val state = reconcileProfileSelection(emptyList(), null, null)

        assertNull(state.selectedKey)
        assertEquals(ProfileSelectionFallback.NO_PROFILES, state.fallback)
    }

    private fun account(id: String, name: String): Account = Account(
        id = AccountId(id),
        displayName = name,
        profile = AccountProfile.Complete(170.0, LocalDate.of(1990, 1, 1), Sex.MALE),
        createdAt = NOW,
        updatedAt = NOW,
    )

    private fun pet(id: String, name: String): PetWithLatestWeight = PetWithLatestWeight(
        pet = Pet(PetId(id), name, createdAt = NOW, updatedAt = NOW),
        latestMeasurement = null,
    )

    private companion object {
        val NOW: Instant = Instant.parse("2026-01-01T00:00:00Z")
    }
}
