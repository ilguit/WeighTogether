package com.example.huaweimisync.charts

import com.example.huaweimisync.data.AppSettings
import com.example.huaweimisync.data.MeasurementMetric
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartMetricSelectionTest {
    @Test
    fun `all sixteen metrics expose stable metadata`() {
        val options = chartMetricOptions()

        assertEquals(16, options.size)
        assertEquals(16, options.map(ChartMetricOption::key).distinct().size)
        assertEquals(MeasurementMetric.entries.map(MeasurementMetric::name), options.map(ChartMetricOption::key))
        assertTrue(options.all { it.displayName.isNotBlank() && it.decimalPlaces >= 0 })
        assertTrue(options.filter { it.unit == "%" }.all { it.deltaUnit == PercentagePointUnit })
        assertTrue(options.filter { it.unit != "%" }.all { it.deltaUnit == it.unit })
    }

    @Test
    fun `missing preference means first launch weight and fat`() {
        assertNull(AppSettings().selectedChartMetricKeys)
        assertEquals(
            linkedSetOf(MeasurementMetric.WEIGHT_KG, MeasurementMetric.BODY_FAT_PERCENT),
            restoreChartMetricSelection(null),
        )
    }

    @Test
    fun `explicit empty preference remains empty`() {
        val settings = AppSettings(selectedChartMetricKeys = emptySet())

        assertEquals(emptySet<String>(), settings.selectedChartMetricKeys)
        assertTrue(restoreChartMetricSelection(settings.selectedChartMetricKeys).isEmpty())
    }

    @Test
    fun `known selection is restored and unknown keys are discarded`() {
        val restored = restoreChartMetricSelection(
            linkedSetOf(
                MeasurementMetric.MUSCLE_MASS_KG.name,
                "REMOVED_METRIC",
                MeasurementMetric.WATER_PERCENT.name,
            ),
        )

        assertEquals(
            linkedSetOf(MeasurementMetric.WATER_PERCENT, MeasurementMetric.MUSCLE_MASS_KG),
            restored,
        )
    }

    @Test
    fun `fully invalid non-empty preference falls back to weight and fat`() {
        assertEquals(
            linkedSetOf(MeasurementMetric.WEIGHT_KG, MeasurementMetric.BODY_FAT_PERCENT),
            restoreChartMetricSelection(setOf("REMOVED_METRIC", "UNKNOWN_METRIC")),
        )
    }

    @Test
    fun `selection survives persisted key round trip including explicit empty`() {
        val selected = linkedSetOf(
            MeasurementMetric.BMI,
            MeasurementMetric.PROTEIN_PERCENT,
            MeasurementMetric.METABOLIC_AGE,
        )

        val savedKeys = selected.toPersistedChartMetricKeys()

        assertEquals(selected, restoreChartMetricSelection(savedKeys))
        assertTrue(restoreChartMetricSelection(emptySet<MeasurementMetric>().toPersistedChartMetricKeys()).isEmpty())
    }
}
