package com.palixander.scalesync.ui.profiles

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.palixander.scalesync.ui.components.HuaweiFilterButton
import com.palixander.scalesync.ui.icons.HuaweiIcons

object HomePetShortcutsTestTags {
    const val Block = "home-pet-shortcuts"
    const val Toggle = "home-pet-toggle"
    const val Add = "home-pet-add"
    fun pet(id: String) = "home-pet-$id"
}

/** Measures real buttons before composing only the visible rows, preserving source order. */
@Composable
fun HomePetShortcuts(
    pets: List<ProfilePresentation.Pet>,
    onProfileSelected: (ProfileKey) -> Unit,
    onAddPet: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    SubcomposeLayout(modifier.fillMaxWidth().testTag(HomePetShortcutsTestTags.Block)) { constraints ->
        val width = constraints.maxWidth
        val gap = 8.dp.roundToPx()
        val childConstraints = Constraints(maxWidth = width)
        // Probes carry no accessibility content or actions. Hidden pets are never composed
        // in the interactive slot, so they cannot receive focus while collapsed.
        val sizes = pets.map { pet ->
            subcompose("probe-${pet.key.petId.value}") {
                Box(Modifier.clearAndSetSemantics {}) {
                    HuaweiFilterButton(text = pet.displayName, icon = pet.selectorIcon(), onClick = {})
                }
            }.single().measure(childConstraints)
        }
        var rowWidth = 0
        var firstRowCount = 0
        for (size in sizes) {
            val next = rowWidth + (if (firstRowCount == 0) 0 else gap) + size.width
            if (next > width) break
            rowWidth = next
            firstRowCount++
        }
        val visibleCount = if (expanded) pets.size else firstRowCount
        val visible = pets.take(visibleCount).map { pet ->
            subcompose("pet-${pet.key.petId.value}") {
                HuaweiFilterButton(
                    text = pet.displayName,
                    icon = pet.selectorIcon(),
                    onClick = { onProfileSelected(pet.key) },
                    modifier = Modifier.testTag(HomePetShortcutsTestTags.pet(pet.key.petId.value))
                        .semantics { contentDescription = "${pet.displayName}, питомец" },
                )
            }.single().measure(childConstraints)
        }
        val positions = mutableListOf<Pair<Int, Int>>()
        var x = 0
        var y = 0
        var rowHeight = 0
        visible.forEach { placeable ->
            if (x > 0 && x + placeable.width > width) {
                y += rowHeight + gap
                x = 0
                rowHeight = 0
            }
            positions += x to y
            x += placeable.width + gap
            rowHeight = maxOf(rowHeight, placeable.height)
        }
        val buttonsHeight = y + rowHeight
        val control = subcompose("toggle") {
            val label = if (expanded) "Свернуть список питомцев" else "Развернуть список питомцев"
            Box(
                modifier = Modifier.fillMaxWidth().height(48.dp)
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
        }.single().measure(childConstraints)
        val add = if (expanded) subcompose("add") {
            HuaweiFilterButton(
                text = "Добавить питомца", onClick = onAddPet,
                modifier = Modifier.testTag(HomePetShortcutsTestTags.Add),
            )
        }.single().measure(childConstraints) else null
        layout(width, buttonsHeight + control.height + (add?.height ?: 0)) {
            visible.forEachIndexed { index, placeable ->
                val (left, top) = positions[index]
                placeable.placeRelative(left, top)
            }
            add?.placeRelative(0, buttonsHeight)
            control.placeRelative(0, buttonsHeight + (add?.height ?: 0))
        }
    }
}
