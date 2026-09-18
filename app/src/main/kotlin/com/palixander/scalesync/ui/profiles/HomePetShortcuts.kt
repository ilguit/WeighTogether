package com.palixander.scalesync.ui.profiles

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Constraints
import com.palixander.scalesync.ui.components.HuaweiFilterButton
import com.palixander.scalesync.ui.components.ProfileAvatar
import com.palixander.scalesync.ui.components.currentProfilePhotoStore
import com.palixander.scalesync.ui.icons.HuaweiIcons

object HomePetShortcutsTestTags {
    const val Block = "home-pet-shortcuts"
    const val Toggle = "home-pet-toggle"
    const val Add = "home-pet-add"
    fun pet(id: String) = "home-pet-$id"
}

/** Measures natural button widths, then fills each visible responsive row. */
@Composable
fun HomePetShortcuts(
    pets: List<ProfilePresentation.Pet>,
    onProfileSelected: (ProfileKey) -> Unit,
    onAddPet: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val photoStore = currentProfilePhotoStore()
    SubcomposeLayout(modifier.fillMaxWidth().testTag(HomePetShortcutsTestTags.Block)) { constraints ->
        val width = constraints.maxWidth
        val gap = 8.dp.roundToPx()
        val childConstraints = Constraints(maxWidth = width)
        val naturalWidths = pets.map { pet ->
            subcompose("probe-${pet.key.petId.value}") {
                Box(Modifier.clearAndSetSemantics {}) {
                    HuaweiFilterButton(
                        text = pet.displayName,
                        icon = pet.selectorIcon(),
                        leadingContent = pet.photoPath()?.let { path -> {
                            ProfileAvatar(path, pet.selectorIcon(), "", photoStore, Modifier.padding(end = 7.dp), 24.dp)
                        } },
                        onClick = {},
                    )
                }
            }.single().measure(Constraints()).width
        }
        val rows = mutableListOf<List<Int>>()
        var currentRow = mutableListOf<Int>()
        var currentWidth = 0
        naturalWidths.forEachIndexed { index, naturalWidth ->
            val buttonWidth = naturalWidth.coerceAtMost(width)
            val candidateWidth = currentWidth + (if (currentRow.isEmpty()) 0 else gap) + buttonWidth
            if (currentRow.isNotEmpty() && candidateWidth > width) {
                rows += currentRow
                currentRow = mutableListOf()
                currentWidth = 0
            }
            currentRow += index
            currentWidth += (if (currentRow.size == 1) 0 else gap) + buttonWidth
        }
        if (currentRow.isNotEmpty()) rows += currentRow

        val visibleRows = if (expanded) rows else rows.take(1)
        val measuredRows = visibleRows.map { indices ->
            val availableForButtons = width - gap * (indices.size - 1)
            val naturalTotal = indices.sumOf { naturalWidths[it].coerceAtMost(width) }
            val free = (availableForButtons - naturalTotal).coerceAtLeast(0)
            val addition = free / indices.size
            val remainder = free % indices.size
            indices.mapIndexed { columnIndex, petIndex ->
                val targetWidth = naturalWidths[petIndex].coerceAtMost(width) + addition +
                    if (columnIndex < remainder) 1 else 0
                val pet = pets[petIndex]
                subcompose("pet-${pet.key.petId.value}") {
                    HuaweiFilterButton(
                        text = pet.displayName,
                        icon = pet.selectorIcon(),
                        leadingContent = pet.photoPath()?.let { path -> {
                            ProfileAvatar(path, pet.selectorIcon(), "", photoStore, Modifier.padding(end = 7.dp), 24.dp)
                        } },
                        onClick = { onProfileSelected(pet.key) },
                        modifier = Modifier.testTag(HomePetShortcutsTestTags.pet(pet.key.petId.value))
                            .semantics { contentDescription = "${pet.displayName}, питомец" },
                    )
                }.single().measure(Constraints.fixedWidth(targetWidth))
            }
        }
        val rowHeights = measuredRows.map { row -> row.maxOfOrNull { it.height } ?: 0 }
        val buttonsHeight = rowHeights.sum() + gap * (rowHeights.size - 1).coerceAtLeast(0)
        val add = if (expanded && pets.isEmpty()) subcompose("add") {
            HuaweiFilterButton(
                text = "Добавить питомца",
                onClick = onAddPet,
                modifier = Modifier.testTag(HomePetShortcutsTestTags.Add),
            )
        }.single().measure(childConstraints) else null
        val control = subcompose("toggle") {
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
                        detectVerticalDragGestures(onDragStart = { handled = false }) { change, amount ->
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
        layout(width, buttonsHeight + (add?.height ?: 0) + control.height) {
            var top = 0
            measuredRows.forEachIndexed { rowIndex, row ->
                var left = 0
                row.forEach { placeable ->
                    placeable.placeRelative(left, top)
                    left += placeable.width + gap
                }
                top += rowHeights[rowIndex] + gap
            }
            add?.placeRelative(0, buttonsHeight)
            control.placeRelative(0, buttonsHeight + (add?.height ?: 0))
        }
    }
}
