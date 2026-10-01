package com.palixander.weightogether.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.palixander.weightogether.ui.icons.ScaleSyncIcons
import com.palixander.weightogether.ui.theme.ScaleSyncColors
import com.palixander.weightogether.ui.theme.ScaleSyncDimensions

/** A settings-only surface that owns the outline and clipping for all of its rows. */
@Composable
internal fun SettingsGroup(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = MaterialTheme.shapes.large
    Surface(
        modifier = modifier.fillMaxWidth().clip(shape),
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(content = content)
    }
}

/**
 * A flexible settings row. Leading identity, state, and navigation affordance are deliberately
 * separate slots so a decorative chevron can never replace the row's identifying icon.
 */
@Composable
internal fun SettingsGroupRow(
    leadingIcon: ImageVector,
    title: String,
    supportingText: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    leadingIconTag: String? = null,
    trailingTag: String? = null,
    status: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = { SettingsTrailingChevron(tag = trailingTag) },
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val compact = maxWidth < SettingsCompactWidth
        val horizontalPadding = if (compact) 11.dp else 14.dp
        val itemSpacing = if (compact) 9.dp else 12.dp
        val leadingSize = if (compact) 34.dp else 38.dp
        val interactionSource = remember { MutableInteractionSource() }
        val focused by interactionSource.collectIsFocusedAsState()
        val pressed by interactionSource.collectIsPressedAsState()
        val interactionModifier = if (onClick == null) {
            Modifier
        } else {
            Modifier.clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
        }
        val interactionColor = when {
            pressed -> ScaleSyncColors.SurfaceContainerHigh
            focused -> ScaleSyncColors.SurfaceInfo
            else -> Color.Transparent
        }

        Row(
            modifier = modifier
                .fillMaxWidth()
                .background(interactionColor)
                .then(interactionModifier)
                .heightIn(min = 68.dp)
                .padding(horizontal = horizontalPadding, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SettingsLeadingIcon(leadingIcon, leadingSize, leadingIconTag)
            Column(
                modifier = Modifier.padding(start = itemSpacing).weight(1f),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = supportingText,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            status?.let {
                Box(Modifier.padding(start = itemSpacing), contentAlignment = Alignment.Center) { it() }
            }
            trailing?.let {
                Box(Modifier.padding(start = itemSpacing), contentAlignment = Alignment.Center) { it() }
            }
        }
    }
}

@Composable
internal fun SettingsGroupDivider(modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val compact = maxWidth < SettingsCompactWidth
        val start = if (compact) 54.dp else 64.dp
        HorizontalDivider(
            modifier = Modifier.padding(start = start),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

@Composable
internal fun SettingsStatusMark(
    icon: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = tint,
        modifier = modifier.size(20.dp),
    )
}

@Composable
internal fun SettingsTrailingChevron(modifier: Modifier = Modifier, tag: String? = null) {
    Icon(
        imageVector = ScaleSyncIcons.ChevronRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .size(20.dp)
            .then(if (tag == null) Modifier else Modifier.testTag(tag)),
    )
}

@Composable
private fun SettingsLeadingIcon(icon: ImageVector, size: Dp, tag: String?) {
    Surface(
        modifier = Modifier.size(size),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.primary,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier
                    .size(ScaleSyncDimensions.Icon)
                    .then(if (tag == null) Modifier else Modifier.testTag(tag)),
            )
        }
    }
}

private val SettingsCompactWidth = 360.dp
