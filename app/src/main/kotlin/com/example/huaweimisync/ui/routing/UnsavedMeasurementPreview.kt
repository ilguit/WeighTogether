package com.example.huaweimisync.ui.routing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.huaweimisync.core.BodyComposition
import com.example.huaweimisync.core.Sex
import com.example.huaweimisync.ui.accounts.formatLocalizedDecimal
import com.example.huaweimisync.ui.theme.HuaweiColors
import com.example.huaweimisync.ui.theme.HuaweiDimensions
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object UnsavedPreviewTestTags {
    const val Dialog = "unsaved-preview"
    const val UnsavedBadge = "unsaved-preview-badge"
    const val ProfileEditor = "unsaved-preview-profile"
    const val Calculate = "unsaved-preview-calculate"
    const val Result = "unsaved-preview-result"
    const val Close = "unsaved-preview-close"
}

@Composable
fun UnsavedMeasurementPreviewDialog(
    state: UnsavedMeasurementPreviewState,
    callbacks: UnsavedPreviewCallbacks,
    modifier: Modifier = Modifier,
    zoneId: ZoneId = ZoneId.systemDefault(),
) {
    AlertDialog(
        modifier = modifier.testTag(UnsavedPreviewTestTags.Dialog),
        onDismissRequest = { callbacks.onCloseAndDiscard(state.pending.id) },
        title = {
            Row(horizontalArrangement = Arrangement.spacedBy(HuaweiDimensions.CompactItemSpacing)) {
                Text("Просмотр измерения")
                UnsavedBadge()
            }
        },
        text = {
            when (state.step) {
                UnsavedPreviewStep.RAW_SUMMARY -> RawUnsavedSummary(state)
                UnsavedPreviewStep.PROFILE_EDITOR -> PreviewProfileEditor(state, callbacks, zoneId)
                UnsavedPreviewStep.RESULT -> UnsavedResult(requireNotNull(state.result).composition)
            }
        },
        confirmButton = {
            when (state.step) {
                UnsavedPreviewStep.RAW_SUMMARY -> Button(
                    onClick = {
                        callbacks.onStateChange(
                            reduceUnsavedPreview(state, UnsavedPreviewAction.EnterProfileRequested),
                        )
                    },
                ) { Text("Рассчитать показатели") }
                UnsavedPreviewStep.PROFILE_EDITOR -> {
                    val measurementDate = state.pending.measuredAt.atZone(zoneId).toLocalDate()
                    val validation = validateUnsavedPreviewProfile(state.profileDraft, measurementDate)
                    Button(
                        onClick = {
                            calculateUnsavedPreview(state.pending, state.profileDraft, zoneId)?.let {
                                callbacks.onStateChange(
                                    reduceUnsavedPreview(
                                        state,
                                        UnsavedPreviewAction.CalculationCompleted(it),
                                    ),
                                )
                            }
                        },
                        enabled = validation.isValid && !state.isCalculating,
                        modifier = Modifier.testTag(UnsavedPreviewTestTags.Calculate),
                    ) { Text("Рассчитать") }
                }
                UnsavedPreviewStep.RESULT -> Button(
                    onClick = { callbacks.onCloseAndDiscard(state.pending.id) },
                    modifier = Modifier.testTag(UnsavedPreviewTestTags.Close),
                ) { Text("Закрыть") }
            }
        },
        dismissButton = {
            if (state.step == UnsavedPreviewStep.RAW_SUMMARY) {
                TextButton(
                    onClick = { callbacks.onCloseAndDiscard(state.pending.id) },
                    modifier = Modifier.testTag(UnsavedPreviewTestTags.Close),
                ) { Text("Закрыть") }
            } else {
                TextButton(
                    onClick = {
                        callbacks.onStateChange(
                            reduceUnsavedPreview(state, UnsavedPreviewAction.BackRequested),
                        )
                    },
                ) { Text("Назад") }
            }
        },
    )
}

@Composable
private fun UnsavedBadge() {
    Surface(
        color = HuaweiColors.WarningContainer,
        contentColor = HuaweiColors.OnWarningContainer,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.testTag(UnsavedPreviewTestTags.UnsavedBadge),
    ) {
        Text(
            "Не сохранено",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun RawUnsavedSummary(state: UnsavedMeasurementPreviewState) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            formatLocalizedDecimal(state.pending.weightKg) + " кг",
            style = MaterialTheme.typography.headlineSmall,
        )
        Text("Импеданс: ${state.pending.impedanceOhm} Ом")
        Text(
            "Время: ${state.pending.measuredAt.atZone(ZoneId.systemDefault()).format(PreviewTimeFormatter)}",
        )
        Text(
            "Профиль и рассчитанные показатели останутся только в памяти и не будут синхронизированы.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun PreviewProfileEditor(
    state: UnsavedMeasurementPreviewState,
    callbacks: UnsavedPreviewCallbacks,
    zoneId: ZoneId,
) {
    val draft = state.profileDraft
    val validation = validateUnsavedPreviewProfile(
        draft,
        state.pending.measuredAt.atZone(zoneId).toLocalDate(),
    )
    Column(
        modifier = Modifier.testTag(UnsavedPreviewTestTags.ProfileEditor),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Одноразовый профиль", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = draft.heightCm,
            onValueChange = {
                callbacks.onStateChange(
                    state.copy(profileDraft = draft.copy(heightCm = it)),
                )
            },
            label = { Text("Рост, см") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            isError = validation.heightError != null,
            supportingText = validation.heightError?.let { message -> { Text(message) } },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = draft.birthDate,
            onValueChange = {
                callbacks.onStateChange(
                    state.copy(profileDraft = draft.copy(birthDate = it)),
                )
            },
            label = { Text("Дата рождения") },
            placeholder = { Text("ДД.ММ.ГГГГ") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            isError = validation.birthDateError != null,
            supportingText = validation.birthDateError?.let { message -> { Text(message) } },
            modifier = Modifier.fillMaxWidth(),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PreviewSexChoice("Мужской", Sex.MALE, draft.sex) {
                callbacks.onStateChange(state.copy(profileDraft = draft.copy(sex = it)))
            }
            PreviewSexChoice("Женский", Sex.FEMALE, draft.sex) {
                callbacks.onStateChange(state.copy(profileDraft = draft.copy(sex = it)))
            }
        }
        validation.sexError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun PreviewSexChoice(
    label: String,
    value: Sex,
    selectedSex: Sex?,
    onSelect: (Sex) -> Unit,
) {
    val selected = selectedSex == value
    OutlinedButton(
        onClick = { onSelect(value) },
        modifier = Modifier.semantics {
            role = Role.RadioButton
            this.selected = selected
        },
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label)
    }
}

@Composable
private fun UnsavedResult(composition: BodyComposition) {
    Column(
        modifier = Modifier.testTag(UnsavedPreviewTestTags.Result),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Результат только для просмотра", style = MaterialTheme.typography.titleMedium)
        PreviewMetric("Вес", composition.weightKg, "кг")
        PreviewMetric("Индекс массы тела", composition.bmi, "")
        PreviewMetric("Жир", composition.bodyFatPercent, "%")
        PreviewMetric("Вода", composition.waterPercent, "%")
        PreviewMetric("Мышечная масса", composition.muscleMassKg, "кг")
        PreviewMetric("Основной обмен", composition.basalMetabolicRateKcal, "ккал", 0)
        Text(
            "Эти данные не сохранены и не будут отправлены во внешние сервисы.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun PreviewMetric(label: String, value: Double, unit: String, decimals: Int = 1) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("${formatLocalizedDecimal(value, decimals)} $unit".trim())
    }
}

private val PreviewTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
