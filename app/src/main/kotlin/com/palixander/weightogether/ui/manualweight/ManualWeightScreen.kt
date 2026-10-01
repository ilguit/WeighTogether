package com.palixander.weightogether.ui.manualweight

import android.app.TimePickerDialog
import android.text.format.DateFormat
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.palixander.weightogether.ui.components.ScaleSyncIconButton
import com.palixander.weightogether.ui.icons.ScaleSyncIcons
import com.palixander.weightogether.R
import com.palixander.weightogether.ui.text.UiText
import com.palixander.weightogether.ui.text.resolve
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

object ManualWeightTags {
    const val Screen = "manual-weight-screen"
    const val Weight = "manual-weight-input"
    const val Date = "manual-weight-date"
    const val Time = "manual-weight-time"
    const val Save = "manual-weight-save"
    const val Back = "manual-weight-back"
    const val Duplicate = "manual-weight-duplicate"
    const val ConfirmDuplicate = "manual-weight-confirm-duplicate"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualWeightScreen(
    draft: ManualWeightDraft,
    onWeightChanged: (String) -> Unit,
    onDateChanged: (LocalDate) -> Unit,
    onTimeChanged: (LocalTime) -> Unit,
    onSave: () -> Unit,
    onConfirmDuplicate: () -> Unit,
    onDismissDuplicate: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var datePickerOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val resources = LocalResources.current
    val dateText = draft.date.format(DateTimeFormatter.ofPattern("dd.MM.uuuu"))
    val timeText = draft.time.format(DateTimeFormatter.ofPattern("HH:mm"))
    Column(
        modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState())
            .padding(16.dp).testTag(ManualWeightTags.Screen),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row {
            ScaleSyncIconButton(
                icon = ScaleSyncIcons.Back,
                contentDescription = stringResource(R.string.manual_weight_back),
                onClick = onBack,
                modifier = Modifier.testTag(ManualWeightTags.Back),
            )
            Text(stringResource(R.string.manual_weight_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
        }
        Text(draft.ownerName, style = MaterialTheme.typography.titleMedium)
        if (!draft.ownerAvailable) {
            FormError(stringResource(R.string.manual_weight_owner_unavailable))
        }
        OutlinedTextField(
            value = draft.weight,
            onValueChange = onWeightChanged,
            label = { Text(stringResource(R.string.manual_weight_label)) },
            singleLine = true,
            enabled = !draft.saving,
            isError = draft.weightError != null,
            supportingText = draft.weightError?.let { message -> { FormError(message.resolve(resources)) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth().testTag(ManualWeightTags.Weight),
        )
        OutlinedButton(
            onClick = { datePickerOpen = true },
            enabled = !draft.saving,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(ManualWeightTags.Date)
                .semantics { contentDescription = resources.getString(R.string.manual_weight_date_cd, dateText) },
        ) { Text(stringResource(R.string.manual_weight_date, dateText)) }
        OutlinedButton(
            onClick = {
                TimePickerDialog(context, { _, hour, minute -> onTimeChanged(LocalTime.of(hour, minute)) },
                    draft.time.hour, draft.time.minute, DateFormat.is24HourFormat(context)).show()
            },
            enabled = !draft.saving,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(ManualWeightTags.Time)
                .semantics { contentDescription = resources.getString(R.string.manual_weight_time_cd, timeText) },
        ) { Text(stringResource(R.string.manual_weight_time, timeText)) }
        draft.dateError?.let { FormError(it.resolve(resources)) }
        draft.error?.let { FormError(it.resolve(resources)) }
        Button(
            onClick = onSave,
            enabled = draft.canSave,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(ManualWeightTags.Save),
        ) {
            if (draft.saving) {
                CircularProgressIndicator(Modifier.size(24.dp).semantics { contentDescription = resources.getString(R.string.manual_weight_saving_cd) })
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.state_saving))
            } else Text(stringResource(R.string.action_save))
        }
    }
    if (datePickerOpen) {
        val today = LocalDate.now()
        val picker = rememberDatePickerState(
            initialSelectedDateMillis = draft.date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            yearRange = 1..maxOf(today.year, draft.date.year),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                    !Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate().isAfter(today)
                override fun isSelectableYear(year: Int): Boolean = year <= today.year
            },
        )
        DatePickerDialog(
            onDismissRequest = { datePickerOpen = false },
            confirmButton = {
                TextButton(onClick = {
                    picker.selectedDateMillis?.let { onDateChanged(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                    datePickerOpen = false
                }, enabled = picker.selectedDateMillis != null) { Text(stringResource(R.string.action_select)) }
            },
            dismissButton = { TextButton(onClick = { datePickerOpen = false }) { Text(stringResource(R.string.action_cancel)) } },
        ) { DatePicker(picker, modifier = Modifier.verticalScroll(rememberScrollState())) }
    }
    if (draft.duplicate) {
        AlertDialog(
            modifier = Modifier.testTag(ManualWeightTags.Duplicate),
            onDismissRequest = onDismissDuplicate,
            title = { Text(stringResource(R.string.manual_weight_duplicate_title)) },
            text = { Text(stringResource(R.string.manual_weight_duplicate_message, draft.weight, dateText, timeText)) },
            confirmButton = {
                TextButton(onClick = onConfirmDuplicate, modifier = Modifier.testTag(ManualWeightTags.ConfirmDuplicate)) { Text(stringResource(R.string.manual_weight_add_another)) }
            },
            dismissButton = { TextButton(onClick = onDismissDuplicate) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun FormError(message: String) {
    Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
}
