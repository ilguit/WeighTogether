package com.palixander.scalesync.ui.profiles

import com.palixander.scalesync.ui.components.ManualOriginIndicator

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.palixander.scalesync.charts.ChartRangePreset
import com.palixander.scalesync.PetBreedCatalog
import com.palixander.scalesync.domain.Pet
import com.palixander.scalesync.ui.components.HuaweiSurface
import com.palixander.scalesync.ui.components.HuaweiIconButton
import com.palixander.scalesync.ui.icons.HuaweiIcons
import com.palixander.scalesync.ui.theme.HuaweiDimensions
import com.palixander.scalesync.ui.reference.AndroidReferenceSourceLauncher
import com.palixander.scalesync.ui.reference.ReferenceSourceLauncher
import androidx.compose.ui.platform.LocalContext

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
    const val ActionError = "pet-history-action-error"
    const val ActionErrorDismiss = "pet-history-action-error-dismiss"
    const val DeleteDialog = "pet-history-delete-dialog"
    const val DeleteConfirm = "pet-history-delete-confirm"
    const val DeleteCancel = "pet-history-delete-cancel"
    fun measurement(id: String) = "pet-history-measurement-$id"
    fun deleteMeasurement(id: String) = "pet-history-delete-$id"
    fun preset(preset: ChartRangePreset) = "pet-history-period-${preset.name.lowercase()}"
}

