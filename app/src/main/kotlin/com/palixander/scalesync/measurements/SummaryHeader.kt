package com.palixander.scalesync.measurements

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.palixander.scalesync.ui.components.HuaweiStatusTone
import com.palixander.scalesync.ui.theme.HuaweiColors

/** Keeps the actions together; moves date above when centering would cause overlap. */
@Composable
internal fun SummaryHeader(
    date: @Composable () -> Unit,
    history: @Composable () -> Unit,
    actions: @Composable () -> Unit,
) {
    Layout(
        modifier = Modifier.testTag("summary-header"),
        content = {
            Box { date() }
            Box { history() }
            Box { actions() }
        },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val actionsPlaceable = measurables[2].measure(loose)
        val historyPlaceable = measurables[1].measure(
            loose.copy(maxWidth = (width - actionsPlaceable.width).coerceAtLeast(0)),
        )
        val datePlaceable = measurables[0].measure(loose)
        val centerX = (width - historyPlaceable.width) / 2
        val actionsX = width - actionsPlaceable.width
        val oneRow = datePlaceable.width + 4.dp.roundToPx() <= centerX &&
            centerX + historyPlaceable.width <= actionsX
        val rowHeight = maxOf(historyPlaceable.height, actionsPlaceable.height)
        val dateHeight = if (oneRow) 0 else datePlaceable.height
        val height = if (oneRow) maxOf(rowHeight, datePlaceable.height) else dateHeight + rowHeight
        layout(width, height) {
            datePlaceable.placeRelative(0, if (oneRow) (height - datePlaceable.height) / 2 else 0)
            historyPlaceable.placeRelative(
                centerX.coerceAtMost(actionsX - historyPlaceable.width).coerceAtLeast(0),
                dateHeight + (height - dateHeight - historyPlaceable.height) / 2,
            )
            actionsPlaceable.placeRelative(actionsX, dateHeight + (height - dateHeight - actionsPlaceable.height) / 2)
        }
    }
}

/** Small status background inside an unchanged, non-overlapping 48dp touch target. */
@Composable
internal fun SummaryStatusAction(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    tone: HuaweiStatusTone,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val (background, foreground) = when (tone) {
        HuaweiStatusTone.Success -> Color.Transparent to MaterialTheme.colorScheme.primary
        HuaweiStatusTone.Pending -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.primary
        HuaweiStatusTone.Warning -> HuaweiColors.WarningContainer to HuaweiColors.Warning
        HuaweiStatusTone.Error -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.error
        HuaweiStatusTone.Local -> HuaweiColors.LocalContainer to HuaweiColors.Local
    }
    IconButton(onClick = onClick, enabled = enabled, modifier = modifier.size(48.dp)) {
        Surface(
            modifier = Modifier.size(28.dp),
            shape = CircleShape,
            color = background.copy(alpha = background.alpha * if (enabled) 1f else 0.4f),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    icon,
                    contentDescription,
                    modifier = Modifier.size(20.dp),
                    tint = foreground.copy(alpha = if (enabled) 1f else 0.4f),
                )
            }
        }
    }
}
