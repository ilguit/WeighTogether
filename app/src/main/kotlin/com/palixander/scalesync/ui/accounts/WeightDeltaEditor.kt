package com.palixander.scalesync.ui.accounts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.palixander.scalesync.ui.components.HuaweiSectionTitle
import com.palixander.scalesync.ui.components.HuaweiSurface
import com.palixander.scalesync.ui.theme.HuaweiDimensions

object WeightDeltaEditorTestTags {
    const val Section = "weight-recognition-section"
    const val Explanation = "weight-recognition-explanation"
    const val Controls = "weight-recognition-controls"
    const val Input = "weight-delta-input"
    const val Error = "weight-delta-error"
    const val Save = "weight-delta-save"
    const val IgnoreUnknown = "ignore-unknown-measurements-switch"
}

@Composable
fun WeightRecognitionSetting(
    state: WeightDeltaEditorState,
    onStateChanged: (WeightDeltaEditorState) -> Unit,
    onSave: (Double) -> Unit,
    ignoreUnknownMeasurements: Boolean,
    onIgnoreUnknownMeasurementsChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(HuaweiDimensions.CompactItemSpacing),
    ) {
        HuaweiSectionTitle("Распознавание измерений")
        HuaweiSurface(Modifier.fillMaxWidth().testTag(WeightDeltaEditorTestTags.Section)) {
            Column(verticalArrangement = Arrangement.spacedBy(HuaweiDimensions.CompactItemSpacing)) {
                Text(
                    "Больший допуск повышает вероятность автоматического назначения " +
                        "и неверного совпадения.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag(WeightDeltaEditorTestTags.Explanation),
                )
                BoxWithConstraints(Modifier.fillMaxWidth().testTag(WeightDeltaEditorTestTags.Controls)) {
                    val narrow = maxWidth < 400.dp
                    if (narrow) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(
                                HuaweiDimensions.CompactItemSpacing,
                            ),
                        ) {
                            WeightToleranceInput(state, onStateChanged, Modifier.fillMaxWidth())
                            WeightToleranceSave(state, onSave, Modifier.fillMaxWidth())
                        }
                    } else {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(
                                HuaweiDimensions.CompactItemSpacing,
                            ),
                            verticalAlignment = Alignment.Top,
                        ) {
                            WeightToleranceInput(state, onStateChanged, Modifier.weight(1f))
                            WeightToleranceSave(state, onSave)
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(
                        HuaweiDimensions.CompactItemSpacing,
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Всегда игнорировать неизвестные показания",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "Применяется только к новым замерам без подходящего профиля.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(
                        checked = ignoreUnknownMeasurements,
                        onCheckedChange = onIgnoreUnknownMeasurementsChanged,
                        modifier = Modifier.testTag(WeightDeltaEditorTestTags.IgnoreUnknown),
                    )
                }
            }
        }
    }
}

@Composable
private fun WeightToleranceInput(
    state: WeightDeltaEditorState,
    onStateChanged: (WeightDeltaEditorState) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = state.input,
        onValueChange = {
            onStateChanged(
                reduceWeightDeltaEditor(state, WeightDeltaEditorAction.InputChanged(it)),
            )
        },
        label = { Text("Допуск по весу, кг") },
        supportingText = state.error?.let { message ->
            { Text(message, Modifier.testTag(WeightDeltaEditorTestTags.Error)) }
        },
        isError = state.error != null,
        enabled = !state.isSaving,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier.testTag(WeightDeltaEditorTestTags.Input),
    )
}

@Composable
private fun WeightToleranceSave(
    state: WeightDeltaEditorState,
    onSave: (Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = { state.parsedValue?.let(onSave) },
        enabled = state.canSave,
        modifier = modifier
            .heightIn(min = HuaweiDimensions.TouchTarget)
            .testTag(WeightDeltaEditorTestTags.Save),
    ) { Text("Сохранить") }
}
