package com.palixander.scalesync

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.FilledTonalButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.palixander.scalesync.ui.icons.HuaweiIcons
import com.palixander.scalesync.ui.profiles.ProfileKey
import com.palixander.scalesync.ui.profiles.ProfilePresentation
import com.palixander.scalesync.ui.profiles.ProfileSelectionUiState

internal object SummaryTopBarTestTags {
    const val Profile = "summary-profile-dropdown"
    fun human(id: String) = "summary-profile-human-$id"
}

/** The profile owns remaining width; large text gets its own row before the three actions. */
@Composable
internal fun SummaryTopBar(
    profileSelection: ProfileSelectionUiState?,
    onProfileSelected: (ProfileKey) -> Unit,
    actions: @Composable RowScope.() -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag(MainScreenTestTags.TopBar),
    ) {
        if (LocalDensity.current.fontScale > 1.3f) {
            HumanProfileDropdown(profileSelection, onProfileSelected, Modifier.fillMaxWidth())
            Row(Modifier.align(Alignment.End), content = actions)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HumanProfileDropdown(profileSelection, onProfileSelected, Modifier.weight(1f))
                actions()
            }
        }
    }
}

@Composable
private fun HumanProfileDropdown(
    selection: ProfileSelectionUiState?,
    onProfileSelected: (ProfileKey) -> Unit,
    modifier: Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val humans = selection?.profiles?.filterIsInstance<ProfilePresentation.Human>().orEmpty()
    val name = (selection?.selectedProfile as? ProfilePresentation.Human)?.displayName
        ?: if (selection == null) "Загрузка профилей…" else "Выберите профиль"
    Box(modifier) {
        FilledTonalButton(
            onClick = { expanded = true },
            enabled = humans.isNotEmpty(),
            modifier = Modifier.heightIn(min = 48.dp)
                .testTag(SummaryTopBarTestTags.Profile)
                .semantics {
                    contentDescription = "Выбор профиля: $name"
                    stateDescription = if (expanded) "Развёрнут" else "Свёрнут"
                },
        ) {
            Icon(HuaweiIcons.Profile, contentDescription = null, modifier = Modifier.size(20.dp).testTag("summary-profile-icon"))
            Text(
                name,
                modifier = Modifier.weight(1f, fill = false).padding(start = 8.dp).testTag(MainScreenTestTags.TopBarTitle),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        DropdownMenu(
            expanded = expanded && humans.isNotEmpty(),
            onDismissRequest = { expanded = false },
        ) {
            humans.forEach { human ->
                DropdownMenuItem(
                    text = { Text(human.displayName) },
                    modifier = Modifier.heightIn(min = 48.dp)
                        .testTag(SummaryTopBarTestTags.human(human.account.id.value))
                        .semantics { selected = human.key == selection?.selectedKey },
                    onClick = {
                        expanded = false
                        onProfileSelected(human.key)
                    },
                )
            }
        }
    }
}
