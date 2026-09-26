package com.palixander.scalesync.ui.profiles

import com.palixander.scalesync.ui.components.HistoryMeasurementIndicators

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.focusable
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.palixander.scalesync.charts.ChartRangePreset
import com.palixander.scalesync.PetBreedCatalog
import com.palixander.scalesync.domain.Pet
import com.palixander.scalesync.ui.components.HuaweiSurface
import com.palixander.scalesync.ui.components.HuaweiIconButton
import com.palixander.scalesync.ui.components.ProfileAvatar
import com.palixander.scalesync.ui.components.currentProfilePhotoStore
import com.palixander.scalesync.ui.icons.HuaweiIcons
import com.palixander.scalesync.ui.theme.HuaweiDimensions
import com.palixander.scalesync.ui.reference.AndroidReferenceSourceLauncher
import com.palixander.scalesync.ui.reference.ReferenceSourceLauncher
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.palixander.scalesync.R
import com.palixander.scalesync.ui.text.resolve

object PetProfileScreenTestTags {
    const val Shell = "pet-profile-shell"
    fun shell(petId: String) = "$Shell-$petId"
    const val StartMeasurement = "pet-history-start-measurement"
    const val AddWeight = "pet-history-add-weight"
    const val Summary = "pet-profile-summary"
    const val Edit = "pet-profile-edit"
    const val PeriodFilter = "pet-history-period-filter"
    const val Chart = "pet-history-chart"
    const val Empty = "pet-history-empty"
    const val NotFound = "pet-history-not-found"
    const val Loading = "pet-history-loading"
    const val ShowRemaining = "pet-history-show-remaining"
    const val ActionError = "pet-history-action-error"
    const val ActionErrorDismiss = "pet-history-action-error-dismiss"
    const val DeleteDialog = "pet-history-delete-dialog"
    const val DeleteConfirm = "pet-history-delete-confirm"
    const val DeleteCancel = "pet-history-delete-cancel"
    fun measurement(id: String) = "pet-history-measurement-$id"
    fun deleteMeasurement(id: String) = "pet-history-delete-$id"
    fun editMeasurement(id: String) = "pet-history-edit-$id"
    const val WeightEditor = "pet-weight-editor"
    const val WeightInput = "pet-weight-editor-input"
    const val WeightSave = "pet-weight-editor-save"
    const val WeightBack = "pet-weight-editor-back"
    const val WeightUnavailable = "pet-weight-editor-unavailable"
    const val WeightError = "pet-weight-editor-error"
    fun preset(preset: ChartRangePreset) = "pet-history-period-${preset.name.lowercase()}"
}

internal fun petProfileListContentPadding(): PaddingValues =
    PaddingValues(bottom = HuaweiDimensions.ContentPadding)

