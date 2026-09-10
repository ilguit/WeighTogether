package com.palixander.scalesync.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import com.palixander.scalesync.domain.MeasurementOrigin
import com.palixander.scalesync.ui.icons.HuaweiIcons

/** Origin remains independent of later edits and synchronization status. */
@Composable
fun MeasurementOriginIndicator(origin: MeasurementOrigin, modifier: Modifier = Modifier) {
    val presentation = when (origin) {
        MeasurementOrigin.MANUAL -> OriginPresentation(
            icon = HuaweiIcons.Keyboard,
            accessibleName = "Источник: ручной ввод",
            explanation = "Измерение введено вручную",
        )
        MeasurementOrigin.SCALE -> OriginPresentation(
            icon = HuaweiIcons.Scale,
            accessibleName = "Источник: весы",
            explanation = "Измерение получено с весов",
        )
        MeasurementOrigin.LEGACY -> return
    }
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
    HuaweiIconButton(
        icon = presentation.icon,
        contentDescription = presentation.accessibleName,
        onClick = { explaining = true },
        modifier = modifier.focusRequester(focusRequester),
    )
    if (explaining) AlertDialog(
        onDismissRequest = dismiss,
        text = { Text(presentation.explanation) },
        confirmButton = {
            TextButton(onClick = dismiss, modifier = Modifier.testTag("measurement-origin-dismiss")) {
                Text("Понятно")
            }
        },
    )
}

private data class OriginPresentation(
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val accessibleName: String,
    val explanation: String,
)
