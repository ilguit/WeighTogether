package com.palixander.scalesync

import android.app.Application
import android.content.Context
import com.palixander.scalesync.charts.AnalyticalChartCallbacks
import com.palixander.scalesync.charts.AnalyticalChartController
import com.palixander.scalesync.charts.AnalyticalChartState
import com.palixander.scalesync.charts.PreferenceAnalyticalChartSettingsStore
import com.palixander.scalesync.charts.buildAnalyticalCard
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onStart
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.palixander.scalesync.charts.ChartFilterSheet
import com.palixander.scalesync.charts.ChartRangePreset
import com.palixander.scalesync.charts.ChartSeries
import com.palixander.scalesync.charts.ChartsCallbacks
import com.palixander.scalesync.charts.ChartsUiState
import com.palixander.scalesync.charts.chartMetricOptions
import com.palixander.scalesync.charts.chartPointsForMetric
import com.palixander.scalesync.charts.restoreChartMetricSelection
import com.palixander.scalesync.charts.toPersistedChartMetricKeys
import com.palixander.scalesync.data.MeasurementMetric
import com.palixander.scalesync.domain.AccountId
import com.palixander.scalesync.measurements.currentLocalDates
import com.palixander.scalesync.ui.accounts.AccountSelectorUiState
import com.palixander.scalesync.ui.accounts.reconcileAccountSelection
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

