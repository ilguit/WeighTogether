package com.palixander.scalesync.charts

import com.palixander.scalesync.data.MeasurementEntity
import com.palixander.scalesync.measurements.HomeKgChartPeriod
import com.palixander.scalesync.measurements.HomeKgChartUiState
import com.palixander.scalesync.measurements.buildHomeKgChartUiState
import com.palixander.scalesync.toMeasurementUiItem
import java.time.LocalDate
import java.time.ZoneId

data class AnalyticalChartCallbacks(
    val controller: AnalyticalChartController,
    val autoSelectMorning: () -> Unit,
    val retry: () -> Unit,
    val onResume: () -> Unit,
)

data class AnalyticalCardUiState(
    val settings: AnalyticalChartSettings,
    val chart: HomeKgChartUiState? = null,
    val hourlyCounts: List<Int> = emptyList(),
    val excluded: List<MeasurementEntity> = emptyList(),
    val sufficientHistory: Boolean = false,
    val sourceCount: Int = 0,
    val retainedCount: Int = 0,
)

internal fun buildAnalyticalCard(
    settings: AnalyticalChartSettings,
    rows: List<MeasurementEntity>,
    start: LocalDate,
    end: LocalDate,
    zone: ZoneId,
): AnalyticalCardUiState {
    if (settings.type == AnalyticalChartType.HOURLY) return AnalyticalCardUiState(
        settings = settings, hourlyCounts = hourlyMeasurementCounts(rows, zone), sourceCount = rows.size,
    )
    val result = when (settings.type) {
        AnalyticalChartType.MORNING -> morningMeasurements(rows, settings.morningWindow, settings.morningMode, zone).let {
            AnalyticalMeasurements(it.retained, it.excludedByFilter, it.sufficientHistory)
        }
        AnalyticalChartType.DAILY_MINIMUM -> dailyMinimumMeasurements(rows, zone)
        AnalyticalChartType.HOURLY -> error("Handled above")
    }
    val chart = buildHomeKgChartUiState(
        result.retained.map { it.toMeasurementUiItem(false, zoneId = zone) },
        settings.activeSeriesKeys,
        zoneId = zone,
    ).copy(period = HomeKgChartPeriod(start, end, start.atStartOfDay(zone).toEpochSecond(), end.plusDays(1).atStartOfDay(zone).toEpochSecond()))
    return AnalyticalCardUiState(settings, chart, excluded = result.excluded,
        sufficientHistory = result.sufficientHistory, sourceCount = rows.size, retainedCount = result.retained.size)
}
