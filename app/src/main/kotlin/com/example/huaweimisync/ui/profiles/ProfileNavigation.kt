package com.example.huaweimisync.ui.profiles

import com.example.huaweimisync.domain.PetId

internal sealed interface ProfileDestination {
    data object HumanShell : ProfileDestination
    data class PetShell(val petId: PetId) : ProfileDestination
}

internal data class ProfileNavigationState(
    val selectedKey: ProfileKey? = null,
    val destination: ProfileDestination = ProfileDestination.HumanShell,
) {
    fun select(key: ProfileKey): ProfileNavigationState = when (key) {
        is ProfileKey.Human -> copy(selectedKey = key, destination = ProfileDestination.HumanShell)
        is ProfileKey.Pet -> copy(selectedKey = key, destination = ProfileDestination.PetShell(key.petId))
    }

    fun reconcile(selection: ProfileSelectionUiState): ProfileNavigationState {
        val selected = selection.selectedKey
        return when (selected) {
            is ProfileKey.Pet -> select(selected)
            is ProfileKey.Human -> select(selected)
            null -> copy(selectedKey = null, destination = ProfileDestination.HumanShell)
        }
    }

    fun back(): ProfileNavigationState = ProfileNavigationState()
}
