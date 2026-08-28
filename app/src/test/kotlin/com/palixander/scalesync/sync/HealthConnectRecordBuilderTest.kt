package com.palixander.scalesync.sync

import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyWaterMassRecord
import androidx.health.connect.client.records.BoneMassRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.WeightRecord
import com.palixander.scalesync.data.MeasurementEntity
import com.palixander.scalesync.data.MeasurementType
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthConnectRecordBuilderTest {
    @Test
    fun `weight-only payload creates one weight record and needs one permission`() {
        val payload = MeasurementSyncPayload(healthWeightOnlyMeasurement(), includesWeight = true)
        val records = buildHealthConnectRecords(payload, UTC)

        assertEquals(listOf(WeightRecord::class), records.map { it::class })
        assertEquals(1, requiredHealthConnectPermissions(payload).size)
        assertEquals("measurement-1:weight", records.single().metadata.clientRecordId)
    }

    @Test
    fun `new full payload preserves complete Health Connect record set`() {
        val payload = MeasurementSyncPayload(healthFullMeasurement(), includesWeight = true)
        val records = buildHealthConnectRecords(payload, UTC)

        assertEquals(
            listOf(
                WeightRecord::class,
                BodyFatRecord::class,
                BodyWaterMassRecord::class,
                BoneMassRecord::class,
                LeanBodyMassRecord::class,
                BasalMetabolicRateRecord::class,
            ),
            records.map { it::class },
        )
        assertEquals(6, requiredHealthConnectPermissions(payload).size)
    }

    @Test
    fun `upgraded payload sends composition without already synced weight`() {
        val payload = MeasurementSyncPayload(healthFullMeasurement(), includesWeight = false)
        val records = buildHealthConnectRecords(payload, UTC)

        assertEquals(5, records.size)
        assertFalse(records.any { it is WeightRecord })
        assertTrue(records.any { it is BodyFatRecord })
        val weightPermission = requiredHealthConnectPermissions(
            MeasurementSyncPayload(healthWeightOnlyMeasurement(), includesWeight = true),
        ).single()
        assertFalse(requiredHealthConnectPermissions(payload).contains(weightPermission))
    }

    @Test
    fun `retry builds the same client ids and versions`() {
        val payload = MeasurementSyncPayload(healthFullMeasurement(), includesWeight = true)
        val first = buildHealthConnectRecords(payload, UTC).map {
            it.metadata.clientRecordId to it.metadata.clientRecordVersion
        }
        val retried = buildHealthConnectRecords(payload, UTC).map {
            it.metadata.clientRecordId to it.metadata.clientRecordVersion
        }

        assertEquals(first, retried)
        assertEquals(6, first.map { it.first }.distinct().size)
        assertTrue(first.all { (_, version) -> version == 0L })
    }

    private companion object {
        val UTC: ZoneId = ZoneId.of("UTC")
    }
}

private fun healthWeightOnlyMeasurement() = healthFullMeasurement().copy(
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

private fun healthFullMeasurement() = MeasurementEntity(
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
