package com.palixander.weightogether.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalculatedValuesSnapshotTest {
    @Test fun encodingRoundTripsHealthConnectValues() {
        val snapshot = fullMeasurement().currentCalculatedValuesSnapshot(ExternalSyncDestination.HEALTH_CONNECT)!!
        assertEquals(snapshot, CalculatedValuesSnapshot.decode(snapshot.encode()))
    }

    @Test fun mismatchUsesHealthConnectSnapshot() {
        val original = fullMeasurement()
        val snapshot = original.currentCalculatedValuesSnapshot(ExternalSyncDestination.HEALTH_CONNECT)!!.encode()
        assertFalse(original.copy(healthConnectSyncedCalculatedValues = snapshot).hasProfileSyncMismatch)
        assertTrue(original.copy(healthConnectSyncedCalculatedValues = "unsupported").hasProfileSyncMismatch)
    }

    @Test fun syncedHealthConnectBackfillCapturesSnapshot() {
        val original = fullMeasurement().copy(healthConnectStatus = SyncStatus.SYNCED.name)
        assertEquals(
            original.fullValues?.toCalculatedValuesSnapshot(ExternalSyncDestination.HEALTH_CONNECT)?.encode(),
            original.backfillMissingSyncedCalculatedValues().healthConnectSyncedCalculatedValues,
        )
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