@Composable
internal fun PetProfileScreen(
    state: PetHistoryUiState,
    callbacks: PetHistoryCallbacks,
    contentPadding: PaddingValues,
    onStartMeasurement: () -> Unit,
    onEditPet: (Pet) -> Unit = {},
    sourceLauncher: ReferenceSourceLauncher = AndroidReferenceSourceLauncher(LocalContext.current),
) {
    state.deleteConfirmation?.let { confirmation ->
        PetHistoryDeleteDialog(
            confirmation = confirmation,
            actionErrorMessage = state.actionErrorMessage,
            onConfirm = callbacks.confirmDelete,
            onDismiss = callbacks.dismissDelete,
        )
    }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    androidx.compose.runtime.LaunchedEffect(state.scrollToMeasurementId, state.measurements, state.isLoading) {
        val index = state.measurements.indexOfFirst { it.id == state.scrollToMeasurementId }
        if (state.scrollToMeasurementId != null && index >= 0 && !state.isLoading) {
            val precedingItems = 5 + (if (state.pet != null && !state.isNotFound) 1 else 0) +
                (if (state.actionErrorMessage != null && state.deleteConfirmation == null) 1 else 0)
            listState.scrollToItem(precedingItems + index)
            callbacks.onScrollToMeasurementHandled()
        }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().padding(contentPadding)
            .padding(horizontal = HuaweiDimensions.ContentPadding)
            .testTag(PetProfileScreenTestTags.shell(state.petId.value))
            .semantics { contentDescription = "История измерений питомца ${state.pet?.displayName.orEmpty()}" },
        verticalArrangement = Arrangement.spacedBy(HuaweiDimensions.ItemSpacing),
    ) {
        item {
            Button(
                onClick = onStartMeasurement,
                enabled = state.pet != null && !state.isNotFound,
                modifier = Modifier.fillMaxWidth().testTag(PetProfileScreenTestTags.StartMeasurement)
                    .semantics { contentDescription = "Взвесить питомца ${state.pet?.displayName.orEmpty()}" },
            ) { Text("Взвесить питомца") }
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
                modifier = Modifier.fillMaxWidth().testTag(PetProfileScreenTestTags.PeriodFilter),
                horizontalArrangement = Arrangement.spacedBy(HuaweiDimensions.CompactItemSpacing),
            ) {
                listOf(ChartRangePreset.LAST_7_DAYS, ChartRangePreset.LAST_30_DAYS, ChartRangePreset.LAST_3_MONTHS)
                    .forEach { preset ->
                        FilterChip(
                            selected = state.rangePreset == preset,
                            onClick = { callbacks.selectRangePreset(preset) },
                            label = { Text(preset.petTitle()) },
                            modifier = Modifier.weight(1f).testTag(PetProfileScreenTestTags.preset(preset)),
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
                Text("Питомец не найден", modifier = Modifier.testTag(PetProfileScreenTestTags.NotFound))
            }
            state.errorMessage != null -> item { Text(state.errorMessage, color = MaterialTheme.colorScheme.error) }
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
                                    message,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(
                                    onClick = callbacks.dismissActionError,
                                    modifier = Modifier.testTag(PetProfileScreenTestTags.ActionErrorDismiss),
                                ) { Text("Закрыть") }
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
                        Text("Измерения", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).semantics { heading() })
                        TextButton(onClick = callbacks.onAddWeightRequested,
                            enabled = state.pet != null && !state.isNotFound,
                            modifier = Modifier.heightIn(min = 48.dp).testTag(PetProfileScreenTestTags.AddWeight)
                                .semantics { contentDescription = "Добавить вес питомца" },
                        ) { Text("+", style = MaterialTheme.typography.headlineMedium) }
                    }
                }
                if (state.measurements.isEmpty()) item {
                    Text("Нет измерений за выбранный период", modifier = Modifier.testTag(PetProfileScreenTestTags.Empty))
                } else {
                    items(state.measurements, key = { it.id }) { measurement ->
                        HuaweiSurface(
                            modifier = Modifier.fillMaxWidth().testTag(PetProfileScreenTestTags.measurement(measurement.id))
                                .semantics { contentDescription = "${measurement.measuredAtText}, ${measurement.weightText}" },
                        ) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(measurement.measuredAtText)
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(measurement.weightText, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f, fill = false))
                                        ManualOriginIndicator(measurement.origin, Modifier.testTag("pet-history-manual-origin-${measurement.id}"))
                                    }
                                }
                                HuaweiIconButton(
                                    icon = HuaweiIcons.Delete,
                                    contentDescription = "Удалить измерение ${measurement.measuredAtText}, ${measurement.weightText}",
                                    onClick = { callbacks.requestDelete(measurement.id) },
                                    enabled = state.deleteConfirmation?.isDeleting != true,
                                    modifier = Modifier.testTag(PetProfileScreenTestTags.deleteMeasurement(measurement.id)),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PetProfileSummaryCard(
    pet: Pet,
    summary: PetProfileSummary,
    onEdit: () -> Unit,
) {
    HuaweiSurface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(PetProfileScreenTestTags.Summary)
            .semantics(mergeDescendants = true) {
                contentDescription = "Данные питомца. ${summary.contentDescription}"
            },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(HuaweiDimensions.CompactItemSpacing)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(HuaweiDimensions.CompactItemSpacing),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Данные питомца",
                    modifier = Modifier.weight(1f).semantics { heading() },
                    style = MaterialTheme.typography.titleMedium,
                )
                TextButton(
                    onClick = onEdit,
                    modifier = Modifier
                        .heightIn(min = HuaweiDimensions.TouchTarget)
                        .testTag(PetProfileScreenTestTags.Edit)
                        .semantics {
                            contentDescription = "Изменить данные питомца ${pet.displayName}"
                        },
                ) { Text("Изменить") }
            }
            if (summary.isEmpty) {
                Text(
                    text = EmptyPetProfileSummary,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                summary.items.forEach { item ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = item.label,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Text(
                            text = item.value,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PetHistoryDeleteDialog(
    confirmation: PetHistoryDeleteConfirmation,
    actionErrorMessage: String?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        modifier = Modifier.testTag(PetProfileScreenTestTags.DeleteDialog),
        onDismissRequest = { if (!confirmation.isDeleting) onDismiss() },
        icon = { Icon(HuaweiIcons.Delete, contentDescription = null) },
        title = { Text("Удалить измерение питомца?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "${confirmation.measurement.measuredAtText} · ${confirmation.measurement.weightText}",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text("Измерение будет удалено без возможности восстановления.")
                actionErrorMessage?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = !confirmation.isDeleting,
                modifier = Modifier.testTag(PetProfileScreenTestTags.DeleteConfirm).semantics {
                    if (confirmation.isDeleting) stateDescription = "Удаление выполняется"
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) {
                Text(if (confirmation.isDeleting) "Удаление…" else if (actionErrorMessage != null) "Повторить" else "Удалить")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !confirmation.isDeleting,
                modifier = Modifier.testTag(PetProfileScreenTestTags.DeleteCancel),
            ) { Text("Отмена") }
        },
    )
}

private fun ChartRangePreset.petTitle() = when (this) {
    ChartRangePreset.LAST_7_DAYS -> "7 дней"
    ChartRangePreset.LAST_30_DAYS -> "30 дней"
    ChartRangePreset.LAST_3_MONTHS -> "3 месяца"
    ChartRangePreset.YEAR_TO_DATE -> "Год"
    ChartRangePreset.CUSTOM -> "Даты"
}

private val FallbackBreedCatalog by lazy(::PetBreedCatalog)
