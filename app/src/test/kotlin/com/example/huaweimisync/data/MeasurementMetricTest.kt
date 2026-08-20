package com.example.huaweimisync.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MeasurementMetricTest {
    @Test
    fun fixedMetricOrderExtractsAllSixteenValues() {
        val values = MeasurementValues(
            weightKg = 1.0,
            impedanceOhm = 2,
            bmi = 3.0,
            bodyFatPercent = 4.0,
            bodyFatMassKg = 5.0,
            waterPercent = 6.0,
            waterMassKg = 7.0,
            muscleMassKg = 8.0,
            skeletalMuscleMassKg = 9.0,
            boneMassKg = 10.0,
            proteinPercent = 11.0,
            proteinMassKg = 12.0,
            visceralFatLevel = 13.0,
            basalMetabolicRateKcal = 14.0,
            metabolicAge = 15,
            leanBodyMassKg = 16.0,
        )

        assertEquals((1..16).map(Int::toDouble), MeasurementMetric.entries.map { it.valueOf(values) })
        assertEquals(16, MeasurementMetric.entries.size)
        assertTrue(MeasurementMetric.entries.all { it.displayName.isNotBlank() })
        assertTrue(MeasurementMetric.entries.all { it.unit.isNotBlank() })
        assertTrue(MeasurementMetric.entries.all { it.decimalPlaces >= 0 })
    }

    @Test
    fun entityValuesRoundTripsAllEditableFields() {
        val entity = measurementForMetricTest()

        assertEquals(entity.fullValues, entity.values)
    }
}

private fun measurementForMetricTest() = MeasurementEntity(
    id = "metric-test",
    deviceAddress = "device",
    measuredAtEpochSecond = 0L,
    rawPayloadHex = "payload",
    weightKg = 1.0,
    impedanceOhm = 2,
    bmi = 3.0,
    bodyFatPercent = 4.0,
    bodyFatMassKg = 5.0,
    waterPercent = 6.0,
    waterMassKg = 7.0,
    muscleMassKg = 8.0,
    skeletalMuscleMassKg = 9.0,
    boneMassKg = 10.0,
    proteinPercent = 11.0,
    proteinMassKg = 12.0,
    visceralFatLevel = 13.0,
    basalMetabolicRateKcal = 14.0,
    metabolicAge = 15,
    leanBodyMassKg = 16.0,
    algorithmVersion = "test",
)
