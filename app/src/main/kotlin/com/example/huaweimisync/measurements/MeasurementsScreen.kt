package com.example.huaweimisync.measurements

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun MeasurementsScreen(
    state: MeasurementsUiState,
    callbacks: MeasurementsCallbacks,
    modifier: Modifier = Modifier,
) {
    val editor = state.editor
    if (editor == null) {
        MeasurementHistory(
            state = state,
            callbacks = callbacks,
            modifier = modifier,
        )
    } else {
        MeasurementEditor(
            editor = editor,
            callbacks = callbacks,
            modifier = modifier,
        )
    }
}

@Composable
private fun MeasurementHistory(
    state: MeasurementsUiState,
    callbacks: MeasurementsCallbacks,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize()) {
        if (state.isLoading && state.measurements.isEmpty()) {
            CircularProgressIndicator(Modifier.align(Alignment.Center))
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Text("История измерений", style = MaterialTheme.typography.headlineSmall)
                }
                if (state.isLoading) {
                    item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                }
                if (state.measurements.isEmpty()) {
                    item { EmptyHistoryCard() }
                } else {
                    items(state.measurements, key = { it.id }) { item ->
                        MeasurementHistoryCard(item, callbacks)
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }

        state.deleteConfirmation?.let { confirmation ->
            DeleteMeasurementDialog(
                confirmation = confirmation,
                onConfirm = { callbacks.onDeleteConfirmed(confirmation.measurementId) },
                onDismiss = callbacks.onDeleteDismissed,
            )
        }
    }
}

@Composable
private fun EmptyHistoryCard() {
    Card(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Пока нет измерений", style = MaterialTheme.typography.titleMedium)
            Text(
                "Стабильные измерения с весов появятся здесь.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun MeasurementHistoryCard(
    item: MeasurementUiItem,
    callbacks: MeasurementsCallbacks,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                "${formatMeasurementValue(MeasurementField.WEIGHT_KG, item.values.weightKg)} кг",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                formatMeasurementDateTime(item.measuredAtEpochMillis),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "ИМТ ${formatMeasurementValue(MeasurementField.BMI, item.values.bmi)} · " +
                    "жир ${formatMeasurementValue(MeasurementField.BODY_FAT_PERCENT, item.values.bodyFatPercent)}% · " +
                    "вода ${formatMeasurementValue(MeasurementField.WATER_PERCENT, item.values.waterPercent)}%",
            )
            Text(
                "Мышцы ${formatMeasurementValue(MeasurementField.MUSCLE_MASS_KG, item.values.muscleMassKg)} кг · " +
                    "кости ${formatMeasurementValue(MeasurementField.BONE_MASS_KG, item.values.boneMassKg)} кг · " +
                    "обмен ${formatMeasurementValue(MeasurementField.BASAL_METABOLIC_RATE_KCAL, item.values.basalMetabolicRateKcal)} ккал",
            )
            HorizontalDivider(Modifier.padding(vertical = 2.dp))
            if (item.isLocalOnly) {
                Text(
                    "Только локально · повторная синхронизация отключена",
                    color = MaterialTheme.colorScheme.secondary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            item.sync.directions.forEach { direction -> SyncStatusLine(direction) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        callbacks.onEditRequested(item.id, MeasurementEditorOrigin.HISTORY)
                    },
                    enabled = !item.isOperationInProgress,
                ) {
                    Text("Изменить")
                }
                OutlinedButton(
                    onClick = { callbacks.onDeleteRequested(item.id) },
                    enabled = !item.isOperationInProgress,
                ) {
                    Text("Удалить")
                }
            }
            if (item.canRetry) {
                TextButton(
                    onClick = { callbacks.onRetryRequested(item.id) },
                    enabled = !item.isOperationInProgress,
                ) {
                    Text("Повторить отправку")
                }
            }
        }
    }
}

@Composable
private fun SyncStatusLine(
    direction: MeasurementSyncDirectionPresentation,
) {
    Text(
        buildString {
            append(direction.label)
            append(": ")
            append(direction.state.label)
            if (direction.message.isNotBlank()) {
                append(" — ")
                append(direction.message)
            }
        },
        style = MaterialTheme.typography.bodySmall,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MeasurementEditor(
    editor: MeasurementEditorState,
    callbacks: MeasurementsCallbacks,
    modifier: Modifier = Modifier,
) {
    val parsedValues = editor.draft.parsedValuesOrNull()
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Изменить измерение") },
                navigationIcon = {
                    TextButton(
                        onClick = callbacks.onEditorDismissed,
                        enabled = !editor.isSaving,
                    ) {
                        Text("Отмена")
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            parsedValues?.let { values ->
                                callbacks.onEditorSaveRequested(editor.measurementId, values)
                            }
                        },
                        enabled = editor.canSave && parsedValues != null,
                    ) {
                        Text(if (editor.isSaving) "Сохранение…" else "Сохранить")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        formatMeasurementDateTime(editor.measuredAtEpochMillis),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        "Дата, устройство и исходные данные не изменяются. Производные значения не пересчитываются.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "Изменится только локальная запись. Уже отправленные данные в Health Connect и Huawei Health не обновятся и не удалятся.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            items(MeasurementField.entries, key = { it.name }) { field ->
                val validation = editor.draft.validation(field)
                OutlinedTextField(
                    value = editor.draft[field],
                    onValueChange = { value -> callbacks.onEditorFieldChanged(field, value) },
                    label = { Text(field.inputLabel) },
                    isError = validation.error != null,
                    supportingText = validation.error?.let { error ->
                        { Text(error) }
                    },
                    enabled = !editor.isSaving,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (field.wholeNumber) {
                            KeyboardType.Number
                        } else {
                            KeyboardType.Decimal
                        },
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            parsedValues?.let { values ->
                                callbacks.onEditorSaveRequested(editor.measurementId, values)
                            }
                        },
                        enabled = editor.canSave && parsedValues != null,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (editor.isSaving) "Сохранение…" else "Сохранить")
                    }
                    OutlinedButton(
                        onClick = callbacks.onEditorDismissed,
                        enabled = !editor.isSaving,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Отмена")
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun DeleteMeasurementDialog(
    confirmation: MeasurementDeleteConfirmation,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!confirmation.isDeleting) onDismiss() },
        title = { Text("Удалить измерение?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "${formatMeasurementDateTime(confirmation.measuredAtEpochMillis)} · " +
                        "${formatMeasurementValue(MeasurementField.WEIGHT_KG, confirmation.weightKg)} кг",
                )
                Text(
                    "Будет удалена только локальная запись. Данные, уже записанные в Health Connect и Huawei Health, останутся без изменений.",
                )
                Text("Удаление нельзя отменить.")
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = !confirmation.isDeleting,
            ) {
                Text(if (confirmation.isDeleting) "Удаление…" else "Удалить")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !confirmation.isDeleting,
            ) {
                Text("Отмена")
            }
        },
    )
}

fun formatMeasurementDateTime(
    epochMillis: Long,
    zoneId: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): String = DateTimeFormatter
    .ofPattern("dd.MM.yyyy HH:mm", locale)
    .format(Instant.ofEpochMilli(epochMillis).atZone(zoneId))

fun formatMeasurementValue(
    field: MeasurementField,
    value: Double,
    locale: Locale = Locale.getDefault(),
): String = String.format(locale, "%.${field.decimalPlaces}f", value)
