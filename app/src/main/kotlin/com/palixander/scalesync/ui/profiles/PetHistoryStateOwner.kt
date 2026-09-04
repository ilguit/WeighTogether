package com.palixander.scalesync.ui.profiles

import com.palixander.scalesync.PetBreedCatalog
import com.palixander.scalesync.charts.ChartDateRange
import com.palixander.scalesync.charts.ChartRangePreset
import com.palixander.scalesync.domain.PetId
import com.palixander.scalesync.domain.PetRepository
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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

internal data class PetHistorySelection(
    val petId: PetId,
    val range: ChartDateRange,
    val rangePreset: ChartRangePreset,
)

private data class PetHistoryInteraction(
    val petId: PetId,
    val deleteConfirmation: PetHistoryDeleteConfirmation? = null,
    val actionErrorMessage: String? = null,
    val scrollToMeasurementId: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class PetHistoryStateOwner(
    initialPetId: PetId,
    private val repository: PetRepository,
    parentScope: CoroutineScope,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val zoneId: ZoneId = clock.zone,
    private val locale: Locale = Locale.getDefault(),
    private val breedCatalog: PetBreedCatalog = PetBreedCatalog(),
    private val referencePresenter: PetHistoryReferencePresenter = PetHistoryReferencePresenter(),
    private val breedReferencePresenter: PetHistoryBreedReferencePresenter = PetHistoryBreedReferencePresenter(clock = clock),
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

    val uiState: StateFlow<PetHistoryUiState> = combine(
        selection.flatMapLatest(::observeSelection),
        interaction,
    ) { state, currentInteraction ->
        if (state.petId == currentInteraction.petId) {
            state.copy(
                deleteConfirmation = currentInteraction.deleteConfirmation,
                actionErrorMessage = currentInteraction.actionErrorMessage,
                scrollToMeasurementId = currentInteraction.scrollToMeasurementId,
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
    )

    fun showSavedMeasurement(saved: com.palixander.scalesync.domain.ManualWeightResult.Saved) {
        val date = saved.measuredAt.atZone(zoneId).toLocalDate()
        selection.update { current ->
            if (date.isBefore(current.range.startDate) || date.isAfter(current.range.endDateInclusive)) {
                current.copy(range = ChartDateRange(date, date), rangePreset = ChartRangePreset.CUSTOM)
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
                            actionErrorMessage = error.message ?: "Не удалось удалить измерение",
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
        val range = requireNotNull(preset.rangeEndingOn(LocalDate.now(clock)))
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
                    val (content, series) = petHistoryPresentation(
                        measurements = measurements,
                        range = current.range,
                        zoneId = zoneId,
                        locale = locale,
                    )
                    current.baseState().copy(
                        pet = observedPet,
                        profileSummary = petProfileSummary(observedPet, breedCatalog),
                        content = content,
                        series = series,
                        weightReference = referencePresenter.present(observedPet, current.range),
                        breedReference = breedReferencePresenter.present(observedPet),
                        breedReferenceTimeline = breedReferencePresenter.presentTimeline(
                            observedPet,
                            series.points.mapNotNull { point ->
                                point.xEpochMillis?.let { epochMillis ->
                                    PetHistoryBreedReferenceTimelineMoment(
                                        epochMillis,
                                        java.time.Instant.ofEpochMilli(epochMillis).atZone(zoneId).toLocalDate(),
                                    )
                                }
                            },
                        ),
                        isLoading = false,
                    )
                }
            },
        )
    }.catch { error ->
        emit(current.baseState().copy(isLoading = false, errorMessage = error.message ?: "Не удалось загрузить историю"))
    }

    private fun PetHistorySelection.baseState() = PetHistoryUiState(
        petId = petId,
        startDate = range.startDate,
        endDateInclusive = range.endDateInclusive,
        rangePreset = rangePreset,
    )

    private fun PetHistorySelection.loadingState() = baseState()

    private fun PetHistorySelection.notFoundState() = baseState().copy(
        isLoading = false,
        isNotFound = true,
    )
}
