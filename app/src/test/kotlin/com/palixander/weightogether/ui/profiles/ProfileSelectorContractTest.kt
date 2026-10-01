package com.palixander.weightogether.ui.profiles

import com.palixander.weightogether.domain.AccountId
import com.palixander.weightogether.R
import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileSelectorContractTest {
    @Test
    fun removedProfileMessageNamesPrimaryOnlyWhenHumanWasActuallySelected() {
        val selectedPrimary = state(ProfileKey.Human(AccountId("primary")))
        val noSelection = state(null)

        assertEquals(
            R.string.profile_fallback_selected_removed,
            profileFallbackMessageRes(selectedPrimary),
        )
        assertEquals(
            R.string.profile_fallback_selected_removed_primary_unavailable,
            profileFallbackMessageRes(noSelection),
        )
    }

    private fun state(selectedKey: ProfileKey?) = ProfileSelectionUiState(
        profiles = emptyList(),
        selectedKey = selectedKey,
        primaryAccountId = (selectedKey as? ProfileKey.Human)?.accountId,
        fallback = ProfileSelectionFallback.SELECTED_PROFILE_UNAVAILABLE,
    )
}
