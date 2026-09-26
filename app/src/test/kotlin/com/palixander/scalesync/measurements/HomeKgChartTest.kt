package com.palixander.scalesync.measurements

import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeKgChartTest {
    @Test
    fun markerUsesNearestMeasurementAndOnlyActiveValuesPresentThere() {
        val state = chartState(
            activeKeys = setOf("weight_kg", "body_fat_mass_kg", "water_mass_kg"),
            values = mapOf(
                "weight_kg" to listOf(100L to 72.4, 200L to 72.1),
                "body_fat_mass_kg" to listOf(100L to 13.5),
                "water_mass_kg" to listOf(200L to 41.8),
            ),
        )

        val selection = requireNotNull(homeKgChartMarkerSelection(state, 110_000L))

        assertEquals(100L, selection.measuredAtEpochSecond)
        assertEquals(listOf("weight_kg", "body_fat_mass_kg"), selection.entries.map { it.key })
        assertEquals(
            listOf(
                HomeKgChartColorToken.BLUE.argb,
                HomeKgChartColorToken.RED.argb,
            ),
            selection.entries.map { it.colorArgb },
        )
    }

    @Test
    fun markerSupportsOnePointAndFormatsAllRows() {
        val state = chartState(
            activeKeys = setOf("weight_kg", "body_fat_mass_kg"),
            values = mapOf(
                "weight_kg" to listOf(1_756_000_000L to 72.4),
                "body_fat_mass_kg" to listOf(1_756_000_000L to 13.55),
            ),
        )

        val selection = requireNotNull(homeKgChartMarkerSelection(state, 1_756_000_000_000L))

        assertEquals(
            "24.08.2025 01:46\nWeight: 72.4 kg\nFat mass: 13.55 kg",
            formatHomeKgChartMarker(selection, ZoneOffset.UTC, Locale.US),
        )
    }

    @Test
    fun markerDoesNotMergeDifferentMeasurementsRecordedInTheSameSecond() {
        val state = chartState(
            activeKeys = setOf("weight_kg", "body_fat_mass_kg"),
        ).copy(
            series = HomeKgChartSeriesCatalog.map { metric ->
                val points = when (metric.key) {
                    "weight_kg" -> listOf(HomeKgChartPoint("weight-measurement", 100L, 72.4))
                    "body_fat_mass_kg" -> listOf(HomeKgChartPoint("fat-measurement", 100L, 13.5))
                    else -> emptyList()
                }
                HomeKgChartSeries(
                    key = metric.key,
                    label = metric.label,
                    unit = metric.unit,
                    decimalPlaces = metric.decimalPlaces,
                    color = metric.color,
                    points = points,
                )
            },
        )

        val selection = requireNotNull(homeKgChartMarkerSelection(state, 100_000L))

        assertEquals(100L, selection.measuredAtEpochSecond)
        assertEquals(listOf("weight_kg"), selection.entries.map { it.key })
    }

    @Test
    fun markerHasNoSelectionWhenEverySeriesIsDisabled() {
        assertNull(homeKgChartMarkerSelection(chartState(activeKeys = emptySet()), 100_000L))
    }

    private fun chartState(
        activeKeys: Set<String>,
        values: Map<String, List<Pair<Long, Double>>> = emptyMap(),
    ): HomeKgChartUiState = HomeKgChartUiState(
        period = HomeKgChartPeriod(
            startDate = LocalDate.of(2025, 8, 1),
            endDateInclusive = LocalDate.of(2025, 8, 30),
            startInclusiveEpochSecond = 1L,
            endExclusiveEpochSecond = 3_000_000_000L,
        ),
        series = HomeKgChartSeriesCatalog.map { metric ->
            HomeKgChartSeries(
                key = metric.key,
                label = metric.label,
                unit = metric.unit,
                decimalPlaces = metric.decimalPlaces,
                color = metric.color,
                points = values[metric.key].orEmpty().mapIndexed { index, (epochSecond, value) ->
                    HomeKgChartPoint("$index", epochSecond, value)
                },
            )
        },
        activeSeriesKeys = activeKeys,
    )
}
