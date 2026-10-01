package com.palixander.weightogether.ui.reference

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.palixander.weightogether.ui.theme.ReferencePalette

internal object ReferenceRangeScaleTestTags {
    const val Scale = "reference-range-scale"
    const val Boundaries = "reference-range-scale-boundaries"
    const val Marker = "reference-range-scale-marker"

    fun segment(index: Int) = "reference-range-scale-segment-$index"
    fun boundary(index: Int) = "reference-range-scale-boundary-$index"
    fun zone(index: Int) = "reference-range-scale-zone-$index"
    fun currentZone(category: String) = "reference-range-scale-current-zone-$category"
}

/**
 * Read-only visualization of a rated metric. Its meaning is deliberately supplied by the
 * containing metric card, so this component contributes no spoken accessibility content.
 */
@Composable
fun ReferenceRangeScale(
    presentation: ReferenceMetricPresentation,
    modifier: Modifier = Modifier,
) {
    val markerFraction = presentation.scaleValue?.let {
        referenceScaleMarkerFraction(it, presentation.zones)
    } ?: return
    val boundaries = presentation.zones.dropLast(1).mapIndexed { index, zone ->
        zone.upperBoundaryLabel ?: presentation.zones[index + 1].lowerBoundaryLabel.orEmpty()
    }
    val currentTone = presentation.zones.firstOrNull(ReferenceZonePresentation::isCurrent)?.tone
        ?: return

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .clearAndSetSemantics { }
            .testTag(ReferenceRangeScaleTestTags.Scale),
    ) {
        val scaleWidth = maxWidth
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            BoundaryLabelRow(labels = boundaries)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(20.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp)),
                ) {
                    presentation.zones.forEachIndexed { index, zone ->
                        Box(
                            Modifier
                                .weight(1f)
                                .height(8.dp)
                                .testTag(ReferenceRangeScaleTestTags.segment(index))
                                .background(ReferencePalette.colors(zone.tone).scale),
                        )
                    }
                }
                val markerColor = ReferencePalette.colors(currentTone).scale
                val markerCenterColor = MaterialTheme.colorScheme.surface
                Canvas(
                    modifier = Modifier
                        .offset(x = scaleWidth * markerFraction.toFloat() - 8.dp)
                        .width(16.dp)
                        .height(16.dp)
                        .testTag(ReferenceRangeScaleTestTags.Marker),
                ) {
                    drawCircle(color = markerColor, radius = size.minDimension / 2f)
                    drawCircle(
                        color = markerCenterColor,
                        radius = size.minDimension / 2f - 3.dp.toPx(),
                    )
                    drawCircle(
                        color = markerColor,
                        radius = size.minDimension / 2f - 1.5.dp.toPx(),
                        style = Stroke(width = 1.dp.toPx()),
                    )
                }
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                presentation.zones.forEachIndexed { index, zone ->
                    val zoneColor = ReferencePalette.colors(zone.tone).content
                    Text(
                        text = zone.label,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 2.dp)
                            .testTag(
                                if (zone.isCurrent) {
                                    ReferenceRangeScaleTestTags.currentZone(zone.category.name)
                                } else {
                                    ReferenceRangeScaleTestTags.zone(index)
                                },
                            ),
                        color = if (zone.isCurrent) zoneColor else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (zone.isCurrent) FontWeight.Medium else FontWeight.Normal,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun BoundaryLabelRow(
    labels: List<String>,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(ReferenceRangeScaleTestTags.Boundaries),
    ) {
        Spacer(Modifier.weight(0.5f))
        labels.forEachIndexed { index, label ->
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    modifier = Modifier.testTag(ReferenceRangeScaleTestTags.boundary(index)),
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.weight(0.5f))
    }
}
