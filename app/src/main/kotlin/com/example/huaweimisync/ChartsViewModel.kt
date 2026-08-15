package com.example.huaweimisync

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.huaweimisync.charts.ChartFilterSheet
import com.example.huaweimisync.charts.ChartPoint
import com.example.huaweimisync.charts.ChartRangePreset
import com.example.huaweimisync.charts.ChartSeries
import com.example.huaweimisync.charts.ChartsUiState
import com.example.huaweimisync.charts.chartMetricOptions
import com.example.huaweimisync.charts.inclusiveDateRangeToEpochRange
import com.example.huaweimisync.charts.restoreChartMetricSelection
import com.example.huaweimisync.charts.toPersistedChartMetricKeys
import com.example.huaweimisync.data.MeasurementMetric
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

private data class ChartFilters(
    val startDate: LocalDate,
    val endDateInclusive: LocalDate,
    val selectedMetrics: Set<MeasurementMetric>,
    val rangePreset: ChartRangePreset,
    val activeFilterSheet: ChartFilterSheet? = null,
    val isCustomDatePickerOpen: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
class ChartsViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as MiSyncApplication).container
    private val repository = container.repository
    private val profileStore = container.profileStore
    private val zoneId = ZoneId.systemDefault()
    private val metricOptionList = chartMetricOptions()
    private val metricOptions = metricOptionList.associateBy { option ->
        MeasurementMetric.valueOf(option.key)
    }
    private val today = LocalDate.now(zoneId)
    private val initialSelectedMetrics = restoreChartMetricSelection(
        profileStore.settings.value.selectedChartMetricKeys,
    )
    private val filters = MutableStateFlow(
        ChartFilters(
            startDate = today.minusDays(6),
            endDateInclusive = today,
            selectedMetrics = initialSelectedMetrics,
            rangePreset = ChartRangePreset.LAST_7_DAYS,
        ),
    )
    private val measurements = filters.flatMapLatest { current ->
        val range = inclusiveDateRangeToEpochRange(
            current.startDate,
            current.endDateInclusive,
            zoneId,
        )
        repository.observeRange(
            startInclusive = Instant.ofEpochMilli(range.startInclusive),
            endExclusive = Instant.ofEpochMilli(range.endExclusive),
        )
    }

    val uiState = combine(filters, measurements) { current, values ->
        ChartsUiState(
            startDate = current.startDate,
            endDateInclusive = current.endDateInclusive,
            metricOptions = metricOptionList,
            selectedMetricKeys = current.selectedMetrics.mapTo(linkedSetOf(), MeasurementMetric::name),
            series = current.selectedMetrics.map { metric ->
                ChartSeries(
                    metric = metricOptions.getValue(metric),
                    points = values.map { value ->
                        ChartPoint(
                            measuredAtEpochMillis = value.measuredAtEpochMillis,
                            value = metric.valueOf(value),
                        )
                    },
                )
            },
            rangePreset = current.rangePreset,
            activeFilterSheet = current.activeFilterSheet,
            isCustomDatePickerOpen = current.isCustomDatePickerOpen,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        ChartsUiState.initial(
            metricOptions = metricOptionList,
            defaultMetricKeys = initialSelectedMetrics.toPersistedChartMetricKeys(),
        ),
    )

    fun setDateRange(startDate: LocalDate, endDateInclusive: LocalDate) {
        if (endDateInclusive.isBefore(startDate)) return
        filters.update {
            it.copy(
                startDate = startDate,
                endDateInclusive = endDateInclusive,
                rangePreset = ChartRangePreset.CUSTOM,
                activeFilterSheet = null,
                isCustomDatePickerOpen = false,
            )
        }
    }

    fun openRangeFilter() {
        filters.update {
            it.copy(activeFilterSheet = ChartFilterSheet.RANGE, isCustomDatePickerOpen = false)
        }
    }

    fun openMetricFilter() {
        filters.update {
            it.copy(activeFilterSheet = ChartFilterSheet.METRICS, isCustomDatePickerOpen = false)
        }
    }

    fun dismissFilterSheet() {
        filters.update { it.copy(activeFilterSheet = null) }
    }

    fun selectRangePreset(preset: ChartRangePreset) {
        if (preset == ChartRangePreset.CUSTOM) {
            filters.update {
                it.copy(activeFilterSheet = null, isCustomDatePickerOpen = true)
            }
            return
        }
        val range = requireNotNull(preset.rangeEndingOn(LocalDate.now(zoneId)))
        filters.update {
            it.copy(
                startDate = range.startDate,
                endDateInclusive = range.endDateInclusive,
                rangePreset = preset,
                activeFilterSheet = null,
                isCustomDatePickerOpen = false,
            )
        }
    }

    fun dismissCustomDatePicker() {
        filters.update { it.copy(isCustomDatePickerOpen = false) }
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
        filters.update { it.copy(activeFilterSheet = null) }
    }

    private fun setSelectedMetrics(selectedMetrics: Set<MeasurementMetric>) {
        val orderedSelection = MeasurementMetric.entries
            .filterTo(linkedSetOf()) { it in selectedMetrics }
        filters.update { current ->
            current.copy(selectedMetrics = orderedSelection)
        }
        profileStore.saveSelectedChartMetricKeys(orderedSelection.toPersistedChartMetricKeys())
    }
}
