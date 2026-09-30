package com.palixander.scalesync.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.palixander.scalesync.ui.theme.ScaleSyncColors
import com.palixander.scalesync.ui.theme.ScaleSyncDimensions

/** Draws the palette behind transparent edge-to-edge system bars without consuming insets. */
@Composable
fun ScaleSyncSystemBarBackgrounds(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize()) {
        Spacer(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .windowInsetsTopHeight(WindowInsets.statusBars)
                .background(MaterialTheme.colorScheme.primary),
        )
        Spacer(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .windowInsetsBottomHeight(WindowInsets.navigationBars)
                .background(MaterialTheme.colorScheme.primary),
        )
    }
}

@Composable
fun ScaleSyncSurface(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(ScaleSyncDimensions.ContentPadding),
    containerColor: Color = MaterialTheme.colorScheme.surface,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = containerColor,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Box(Modifier.padding(contentPadding)) { content() }
    }
}

@Composable
fun ScaleSyncSectionTitle(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        modifier = modifier.semantics { heading() },
        color = MaterialTheme.colorScheme.onBackground,
        style = MaterialTheme.typography.titleMedium,
    )
}

@Composable
fun ScaleSyncFilterButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
    leadingContent: (@Composable () -> Unit)? = null,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = ScaleSyncDimensions.TouchTarget)
            .semantics { this.selected = selected },
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        ),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
            contentColor = if (selected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        ),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
    ) {
        leadingContent?.invoke()
        if (leadingContent == null) icon?.let {
            Icon(
                imageVector = it,
                contentDescription = null,
                modifier = Modifier.padding(end = 7.dp).size(18.dp),
            )
        }
        Text(text = text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun ScaleSyncSettingRow(
    icon: ImageVector,
    title: String,
    supportingText: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    titleMaxLines: Int = 2,
    supportingTextMaxLines: Int = 3,
    action: @Composable RowScope.() -> Unit = {},
) {
    val interactionModifier = if (onClick == null) {
        Modifier
    } else {
        Modifier.clickable(role = Role.Button, onClick = onClick)
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(interactionModifier)
            .heightIn(min = ScaleSyncDimensions.TouchTarget)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ScaleSyncRowIcon(icon = icon)
        androidx.compose.foundation.layout.Column(
            modifier = Modifier.padding(horizontal = 12.dp).weight(1f),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = titleMaxLines,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = supportingText,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                maxLines = supportingTextMaxLines,
                overflow = TextOverflow.Ellipsis,
            )
        }
        action()
    }
}

@Composable
fun ScaleSyncRowIcon(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    Surface(
        modifier = modifier.size(ScaleSyncDimensions.RowIconContainer),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.primary,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(ScaleSyncDimensions.Icon),
            )
        }
    }
}

@Composable
fun ScaleSyncIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: IconButtonColors = IconButtonDefaults.iconButtonColors(),
) {
    IconButton(
        onClick = onClick,
        modifier = modifier
            .size(ScaleSyncDimensions.TouchTarget),
        enabled = enabled,
        colors = colors,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(ScaleSyncDimensions.Icon),
        )
    }
}

@Immutable
enum class ScaleSyncStatusTone {
    Success,
    Pending,
    Warning,
    Error,
    Local,
}

@Composable
fun ScaleSyncStatusAction(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: ScaleSyncStatusTone = ScaleSyncStatusTone.Success,
    enabled: Boolean = true,
) {
    val (containerColor, contentColor) = when (tone) {
        ScaleSyncStatusTone.Success -> Color.Transparent to MaterialTheme.colorScheme.primary
        ScaleSyncStatusTone.Pending -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.primary
        ScaleSyncStatusTone.Warning -> ScaleSyncColors.WarningContainer to ScaleSyncColors.Warning
        ScaleSyncStatusTone.Error -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.error
        ScaleSyncStatusTone.Local -> ScaleSyncColors.LocalContainer to ScaleSyncColors.Local
    }
    ScaleSyncIconButton(
        icon = icon,
        contentDescription = contentDescription,
        onClick = onClick,
        modifier = modifier.widthIn(min = ScaleSyncDimensions.TouchTarget),
        enabled = enabled,
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = containerColor,
            contentColor = contentColor,
            disabledContainerColor = containerColor.copy(alpha = 0.4f),
            disabledContentColor = contentColor.copy(alpha = 0.4f),
        ),
    )
}
