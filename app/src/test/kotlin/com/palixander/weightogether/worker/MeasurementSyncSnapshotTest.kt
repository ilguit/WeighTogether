package com.palixander.weightogether.worker

import com.palixander.weightogether.data.CalculatedValuesSnapshot
import com.palixander.weightogether.data.ExternalSyncDestination
import com.palixander.weightogether.data.MeasurementEntity
import com.palixander.weightogether.sync.MeasurementSyncPayload
import com.palixander.weightogether.sync.SyncResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MeasurementSyncSnapshotTest {
    @Test fun successCapturesHealthConnectPayload() {
        val measurement = fullMeasurement()
        val encoded = MeasurementSyncPayload(measurement, includesWeight = false)
            .successfulCalculatedValuesSnapshot(SyncResult.Success, ExternalSyncDestination.HEALTH_CONNECT)
        val snapshot = CalculatedValuesSnapshot.decode(requireNotNull(encoded))
        assertEquals(measurement.bodyFatPercent, snapshot?.bodyFatPercent)
        assertEquals(measurement.leanBodyMassKg, snapshot?.leanBodyMassKg)
        assertNull(snapshot?.bmi)
    }

    @Test fun failedWriteNeverCreatesSnapshot() {
        assertNull(MeasurementSyncPayload(fullMeasurement(), includesWeight = true)
            .successfulCalculatedValuesSnapshot(SyncResult.Retryable("offline"), ExternalSyncDestination.HEALTH_CONNECT))
    }

    private fun fullMeasurement() = MeasurementEntity(
        id = "measurement", deviceAddress = "AA:BB", measuredAtEpochSecond = 123, rawPayloadHex = "00",
        weightKg = 70.0, impedanceOhm = 500, bmi = 22.9, bodyFatPercent = 20.0,
        bodyFatMassKg = 14.0, waterPercent = 55.0, waterMassKg = 38.5, muscleMassKg = 40.0,
        skeletalMuscleMassKg = 20.0, boneMassKg = 3.0, proteinPercent = 18.0,
        proteinMassKg = 12.6, visceralFatLevel = 7.0, basalMetabolicRateKcal = 1_500.0,
        metabolicAge = 35, leanBodyMassKg = 56.0, algorithmVersion = "test",
    )
}
