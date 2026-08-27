package com.example.huaweimisync.ui.profiles

import androidx.compose.runtime.Immutable
import com.example.huaweimisync.domain.Account
import com.example.huaweimisync.domain.AccountId
import com.example.huaweimisync.domain.PetId
import com.example.huaweimisync.domain.PetWithLatestWeight
import java.util.Locale

/** A collision-safe key for a profile shown by account- and pet-aware UI. */
sealed interface ProfileKey {
    data class Human(val accountId: AccountId) : ProfileKey

    data class Pet(val petId: PetId) : ProfileKey
}

/**
 * Presentation-only profile model. Human-only capabilities deliberately keep [AccountId] outside
 * this contract, so a [Pet] cannot be passed to primary-account or external-sync APIs.
 */
@Immutable
sealed interface ProfilePresentation {
    val key: ProfileKey
    val displayName: String

    @Immutable
    data class Human(val account: Account) : ProfilePresentation {
        override val key: ProfileKey.Human = ProfileKey.Human(account.id)
        override val displayName: String = account.displayName
    }

    @Immutable
    data class Pet(val petWithLatestWeight: PetWithLatestWeight) : ProfilePresentation {
        override val key: ProfileKey.Pet = ProfileKey.Pet(petWithLatestWeight.pet.id)
        override val displayName: String = petWithLatestWeight.pet.displayName
    }
}

@Immutable
data class ProfileManagementCapabilities(
    val canEdit: Boolean,
    val canDelete: Boolean,
    val canMakePrimary: Boolean,
)

fun ProfilePresentation.managementCapabilities(isPrimaryHuman: Boolean): ProfileManagementCapabilities =
    when (this) {
        is ProfilePresentation.Human -> ProfileManagementCapabilities(
            canEdit = true,
            canDelete = true,
            canMakePrimary = !isPrimaryHuman,
        )
        is ProfilePresentation.Pet -> ProfileManagementCapabilities(
            canEdit = true,
            canDelete = true,
            canMakePrimary = false,
        )
    }

enum class ProfileSelectionFallback {
    NONE,
    SELECTED_PROFILE_UNAVAILABLE,
    PRIMARY_ACCOUNT_UNAVAILABLE,
    NO_PROFILES,
}

@Immutable
data class ProfileSelectionUiState(
    val profiles: List<ProfilePresentation>,
    val selectedKey: ProfileKey?,
    val primaryAccountId: AccountId?,
    val fallback: ProfileSelectionFallback = ProfileSelectionFallback.NONE,
) {
    val selectedProfile: ProfilePresentation?
        get() = profiles.firstOrNull { it.key == selectedKey }
}

/** Merges both profile sources into one stable, locale-independent presentation order. */
fun buildProfilePresentations(
    accounts: List<Account>,
    pets: List<PetWithLatestWeight>,
): List<ProfilePresentation> =
    (accounts.map(ProfilePresentation::Human) + pets.map(ProfilePresentation::Pet))
        .sortedWith(profilePresentationComparator)

/**
 * Keeps an existing typed selection or falls back exclusively to the primary human account.
 * An arbitrary profile (in particular a pet) is never promoted to an account role.
 */
fun reconcileProfileSelection(
    profiles: List<ProfilePresentation>,
    requestedKey: ProfileKey?,
    primaryAccountId: AccountId?,
): ProfileSelectionUiState {
    val keys = profiles.mapTo(mutableSetOf()) { it.key }
    val requestedExists = requestedKey != null && requestedKey in keys
    val validPrimaryAccountId = primaryAccountId?.takeIf {
        ProfileKey.Human(it) in keys
    }
    val selectedKey = when {
        requestedExists -> requestedKey
        validPrimaryAccountId != null -> ProfileKey.Human(validPrimaryAccountId)
        else -> null
    }
    val fallback = when {
        profiles.isEmpty() -> ProfileSelectionFallback.NO_PROFILES
        requestedKey != null && !requestedExists ->
            ProfileSelectionFallback.SELECTED_PROFILE_UNAVAILABLE
        selectedKey == null -> ProfileSelectionFallback.PRIMARY_ACCOUNT_UNAVAILABLE
        else -> ProfileSelectionFallback.NONE
    }
    return ProfileSelectionUiState(
        profiles = profiles,
        selectedKey = selectedKey,
        primaryAccountId = validPrimaryAccountId,
        fallback = fallback,
    )
}

private val profilePresentationComparator =
    compareBy<ProfilePresentation> {
        when (it) {
            is ProfilePresentation.Human -> 0
            is ProfilePresentation.Pet -> 1
        }
    }
        .thenBy { it.displayName.lowercase(Locale.ROOT) }
        .thenBy {
            when (val key = it.key) {
                is ProfileKey.Human -> key.accountId.value
                is ProfileKey.Pet -> key.petId.value
            }
        }
