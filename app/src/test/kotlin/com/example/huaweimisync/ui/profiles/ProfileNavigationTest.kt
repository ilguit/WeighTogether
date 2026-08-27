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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProfileNavigationTest {
    @Test
    fun selectingPetCarriesExactTypedIdToPetShell() {
        val petId = PetId("pet:account-collision")

        val state = ProfileNavigationState().select(ProfileKey.Pet(petId))

        assertEquals(ProfileKey.Pet(petId), state.selectedKey)
        assertEquals(ProfileDestination.PetShell(petId), state.destination)
    }

    @Test
    fun selectingHumanReturnsToHumanShellWithoutChangingTypedId() {
        val accountId = AccountId("human")
        val state = ProfileNavigationState()
            .select(ProfileKey.Pet(PetId("pet")))
            .select(ProfileKey.Human(accountId))

        assertEquals(ProfileKey.Human(accountId), state.selectedKey)
        assertEquals(ProfileDestination.HumanShell, state.destination)
    }

    @Test
    fun removedPetReconcilesToPrimaryHumanAndClosesPetShell() {
        val account = account("primary")
        val selection = reconcileProfileSelection(
            profiles = buildProfilePresentations(listOf(account), emptyList()),
            requestedKey = ProfileKey.Pet(PetId("removed")),
            primaryAccountId = account.id,
        )

        val state = ProfileNavigationState()
            .select(ProfileKey.Pet(PetId("removed")))
            .reconcile(selection)

        assertEquals(ProfileKey.Human(account.id), state.selectedKey)
        assertEquals(ProfileDestination.HumanShell, state.destination)
    }

    @Test
    fun removedPetWithoutPrimaryClosesPetShellWithoutPromotingAnotherProfile() {
        val remainingPet = pet("remaining")
        val selection = reconcileProfileSelection(
            profiles = buildProfilePresentations(emptyList(), listOf(remainingPet)),
            requestedKey = ProfileKey.Pet(PetId("removed")),
            primaryAccountId = null,
        )

        val state = ProfileNavigationState()
            .select(ProfileKey.Pet(PetId("removed")))
            .reconcile(selection)

        assertNull(state.selectedKey)
        assertEquals(ProfileDestination.HumanShell, state.destination)
    }

    @Test
    fun backFromPetClearsPetContextBeforeHumanSelectionIsReconciled() {
        val state = ProfileNavigationState()
            .select(ProfileKey.Pet(PetId("pet")))
            .back()

        assertNull(state.selectedKey)
        assertEquals(ProfileDestination.HumanShell, state.destination)
    }

    private fun account(id: String) = Account(
        id = AccountId(id),
        displayName = id,
        profile = AccountProfile.Complete(170.0, LocalDate.of(1990, 1, 1), Sex.MALE),
        createdAt = NOW,
        updatedAt = NOW,
    )

    private fun pet(id: String) = PetWithLatestWeight(
        pet = Pet(PetId(id), id, createdAt = NOW, updatedAt = NOW),
        latestMeasurement = null,
    )

    private companion object {
        val NOW: Instant = Instant.parse("2026-01-01T00:00:00Z")
    }
}
