package com.palixander.scalesync.ui.routing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.palixander.scalesync.R
import com.palixander.scalesync.core.BodyMetric
import com.palixander.scalesync.core.Sex
import com.palixander.scalesync.core.chronologicalAge
import com.palixander.scalesync.measurements.formatMeasurementDateTime
import com.palixander.scalesync.ui.accounts.formatLocalizedDecimal
import com.palixander.scalesync.ui.components.BirthDateField
import com.palixander.scalesync.ui.components.BirthDateSelectionPolicy
import com.palixander.scalesync.ui.reference.AndroidReferenceSourceLauncher
import com.palixander.scalesync.ui.reference.GroupedMetricReferences
import com.palixander.scalesync.ui.reference.MetricHelpDialog
import com.palixander.scalesync.ui.reference.ReferenceMetricPresentation
import com.palixander.scalesync.ui.reference.ReferencePresentationFactory
import com.palixander.scalesync.ui.reference.ReferenceSourceLauncher
import com.palixander.scalesync.ui.theme.HuaweiColors
import com.palixander.scalesync.ui.theme.HuaweiDimensions
import java.time.ZoneId

object UnsavedPreviewTestTags {
    const val Dialog = "unsaved-preview"
    const val Title = "unsaved-preview-title"
    const val UnsavedBadge = "unsaved-preview-badge"
    const val ProfileEditor = "unsaved-preview-profile"
    const val BirthDate = "unsaved-preview-birth-date"
    const val Calculate = "unsaved-preview-calculate"
    const val Result = "unsaved-preview-result"
    const val CalculationError = "unsaved-preview-calculation-error"
    const val Calculating = "unsaved-preview-calculating"
    const val Close = "unsaved-preview-close"
}

@Composable
fun UnsavedMeasurementPreviewDialog(
    state: UnsavedMeasurementPreviewState,
    callbacks: UnsavedPreviewCallbacks,
    modifier: Modifier = Modifier,
    zoneId: ZoneId = ZoneId.systemDefault(),
    snackbarHostState: SnackbarHostState? = null,
    sourceLauncher: ReferenceSourceLauncher = AndroidReferenceSourceLauncher(LocalContext.current),
) {
    val effectiveSnackbarHostState = snackbarHostState ?: remember { SnackbarHostState() }
    val closeRequested = remember(state.pending.id) { mutableStateOf(false) }
    val resultScrollState = rememberScrollState()
    val errorFocusRequester = remember(state.pending.id) { FocusRequester() }
    val infoFocusRequesters = remember(state.pending.id) {
        BodyMetric.entries.associateWith { FocusRequester() }
    }
    var helpMetricName by rememberSaveable(state.pending.id) { mutableStateOf<String?>(null) }
    var focusAfterHelp by remember(state.pending.id) { mutableStateOf<BodyMetric?>(null) }
    val context = LocalContext.current
    val resultPresentations = state.result?.let { result ->
        ReferencePresentationFactory(context.resources).createAll(
            readings = result.readings,
            interpretations = result.interpretations,
            preliminary = true,
        )
    }.orEmpty()
    val helpMetric = helpMetricName?.let { name ->
        BodyMetric.entries.firstOrNull { it.name == name }
    }
    val selectedHelp = helpMetric?.let { metric ->
        resultPresentations.singleOrNull { it.definition.metric == metric }
    }

    LaunchedEffect(state.calculationError) {
        if (state.calculationError != null) errorFocusRequester.requestFocus()
    }
    LaunchedEffect(focusAfterHelp, helpMetricName) {
        val metric = focusAfterHelp
        if (metric != null && helpMetricName == null) {
            infoFocusRequesters.getValue(metric).requestFocus()
            focusAfterHelp = null
        }
    }

    if (selectedHelp != null) {
        val result = requireNotNull(state.result)
        val measurementDate = state.pending.measuredAt.atZone(zoneId).toLocalDate()
        MetricHelpDialog(
            presentation = selectedHelp,
            snackbarHostState = effectiveSnackbarHostState,
            onDismissRequest = {
                focusAfterHelp = selectedHelp.definition.metric
                helpMetricName = null
            },
            sourceLauncher = sourceLauncher,
            usedDataText = stringResource(
                R.string.reference_used_data,
                formatLocalizedDecimal(result.profile.heightCm, 1),
                chronologicalAge(result.profile.birthDate, measurementDate),
            ),
        )
        return
    }
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
                UnsavedPreviewStep.PROFILE_EDITOR -> PreviewProfileEditor(
                    state,
                    callbacks,
                    zoneId,
                    errorFocusRequester,
                )
                UnsavedPreviewStep.RESULT -> UnsavedResult(
                    presentations = resultPresentations,
                    scrollState = resultScrollState,
                    onInfoClick = { helpMetricName = it.definition.metric.name },
                    infoButtonModifier = { presentation ->
                        Modifier.focusRequester(infoFocusRequesters.getValue(presentation.definition.metric))
                    },
                )
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
                            callbacks.onCalculate(state.pending.id)
                        },
                        enabled = validation.isValid && !state.isCalculating,
                        modifier = Modifier.testTag(UnsavedPreviewTestTags.Calculate),
                    ) {
                        if (state.isCalculating) {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .padding(end = 8.dp)
                                    .testTag(UnsavedPreviewTestTags.Calculating),
                                strokeWidth = 2.dp,
                            )
                        }
                        Text("Рассчитать")
                    }
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
    errorFocusRequester: FocusRequester,
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
        if (state.calculationError == UnsavedPreviewCalculationError.CALCULATION_FAILED) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(errorFocusRequester)
                    .focusable()
                    .semantics { heading() }
                    .testTag(UnsavedPreviewTestTags.CalculationError),
            ) {
                Text(
                    text = stringResource(R.string.unsaved_preview_calculation_error),
                    modifier = Modifier.padding(12.dp),
                )
            }
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
private fun UnsavedResult(
    presentations: List<ReferenceMetricPresentation>,
    scrollState: androidx.compose.foundation.ScrollState,
    onInfoClick: (ReferenceMetricPresentation) -> Unit,
    infoButtonModifier: (ReferenceMetricPresentation) -> Modifier,
) {
    Column(
        modifier = Modifier
            .testTag(UnsavedPreviewTestTags.Result)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Результат только для просмотра", style = MaterialTheme.typography.titleMedium)
        GroupedMetricReferences(
            groups = ReferencePresentationFactory(LocalContext.current.resources).group(presentations),
            onInfoClick = onInfoClick,
            infoButtonModifier = infoButtonModifier,
        )
        Text(
            "Эти данные не сохранены и не будут отправлены во внешние сервисы.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
