package com.palixander.scalesync.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DisplayMode
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
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
import androidx.compose.ui.res.stringResource
import com.palixander.scalesync.R
import com.palixander.scalesync.ui.icons.HuaweiIcons
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Read-only birth-date input backed by the Material 3 date picker.
 *
 * Keeping the value typed as [LocalDate] prevents UI-specific picker milliseconds from leaking
 * into editor state. [selectionPolicy] makes the context-specific upper bound explicit: callers
 * use today's date for an account and the measurement date for an unsaved preview.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BirthDateField(
    value: LocalDate?,
    onValueChange: (LocalDate) -> Unit,
    selectionPolicy: BirthDateSelectionPolicy,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isError: Boolean = false,
    supportingText: String? = null,
) {
    val openCalendarLabel = stringResource(R.string.birth_date_open_calendar)
    var isPickerOpen by rememberSaveable { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    LaunchedEffect(isPressed, enabled) {
        if (isPressed && enabled) isPickerOpen = true
    }

    OutlinedTextField(
        value = value?.let(::formatBirthDate).orEmpty(),
        onValueChange = {},
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                role = Role.Button
                onClick(label = openCalendarLabel) {
                    if (enabled) isPickerOpen = true
                    enabled
                }
            },
        readOnly = true,
        enabled = enabled,
        label = { Text(stringResource(R.string.birth_date_label)) },
        placeholder = { Text(stringResource(R.string.birth_date_choose)) },
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
        val selectableDates = remember(selectionPolicy) {
            birthDateSelectableDates(selectionPolicy)
        }
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = value
                ?.takeIf(selectionPolicy::allows)
                ?.toBirthDatePickerMillis(),
            initialDisplayedMonthMillis = initialBirthDatePickerDate(
                value = value,
                today = selectionPolicy.maxDateInclusive,
            )
                .coerceAtMost(selectionPolicy.maxDateInclusive)
                .toBirthDatePickerMillis(),
            initialDisplayMode = DisplayMode.Picker,
            selectableDates = selectableDates,
        )
        val confirmedDate = confirmedBirthDate(
            selectedDateMillis = pickerState.selectedDateMillis,
            selectionPolicy = selectionPolicy,
        )
        DatePickerDialog(
            onDismissRequest = { isPickerOpen = false },
            confirmButton = {
                TextButton(
                    enabled = confirmedDate != null,
                    onClick = {
                        // Re-read and validate on click instead of trusting the previously composed
                        // button state. This keeps the upper bound safe if picker state changes.
                        val selectedDate = confirmedBirthDate(
                            selectedDateMillis = pickerState.selectedDateMillis,
                            selectionPolicy = selectionPolicy,
                        ) ?: return@TextButton
                        onValueChange(selectedDate)
                        isPickerOpen = false
                    },
                ) {
                    Text(stringResource(R.string.action_select))
                }
            },
            dismissButton = {
                TextButton(onClick = { isPickerOpen = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        ) {
            DatePicker(
                state = pickerState,
                title = { Text(stringResource(R.string.birth_date_label), Modifier.padding(start = 24.dp, top = 16.dp)) },
                // Calendar mode includes the month/year menu used for fast year selection.
                showModeToggle = false,
            )
        }
    }
}

internal fun formatBirthDate(date: LocalDate): String =
    date.format(DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM))

internal fun initialBirthDatePickerDate(
    value: LocalDate?,
    today: LocalDate = LocalDate.now(),
): LocalDate = value ?: today.minusYears(30)

internal fun LocalDate.toBirthDatePickerMillis(): Long =
    atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

internal fun birthDateFromPickerMillis(utcMillis: Long): LocalDate =
    Instant.ofEpochMilli(utcMillis).atZone(ZoneOffset.UTC).toLocalDate()

data class BirthDateSelectionPolicy(
    val maxDateInclusive: LocalDate,
) {
    fun allows(date: LocalDate): Boolean = !date.isAfter(maxDateInclusive)

    companion object {
        fun forAccount(today: LocalDate): BirthDateSelectionPolicy =
            BirthDateSelectionPolicy(maxDateInclusive = today)

        fun forUnsavedPreview(measurementDate: LocalDate): BirthDateSelectionPolicy =
            BirthDateSelectionPolicy(maxDateInclusive = measurementDate)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
internal fun birthDateSelectableDates(
    selectionPolicy: BirthDateSelectionPolicy,
): SelectableDates = object : SelectableDates {
    override fun isSelectableDate(utcTimeMillis: Long): Boolean =
        selectionPolicy.allows(birthDateFromPickerMillis(utcTimeMillis))

    override fun isSelectableYear(year: Int): Boolean =
        year <= selectionPolicy.maxDateInclusive.year
}

internal fun confirmedBirthDate(
    selectedDateMillis: Long?,
    selectionPolicy: BirthDateSelectionPolicy,
): LocalDate? = selectedDateMillis
    ?.let(::birthDateFromPickerMillis)
    ?.takeIf(selectionPolicy::allows)
