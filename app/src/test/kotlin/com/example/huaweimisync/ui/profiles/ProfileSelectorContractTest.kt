package com.example.huaweimisync.ui.profiles

import com.example.huaweimisync.domain.AccountId
import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileSelectorContractTest {
    @Test
    fun removedProfileMessageNamesPrimaryOnlyWhenHumanWasActuallySelected() {
        val selectedPrimary = state(ProfileKey.Human(AccountId("primary")))
        val noSelection = state(null)

        assertEquals(
            "Выбранный профиль удалён. Показан основной аккаунт.",
            profileFallbackMessage(selectedPrimary),
        )
        assertEquals(
            "Выбранный профиль удалён. Основной аккаунт недоступен. Выберите профиль.",
            profileFallbackMessage(noSelection),
        )
    }

    private fun state(selectedKey: ProfileKey?) = ProfileSelectionUiState(
        profiles = emptyList(),
        selectedKey = selectedKey,
        primaryAccountId = (selectedKey as? ProfileKey.Human)?.accountId,
        fallback = ProfileSelectionFallback.SELECTED_PROFILE_UNAVAILABLE,
    )
}
