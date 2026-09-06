package com.palixander.scalesync

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.palixander.scalesync.charts.ChartFilterSheet
import com.palixander.scalesync.charts.ChartRangePreset
import com.palixander.scalesync.charts.ChartSeries
import com.palixander.scalesync.charts.ChartsCallbacks
import com.palixander.scalesync.charts.ChartsUiState
import com.palixander.scalesync.charts.chartMetricOptions
import com.palixander.scalesync.charts.chartPointsForMetric
import com.palixander.scalesync.charts.inclusiveDateRangeToEpochRange
import com.palixander.scalesync.charts.restoreChartMetricSelection
import com.palixander.scalesync.charts.toPersistedChartMetricKeys
import com.palixander.scalesync.data.MeasurementMetric
import com.palixander.scalesync.domain.AccountId
import com.palixander.scalesync.measurements.currentLocalDates
import com.palixander.scalesync.ui.accounts.AccountSelectorUiState
import com.palixander.scalesync.ui.accounts.reconcileAccountSelection
import java.time.Clock
import java.time.Instant
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
    fun shiftDateWindowByDays(days: Long, today: LocalDate): ChartFilters {
        val startEpochDay = startDate.toEpochDay()
        val windowLengthMinusOne = endDateInclusive.toEpochDay() - startEpochDay
        val latestStartEpochDay = today.toEpochDay() - windowLengthMinusOne
        require(latestStartEpochDay >= LocalDate.MIN.toEpochDay()) {
            "The date window must fit on or before today."
        }
        val requestedStartEpochDay = when {
            days > 0 && startEpochDay > Long.MAX_VALUE - days -> Long.MAX_VALUE
            days < 0 && startEpochDay < Long.MIN_VALUE - days -> Long.MIN_VALUE
            else -> startEpochDay + days
        }
        if (days > 0 && requestedStartEpochDay > latestStartEpochDay) return this
        val shiftedStartEpochDay = requestedStartEpochDay.coerceAtLeast(LocalDate.MIN.toEpochDay())

        if (shiftedStartEpochDay == startEpochDay) return this

        return copy(
            startDate = LocalDate.ofEpochDay(shiftedStartEpochDay),
            endDateInclusive = LocalDate.ofEpochDay(shiftedStartEpochDay + windowLengthMinusOne),
            rangePreset = ChartRangePreset.CUSTOM,
        )
    }

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
    private val metricOptionList = chartMetricOptions()
    private val metricOptions = metricOptionList.associateBy { option ->
        MeasurementMetric.valueOf(option.key)
    }
    private val today = LocalDate.now(zoneId)
    private val currentDate = currentLocalDates(
        zoneId = zoneId,
        clock = Clock.system(zoneId),
    ).stateIn(
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
    private val measurements = combine(filters, accountSelector) { current, selector ->
        current to selector.selectedAccountId
    }.flatMapLatest { (current, accountId) ->
        accountScopedLoad(
            accountId = accountId,
            emptyValue = emptyList(),
        ) { selectedAccountId ->
            val range = inclusiveDateRangeToEpochRange(
                current.startDate,
                current.endDateInclusive,
                zoneId,
            )
            repository.observeRangeEntities(
                accountId = selectedAccountId,
                startInclusive = Instant.ofEpochSecond(range.startInclusiveEpochSecond),
                endExclusive = Instant.ofEpochSecond(range.endExclusiveEpochSecond),
            )
        }
    }

    private val series = combine(filters, measurements) { current, loadState ->
        current to loadState
    }.mapLatest { (current, loadState) ->
        withContext(Dispatchers.Default) {
            ChartsPresentation(
                loadState = loadState,
                series = current.selectedMetrics.map { metric ->
                    ChartSeries(
                        metric = metricOptions.getValue(metric),
                        points = chartPointsForMetric(loadState.valuesOrEmpty(), metric),
                    )
                },
            )
        }
    }

    val uiState = combine(filters, series, accountSelector, currentDate) {
            current,
            presentation,
            selector,
            currentDate,
        ->
        ChartsUiState(
            startDate = current.startDate,
            endDateInclusive = current.endDateInclusive,
            currentDate = currentDate,
            metricOptions = metricOptionList,
            selectedMetricKeys = current.selectedMetrics.mapTo(linkedSetOf(), MeasurementMetric::name),
            series = presentation.series,
            rangePreset = current.rangePreset,
            activeFilterSheet = current.activeFilterSheet,
            isCustomDatePickerOpen = current.isCustomDatePickerOpen,
            isLoading = presentation.loadState is AccountScopedLoad.Loading,
            accountSelector = selector,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(),
        initialUiState,
    )

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
        shiftDateWindowByDays = ::shiftDateWindowByDays,
        onAccountSelected = ::selectAccount,
    )

    private fun selectAccount(accountId: AccountId) {
        if (accountSelector.value.accounts.any { it.id == accountId }) {
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

    fun shiftDateWindowByDays(days: Long) {
        if (days == 0L) return
        filters.update { it.shiftDateWindowByDays(days, currentDate.value) }
    }

    private fun setSelectedMetrics(selectedMetrics: Set<MeasurementMetric>) {
        val orderedSelection = MeasurementMetric.entries
            .filterTo(linkedSetOf()) { it in selectedMetrics }
        filters.update { current -> current.selectMetrics(orderedSelection) }
        profileStore.saveSelectedChartMetricKeys(orderedSelection.toPersistedChartMetricKeys())
    }
}

private fun AccountScopedLoad<List<com.palixander.scalesync.data.MeasurementEntity>>.valuesOrEmpty():
    List<com.palixander.scalesync.data.MeasurementEntity> =
    when (this) {
        AccountScopedLoad.Loading -> emptyList()
        is AccountScopedLoad.Loaded -> value
    }

private data class ChartsPresentation(
    val loadState: AccountScopedLoad<List<com.palixander.scalesync.data.MeasurementEntity>>,
    val series: List<ChartSeries>,
)
