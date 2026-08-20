package com.example.huaweimisync.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.huaweimisync.ui.icons.HuaweiIcons
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Read-only birth-date input backed by the Material 3 date picker.
 *
 * Keeping the value typed as [LocalDate] prevents UI-specific picker milliseconds from leaking
 * into editor state. Date restrictions and the final display format are intentionally owned by
 * later contract steps.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BirthDateField(
    value: LocalDate?,
    onValueChange: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isError: Boolean = false,
    supportingText: String? = null,
) {
    var isPickerOpen by rememberSaveable { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    LaunchedEffect(isPressed, enabled) {
        if (isPressed && enabled) isPickerOpen = true
    }

    OutlinedTextField(
        value = value?.toString().orEmpty(),
        onValueChange = {},
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                role = Role.Button
                onClick(label = "Открыть календарь") {
                    if (enabled) isPickerOpen = true
                    enabled
                }
            },
        readOnly = true,
        enabled = enabled,
        label = { Text("Дата рождения") },
        placeholder = { Text("Выберите дату") },
        trailingIcon = {
            Icon(
                imageVector = HuaweiIcons.Calendar,
                contentDescription = null,
            )
        },
        singleLine = true,
        isError = isError,
        supportingText = supportingText?.let { message -> { Text(message) } },
        interactionSource = interactionSource,
    )

    if (isPickerOpen) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = value?.toBirthDatePickerMillis(),
        )
        DatePickerDialog(
            onDismissRequest = { isPickerOpen = false },
            confirmButton = {
                TextButton(
                    enabled = pickerState.selectedDateMillis != null,
                    onClick = {
                        val selectedMillis = pickerState.selectedDateMillis ?: return@TextButton
                        onValueChange(birthDateFromPickerMillis(selectedMillis))
                        isPickerOpen = false
                    },
                ) {
                    Text("Выбрать")
                }
            },
            dismissButton = {
                TextButton(onClick = { isPickerOpen = false }) {
                    Text("Отмена")
                }
            },
        ) {
            DatePicker(
                state = pickerState,
                title = { Text("Дата рождения", Modifier.padding(start = 24.dp, top = 16.dp)) },
            )
        }
    }
}

internal fun LocalDate.toBirthDatePickerMillis(): Long =
    atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

internal fun birthDateFromPickerMillis(utcMillis: Long): LocalDate =
    Instant.ofEpochMilli(utcMillis).atZone(ZoneOffset.UTC).toLocalDate()
