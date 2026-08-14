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

        assertEquals(entity.weightKg, entity.values.weightKg, 0.0)
        assertEquals(entity.impedanceOhm, entity.values.impedanceOhm)
        assertEquals(entity.bmi, entity.values.bmi, 0.0)
        assertEquals(entity.bodyFatPercent, entity.values.bodyFatPercent, 0.0)
        assertEquals(entity.bodyFatMassKg, entity.values.bodyFatMassKg, 0.0)
        assertEquals(entity.waterPercent, entity.values.waterPercent, 0.0)
        assertEquals(entity.waterMassKg, entity.values.waterMassKg, 0.0)
        assertEquals(entity.muscleMassKg, entity.values.muscleMassKg, 0.0)
        assertEquals(entity.skeletalMuscleMassKg, entity.values.skeletalMuscleMassKg, 0.0)
        assertEquals(entity.boneMassKg, entity.values.boneMassKg, 0.0)
        assertEquals(entity.proteinPercent, entity.values.proteinPercent, 0.0)
        assertEquals(entity.proteinMassKg, entity.values.proteinMassKg, 0.0)
        assertEquals(entity.visceralFatLevel, entity.values.visceralFatLevel, 0.0)
        assertEquals(entity.basalMetabolicRateKcal, entity.values.basalMetabolicRateKcal, 0.0)
        assertEquals(entity.metabolicAge, entity.values.metabolicAge)
        assertEquals(entity.leanBodyMassKg, entity.values.leanBodyMassKg, 0.0)
    }
}

private fun measurementForMetricTest() = MeasurementEntity(
    id = "metric-test",
    deviceAddress = "device",
    measuredAtEpochMillis = 1L,
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
