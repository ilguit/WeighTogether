package com.example.huaweimisync.charts

import com.example.huaweimisync.data.AppSettings
import com.example.huaweimisync.data.MeasurementMetric
import com.example.huaweimisync.data.MeasurementEntity
import com.example.huaweimisync.data.MeasurementType
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

    @Test
    fun `weight only contributes to weight series but not composition series`() {
        val full = chartMeasurement(id = "full", measuredAt = 100L).copy(
            measuredAtEpochSecond = 0L,
            measuredAtNano = 100_123_456,
        )
        val weightOnly = chartMeasurement(id = "weight", measuredAt = 200L).copy(
            measurementType = MeasurementType.WEIGHT_ONLY,
            weightKg = 71.5,
            impedanceOhm = null,
            bmi = null,
            bodyFatPercent = null,
            bodyFatMassKg = null,
            waterPercent = null,
            waterMassKg = null,
            muscleMassKg = null,
            skeletalMuscleMassKg = null,
            boneMassKg = null,
            proteinPercent = null,
            proteinMassKg = null,
            visceralFatLevel = null,
            basalMetabolicRateKcal = null,
            metabolicAge = null,
            leanBodyMassKg = null,
            algorithmVersion = null,
        )

        val weightPoints = chartPointsForMetric(
            listOf(full, weightOnly),
            MeasurementMetric.WEIGHT_KG,
        )
        val fatPoints = chartPointsForMetric(
            listOf(full, weightOnly),
            MeasurementMetric.BODY_FAT_PERCENT,
        )

        assertEquals(listOf(70.0, 71.5), weightPoints.map(ChartPoint::value))
        assertEquals(listOf(100L, 200L), weightPoints.map(ChartPoint::measuredAtEpochMillis))
        assertEquals(100_123_456, weightPoints.first().measuredAtNano)
        assertEquals(listOf(20.0), fatPoints.map(ChartPoint::value))
    }
}

private fun chartMeasurement(id: String, measuredAt: Long) = MeasurementEntity(
    id = id,
    fingerprint = "fingerprint-$id",
    deviceAddress = "AA:BB:CC:DD:EE:FF",
    measuredAtEpochMillis = measuredAt,
    rawPayloadHex = "010203",
    weightKg = 70.0,
    impedanceOhm = 500,
    bmi = 22.9,
    bodyFatPercent = 20.0,
    bodyFatMassKg = 14.0,
    waterPercent = 55.0,
    waterMassKg = 38.5,
    muscleMassKg = 40.0,
    skeletalMuscleMassKg = 20.0,
    boneMassKg = 3.0,
    proteinPercent = 18.0,
    proteinMassKg = 12.6,
    visceralFatLevel = 7.0,
    basalMetabolicRateKcal = 1_500.0,
    metabolicAge = 35,
    leanBodyMassKg = 56.0,
    algorithmVersion = "test-v1",
)
