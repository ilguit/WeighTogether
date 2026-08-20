package com.example.huaweimisync.ui.routing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.example.huaweimisync.measurements.formatMeasurementDateTime
import com.example.huaweimisync.ui.accounts.formatLocalizedDecimal
import com.example.huaweimisync.ui.components.BirthDateField
import com.example.huaweimisync.ui.components.BirthDateSelectionPolicy
import com.example.huaweimisync.ui.theme.HuaweiColors
import com.example.huaweimisync.ui.theme.HuaweiDimensions
import java.time.ZoneId

object UnsavedPreviewTestTags {
    const val Dialog = "unsaved-preview"
    const val Title = "unsaved-preview-title"
    const val UnsavedBadge = "unsaved-preview-badge"
    const val ProfileEditor = "unsaved-preview-profile"
    const val BirthDate = "unsaved-preview-birth-date"
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
    val closeRequested = remember(state.pending.id) { mutableStateOf(false) }
    val requestClose = {
        if (!state.isCalculating && !closeRequested.value) {
            closeRequested.value = true
            callbacks.onCloseAndDiscard(state.pending.id)
        }
    }
    AlertDialog(
        modifier = modifier.testTag(UnsavedPreviewTestTags.Dialog),
        onDismissRequest = requestClose,
        title = { UnsavedPreviewTitle() },
        text = {
            when (state.step) {
                UnsavedPreviewStep.RAW_SUMMARY -> RawUnsavedSummary(state, zoneId)
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
                            val calculatingState = reduceUnsavedPreview(
                                state,
                                UnsavedPreviewAction.CalculationStarted,
                            )
                            calculateUnsavedPreview(state.pending, state.profileDraft, zoneId)?.let {
                                callbacks.onStateChange(
                                    reduceUnsavedPreview(
                                        calculatingState,
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
                    onClick = requestClose,
                    enabled = !closeRequested.value,
                    modifier = Modifier.testTag(UnsavedPreviewTestTags.Close),
                ) { Text("Закрыть") }
            }
        },
        dismissButton = {
            if (state.step == UnsavedPreviewStep.RAW_SUMMARY) {
                TextButton(
                    onClick = requestClose,
                    enabled = !closeRequested.value,
                    modifier = Modifier.testTag(UnsavedPreviewTestTags.Close),
                ) { Text("Закрыть") }
            } else {
                TextButton(
                    onClick = {
                        callbacks.onStateChange(
                            reduceUnsavedPreview(state, UnsavedPreviewAction.BackRequested),
                        )
                    },
                    enabled = !state.isCalculating,
                ) { Text("Назад") }
            }
        },
    )
}

@Composable
internal fun UnsavedPreviewTitle(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(HuaweiDimensions.CompactItemSpacing),
    ) {
        Text(
            "Просмотр измерения",
            modifier = Modifier.testTag(UnsavedPreviewTestTags.Title),
        )
        UnsavedBadge()
    }
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
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun RawUnsavedSummary(
    state: UnsavedMeasurementPreviewState,
    zoneId: ZoneId,
) {
    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            formatLocalizedDecimal(state.pending.weightKg) + " кг",
            style = MaterialTheme.typography.headlineSmall,
        )
        Text("Импеданс: ${state.pending.impedanceOhm} Ом")
        Text(
            "Время: ${formatMeasurementDateTime(state.pending.measuredAt, zoneId)}",
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
    val measurementDate = state.pending.measuredAt.atZone(zoneId).toLocalDate()
    val validation = validateUnsavedPreviewProfile(
        draft,
        measurementDate,
    )
    Column(
        modifier = Modifier
            .testTag(UnsavedPreviewTestTags.ProfileEditor)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Одноразовый профиль", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = draft.heightCm,
            onValueChange = {
                callbacks.onStateChange(
                    reduceUnsavedPreview(
                        state,
                        UnsavedPreviewAction.ProfileChanged(draft.copy(heightCm = it)),
                    ),
                )
            },
            label = { Text("Рост, см") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            isError = validation.heightError != null,
            supportingText = validation.heightError?.let { message -> { Text(message) } },
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.isCalculating,
        )
        BirthDateField(
            value = draft.birthDate,
            onValueChange = { birthDate ->
                callbacks.onStateChange(
                    reduceUnsavedPreview(
                        state,
                        UnsavedPreviewAction.ProfileChanged(
                            draft.copy(birthDate = birthDate),
                        ),
                    ),
                )
            },
            selectionPolicy = BirthDateSelectionPolicy.forUnsavedPreview(measurementDate),
            isError = validation.birthDateError != null,
            supportingText = validation.birthDateError,
            modifier = Modifier.testTag(UnsavedPreviewTestTags.BirthDate),
            enabled = !state.isCalculating,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PreviewSexChoice("Мужской", Sex.MALE, draft.sex, !state.isCalculating) {
                callbacks.onStateChange(
                    reduceUnsavedPreview(
                        state,
                        UnsavedPreviewAction.ProfileChanged(draft.copy(sex = it)),
                    ),
                )
            }
            PreviewSexChoice("Женский", Sex.FEMALE, draft.sex, !state.isCalculating) {
                callbacks.onStateChange(
                    reduceUnsavedPreview(
                        state,
                        UnsavedPreviewAction.ProfileChanged(draft.copy(sex = it)),
                    ),
                )
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
    enabled: Boolean,
    onSelect: (Sex) -> Unit,
) {
    val selected = selectedSex == value
    OutlinedButton(
        onClick = { onSelect(value) },
        enabled = enabled,
        modifier = Modifier.semantics {
            role = Role.RadioButton
            this.selected = selected
        },
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Text(label)
    }
}

@Composable
private fun UnsavedResult(composition: BodyComposition) {
    Column(
        modifier = Modifier
            .testTag(UnsavedPreviewTestTags.Result)
            .verticalScroll(rememberScrollState()),
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
