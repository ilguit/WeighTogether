package com.palixander.scalesync.ui.profiles

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.palixander.scalesync.ui.components.HuaweiFilterButton
import com.palixander.scalesync.ui.icons.HuaweiIcons

object HomePetShortcutsTestTags {
    const val Block = "home-pet-shortcuts"
    const val Toggle = "home-pet-toggle"
    const val Add = "home-pet-add"
    fun pet(id: String) = "home-pet-$id"
}

/** Composes only visible full-width shortcuts, preserving source order. */
@Composable
fun HomePetShortcuts(
    pets: List<ProfilePresentation.Pet>,
    onProfileSelected: (ProfileKey) -> Unit,
    onAddPet: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().testTag(HomePetShortcutsTestTags.Block)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val visiblePets = if (expanded) pets else pets.take(1)
            visiblePets.forEach { pet ->
                HuaweiFilterButton(
                    text = pet.displayName,
                    icon = pet.selectorIcon(),
                    onClick = { onProfileSelected(pet.key) },
                    modifier = Modifier.fillMaxWidth()
                        .testTag(HomePetShortcutsTestTags.pet(pet.key.petId.value))
                        .semantics { contentDescription = "${pet.displayName}, питомец" },
                )
            }
        }
        if (expanded && pets.isEmpty()) {
            HuaweiFilterButton(
                text = "Добавить питомца",
                onClick = onAddPet,
                modifier = Modifier.testTag(HomePetShortcutsTestTags.Add),
            )
        }
        val label = if (expanded) "Свернуть список питомцев" else "Развернуть список питомцев"
        Box(
            modifier = Modifier.fillMaxWidth().height(24.dp)
                .testTag(HomePetShortcutsTestTags.Toggle)
                .semantics {
                    contentDescription = label
                    stateDescription = if (expanded) "Развёрнуто" else "Свёрнуто"
                }
                .clickable(role = Role.Button, onClickLabel = label) { expanded = !expanded }
                .pointerInput(Unit) {
                    var handled = false
                    detectVerticalDragGestures(
                        onDragStart = { handled = false },
                    ) { change, amount ->
                        change.consume()
                        if (!handled && amount != 0f) {
                            expanded = amount > 0
                            handled = true
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = HuaweiIcons.ChevronDown,
                contentDescription = null,
                modifier = Modifier.size(16.dp).rotate(if (expanded) 180f else 0f),
            )
        }
    }
}