@Composable
internal fun PetProfileScreen(
    state: PetHistoryUiState,
    callbacks: PetHistoryCallbacks,
    contentPadding: PaddingValues,
    onStartMeasurement: () -> Unit,
    onEditPet: (Pet) -> Unit = {},
    sourceLauncher: ReferenceSourceLauncher = AndroidReferenceSourceLauncher(LocalContext.current),
) {
    val resources = LocalContext.current.resources
    state.weightEditor?.let { editor ->
        androidx.activity.compose.BackHandler(enabled = !editor.isSaving, onBack = callbacks.dismissWeightEditor)
        PetWeightEditorScreen(editor, callbacks, contentPadding)
        return
    }
    state.deleteConfirmation?.let { confirmation ->
        PetHistoryDeleteDialog(
            confirmation = confirmation,
            actionErrorMessage = state.actionErrorMessage,
            onConfirm = callbacks.confirmDelete,
            onDismiss = callbacks.dismissDelete,
        )
    }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    var showAllMeasurements by remember(state.petId) { mutableStateOf(false) }
    val restoredMeasurementFocusRequester = remember(state.scrollToMeasurementId) { FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(
        state.scrollToMeasurementId,
        state.measurements,
        state.isLoading,
        showAllMeasurements,
    ) {
        val index = state.measurements.indexOfFirst { it.id == state.scrollToMeasurementId }
        if (state.scrollToMeasurementId != null && index >= 0 && !state.isLoading) {
            if (index >= DEFAULT_VISIBLE_MEASUREMENT_COUNT && !showAllMeasurements) {
                showAllMeasurements = true
                return@LaunchedEffect
            }
            val precedingItems = 5 + (if (state.pet != null && !state.isNotFound) 1 else 0) +
                (if (state.actionErrorMessage != null && state.deleteConfirmation == null) 1 else 0)
            listState.scrollToItem(precedingItems + index)
            withFrameNanos { }
            restoredMeasurementFocusRequester.requestFocus()
            callbacks.onScrollToMeasurementHandled()
        }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().padding(contentPadding)
            .padding(horizontal = HuaweiDimensions.ContentPadding)
            .testTag(PetProfileScreenTestTags.shell(state.petId.value))
            .semantics { contentDescription = resources.getString(R.string.pet_profile_history_a11y, state.pet?.displayName.orEmpty()) },
        verticalArrangement = Arrangement.spacedBy(HuaweiDimensions.ItemSpacing),
        contentPadding = petProfileListContentPadding(),
    ) {
        item {
            Button(
                onClick = onStartMeasurement,
                enabled = state.pet != null && !state.isNotFound,
                modifier = Modifier.fillMaxWidth().testTag(PetProfileScreenTestTags.StartMeasurement)
                    .semantics { contentDescription = resources.getString(R.string.pet_profile_weigh_a11y, state.pet?.displayName.orEmpty()) },
            ) { Text(stringResource(R.string.pet_profile_weigh)) }
        }
        state.pet?.takeUnless { state.isNotFound }?.let { pet ->
            item {
                PetProfileSummaryCard(
                    pet = pet,
                    summary = state.profileSummary ?: petProfileSummary(pet, FallbackBreedCatalog),
                    onEdit = { onEditPet(pet) },
                )
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .testTag(PetProfileScreenTestTags.PeriodFilter),
                horizontalArrangement = Arrangement.spacedBy(HuaweiDimensions.CompactItemSpacing),
            ) {
                listOf(
                    ChartRangePreset.ALL,
                    ChartRangePreset.LAST_30_DAYS,
                    ChartRangePreset.LAST_3_MONTHS,
                )
                    .forEach { preset ->
                        FilterChip(
                            selected = state.rangePreset == preset,
                            onClick = { callbacks.selectRangePreset(preset) },
                            label = { Text(preset.petTitle(), maxLines = 1, softWrap = false) },
                            modifier = Modifier.testTag(PetProfileScreenTestTags.preset(preset)),
                        )
                    }
            }
        }
        when {
            state.isLoading -> item {
                Column(
                    modifier = Modifier.fillMaxWidth().testTag(PetProfileScreenTestTags.Loading),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) { CircularProgressIndicator() }
            }
            state.isNotFound -> item {
                Text(stringResource(R.string.pet_profile_not_found), modifier = Modifier.testTag(PetProfileScreenTestTags.NotFound))
            }
            state.errorMessage != null -> item { Text(state.errorMessage.resolve(resources), color = MaterialTheme.colorScheme.error) }
            else -> {
                state.actionErrorMessage?.takeIf { state.deleteConfirmation == null }?.let { message ->
                    item {
                        HuaweiSurface(
                            modifier = Modifier.fillMaxWidth().testTag(PetProfileScreenTestTags.ActionError),
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    message.resolve(resources),
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(
                                    onClick = callbacks.dismissActionError,
                                    modifier = Modifier.testTag(PetProfileScreenTestTags.ActionErrorDismiss),
                                ) { Text(stringResource(R.string.action_close)) }
                            }
                        }
                    }
                }
                item {
                    PetHistoryBreedReferenceCard(
                        reference = state.breedReference,
                        onEdit = { state.pet?.let(onEditPet) },
                        sourceLauncher = sourceLauncher,
                    )
                }
                item {
                    Column(Modifier.testTag(PetProfileScreenTestTags.Chart)) {
                        PetWeightReferenceChartCard(
                            series = state.series,
                            reference = state.weightReference,
                            breedReference = state.breedReference,
                            breedReferenceTimeline = state.breedReferenceTimeline,
                            startDate = state.startDate,
                            endDateInclusive = state.endDateInclusive,
                            zoneId = java.time.ZoneId.systemDefault(),
                            sourceLauncher = sourceLauncher,
                        )
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.pet_profile_measurements), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).semantics { heading() })
                        TextButton(onClick = callbacks.onAddWeightRequested,
                            enabled = state.pet != null && !state.isNotFound,
                            modifier = Modifier.heightIn(min = 48.dp).testTag(PetProfileScreenTestTags.AddWeight)
                                .semantics { contentDescription = resources.getString(R.string.pet_profile_add_weight_a11y) },
                        ) { Text("+", style = MaterialTheme.typography.headlineMedium) }
                    }
                }
                if (state.measurements.isEmpty()) item {
                    Text(stringResource(R.string.pet_profile_no_measurements), modifier = Modifier.testTag(PetProfileScreenTestTags.Empty))
                } else {
                    val visibleMeasurements = if (showAllMeasurements) {
                        state.measurements
                    } else {
                        state.measurements.take(DEFAULT_VISIBLE_MEASUREMENT_COUNT)
                    }
                    items(visibleMeasurements, key = { it.id }) { measurement ->
                        HuaweiSurface(
                            modifier = Modifier.fillMaxWidth().testTag(PetProfileScreenTestTags.measurement(measurement.id))
                                .then(
                                    if (measurement.id == state.scrollToMeasurementId) {
                                        Modifier.focusRequester(restoredMeasurementFocusRequester)
                                    } else {
                                        Modifier
                                    },
                                )
                                .focusable()
                                .semantics { contentDescription = "${measurement.measuredAtText}, ${measurement.weightText}" },
                        ) {
                            PetHistoryMeasurementDetails(
                                measurement = measurement,
                                actionsEnabled = state.deleteConfirmation?.isDeleting != true,
                                onEdit = { callbacks.editMeasurement(measurement.id) },
                                onDelete = { callbacks.requestDelete(measurement.id) },
                            )
                        }
                    }
                    if (!showAllMeasurements && state.measurements.size > DEFAULT_VISIBLE_MEASUREMENT_COUNT) {
                        item {
                            TextButton(
                                onClick = { showAllMeasurements = true },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                    .testTag(PetProfileScreenTestTags.ShowRemaining)
                                    .semantics { contentDescription = resources.getString(R.string.pet_profile_show_remaining_a11y) },
                            ) { Text(stringResource(R.string.pet_profile_show_remaining)) }
                        }
                    }
                }
            }
        }
    }
}

