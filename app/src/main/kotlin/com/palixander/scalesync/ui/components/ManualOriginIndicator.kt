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
fun ManualOriginIndicator(origin: MeasurementOrigin, modifier: Modifier = Modifier) {
    if (origin != MeasurementOrigin.MANUAL) return
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
        icon = HuaweiIcons.Keyboard,
        contentDescription = "Введено вручную",
        onClick = { explaining = true },
        modifier = modifier.focusRequester(focusRequester),
    )
    if (explaining) AlertDialog(
        onDismissRequest = dismiss,
        text = { Text("Введено вручную") },
        confirmButton = {
            TextButton(onClick = dismiss, modifier = Modifier.testTag("manual-origin-dismiss")) {
                Text("Понятно")
            }
        },
    )
}
