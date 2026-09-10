package com.palixander.scalesync.ui.profiles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.selectableGroup
import androidx.compose.ui.semantics.semantics
import com.palixander.scalesync.domain.PetSpecies
import com.palixander.scalesync.ui.components.HuaweiFilterButton
import com.palixander.scalesync.ui.icons.HuaweiIcons
import com.palixander.scalesync.ui.theme.HuaweiDimensions

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
    val scrollState = rememberScrollState()
    val orderedProfiles = remember(state.profiles) {
        state.profiles.filterIsInstance<ProfilePresentation.Human>() +
            state.profiles.filterIsInstance<ProfilePresentation.Pet>()
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag(ProfileSelectorTestTags.Selector)
            .semantics {
                contentDescription = "Профили и питомцы"
                isTraversalGroup = true
            },
        verticalArrangement = Arrangement.spacedBy(HuaweiDimensions.CompactItemSpacing),
    ) {
        Text("Профили и питомцы", style = MaterialTheme.typography.labelLarge)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(scrollState)
                .semantics { selectableGroup() },
            horizontalArrangement = Arrangement.spacedBy(HuaweiDimensions.CompactItemSpacing),
        ) {
            orderedProfiles.forEach { profile ->
                val kind = if (profile is ProfilePresentation.Human) "человек" else "питомец"
                val tag = when (val key = profile.key) {
                    is ProfileKey.Human -> ProfileSelectorTestTags.human(key.accountId.value)
                    is ProfileKey.Pet -> ProfileSelectorTestTags.pet(key.petId.value)
                }
                HuaweiFilterButton(
                    text = profile.displayName,
                    icon = profile.selectorIcon(),
                    selected = profile.key == state.selectedKey,
                    onClick = { onProfileSelected(profile.key) },
                    modifier = Modifier
                        .then(bringSelectedIntoHorizontalView(
                            selected = profile.key == state.selectedKey,
                            scrollState = scrollState,
                        ))
                        .testTag(tag)
                        .semantics {
                        contentDescription = "${profile.displayName}, $kind"
                    },
                )
            }
        }
        profileFallbackMessage(state)?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(ProfileSelectorTestTags.Fallback),
            )
        }
    }
}

@Composable
private fun bringSelectedIntoHorizontalView(
    selected: Boolean,
    scrollState: androidx.compose.foundation.ScrollState,
): Modifier {
    val requester = remember { BringIntoViewRequester() }
    LaunchedEffect(selected, scrollState.maxValue) {
        if (selected && scrollState.maxValue > 0) requester.bringIntoView()
    }
    return Modifier.bringIntoViewRequester(requester)
}

internal fun ProfilePresentation.selectorIcon(): ImageVector = when (this) {
    is ProfilePresentation.Human -> HuaweiIcons.Profile
    is ProfilePresentation.Pet -> when (petWithLatestWeight.pet.species) {
        PetSpecies.CAT -> HuaweiIcons.Cat
        PetSpecies.DOG -> HuaweiIcons.Dog
        PetSpecies.UNSPECIFIED -> HuaweiIcons.Profile
    }
}

internal fun profileFallbackMessage(state: ProfileSelectionUiState): String? = when (state.fallback) {
    ProfileSelectionFallback.NONE -> null
    ProfileSelectionFallback.SELECTED_PROFILE_UNAVAILABLE -> if (state.selectedKey is ProfileKey.Human) {
        "Выбранный профиль удалён. Показан основной профиль."
    } else {
        "Выбранный профиль удалён. Основной профиль недоступен. Выберите профиль."
    }
    ProfileSelectionFallback.PRIMARY_ACCOUNT_UNAVAILABLE ->
        "Основной профиль недоступен. Выберите профиль."
    ProfileSelectionFallback.NO_PROFILES -> "Создайте профиль в настройках."
}