private const val DEFAULT_VISIBLE_MEASUREMENT_COUNT = 10

@Composable
private fun PetWeightEditorScreen(
    editor: PetWeightEditorState,
    callbacks: PetHistoryCallbacks,
    contentPadding: PaddingValues,
) {
    val resources = LocalContext.current.resources
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(contentPadding).imePadding()
            .padding(horizontal = HuaweiDimensions.ContentPadding)
            .testTag(PetProfileScreenTestTags.WeightEditor)
            .semantics { contentDescription = resources.getString(R.string.pet_weight_editor_a11y, editor.petName) },
        verticalArrangement = Arrangement.spacedBy(HuaweiDimensions.ItemSpacing),
        contentPadding = PaddingValues(vertical = HuaweiDimensions.ContentPadding),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HuaweiIconButton(
                    icon = HuaweiIcons.Back,
                    contentDescription = stringResource(R.string.pet_weight_editor_back_a11y),
                    onClick = callbacks.dismissWeightEditor,
                    enabled = !editor.isSaving,
                    modifier = Modifier.testTag(PetProfileScreenTestTags.WeightBack),
                )
                Text(stringResource(R.string.pet_weight_editor_title), style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.semantics { heading() })
            }
        }
        item { Text(editor.petName, style = MaterialTheme.typography.titleMedium) }
        item {
            OutlinedTextField(
                value = editor.weightInput,
                onValueChange = callbacks.changeEditedWeight,
                enabled = !editor.isSaving && !editor.isUnavailable,
                singleLine = true,
                label = { Text(stringResource(R.string.pet_weight_label)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                isError = editor.parsedWeightKg == null,
                supportingText = if (editor.parsedWeightKg == null) {
                    {
                        Text(
                            stringResource(R.string.pet_weight_validation),
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                } else null,
                modifier = Modifier.fillMaxWidth().testTag(PetProfileScreenTestTags.WeightInput),
            )
        }
        item {
            Column(Modifier.semantics {
                contentDescription = resources.getString(R.string.pet_weight_date_a11y, editor.measuredAtText)
            }) {
                Text(stringResource(R.string.pet_weight_date), style = MaterialTheme.typography.labelLarge)
                Text(editor.measuredAtText)
                Text(stringResource(R.string.pet_weight_date_unchanged), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (editor.isUnavailable) item {
            Text(
                stringResource(R.string.pet_weight_unavailable),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag(PetProfileScreenTestTags.WeightUnavailable)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        editor.saveError?.let { error -> item {
            Text(error.resolve(resources), color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag(PetProfileScreenTestTags.WeightError)
                    .semantics { liveRegion = LiveRegionMode.Polite })
        } }
        item {
            Button(
                onClick = callbacks.saveEditedWeight,
                enabled = editor.canSave,
                modifier = Modifier.fillMaxWidth().heightIn(min = HuaweiDimensions.TouchTarget)
                    .testTag(PetProfileScreenTestTags.WeightSave)
                    .semantics { if (editor.isSaving) stateDescription = resources.getString(R.string.state_saving) },
            ) { Text(stringResource(if (editor.isSaving) R.string.state_saving_ellipsis else R.string.action_save)) }
        }
    }
}

@Composable
private fun PetProfileSummaryCard(
    pet: Pet,
    summary: PetProfileSummary,
    onEdit: () -> Unit,
) {
    val photoStore = currentProfilePhotoStore()
    val resources = LocalContext.current.resources
    val summaryDescription = if (summary.isEmpty) {
        EmptyPetProfileSummary.resolve(resources)
    } else {
        summary.items.joinToString(separator = ". ") { item ->
            resources.getString(R.string.pet_profile_summary_item_a11y, item.label.resolve(resources), item.value.resolve(resources))
        }
    }
    HuaweiSurface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(PetProfileScreenTestTags.Summary)
            .semantics(mergeDescendants = true) {
                contentDescription = resources.getString(R.string.pet_profile_summary_a11y, summaryDescription)
            },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(HuaweiDimensions.CompactItemSpacing)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(HuaweiDimensions.CompactItemSpacing),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.pet_profile_summary_title),
                    modifier = Modifier.weight(1f).semantics { heading() },
                    style = MaterialTheme.typography.titleMedium,
                )
                TextButton(
                    onClick = onEdit,
                    modifier = Modifier
                        .heightIn(min = HuaweiDimensions.TouchTarget)
                        .testTag(PetProfileScreenTestTags.Edit)
                        .semantics {
                            contentDescription = resources.getString(R.string.pet_profile_edit_a11y, pet.displayName)
                        },
                ) { Text(stringResource(R.string.action_edit)) }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(HuaweiDimensions.CompactItemSpacing),
                ) {
                    if (summary.isEmpty) {
                        Text(
                            text = EmptyPetProfileSummary.resolve(resources),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    } else summary.items.forEach { item ->
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = item.label.resolve(resources),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelMedium,
                            )
                            Text(text = item.value.resolve(resources), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
                if (pet.photoPath != null) ProfileAvatar(
                    photoPath = pet.photoPath,
                    fallbackIcon = when (pet.species) {
                        com.palixander.scalesync.domain.PetSpecies.CAT -> HuaweiIcons.Cat
                        com.palixander.scalesync.domain.PetSpecies.DOG -> HuaweiIcons.Dog
                        com.palixander.scalesync.domain.PetSpecies.UNSPECIFIED -> HuaweiIcons.Profile
                    },
                    contentDescription = resources.getString(R.string.pet_profile_photo_a11y, pet.displayName),
                    store = photoStore,
                    size = 72.dp,
                )
            }
        }
    }
}

@Composable
private fun PetHistoryDeleteDialog(
    confirmation: PetHistoryDeleteConfirmation,
    actionErrorMessage: com.palixander.scalesync.ui.text.UiText?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val resources = LocalContext.current.resources
    AlertDialog(
        modifier = Modifier.testTag(PetProfileScreenTestTags.DeleteDialog),
        onDismissRequest = { if (!confirmation.isDeleting) onDismiss() },
        icon = { Icon(HuaweiIcons.Delete, contentDescription = null) },
        title = { Text(stringResource(R.string.pet_measurement_delete_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "${confirmation.measurement.measuredAtText} · ${confirmation.measurement.weightText}",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(stringResource(R.string.pet_measurement_delete_message))
                actionErrorMessage?.let {
                    Text(it.resolve(resources), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = !confirmation.isDeleting,
                modifier = Modifier.testTag(PetProfileScreenTestTags.DeleteConfirm).semantics {
                    if (confirmation.isDeleting) stateDescription = resources.getString(R.string.state_deleting)
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) {
                Text(stringResource(if (confirmation.isDeleting) R.string.state_deleting_ellipsis else if (actionErrorMessage != null) R.string.action_retry else R.string.action_delete))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !confirmation.isDeleting,
                modifier = Modifier.testTag(PetProfileScreenTestTags.DeleteCancel),
            ) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun ChartRangePreset.petTitle() = stringResource(when (this) {
    ChartRangePreset.ALL -> R.string.chart_range_all
    ChartRangePreset.LAST_7_DAYS -> R.string.chart_range_7_days
    ChartRangePreset.LAST_30_DAYS -> R.string.chart_range_30_days
    ChartRangePreset.LAST_3_MONTHS -> R.string.chart_range_3_months
    ChartRangePreset.YEAR_TO_DATE -> R.string.chart_range_year
    ChartRangePreset.CUSTOM -> R.string.chart_range_dates
})

private val FallbackBreedCatalog by lazy(::PetBreedCatalog)

@Composable
internal fun PetHistoryMeasurementDetails(
    measurement: PetHistoryMeasurementUi,
    actionsEnabled: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val resources = LocalContext.current.resources
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(measurement.measuredAtText)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    measurement.weightText,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.testTag("pet-history-weight-${measurement.id}"),
                )
                HistoryMeasurementIndicators(measurement.origin, measurement.isManuallyEdited, "pet-history-${measurement.id}")
            }
        }
        Row {
            HuaweiIconButton(
                icon = HuaweiIcons.Edit,
                contentDescription = resources.getString(R.string.pet_measurement_edit_a11y, measurement.measuredAtText, measurement.weightText),
                onClick = { onEdit() },
                enabled = actionsEnabled,
                modifier = Modifier.testTag(PetProfileScreenTestTags.editMeasurement(measurement.id)),
            )
            HuaweiIconButton(
                icon = HuaweiIcons.Delete,
                contentDescription = resources.getString(R.string.pet_measurement_delete_a11y, measurement.measuredAtText, measurement.weightText),
                onClick = { onDelete() },
                enabled = actionsEnabled,
                modifier = Modifier.testTag(PetProfileScreenTestTags.deleteMeasurement(measurement.id)),
            )
        }
    }
}
