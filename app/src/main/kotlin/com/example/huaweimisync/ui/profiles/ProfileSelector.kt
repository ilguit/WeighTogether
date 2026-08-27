package com.example.huaweimisync.ui.profiles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.example.huaweimisync.ui.components.HuaweiFilterButton
import com.example.huaweimisync.ui.theme.HuaweiDimensions

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
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag(ProfileSelectorTestTags.Selector)
            .semantics { contentDescription = "Выбор профиля" },
        verticalArrangement = Arrangement.spacedBy(HuaweiDimensions.CompactItemSpacing),
    ) {
        Text("Профиль", style = MaterialTheme.typography.labelLarge)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(HuaweiDimensions.CompactItemSpacing),
            verticalArrangement = Arrangement.spacedBy(HuaweiDimensions.CompactItemSpacing),
        ) {
            state.profiles.forEach { profile ->
                val kind = if (profile is ProfilePresentation.Human) "человек" else "питомец"
                val tag = when (val key = profile.key) {
                    is ProfileKey.Human -> ProfileSelectorTestTags.human(key.accountId.value)
                    is ProfileKey.Pet -> ProfileSelectorTestTags.pet(key.petId.value)
                }
                HuaweiFilterButton(
                    text = profile.displayName,
                    selected = profile.key == state.selectedKey,
                    onClick = { onProfileSelected(profile.key) },
                    modifier = Modifier.testTag(tag).semantics {
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

internal fun profileFallbackMessage(state: ProfileSelectionUiState): String? = when (state.fallback) {
    ProfileSelectionFallback.NONE -> null
    ProfileSelectionFallback.SELECTED_PROFILE_UNAVAILABLE -> if (state.selectedKey is ProfileKey.Human) {
        "Выбранный профиль удалён. Показан основной аккаунт."
    } else {
        "Выбранный профиль удалён. Основной аккаунт недоступен. Выберите профиль."
    }
    ProfileSelectionFallback.PRIMARY_ACCOUNT_UNAVAILABLE ->
        "Основной аккаунт недоступен. Выберите профиль."
    ProfileSelectionFallback.NO_PROFILES -> "Создайте профиль в настройках."
}
