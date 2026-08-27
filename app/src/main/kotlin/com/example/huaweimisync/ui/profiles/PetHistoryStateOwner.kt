package com.example.huaweimisync.ui.profiles

import com.example.huaweimisync.charts.ChartDateRange
import com.example.huaweimisync.charts.ChartRangePreset
import com.example.huaweimisync.domain.PetId
import com.example.huaweimisync.domain.PetRepository
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

internal data class PetHistorySelection(
    val petId: PetId,
    val range: ChartDateRange,
    val rangePreset: ChartRangePreset,
)

@OptIn(ExperimentalCoroutinesApi::class)
class PetHistoryStateOwner(
    initialPetId: PetId,
    private val repository: PetRepository,
    scope: CoroutineScope,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val zoneId: ZoneId = clock.zone,
    private val locale: Locale = Locale.getDefault(),
) {
    private val initialState = PetHistoryUiState.initial(initialPetId, clock)
    private val selection = MutableStateFlow(
        PetHistorySelection(
            petId = initialPetId,
            range = ChartDateRange(initialState.startDate, initialState.endDateInclusive),
            rangePreset = initialState.rangePreset,
        ),
    )

    val uiState: StateFlow<PetHistoryUiState> = selection
        .flatMapLatest(::observeSelection)
        .stateIn(scope, SharingStarted.Eagerly, initialState)

    val callbacks = PetHistoryCallbacks(
        selectRangePreset = ::selectRangePreset,
        setDateRange = ::setDateRange,
    )

    fun selectPet(petId: PetId) {
        selection.update { it.copy(petId = petId) }
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
            repository.observeMeasurements(current.petId).map { measurements ->
                val (content, series) = petHistoryPresentation(
                    measurements = measurements,
                    range = current.range,
                    zoneId = zoneId,
                    locale = locale,
                )
                current.baseState().copy(
                    pet = pet,
                    content = content,
                    series = series,
                    isLoading = false,
                )
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
