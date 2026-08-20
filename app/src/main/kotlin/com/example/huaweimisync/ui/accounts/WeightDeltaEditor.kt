package com.example.huaweimisync.ui.accounts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import com.example.huaweimisync.ui.components.HuaweiSectionTitle
import com.example.huaweimisync.ui.components.HuaweiSurface
import com.example.huaweimisync.ui.theme.HuaweiDimensions

object WeightDeltaEditorTestTags {
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
        HuaweiSectionTitle("Распознавание аккаунта")
        HuaweiSurface(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(HuaweiDimensions.CompactItemSpacing)) {
                Text(
                    "Измерение считается подходящим, если вес отличается от недавней медианы не больше этой дельты.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(HuaweiDimensions.CompactItemSpacing),
                    verticalAlignment = Alignment.Top,
                ) {
                    OutlinedTextField(
                        value = state.input,
                        onValueChange = {
                            onStateChanged(
                                reduceWeightDeltaEditor(
                                    state,
                                    WeightDeltaEditorAction.InputChanged(it),
                                ),
                            )
                        },
                        label = { Text("Дельта веса, кг") },
                        supportingText = state.error?.let { message ->
                            { Text(message, Modifier.testTag(WeightDeltaEditorTestTags.Error)) }
                        },
                        isError = state.error != null,
                        enabled = !state.isSaving,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f).testTag(WeightDeltaEditorTestTags.Input),
                    )
                    Button(
                        onClick = { state.parsedValue?.let(onSave) },
                        enabled = state.canSave,
                        modifier = Modifier
                            .padding(top = HuaweiDimensions.CompactContentPadding)
                            .testTag(WeightDeltaEditorTestTags.Save),
                    ) { Text("Сохранить") }
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
                            "Применяется только к новым замерам без подходящего аккаунта.",
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
