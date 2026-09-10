package com.palixander.scalesync.ui.accounts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import com.palixander.scalesync.ui.components.HuaweiSectionTitle
import com.palixander.scalesync.ui.components.HuaweiSurface
import com.palixander.scalesync.ui.theme.HuaweiDimensions

object WeightDeltaEditorTestTags {
    const val Input = "weight-delta-input"
    const val Error = "weight-delta-error"
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
    val focusManager = LocalFocusManager.current
    var wasFocused by remember { mutableStateOf(false) }
    var completionSubmitted by remember { mutableStateOf(false) }
    LaunchedEffect(state.isSaving) {
        if (!state.isSaving) completionSubmitted = false
    }
    val completeInput = {
        if (!completionSubmitted) {
            state.parsedValue?.takeIf { state.canSave }?.let {
                completionSubmitted = true
                onSave(it)
            }
        }
    }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(HuaweiDimensions.CompactItemSpacing),
    ) {
        HuaweiSectionTitle("Распознавание измерений")
        HuaweiSurface(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(HuaweiDimensions.CompactItemSpacing)) {
                Text(
                    "Больший допуск увеличивает вероятность автоматического назначения и неверного совпадения.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                        value = state.input,
                        onValueChange = {
                            completionSubmitted = false
                            onStateChanged(
                                reduceWeightDeltaEditor(
                                    state,
                                    WeightDeltaEditorAction.InputChanged(it),
                                ),
                            )
                        },
                        label = { Text("Допуск по весу, кг") },
                        supportingText = state.error?.let { message ->
                            { Text(message, Modifier.testTag(WeightDeltaEditorTestTags.Error)) }
                        },
                        isError = state.error != null,
                        enabled = !state.isSaving,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Decimal,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(onDone = {
                            completeInput()
                            focusManager.clearFocus()
                        }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { focusState ->
                                if (wasFocused && !focusState.isFocused) completeInput()
                                wasFocused = focusState.isFocused
                            }
                            .testTag(WeightDeltaEditorTestTags.Input)
                            .semantics {
                                if (state.isSaving) {
                                    contentDescription = "Сохранение допуска по весу"
                                }
                            },
                    )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(
                        HuaweiDimensions.CompactItemSpacing,
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Игнорировать неизвестные показания",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "Только для новых измерений после включения",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Checkbox(
                        checked = ignoreUnknownMeasurements,
                        onCheckedChange = onIgnoreUnknownMeasurementsChanged,
                        modifier = Modifier.testTag(WeightDeltaEditorTestTags.IgnoreUnknown),
                    )
                }
            }
        }
    }
}
