package com.palixander.scalesync.measurements

import com.palixander.scalesync.charts.ChartPoint
import com.palixander.scalesync.charts.chartMetricOptions
import com.palixander.scalesync.charts.formatChartCurrentValue
import com.palixander.scalesync.charts.formatChartMarkerText
import com.palixander.scalesync.ui.profiles.PetWeightChartMetric
import java.time.ZoneOffset
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class ManualWeightPresentationTest {
    @Test
    fun petHistoryPreservesOriginAndEditedFlagIndependently() {
        for (origin in com.palixander.scalesync.domain.MeasurementOrigin.entries) {
            for (edited in listOf(false, true)) {
                val manual = origin == com.palixander.scalesync.domain.MeasurementOrigin.MANUAL
                val record = com.palixander.scalesync.domain.PetMeasurement(
                    "record", com.palixander.scalesync.domain.PetId("pet"), java.time.Instant.EPOCH,
                    if (manual) null else 70.0, if (manual) null else 74.0,
                    4.0, origin, edited,
                )
                val day = java.time.LocalDate.of(1970, 1, 1)
                val (content, _) = com.palixander.scalesync.ui.profiles.petHistoryPresentation(
                    listOf(record), com.palixander.scalesync.charts.ChartDateRange(day, day), ZoneOffset.UTC, Locale.US,
                )
                val row = (content as com.palixander.scalesync.ui.profiles.PetHistoryContent.Single).measurement
                assertEquals(origin, row.origin)
                assertEquals(edited, row.isManuallyEdited)
            }
        }
    }

    @Test
    fun directPetWeightHasOriginAndChartPointWithoutSourceReadings() {
        val record = com.palixander.scalesync.domain.PetMeasurement(
            "manual", com.palixander.scalesync.domain.PetId("pet"), java.time.Instant.EPOCH,
            null, null, 4.125, com.palixander.scalesync.domain.MeasurementOrigin.MANUAL,
        )
        val day = java.time.LocalDate.of(1970, 1, 1)
        val (content, series) = com.palixander.scalesync.ui.profiles.petHistoryPresentation(
            listOf(record), com.palixander.scalesync.charts.ChartDateRange(day, day), ZoneOffset.UTC, Locale.US,
        )
        val row = (content as com.palixander.scalesync.ui.profiles.PetHistoryContent.Single).measurement
        assertEquals(record.origin, row.origin)
        assertEquals("4.125 кг", row.weightText)
        assertEquals(4.125, series.points.single().value, 0.0)
        assertNull(record.firstWeightKg)
        assertNull(record.secondWeightKg)
    }

    @Test
    fun gramPrecisionIsVisibleInHumanPetAndHomeChartMarkers() {
        val human = chartMetricOptions().first { it.key == "WEIGHT_KG" }
        for (weight in listOf(4.121, 4.124, 4.125)) {
            val expected = weight.toString().replace('.', ',')
            assertEquals(expected, formatWeight(weight, Locale.forLanguageTag("ru")))
            for (metric in listOf(human, PetWeightChartMetric)) {
                assertEquals("$expected кг", formatChartCurrentValue(weight, metric, Locale.forLanguageTag("ru")))
                assertTrue(formatChartMarkerText(ChartPoint(0, weight), metric, ZoneOffset.UTC, Locale.forLanguageTag("ru")).endsWith("$expected кг"))
            }
            val selection = HomeKgChartMarkerSelection(0, listOf(
                HomeKgChartMarkerEntry(HomeKgChartMetric.WEIGHT.key, "Вес", weight, 2, 0),
                HomeKgChartMarkerEntry(HomeKgChartMetric.BODY_FAT_MASS.key, "Жир", 1.2, 2, 0),
            ))
            val text = formatHomeKgChartMarker(selection, ZoneOffset.UTC, Locale.US)
            assertTrue(text.contains("Вес: $weight кг"))
            assertTrue(text.contains("Жир: 1.20 кг"))
        }
    }

    @Test
    fun trailingZerosAreRemovedAndMissingWeightRemainsMissing() {
        assertEquals("4", formatWeight(4.0, Locale.US))
        assertEquals("4.12", formatWeight(4.120, Locale.US))
        assertEquals("0.001", formatWeight(0.001, Locale.US))
        assertEquals("—", formatChartCurrentValue(null, PetWeightChartMetric, Locale.US))
        val fat = chartMetricOptions().first { it.key == "BODY_FAT_PERCENT" }
        assertEquals("1.2 %", formatChartCurrentValue(1.2, fat, Locale.US))
    }
}
