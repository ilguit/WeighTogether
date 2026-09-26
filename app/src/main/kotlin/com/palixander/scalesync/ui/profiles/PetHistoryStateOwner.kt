package com.palixander.scalesync.ui.profiles

import com.palixander.scalesync.R
import com.palixander.scalesync.ui.text.UiText
import com.palixander.scalesync.ui.text.uiText

import com.palixander.scalesync.PetBreedCatalog
import com.palixander.scalesync.charts.ChartDateRange
import com.palixander.scalesync.charts.ChartRangePreset
import com.palixander.scalesync.charts.ChartPoint
import com.palixander.scalesync.domain.PetId
import com.palixander.scalesync.domain.PetMeasurement
import com.palixander.scalesync.domain.PetRepository
import com.palixander.scalesync.domain.PetMeasurementNotFoundException
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

data class PetHistoryReferenceDependencies(
    val breedCatalog: PetBreedCatalog,
    val referencePresenter: PetHistoryReferencePresenter,
    val breedReferencePresenter: PetHistoryBreedReferencePresenter,
) {
    companion object {
        fun bundled(clock: Clock = Clock.systemDefaultZone()) = PetHistoryReferenceDependencies(
            breedCatalog = PetBreedCatalog(),
            referencePresenter = PetHistoryReferencePresenter(),
            breedReferencePresenter = PetHistoryBreedReferencePresenter(clock = clock),
        )
    }
}

internal class PetHistoryReferenceLoader(
    scope: CoroutineScope,
    context: CoroutineContext = EmptyCoroutineContext,
    private val factory: suspend () -> PetHistoryReferenceDependencies,
) {
    private val dependencies by lazy(LazyThreadSafetyMode.NONE) {
        scope.async(context, start = CoroutineStart.LAZY) { factory() }
    }

    suspend fun load(): PetHistoryReferenceDependencies = dependencies.await()
}

internal data class PetHistorySelection(
    val petId: PetId,
    val range: ChartDateRange,
    val rangePreset: ChartRangePreset,
)

