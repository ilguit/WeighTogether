package com.palixander.scalesync.ui.profiles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.palixander.scalesync.R
import com.palixander.scalesync.domain.PetSpecies
import com.palixander.scalesync.ui.components.ScaleSyncFilterButton
import com.palixander.scalesync.ui.components.ProfileAvatar
import com.palixander.scalesync.ui.components.currentProfilePhotoStore
import com.palixander.scalesync.ui.icons.ScaleSyncIcons
import com.palixander.scalesync.ui.theme.ScaleSyncDimensions

object ProfileSelectorTestTags {
    const val Selector = "profile-selector"
    const val Fallback = "profile-selector-fallback"
    fun human(accountId: String) = "profile-selector-human-$accountId"
    fun pet(petId: String) = "profile-selector-pet-$petId"
}

@Composable
fun ProfileSelector(
    state: ProfileSelectionUiState,
    onProfileSelected: (ProfileKey) -> Unit,
    modifier: Modifier = Modifier,
) {
    val photoStore = currentProfilePhotoStore()
    val selectorDescription = stringResource(R.string.profile_selector_description)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag(ProfileSelectorTestTags.Selector)
            .semantics { contentDescription = selectorDescription },
        verticalArrangement = Arrangement.spacedBy(ScaleSyncDimensions.CompactItemSpacing),
    ) {
        Text(stringResource(R.string.profile_selector_title), style = MaterialTheme.typography.labelLarge)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(ScaleSyncDimensions.CompactItemSpacing),
        ) {
            state.profiles.forEach { profile ->
                val kind = stringResource(if (profile is ProfilePresentation.Human) R.string.profile_kind_human else R.string.profile_kind_pet)
                val tag = when (val key = profile.key) {
                    is ProfileKey.Human -> ProfileSelectorTestTags.human(key.accountId.value)
                    is ProfileKey.Pet -> ProfileSelectorTestTags.pet(key.petId.value)
                }
                ScaleSyncFilterButton(
                    text = profile.displayName,
                    icon = profile.selectorIcon(),
                    leadingContent = profile.photoPath()?.let { path -> {
                        ProfileAvatar(
                            photoPath = path,
                            fallbackIcon = profile.selectorIcon(),
                            contentDescription = "",
                            store = photoStore,
                            size = 24.dp,
                            modifier = Modifier.padding(end = 7.dp),
                        )
                    } },
                    selected = profile.key == state.selectedKey,
                    onClick = { onProfileSelected(profile.key) },
                    modifier = Modifier.testTag(tag).semantics {
                        contentDescription = "${profile.displayName}, $kind"
                    },
                )
            }
        }
        profileFallbackMessageRes(state)?.let { message ->
            Text(
                text = stringResource(message),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(ProfileSelectorTestTags.Fallback),
            )
        }
    }
}

internal fun ProfilePresentation.photoPath(): String? = when (this) {
    is ProfilePresentation.Human -> account.photoPath
    is ProfilePresentation.Pet -> petWithLatestWeight.pet.photoPath
}

internal fun ProfilePresentation.selectorIcon(): ImageVector = when (this) {
    is ProfilePresentation.Human -> ScaleSyncIcons.Profile
    is ProfilePresentation.Pet -> when (petWithLatestWeight.pet.species) {
        PetSpecies.CAT -> ScaleSyncIcons.Cat
        PetSpecies.DOG -> ScaleSyncIcons.Dog
        PetSpecies.UNSPECIFIED -> ScaleSyncIcons.Profile
    }
}

internal fun profileFallbackMessageRes(state: ProfileSelectionUiState): Int? = when (state.fallback) {
    ProfileSelectionFallback.NONE -> null
    ProfileSelectionFallback.SELECTED_PROFILE_UNAVAILABLE -> if (state.selectedKey is ProfileKey.Human) {
        R.string.profile_fallback_selected_removed
    } else {
        R.string.profile_fallback_selected_removed_primary_unavailable
    }
    ProfileSelectionFallback.PRIMARY_ACCOUNT_UNAVAILABLE -> R.string.profile_fallback_primary_unavailable
    ProfileSelectionFallback.NO_PROFILES -> R.string.profile_fallback_no_profiles
}
