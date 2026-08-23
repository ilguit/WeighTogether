package com.example.huaweimisync.changelog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.huaweimisync.ui.components.HuaweiSurface
import com.example.huaweimisync.ui.theme.HuaweiDimensions

internal object ChangelogScreenTestTags {
    const val List = "changelog-list"
    fun release(version: String) = "changelog-release-$version"
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
        items(releases, key = AppRelease::version) { release ->
            ReleaseCard(
                release = release,
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
    modifier: Modifier = Modifier,
) {
    HuaweiSurface(modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(HuaweiDimensions.CompactItemSpacing)) {
            Text(
                text = "Версия ${release.version}",
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleMedium,
            )
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
