package com.example.huaweimisync.ui.profiles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.example.huaweimisync.domain.PetWithLatestWeight
import com.example.huaweimisync.ui.theme.HuaweiDimensions

object PetProfileScreenTestTags {
    const val Shell = "pet-profile-shell"
    fun shell(petId: String) = "$Shell-$petId"
    const val LatestWeight = "pet-profile-latest-weight"
}

@Composable
internal fun PetProfileScreen(
    profile: PetWithLatestWeight,
    contentPadding: PaddingValues,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(HuaweiDimensions.ContentPadding)
            .testTag(PetProfileScreenTestTags.shell(profile.pet.id.value))
            .semantics { contentDescription = "Профиль питомца ${profile.pet.displayName}" },
        verticalArrangement = Arrangement.spacedBy(HuaweiDimensions.ItemSpacing),
    ) {
        Text(
            text = profile.pet.displayName,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = profile.latestPetWeightKg?.let { "Последний вес: %.2f кг".format(it) }
                ?: "Измерений пока нет",
            modifier = Modifier.testTag(PetProfileScreenTestTags.LatestWeight),
        )
        Text(
            text = "Измерения питомца хранятся отдельно от показателей человека.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