internal data class ChartFilters(
    val startDate: LocalDate,
    val endDateInclusive: LocalDate,
    val selectedMetrics: Set<MeasurementMetric>,
    val rangePreset: ChartRangePreset,
    val activeFilterSheet: ChartFilterSheet? = null,
    val isCustomDatePickerOpen: Boolean = false,
) {
    fun confirmCustomDateRange(startDate: LocalDate, endDateInclusive: LocalDate): ChartFilters =
        if (endDateInclusive.isBefore(startDate)) {
            this
        } else {
            copy(
                startDate = startDate,
                endDateInclusive = endDateInclusive,
                rangePreset = ChartRangePreset.CUSTOM,
                activeFilterSheet = null,
                isCustomDatePickerOpen = false,
            )
        }

    fun openFilter(sheet: ChartFilterSheet): ChartFilters = copy(
        activeFilterSheet = sheet,
        isCustomDatePickerOpen = false,
    )

    fun dismissFilterSheet(): ChartFilters = copy(activeFilterSheet = null)

    fun selectRangePreset(preset: ChartRangePreset, today: LocalDate): ChartFilters {
        if (preset == ChartRangePreset.ALL) return this
        if (preset == ChartRangePreset.CUSTOM) {
            return copy(activeFilterSheet = null, isCustomDatePickerOpen = true)
        }
        val range = requireNotNull(preset.rangeEndingOn(today))
        return copy(
            startDate = range.startDate,
            endDateInclusive = range.endDateInclusive,
            rangePreset = preset,
            activeFilterSheet = null,
            isCustomDatePickerOpen = false,
        )
    }

    fun dismissCustomDatePicker(): ChartFilters = copy(isCustomDatePickerOpen = false)

    fun selectMetrics(selectedMetrics: Set<MeasurementMetric>): ChartFilters = copy(
        selectedMetrics = MeasurementMetric.entries
            .filterTo(linkedSetOf()) { it in selectedMetrics },
    )

    fun doneSelectingMetrics(): ChartFilters = copy(activeFilterSheet = null)

    companion object {
        fun initial(
            today: LocalDate,
            selectedMetrics: Set<MeasurementMetric>,
        ): ChartFilters {
            val range = requireNotNull(ChartRangePreset.LAST_7_DAYS.rangeEndingOn(today))
            return ChartFilters(
                startDate = range.startDate,
                endDateInclusive = range.endDateInclusive,
                selectedMetrics = MeasurementMetric.entries
                    .filterTo(linkedSetOf()) { it in selectedMetrics },
                rangePreset = ChartRangePreset.LAST_7_DAYS,
            )
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ChartsViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as ScaleSyncApplication).container
    private val repository = container.repository
    private val profileStore = container.profileStore
    private val zoneId = ZoneId.systemDefault()
    private val liveZone = MutableStateFlow(zoneId)
    private val retryGeneration = MutableStateFlow(0)
    val analyticalController = AnalyticalChartController(
        PreferenceAnalyticalChartSettingsStore(application.getSharedPreferences("analytical_charts", Context.MODE_PRIVATE)),
        viewModelScope,
    )
    fun onResume() {
        val zone = ZoneId.systemDefault()
        if (liveZone.value != zone) {
            analyticalController.invalidateCalculation()
            liveZone.value = zone
        }
    }
    fun retryAnalyticalCharts() { retryGeneration.value++ }
    fun autoSelectMorning() {
        val snapshot = analyticalInput.value
        val account = snapshot.selector.selectedAccountId ?: return
        if (!snapshot.loading && !snapshot.error) analyticalController.autoSelect(account, snapshot.rows, liveZone.value)
    }
    private val metricOptionList = chartMetricOptions(application::getString)
    private val metricOptions = metricOptionList.associateBy { option ->
        MeasurementMetric.valueOf(option.key)
    }
    private val today = LocalDate.now(zoneId)
    private val currentDate = liveZone.flatMapLatest { zone ->
        currentLocalDates(zoneId = zone, clock = Clock.system(zone))
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        today,
    )
    private val initialSelectedMetrics = restoreChartMetricSelection(
        profileStore.settings.value.selectedChartMetricKeys,
    )
    internal val initialUiState = ChartsUiState.initial(
        metricOptions = metricOptionList,
        defaultMetricKeys = initialSelectedMetrics.toPersistedChartMetricKeys(),
        today = today,
    )
    private val filters = MutableStateFlow(
        ChartFilters.initial(
            today = today,
            selectedMetrics = initialSelectedMetrics,
        ),
    )
    private val accountSelector = combine(
        container.accounts.observeAccounts(),
        container.accounts.observeSettings(),
        container.accountSelection.selection,
    ) { accounts, settings, selection ->
        selection to reconcileAccountSelection(accounts, selection.accountId, settings.primaryAccountId)
    }.onEach { (sourceSelection, selector) ->
        container.accountSelection.selectIfCurrent(sourceSelection, selector.selectedAccountId)
    }.map { (_, selector) ->
        selector
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        AccountSelectorUiState(
            accounts = emptyList(),
            selectedAccountId = null,
            primaryAccountId = null,
            isLoading = true,
        ),
    )
    private val analyticalInput = combine(accountSelector, retryGeneration) { selector, _ -> selector }
        .flatMapLatest { selector ->
            analyticalController.selectAccount(selector.selectedAccountId)
            val account = selector.selectedAccountId
            if (account == null) kotlinx.coroutines.flow.flowOf(AnalyticalInput(selector))
            else repository.observeAllEntities(account)
                .map {
                    analyticalController.invalidateCalculation()
                    AnalyticalInput(selector, rows = it)
                }
                .onStart { emit(AnalyticalInput(selector, loading = true)) }
                .catch { emit(AnalyticalInput(selector, error = true)) }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, AnalyticalInput(accountSelector.value, loading = true))

    val uiState = combine(filters, analyticalInput, currentDate, analyticalController.state, liveZone) {
            current, input, date, analytical, zone ->
        ChartInputs(current, input, date, analytical, zone)
    }.mapLatest { inputs ->
        withContext(Dispatchers.Default) {
            val (current, input, date, analytical, zone) = inputs
            val consistent = analytical.accountId == input.selector.selectedAccountId
            val cardResult = runCatching {
                if (!consistent || input.loading || input.error) emptyList() else analytical.settings.map { setting ->
                    buildAnalyticalCard(setting, input.rows, current.startDate, current.endDateInclusive, zone)
                }
            }
            val cards = cardResult.getOrDefault(emptyList())
            ChartsUiState(
                startDate = current.startDate,
                endDateInclusive = current.endDateInclusive,
                currentDate = date,
                metricOptions = metricOptionList,
                selectedMetricKeys = current.selectedMetrics.mapTo(linkedSetOf(), MeasurementMetric::name),
                series = current.selectedMetrics.map { metric ->
                    ChartSeries(metricOptions.getValue(metric), chartPointsForMetric(input.rows, metric))
                },
                rangePreset = current.rangePreset,
                activeFilterSheet = current.activeFilterSheet,
                isCustomDatePickerOpen = current.isCustomDatePickerOpen,
                isLoading = input.loading,
                accountSelector = input.selector,
                analytical = if (consistent) analytical else AnalyticalChartState(input.selector.selectedAccountId),
                analyticalCards = cards,
                analyticalError = input.error || cardResult.isFailure,
                zoneId = zone,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), initialUiState)

    val callbacks = ChartsCallbacks(
        openRangeFilter = ::openRangeFilter,
        openMetricFilter = ::openMetricFilter,
        dismissFilterSheet = ::dismissFilterSheet,
        selectRangePreset = ::selectRangePreset,
        dismissCustomDatePicker = ::dismissCustomDatePicker,
        setDateRange = ::setDateRange,
        setMetricSelected = ::setMetricSelected,
        selectAll = ::selectAll,
        clearSelection = ::clearSelection,
        doneSelectingMetrics = ::doneSelectingMetrics,
        onAccountSelected = ::selectAccount,
        analytical = AnalyticalChartCallbacks(analyticalController, ::autoSelectMorning, ::retryAnalyticalCharts, ::onResume),
    )

    private fun selectAccount(accountId: AccountId) {
        if (accountSelector.value.accounts.any { it.id == accountId }) {
            analyticalController.selectAccount(accountId)
            container.accountSelection.select(accountId)
        }
    }

    /** Confirms a user-entered custom interval. Presets use [selectRangePreset]. */
    fun setDateRange(startDate: LocalDate, endDateInclusive: LocalDate) {
        filters.update { it.confirmCustomDateRange(startDate, endDateInclusive) }
    }

    fun openRangeFilter() {
        filters.update { it.openFilter(ChartFilterSheet.RANGE) }
    }

    fun openMetricFilter() {
        filters.update { it.openFilter(ChartFilterSheet.METRICS) }
    }

    fun dismissFilterSheet() {
        filters.update(ChartFilters::dismissFilterSheet)
    }

    fun selectRangePreset(preset: ChartRangePreset) {
        filters.update { it.selectRangePreset(preset, currentDate.value) }
    }

    fun dismissCustomDatePicker() {
        filters.update(ChartFilters::dismissCustomDatePicker)
    }

    fun setMetricSelected(key: String, selected: Boolean) {
        val metric = MeasurementMetric.entries.firstOrNull { it.name == key } ?: return
        val current = filters.value.selectedMetrics
        setSelectedMetrics(
            if (selected) {
                current + metric
            } else {
                current - metric
            },
        )
    }

    fun selectAll() {
        setSelectedMetrics(MeasurementMetric.entries.toSet())
    }

    fun clearSelection() {
        setSelectedMetrics(emptySet())
    }

    fun doneSelectingMetrics() {
        filters.update(ChartFilters::doneSelectingMetrics)
    }

    private fun setSelectedMetrics(selectedMetrics: Set<MeasurementMetric>) {
        val orderedSelection = MeasurementMetric.entries
            .filterTo(linkedSetOf()) { it in selectedMetrics }
        filters.update { current -> current.selectMetrics(orderedSelection) }
        profileStore.saveSelectedChartMetricKeys(orderedSelection.toPersistedChartMetricKeys())
    }
}

private data class AnalyticalInput(
    val selector: AccountSelectorUiState,
    val rows: List<com.palixander.scalesync.data.MeasurementEntity> = emptyList(),
    val loading: Boolean = false,
    val error: Boolean = false,
)
private data class ChartInputs(
    val filters: ChartFilters,
    val input: AnalyticalInput,
    val date: LocalDate,
    val analytical: AnalyticalChartState,
    val zone: ZoneId,
)
