package com.example.huaweimisync

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.huaweimisync.charts.ChartMetricOption
import com.example.huaweimisync.charts.ChartPoint
import com.example.huaweimisync.charts.ChartSeries
import com.example.huaweimisync.charts.ChartsUiState
import com.example.huaweimisync.charts.inclusiveDateRangeToEpochRange
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
)

@OptIn(ExperimentalCoroutinesApi::class)
class ChartsViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = (application as MiSyncApplication).container.repository
    private val zoneId = ZoneId.systemDefault()
    private val metricOptions = MeasurementMetric.entries.associateWith { metric ->
        ChartMetricOption(
            key = metric.name,
            displayName = metric.displayName,
            unit = metric.unit,
            decimalPlaces = metric.decimalPlaces,
        )
    }
    private val today = LocalDate.now(zoneId)
    private val filters = MutableStateFlow(
        ChartFilters(
            startDate = today.minusDays(6),
            endDateInclusive = today,
            selectedMetrics = setOf(MeasurementMetric.WEIGHT_KG),
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
            metricOptions = MeasurementMetric.entries.map(metricOptions::getValue),
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
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        ChartsUiState.initial(
            metricOptions = MeasurementMetric.entries.map(metricOptions::getValue),
            defaultMetricKey = MeasurementMetric.WEIGHT_KG.name,
        ),
    )

    fun setDateRange(startDate: LocalDate, endDateInclusive: LocalDate) {
        if (endDateInclusive.isBefore(startDate)) return
        filters.update { it.copy(startDate = startDate, endDateInclusive = endDateInclusive) }
    }

    fun setMetricSelected(key: String, selected: Boolean) {
        val metric = MeasurementMetric.entries.firstOrNull { it.name == key } ?: return
        filters.update { current ->
            current.copy(
                selectedMetrics = if (selected) {
                    current.selectedMetrics + metric
                } else {
                    current.selectedMetrics - metric
                },
            )
        }
    }

    fun selectAll() {
        filters.update { it.copy(selectedMetrics = MeasurementMetric.entries.toSet()) }
    }

    fun clearSelection() {
        filters.update { it.copy(selectedMetrics = emptySet()) }
    }
}
