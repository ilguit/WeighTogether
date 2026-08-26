package com.example.huaweimisync.changelog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.unit.dp
import com.example.huaweimisync.ui.components.HuaweiSurface
import com.example.huaweimisync.ui.theme.HuaweiDimensions

internal object ChangelogScreenTestTags {
    const val List = "changelog-list"
    fun release(version: String) = "changelog-release-$version"
    fun releaseToggle(version: String) = "changelog-release-toggle-$version"
    fun releaseChanges(version: String) = "changelog-release-changes-$version"
}

@Composable
fun ChangelogScreen(
    modifier: Modifier = Modifier,
    releases: List<AppRelease> = AppReleaseHistory.releases,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag(ChangelogScreenTestTags.List),
        contentPadding = PaddingValues(
            start = HuaweiDimensions.ContentPadding,
            top = HuaweiDimensions.CompactItemSpacing + contentPadding.calculateTopPadding(),
            end = HuaweiDimensions.ContentPadding,
            bottom = 28.dp + contentPadding.calculateBottomPadding(),
        ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(HuaweiDimensions.ItemSpacing),
    ) {
        itemsIndexed(releases, key = { _, release -> release.version }) { index, release ->
            ReleaseCard(
                release = release,
                initiallyExpanded = index == 0,
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 720.dp)
                    .testTag(ChangelogScreenTestTags.release(release.version)),
            )
        }
    }
}

@Composable
private fun ReleaseCard(
    release: AppRelease,
    initiallyExpanded: Boolean,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable(release.version) { mutableStateOf(initiallyExpanded) }
    HuaweiSurface(modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(HuaweiDimensions.CompactItemSpacing)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = expanded,
                        role = Role.Button,
                        onValueChange = { expanded = it },
                    )
                    .semantics {
                        heading()
                        stateDescription = if (expanded) "Развёрнуто" else "Свёрнуто"
                        toggleableState = ToggleableState(expanded)
                        onClick(
                            label = if (expanded) "Свернуть версию" else "Развернуть версию",
                            action = null,
                        )
                    }
                    .testTag(ChangelogScreenTestTags.releaseToggle(release.version)),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Версия ${release.version}",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "⌄",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .size(24.dp)
                        .rotate(if (expanded) 180f else 0f)
                        .clearAndSetSemantics { },
                )
            }
            if (expanded) {
                Column(
                    modifier = Modifier.testTag(ChangelogScreenTestTags.releaseChanges(release.version)),
                    verticalArrangement = Arrangement.spacedBy(HuaweiDimensions.CompactItemSpacing),
                ) {
                    release.changes.forEach { change ->
                        Row(horizontalArrangement = Arrangement.spacedBy(HuaweiDimensions.CompactItemSpacing)) {
                            Text(
                                text = "•",
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = change.description,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    text = "Задача #${change.issueNumber}",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
