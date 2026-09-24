package com.palixander.scalesync.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.palixander.scalesync.domain.MeasurementOrigin
import com.palixander.scalesync.ui.icons.HuaweiIcons

/** Fixed compact lane: provenance never changes the weight's available width or line height. */
@Composable
internal fun HistoryMeasurementIndicators(
    origin: MeasurementOrigin,
    isManuallyEdited: Boolean,
    tagPrefix: String,
) {
    val manual = origin == MeasurementOrigin.MANUAL
    val explanation = listOfNotNull(
        "Введено вручную".takeIf { manual },
        "Изменено вручную".takeIf { isManuallyEdited },
    ).joinToString(". ")
    var explaining by remember { mutableStateOf(false) }
    var restoreFocus by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(explaining, restoreFocus) {
        if (!explaining && restoreFocus) {
            withFrameNanos { }
            focusRequester.requestFocus()
            restoreFocus = false
        }
    }
    val dismiss = { explaining = false; restoreFocus = true }
    Box(
        Modifier.width(48.dp).height(24.dp).then(
            if (explanation.isNotEmpty()) {
                // Foundation expands the touch target to 48 dp without stretching the text row.
                Modifier.focusRequester(focusRequester)
                    .clickable(role = Role.Button) { explaining = true }
                    .semantics { contentDescription = explanation }
                    .testTag("$tagPrefix-indicators")
            } else Modifier,
        ),
        contentAlignment = Alignment.Center,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (manual) Icon(
                HuaweiIcons.Keyboard,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp).testTag("$tagPrefix-manual-origin"),
            )
            if (isManuallyEdited) Icon(
                HuaweiIcons.Edit,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp).testTag("$tagPrefix-manually-edited"),
            )
        }
    }
    if (explaining) AlertDialog(
        onDismissRequest = dismiss,
        text = { Text(explanation) },
        confirmButton = {
            TextButton(onClick = dismiss, modifier = Modifier.testTag("$tagPrefix-indicators-dismiss")) {
                Text("Понятно")
            }
        },
    )
}
