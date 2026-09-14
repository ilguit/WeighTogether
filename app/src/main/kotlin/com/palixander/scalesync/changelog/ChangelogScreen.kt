package com.palixander.scalesync.changelog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.palixander.scalesync.BuildConfig
import com.palixander.scalesync.ui.components.HuaweiSurface
import com.palixander.scalesync.ui.icons.HuaweiIcons
import com.palixander.scalesync.ui.theme.HuaweiDimensions

internal object ChangelogScreenTestTags {
    const val List = "changelog-list"
    const val LatestChanges = "changelog-latest-changes"
    const val LatestChangesHeading = "changelog-latest-changes-heading"
    const val LatestChangesContent = "changelog-latest-changes-content"
    const val PreviousReleases = "changelog-previous-releases"
    const val PreviousReleasesToggle = "changelog-previous-releases-toggle"
    const val PreviousReleasesIndicator = "changelog-previous-releases-indicator"
    const val PreviousReleasesTitle = "changelog-previous-releases-title"
    const val PreviousReleasesContent = "changelog-previous-releases-content"
    fun release(version: String) = "changelog-release-$version"
    fun releaseChanges(version: String) = "changelog-release-changes-$version"
}

@Composable
fun ChangelogScreen(
    modifier: Modifier = Modifier,
    releases: List<AppRelease> = AppReleaseHistory.releases,
    latestChanges: List<ReleaseChange> = AppReleaseHistory.latestChanges,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    var previousExpanded by rememberSaveable { mutableStateOf(false) }
    LazyColumn(
        modifier = modifier.fillMaxSize().testTag(ChangelogScreenTestTags.List),
        contentPadding = PaddingValues(
            start = HuaweiDimensions.ContentPadding,
            top = HuaweiDimensions.CompactItemSpacing + contentPadding.calculateTopPadding(),
            end = HuaweiDimensions.ContentPadding,
            bottom = 28.dp + contentPadding.calculateBottomPadding(),
        ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(HuaweiDimensions.ItemSpacing),
    ) {
        if (latestChanges.isNotEmpty()) {
            item(key = "latest-changes") {
                LatestChangesCard(
                    changes = latestChanges,
                    modifier = Modifier.fillMaxWidth().widthIn(max = 720.dp)
                        .testTag(ChangelogScreenTestTags.LatestChanges),
                )
            }
        }
        releases.firstOrNull()?.let { release ->
            item(key = release.version) {
                ReleaseCard(release, cardModifier(release.version))
            }
        }
        val previous = releases.drop(1)
        if (previous.isNotEmpty()) {
            item(key = "previous-releases") {
                PreviousReleasesCard(
                    releases = previous,
                    expanded = previousExpanded,
                    onExpandedChange = { previousExpanded = it },
                    modifier = Modifier.fillMaxWidth().widthIn(max = 720.dp)
                        .testTag(ChangelogScreenTestTags.PreviousReleases),
                )
            }
        }
    }
}

@Composable
private fun LatestChangesCard(changes: List<ReleaseChange>, modifier: Modifier = Modifier) {
    HuaweiSurface(modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(HuaweiDimensions.CompactItemSpacing)) {
            Text(
                "Последние изменения (${BuildConfig.VERSION_CODE})",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() }
                    .testTag(ChangelogScreenTestTags.LatestChangesHeading),
            )
            ReleaseChanges(
                changes = changes,
                modifier = Modifier.testTag(ChangelogScreenTestTags.LatestChangesContent),
            )
        }
    }
}

private fun cardModifier(version: String) = Modifier.fillMaxWidth().widthIn(max = 720.dp)
    .testTag(ChangelogScreenTestTags.release(version))

@Composable
private fun ReleaseCard(release: AppRelease, modifier: Modifier = Modifier) {
    HuaweiSurface(modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(HuaweiDimensions.CompactItemSpacing)) {
            ReleaseHeading(release.version)
            ReleaseChanges(release)
        }
    }
}

@Composable
private fun PreviousReleasesCard(
    releases: List<AppRelease>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    HuaweiSurface(modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(HuaweiDimensions.CompactItemSpacing)) {
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = HuaweiDimensions.TouchTarget)
                    .clickable(
                        role = Role.Button,
                        onClickLabel = if (expanded) "Свернуть предыдущие версии" else "Развернуть предыдущие версии",
                    ) { onExpandedChange(!expanded) }
                    .semantics {
                        heading()
                        stateDescription = if (expanded) "Развёрнуто" else "Свёрнуто"
                    }
                    .testTag(ChangelogScreenTestTags.PreviousReleasesToggle),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Предыдущие версии",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f).padding(end = HuaweiDimensions.CompactItemSpacing)
                        .testTag(ChangelogScreenTestTags.PreviousReleasesTitle),
                )
                Icon(
                    HuaweiIcons.ChevronDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp).rotate(if (expanded) 180f else 0f)
                        .testTag(ChangelogScreenTestTags.PreviousReleasesIndicator),
                )
            }
            if (expanded) {
                Column(
                    modifier = Modifier.testTag(ChangelogScreenTestTags.PreviousReleasesContent),
                    verticalArrangement = Arrangement.spacedBy(HuaweiDimensions.ItemSpacing),
                ) {
                    releases.forEach { release ->
                        Column(
                            modifier = Modifier.testTag(ChangelogScreenTestTags.release(release.version)),
                            verticalArrangement = Arrangement.spacedBy(HuaweiDimensions.CompactItemSpacing),
                        ) {
                            ReleaseHeading(release.version)
                            ReleaseChanges(release)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReleaseHeading(version: String) {
    Text(
        "Версия $version",
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.semantics { heading() },
    )
}

@Composable
private fun ReleaseChanges(release: AppRelease) {
    ReleaseChanges(
        changes = release.changes,
        modifier = Modifier.testTag(ChangelogScreenTestTags.releaseChanges(release.version)),
    )
}

@Composable
private fun ReleaseChanges(changes: List<ReleaseChange>, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(HuaweiDimensions.CompactItemSpacing),
    ) {
        changes.forEach { change ->
            Row(horizontalArrangement = Arrangement.spacedBy(HuaweiDimensions.CompactItemSpacing)) {
                Text("•", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                Column(modifier = Modifier.weight(1f)) {
                    Text(change.description, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Задача #${change.issueNumber}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}
