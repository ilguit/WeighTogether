package com.palixander.scalesync.ui.profiles

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import com.palixander.scalesync.domain.AccountId
import com.palixander.scalesync.domain.PetId

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

    companion object {
        val Saver: Saver<ProfileNavigationState, Any> = listSaver(
            save = { state ->
                when (val key = state.selectedKey) {
                    is ProfileKey.Human -> listOf(HUMAN_KEY, key.accountId.value)
                    is ProfileKey.Pet -> listOf(PET_KEY, key.petId.value)
                    null -> listOf(DEFAULT_KEY)
                }
            },
            restore = { saved ->
                when (saved.firstOrNull()) {
                    HUMAN_KEY -> saved.getOrNull(1)?.let { ProfileKey.Human(AccountId(it)) }
                    PET_KEY -> saved.getOrNull(1)?.let { ProfileKey.Pet(PetId(it)) }
                    else -> null
                }?.let { ProfileNavigationState().select(it) } ?: ProfileNavigationState()
            },
        )

        private const val HUMAN_KEY = "human"
        private const val PET_KEY = "pet"
        private const val DEFAULT_KEY = "default"
    }
}

internal fun reconcileProfileNavigation(
    state: ProfileNavigationState,
    selection: ProfileSelectionUiState,
    profilesLoaded: Boolean,
): ProfileNavigationState = if (profilesLoaded) state.reconcile(selection) else state
