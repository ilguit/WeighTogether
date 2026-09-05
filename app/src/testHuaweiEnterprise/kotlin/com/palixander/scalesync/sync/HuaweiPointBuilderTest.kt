package com.palixander.scalesync.sync

import com.palixander.scalesync.data.MeasurementEntity
import com.palixander.scalesync.data.MeasurementType
import com.huawei.hihealthkit.data.type.HiHealthPointType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HuaweiPointBuilderTest {
    @Test fun manualOriginPreservesExternalIdAndUsesStableMetadata() {
        val row = huaweiWeightOnlyMeasurement()
        val scale = buildHuaweiPointSpecs(MeasurementSyncPayload(row, true)).single()
        val payload = MeasurementSyncPayload(row.copy(origin = com.palixander.scalesync.domain.MeasurementOrigin.MANUAL), true)
        val manual = buildHuaweiPointSpecs(payload).single()
        assertEquals(scale.externalId, manual.externalId)
        assertEquals(scale.externalId + ";origin=MANUAL", manual.metadata)
        assertEquals(manual, buildHuaweiPointSpecs(payload).single())
        assertEquals(scale.externalId, scale.metadata)
    }

    @Test
    fun `weight-only payload creates exactly one Huawei weight point`() {
        val points = buildHuaweiPointSpecs(
            MeasurementSyncPayload(huaweiWeightOnlyMeasurement(), includesWeight = true),
        )
        assertEquals(1, points.size)
        assertEquals(HiHealthPointType.DATA_POINT_WEIGHT, points.single().type)
        assertEquals(70.0, points.single().value, 0.0)
    }

    @Test
    fun `new full payload preserves complete Huawei point set`() {
        val points = buildHuaweiPointSpecs(
            MeasurementSyncPayload(huaweiFullMeasurement(), includesWeight = true),
        )
        assertEquals(14, points.size)
        assertEquals(HiHealthPointType.DATA_POINT_WEIGHT, points.first().type)
        assertTrue(points.any { it.type == HiHealthPointType.DATA_POINT_WEIGHT_IMPEDANCE })
        assertEquals(14, points.map(HuaweiPointSpec::type).distinct().size)
    }

    @Test
    fun `upgraded payload omits confirmed weight and keeps all composition points`() {
        val points = buildHuaweiPointSpecs(
            MeasurementSyncPayload(huaweiFullMeasurement(), includesWeight = false),
        )
        assertEquals(13, points.size)
        assertFalse(points.any { it.type == HiHealthPointType.DATA_POINT_WEIGHT })
        assertTrue(points.any { it.type == HiHealthPointType.DATA_POINT_WEIGHT_BMI })
        assertTrue(points.any { it.type == HiHealthPointType.DATA_POINT_WEIGHT_IMPEDANCE })
    }

    @Test
    fun `Huawei retry produces stable per-point external ids`() {
        val payload = MeasurementSyncPayload(huaweiFullMeasurement(), includesWeight = true)
        val first = buildHuaweiPointSpecs(payload)
        val retried = buildHuaweiPointSpecs(payload)

        assertEquals(first, retried)
        assertEquals(first.size, first.map(HuaweiPointSpec::externalId).distinct().size)
        assertTrue(first.all { it.externalId.startsWith("measurement-1:huawei:") })
    }
}

private fun huaweiWeightOnlyMeasurement() = huaweiFullMeasurement().copy(
    measurementType = MeasurementType.WEIGHT_ONLY,
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

private fun huaweiFullMeasurement() = MeasurementEntity(
    id = "measurement-1",
    deviceAddress = "AA:BB:CC:DD:EE:FF",
    measuredAtEpochSecond = 1_754_912_096,
    rawPayloadHex = "00",
    weightKg = 70.0,
    impedanceOhm = 500,
    bmi = 22.9,
    bodyFatPercent = 18.0,
    bodyFatMassKg = 12.6,
    waterPercent = 55.0,
    waterMassKg = 38.5,
    muscleMassKg = 52.0,
    skeletalMuscleMassKg = 28.0,
    boneMassKg = 3.0,
    proteinPercent = 19.0,
    proteinMassKg = 13.3,
    visceralFatLevel = 8.0,
    basalMetabolicRateKcal = 1_650.0,
    metabolicAge = 35,
    leanBodyMassKg = 57.4,
    algorithmVersion = "test",
)
