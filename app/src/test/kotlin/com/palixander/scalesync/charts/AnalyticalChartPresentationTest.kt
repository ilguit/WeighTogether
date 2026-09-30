package com.palixander.scalesync.charts

import com.palixander.scalesync.measurements.HomeKgChartPeriod
import com.palixander.scalesync.measurements.HomeKgChartPoint
import com.palixander.scalesync.measurements.HomeKgChartSeries
import com.palixander.scalesync.measurements.HomeKgChartSeriesCatalog
import com.palixander.scalesync.measurements.HomeKgChartUiState
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnalyticalChartPresentationTest {
    @Test
    fun differentMeasurementsAtSameTimestampDoNotProduceSingleMeasurementSummary() {
        val chart = chartState(
            mapOf(
                "weight_kg" to listOf(HomeKgChartPoint("a", 100L, 70.0), HomeKgChartPoint("b", 100L, 71.0)),
                "body_fat_mass_kg" to listOf(HomeKgChartPoint("b", 100L, 14.0)),
            ),
        )

        assertNull(analyticalSingleMeasurement(chart))
        assertNull(analyticalSingleMeasurement(chart.copy(series = chart.series.reversed())))
    }

    @Test
    fun singleMeasurementPreservesItsValuesAndMissingMetrics() {
        val chart = chartState(
            mapOf(
                "weight_kg" to listOf(HomeKgChartPoint("b", 100L, 71.0)),
                "body_fat_mass_kg" to listOf(HomeKgChartPoint("b", 100L, 14.0)),
            ),
        )

        val summary = requireNotNull(analyticalSingleMeasurement(chart))

        assertEquals("b", summary.measurementId)
        assertEquals(100L, summary.measuredAtEpochSecond)
        assertEquals(mapOf("weight_kg" to 71.0, "body_fat_mass_kg" to 14.0, "water_mass_kg" to null), summary.valuesKg)
    }

    @Test
    fun emptyDataOrDisabledSeriesHaveNoSingleMeasurementSummary() {
        assertNull(analyticalSingleMeasurement(chartState(emptyMap())))
        val chart = chartState(mapOf("weight_kg" to listOf(HomeKgChartPoint("a", 100L, 70.0))))
        assertNull(analyticalSingleMeasurement(chart.copy(activeSeriesKeys = emptySet())))
    }

    private fun chartState(values: Map<String, List<HomeKgChartPoint>>) = HomeKgChartUiState(
        period = HomeKgChartPeriod(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 1), 1L, 200L),
        series = HomeKgChartSeriesCatalog.map { metric ->
            HomeKgChartSeries(metric.key, metric.label, metric.unit, metric.decimalPlaces, metric.color, values[metric.key].orEmpty())
        },
        activeSeriesKeys = setOf("weight_kg", "body_fat_mass_kg", "water_mass_kg"),
    )
}