private data class PetHistoryInteraction(
    val petId: PetId,
    val deleteConfirmation: PetHistoryDeleteConfirmation? = null,
    val actionErrorMessage: UiText? = null,
    val scrollToMeasurementId: String? = null,
    val weightEditor: PetWeightEditorState? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class PetHistoryStateOwner(
    initialPetId: PetId,
    private val repository: PetRepository,
    parentScope: CoroutineScope,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val zoneId: ZoneId = clock.zone,
    private val locale: Locale = Locale.getDefault(),
    private val referenceDependencies: suspend () -> PetHistoryReferenceDependencies = {
        PetHistoryReferenceDependencies.bundled(clock)
    },
) : AutoCloseable {
    private val ownerJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val ownerScope = CoroutineScope(parentScope.coroutineContext + ownerJob)
    private val initialState = PetHistoryUiState.initial(initialPetId, clock)
    private val selection = MutableStateFlow(
        PetHistorySelection(
            petId = initialPetId,
            range = ChartDateRange(initialState.startDate, initialState.endDateInclusive),
            rangePreset = initialState.rangePreset,
        ),
    )
    private val interaction = MutableStateFlow(PetHistoryInteraction(initialPetId))
    private val references by lazy(LazyThreadSafetyMode.NONE) {
        ownerScope.async(start = CoroutineStart.LAZY) { referenceDependencies() }
    }

    val uiState: StateFlow<PetHistoryUiState> = combine(
        selection.flatMapLatest(::observeSelection),
        interaction,
    ) { state, currentInteraction ->
        if (state.petId == currentInteraction.petId) {
            state.copy(
                deleteConfirmation = currentInteraction.deleteConfirmation,
                actionErrorMessage = currentInteraction.actionErrorMessage,
                scrollToMeasurementId = currentInteraction.scrollToMeasurementId,
                weightEditor = currentInteraction.weightEditor?.copy(
                    isUnavailable = state.isNotFound || state.measurements.none {
                        it.id == currentInteraction.weightEditor.measurementId
                    },
                ),
            )
        } else {
            state
        }
    }
        .stateIn(
            ownerScope,
            SharingStarted.WhileSubscribed(
                stopTimeoutMillis = 0,
                replayExpirationMillis = 0,
            ),
            initialState,
        )

    val callbacks = PetHistoryCallbacks(
        selectRangePreset = ::selectRangePreset,
        setDateRange = ::setDateRange,
        requestDelete = ::requestDelete,
        confirmDelete = ::confirmDelete,
        dismissDelete = ::dismissDelete,
        dismissActionError = ::dismissActionError,
        onScrollToMeasurementHandled = { interaction.update { it.copy(scrollToMeasurementId = null) } },
        editMeasurement = ::editMeasurement,
        changeEditedWeight = ::changeEditedWeight,
        saveEditedWeight = ::saveEditedWeight,
        dismissWeightEditor = ::dismissWeightEditor,
    )

    fun editMeasurement(measurementId: String) {
        val state = uiState.value
        val measurement = state.measurements.firstOrNull { it.id == measurementId } ?: return
        val pet = state.pet ?: return
        interaction.update {
            it.copy(
                actionErrorMessage = null,
                weightEditor = PetWeightEditorState(
                    measurementId = measurement.id,
                    petName = pet.displayName,
                    measuredAtText = measurement.measuredAtText,
                    originalWeightKg = measurement.weightKg,
                    weightInput = canonicalPetWeight(measurement.weightKg),
                ),
            )
        }
    }

    fun changeEditedWeight(value: String) {
        interaction.update { current ->
            val editor = current.weightEditor
            if (editor == null || editor.isSaving) current
            else current.copy(weightEditor = editor.copy(weightInput = value, saveError = null))
        }
    }

    fun saveEditedWeight() {
        val current = interaction.value
        val editor = current.weightEditor ?: return
        val weight = editor.parsedWeightKg ?: return
        if (!editor.canSave) return
        val saving = editor.copy(isSaving = true, saveError = null)
        interaction.value = current.copy(weightEditor = saving)
        ownerScope.launch {
            runCatching { repository.updateMeasurementWeight(current.petId, editor.measurementId, weight) }
                .onSuccess {
                    interaction.update { latest ->
                        if (latest.petId == current.petId && latest.weightEditor == saving) {
                            latest.copy(weightEditor = null, scrollToMeasurementId = editor.measurementId)
                        } else latest
                    }
                }
                .onFailure { error ->
                    interaction.update { latest ->
                        if (latest.petId != current.petId || latest.weightEditor != saving) latest else latest.copy(
                            weightEditor = saving.copy(
                                isSaving = false,
                                isUnavailable = error is PetMeasurementNotFoundException,
                                saveError = if (error is PetMeasurementNotFoundException) null
                                else uiText(R.string.pet_weight_save_error),
                            ),
                        )
                    }
                }
        }
    }

    fun dismissWeightEditor() {
        interaction.update { current ->
            if (current.weightEditor?.isSaving == true) current else current.copy(weightEditor = null)
        }
    }

    fun showSavedMeasurement(saved: com.palixander.scalesync.domain.ManualWeightResult.Saved) {
        val date = saved.measuredAt.atZone(zoneId).toLocalDate()
        selection.update { current ->
            if (current.rangePreset != ChartRangePreset.ALL &&
                (date.isBefore(current.range.startDate) || date.isAfter(current.range.endDateInclusive))
            ) {
                current.copy(
                    range = ChartDateRange(
                        startDate = minOf(current.range.startDate, date),
                        endDateInclusive = maxOf(current.range.endDateInclusive, date),
                    ),
                    rangePreset = ChartRangePreset.CUSTOM,
                )
            } else current
        }
        interaction.update { it.copy(scrollToMeasurementId = saved.measurementId) }
    }

    fun selectPet(petId: PetId) {
        interaction.value = PetHistoryInteraction(petId)
        selection.update { it.copy(petId = petId) }
    }

    fun requestDelete(measurementId: String) {
        if (interaction.value.deleteConfirmation?.isDeleting == true) return
        val state = uiState.value
        val selected = state.measurements.firstOrNull { it.id == measurementId } ?: return
        val currentPetId = selection.value.petId
        if (state.petId != currentPetId) return
        interaction.value = PetHistoryInteraction(
            petId = currentPetId,
            deleteConfirmation = PetHistoryDeleteConfirmation(currentPetId, selected),
        )
    }

    fun confirmDelete() {
        val owner = interaction.value
        val confirmation = owner.deleteConfirmation ?: return
        if (confirmation.isDeleting || owner.petId != selection.value.petId) return
        val operation = confirmation.copy(isDeleting = true)
        interaction.value = owner.copy(deleteConfirmation = operation, actionErrorMessage = null)
        ownerScope.launch {
            runCatching {
                repository.deleteMeasurement(operation.petId, operation.measurement.id)
            }.onSuccess {
                interaction.update { current ->
                    if (current.petId == operation.petId && current.deleteConfirmation == operation) {
                        current.copy(deleteConfirmation = null, actionErrorMessage = null)
                    } else {
                        current
                    }
                }
            }.onFailure { error ->
                interaction.update { current ->
                    if (current.petId == operation.petId && current.deleteConfirmation == operation) {
                        current.copy(
                            deleteConfirmation = operation.copy(isDeleting = false),
                            actionErrorMessage = error.message?.let(UiText::Raw)
                                ?: uiText(R.string.pet_measurement_delete_error),
                        )
                    } else {
                        current
                    }
                }
            }
        }
    }

    fun dismissDelete() {
        interaction.update { current ->
            if (current.deleteConfirmation?.isDeleting == true) current
            else current.copy(deleteConfirmation = null)
        }
    }

    fun dismissActionError() {
        interaction.update { it.copy(actionErrorMessage = null) }
    }

    override fun close() {
        ownerScope.cancel()
    }

    fun selectRangePreset(preset: ChartRangePreset) {
        if (preset == ChartRangePreset.CUSTOM) return
        val today = LocalDate.now(clock)
        val range = preset.rangeEndingOn(today) ?: ChartDateRange(today, today)
        selection.update { it.copy(range = range, rangePreset = preset) }
    }

    fun setDateRange(startDate: LocalDate, endDateInclusive: LocalDate) {
        val range = ChartDateRange(startDate, endDateInclusive)
        selection.update { it.copy(range = range, rangePreset = ChartRangePreset.CUSTOM) }
    }

    private fun observeSelection(current: PetHistorySelection): Flow<PetHistoryUiState> = flow {
        emit(current.loadingState())
        val pet = repository.getPet(current.petId)
        if (pet == null) {
            emit(current.notFoundState())
            return@flow
        }
        val referenceData = references.await()
        emitAll(
            combine(
                repository.observePets()
                    .map { pets -> pets.firstOrNull { it.pet.id == current.petId }?.pet }
                    .onStart { emit(pet) },
                repository.observeMeasurements(current.petId),
            ) { observedPet, measurements ->
                if (observedPet == null) {
                    current.notFoundState()
                } else {
                    val chartMeasurements = measurements.filter { measurement ->
                        measurement.petWeightKg.isFinite() &&
                            ChartPoint(measurement.measuredAt.epochSecond, measurement.petWeightKg).xEpochMillis != null &&
                            runCatching { measurement.measuredAt.atZone(zoneId).toLocalDate() }.isSuccess
                    }
                    val presentationRange = if (current.rangePreset == ChartRangePreset.ALL) {
                        chartMeasurements.allHistoryRange(LocalDate.now(clock), zoneId)
                    } else {
                        current.range
                    }
                    val (content, _) = petHistoryPresentation(
                        measurements = measurements,
                        range = presentationRange,
                        zoneId = zoneId,
                        locale = locale,
                        includeAll = true,
                    )
                    // The selected range controls only the initial viewport and the rows shown
                    // below the chart. The chart remains horizontally scrollable across the full
                    // factual history, so its reference overlays must cover that same model
                    // domain instead of disappearing as soon as the user pans out of the initial
                    // viewport.
                    val referenceRange = presentationRange.coveringMeasurements(chartMeasurements, zoneId)
                    val referenceDates = referenceSampleDates(referenceRange)
                    val (_, series) = petHistoryPresentation(
                        measurements = chartMeasurements,
                        range = chartMeasurements.allHistoryRange(LocalDate.now(clock), zoneId),
                        zoneId = zoneId,
                        locale = locale,
                        includeAll = true,
                    )
                    current.baseState(presentationRange).copy(
                        pet = observedPet,
                        profileSummary = petProfileSummary(
                            observedPet,
                            referenceData.breedCatalog,
                            LocalDate.now(clock),
                        ),
                        content = content,
                        series = series,
                        weightReference = referenceData.referencePresenter.present(observedPet, referenceRange),
                        breedReference = referenceData.breedReferencePresenter.present(observedPet),
                        breedReferenceTimeline = referenceData.breedReferencePresenter.presentTimeline(
                            observedPet,
                            referenceDates.mapIndexed { index, date ->
                                PetHistoryBreedReferenceTimelineMoment(
                                    chartMeasurements.referenceXEpochMillis(
                                        date = date,
                                        isFirstReferenceDate = index == 0,
                                        isLastReferenceDate = index == referenceDates.lastIndex,
                                        zoneId = zoneId,
                                    ),
                                    date,
                                )
                            },
                        ),
                        isLoading = false,
                    )
                }
            },
        )
    }.catch { error ->
        emit(current.baseState().copy(
            isLoading = false,
            errorMessage = error.message?.let(UiText::Raw) ?: uiText(R.string.pet_history_load_error),
        ))
    }

    private fun PetHistorySelection.baseState(displayRange: ChartDateRange = range) = PetHistoryUiState(
        petId = petId,
        startDate = displayRange.startDate,
        endDateInclusive = displayRange.endDateInclusive,
        rangePreset = rangePreset,
    )

    private fun PetHistorySelection.loadingState() = baseState()

    private fun PetHistorySelection.notFoundState() = baseState().copy(
        isLoading = false,
        isNotFound = true,
    )
}

private fun List<PetMeasurement>.allHistoryRange(today: LocalDate, zoneId: ZoneId): ChartDateRange {
    if (isEmpty()) return ChartDateRange(today.minusDays(MINIMUM_ALL_HISTORY_SPAN_DAYS), today)
    val dates = map { measurement ->
        runCatching { measurement.measuredAt.atZone(zoneId).toLocalDate() }.getOrElse {
            if (measurement.measuredAt.isBefore(java.time.Instant.EPOCH)) LocalDate.MIN else LocalDate.MAX
        }
    }
    val first = dates.minOrNull() ?: today
    val last = dates.maxOrNull() ?: today
    return ChartDateRange(minOf(first, last.minusDays(MINIMUM_ALL_HISTORY_SPAN_DAYS)), last)
}

private fun ChartDateRange.coveringMeasurements(
    measurements: List<PetMeasurement>,
    zoneId: ZoneId,
): ChartDateRange {
    if (measurements.isEmpty()) return this
    val dates = measurements.map { measurement ->
        runCatching { measurement.measuredAt.atZone(zoneId).toLocalDate() }.getOrElse {
            if (measurement.measuredAt.isBefore(java.time.Instant.EPOCH)) LocalDate.MIN else LocalDate.MAX
        }
    }
    return ChartDateRange(
        startDate = minOf(startDate, dates.minOrNull() ?: startDate),
        endDateInclusive = maxOf(endDateInclusive, dates.maxOrNull() ?: endDateInclusive),
    )
}

private fun List<PetMeasurement>.referenceXEpochMillis(
    date: LocalDate,
    isFirstReferenceDate: Boolean,
    isLastReferenceDate: Boolean,
    zoneId: ZoneId,
): Long {
    val sameDayXs = asSequence()
        .filter { it.measuredAt.atZone(zoneId).toLocalDate() == date }
        .map { requireNotNull(ChartPoint(it.measuredAt.epochSecond, it.petWeightKg).xEpochMillis) }
        .toList()
    return when {
        isFirstReferenceDate && sameDayXs.isNotEmpty() -> sameDayXs.min()
        isLastReferenceDate && sameDayXs.isNotEmpty() -> sameDayXs.max()
        else -> date.atStartOfDay(zoneId).toInstant().toEpochMilli()
    }
}

/** A one-day Vico domain collapses reference lines and bands into invisible vertical geometry. */
private const val MINIMUM_ALL_HISTORY_SPAN_DAYS = 6L
