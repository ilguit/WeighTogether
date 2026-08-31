package com.palixander.scalesync.ui.reference

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.testTag
import com.palixander.scalesync.R
import com.palixander.scalesync.ui.theme.ReferencePalette
import kotlinx.coroutines.launch

object ReferenceComponentTestTags {
    const val GridOneColumn = "reference-grid-one-column"
    const val GridTwoColumns = "reference-grid-two-columns"
    const val Information = "reference-information"
    const val InfoButton = "reference-info-button"
    const val HelpDialog = "reference-help-dialog"
    const val SourceAction = "reference-source-action"
}

@Composable
fun CompactMetricStatus(
    presentation: ReferenceMetricPresentation,
    modifier: Modifier = Modifier,
) {
    val colors = ReferencePalette.colors(presentation.tone)
    Column(
        modifier = modifier
            .clearAndSetSemantics {
                contentDescription = presentation.compactAccessibilityDescription
            }
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = presentation.title,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = presentation.visualNumber ?: stringResource(R.string.reference_missing_value_symbol),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium,
            )
            if (presentation.visualNumber != null) {
                Text(
                    text = " ${presentation.visibleUnit}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Surface(
            color = colors.container,
            contentColor = colors.content,
            shape = MaterialTheme.shapes.small,
        ) {
            Text(
                text = presentation.status,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
fun ExpandedMetricReference(
    presentation: ReferenceMetricPresentation,
    onInfoClick: () -> Unit,
    modifier: Modifier = Modifier,
    infoButtonModifier: Modifier = Modifier,
) {
    val infoContentDescription = stringResource(
        R.string.reference_info_action,
        presentation.title,
    )
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .testTag(ReferenceComponentTestTags.Information)
                    .clearAndSetSemantics {
                        contentDescription = presentation.accessibilityDescription
                    },
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(presentation.title, style = MaterialTheme.typography.titleSmall)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = presentation.visualNumber ?: stringResource(R.string.reference_missing_value_symbol),
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (presentation.visualNumber != null) {
                        Text(
                            text = " ${presentation.visibleUnit}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                val colors = ReferencePalette.colors(presentation.tone)
                Surface(
                    color = colors.container,
                    contentColor = colors.content,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        presentation.status,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                presentation.zones.forEach { ReferenceZoneRow(it) }
            }
            IconButton(
                onClick = onInfoClick,
                modifier = infoButtonModifier
                    .size(48.dp)
                    .testTag(ReferenceComponentTestTags.InfoButton)
                    .semantics {
                        contentDescription = infoContentDescription
                    },
            ) {
                Icon(
                    imageVector = ReferenceInfoIcon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun ReferenceZoneRow(
    zone: ReferenceZonePresentation,
    modifier: Modifier = Modifier,
) {
    val markerColor = if (zone.isCurrent) {
        ReferencePalette.colors(zone.tone).content
    } else {
        ReferencePalette.NeutralZoneMarker
    }
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier
                .padding(top = 6.dp)
                .size(8.dp)
                .clip(CircleShape)
                .background(markerColor)
                .clearAndSetSemantics { },
        )
        Text(
            text = stringResource(R.string.reference_zone_visual, zone.label, zone.range),
            modifier = Modifier.weight(1f),
            fontWeight = if (zone.isCurrent) FontWeight.Bold else FontWeight.Normal,
            color = if (zone.isCurrent) markerColor else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
fun GroupedMetricReferences(
    groups: List<ReferenceGroupPresentation>,
    onInfoClick: (ReferenceMetricPresentation) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val twoColumns = maxWidth >= 360.dp && LocalDensity.current.fontScale < 1.3f
        val columnCount = if (twoColumns) 2 else 1
        Column(
            modifier = Modifier.testTag(
                if (twoColumns) ReferenceComponentTestTags.GridTwoColumns
                else ReferenceComponentTestTags.GridOneColumn,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            groups.forEach { group ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    group.title?.let {
                        Text(
                            text = it,
                            modifier = Modifier.semantics { heading() },
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    group.metrics.chunked(columnCount).forEach { rowMetrics ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            rowMetrics.forEach { metric ->
                                ExpandedMetricReference(
                                    presentation = metric,
                                    onInfoClick = { onInfoClick(metric) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            repeat(columnCount - rowMetrics.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MetricHelpDialog(
    presentation: ReferenceMetricPresentation,
    snackbarHostState: SnackbarHostState,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sourceLauncher: ReferenceSourceLauncher = AndroidReferenceSourceLauncher(LocalContext.current),
    manualWarning: Boolean = false,
    legacyHeightWarning: Boolean = false,
    usedDataText: String? = null,
) {
    val scope = rememberCoroutineScope()
    val help = presentation.definition.help
    val sourceError = stringResource(R.string.reference_source_open_error)
    AlertDialog(
        modifier = modifier.testTag(ReferenceComponentTestTags.HelpDialog),
        onDismissRequest = onDismissRequest,
        title = { Text(presentation.title) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                HelpSection(R.string.reference_help_value_heading) {
                    Text(stringResource(R.string.reference_help_value_summary, presentation.visualValue, presentation.status))
                }
                if (presentation.zones.isNotEmpty()) {
                    HelpSection(R.string.reference_help_zones_heading) {
                        presentation.zones.forEach { ReferenceZoneRow(it) }
                    }
                }
                HelpSection(R.string.reference_help_meaning_heading) { Text(stringResource(help.meaningRes)) }
                HelpSection(R.string.reference_help_calculation_heading) { Text(stringResource(help.calculationRes)) }
                HelpSection(R.string.reference_help_dependencies_heading) { Text(stringResource(help.dependenciesRes)) }
                usedDataText?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                HelpSection(R.string.reference_help_limitations_heading) {
                    Text(stringResource(help.limitationsRes))
                    help.secondaryCitationRes?.let {
                        Text(
                            stringResource(it),
                            modifier = Modifier.padding(top = 6.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (manualWarning) Text(stringResource(R.string.reference_manual_warning))
                if (legacyHeightWarning) Text(stringResource(R.string.reference_legacy_height_warning))
                HelpSection(R.string.reference_help_source_heading) {
                    Text(stringResource(R.string.reference_set_label), fontWeight = FontWeight.Medium)
                    Text(stringResource(help.sourceNameRes), modifier = Modifier.padding(top = 4.dp))
                }
                HelpSection(R.string.reference_help_disclaimer_heading) {
                    if (help.showBiaDisclaimer) Text(stringResource(R.string.reference_bia_disclaimer))
                    if (help.showBiaContraindications) {
                        Text(
                            stringResource(R.string.reference_bia_contraindications),
                            modifier = Modifier.padding(top = if (help.showBiaDisclaimer) 6.dp else 0.dp),
                        )
                    }
                    Text(
                        stringResource(R.string.reference_general_disclaimer),
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (!sourceLauncher.open(help.sourceUrl)) {
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                message = sourceError,
                                duration = SnackbarDuration.Short,
                            )
                        }
                    }
                },
                modifier = Modifier.testTag(ReferenceComponentTestTags.SourceAction),
            ) {
                Text(stringResource(help.sourceKind.actionLabelRes))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) { Text(stringResource(R.string.reference_close)) }
        },
    )
}

@Composable
private fun HelpSection(
    @androidx.annotation.StringRes headingRes: Int,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(headingRes),
            modifier = Modifier.semantics { heading() },
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp,
        )
        content()
    }
}
