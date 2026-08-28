package com.palixander.scalesync.worker

import com.palixander.scalesync.data.CalculatedValuesSnapshot
import com.palixander.scalesync.data.ExternalSyncDestination
import com.palixander.scalesync.data.MeasurementEntity
import com.palixander.scalesync.data.MeasurementType
import com.palixander.scalesync.sync.MeasurementSyncPayload
import com.palixander.scalesync.sync.SyncResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MeasurementSyncSnapshotTest {
    @Test
    fun successCapturesThePayloadActuallySentToEachDestination() {
        val measurement = fullMeasurement()
        val payload = MeasurementSyncPayload(measurement, includesWeight = false)

        val huawei = CalculatedValuesSnapshot.decode(
            requireNotNull(
                payload.successfulCalculatedValuesSnapshot(
                    SyncResult.Success,
                    ExternalSyncDestination.HUAWEI,
                ),
            ),
        )
        val healthConnect = CalculatedValuesSnapshot.decode(
            requireNotNull(
                payload.successfulCalculatedValuesSnapshot(
                    SyncResult.Success,
                    ExternalSyncDestination.HEALTH_CONNECT,
                ),
            ),
        )

        assertEquals(measurement.bmi, huawei?.bmi)
        assertEquals(measurement.waterPercent, huawei?.waterPercent)
        assertNull(huawei?.leanBodyMassKg)
        assertEquals(measurement.bodyFatPercent, healthConnect?.bodyFatPercent)
        assertEquals(measurement.leanBodyMassKg, healthConnect?.leanBodyMassKg)
        assertNull(healthConnect?.bmi)
    }

    @Test
    fun failedAndWeightOnlyWritesNeverCreateCalculatedSnapshots() {
        val fullPayload = MeasurementSyncPayload(fullMeasurement(), includesWeight = true)
        val weightOnlyPayload = MeasurementSyncPayload(
            fullMeasurement().copy(
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
            ),
            includesWeight = true,
        )

        assertNull(
            fullPayload.successfulCalculatedValuesSnapshot(
                SyncResult.Retryable("offline"),
                ExternalSyncDestination.HUAWEI,
            ),
        )
        assertNull(
            fullPayload.successfulCalculatedValuesSnapshot(
                SyncResult.Blocked("permission"),
                ExternalSyncDestination.HEALTH_CONNECT,
            ),
        )
        assertNull(
            weightOnlyPayload.successfulCalculatedValuesSnapshot(
                SyncResult.Success,
                ExternalSyncDestination.HUAWEI,
            ),
        )
    }

    @Test
    fun recalculatedPayloadProducesNewSnapshotWithoutMutatingOldDestinationSnapshot() {
        val oldMeasurement = fullMeasurement()
        val oldHuaweiSnapshot = MeasurementSyncPayload(oldMeasurement, includesWeight = true)
            .successfulCalculatedValuesSnapshot(SyncResult.Success, ExternalSyncDestination.HUAWEI)
        val recalculated = oldMeasurement.copy(
            bodyFatPercent = 24.0,
            waterMassKg = 36.5,
            leanBodyMassKg = 53.2,
            huaweiSyncedCalculatedValues = oldHuaweiSnapshot,
        )
        val newHealthSnapshot = MeasurementSyncPayload(recalculated, includesWeight = false)
            .successfulCalculatedValuesSnapshot(
                SyncResult.Success,
                ExternalSyncDestination.HEALTH_CONNECT,
            )
        val persisted = recalculated.copy(healthConnectSyncedCalculatedValues = newHealthSnapshot)

        assertEquals(oldHuaweiSnapshot, persisted.huaweiSyncedCalculatedValues)
        assertTrue(persisted.hasProfileSyncMismatch)
        assertEquals(
            recalculated.currentCalculatedValuesSnapshot(ExternalSyncDestination.HEALTH_CONNECT),
            CalculatedValuesSnapshot.decode(requireNotNull(newHealthSnapshot)),
        )
    }

    private fun fullMeasurement() = MeasurementEntity(
        id = "measurement",
        deviceAddress = "AA:BB:CC:DD:EE:FF",
        measuredAtEpochSecond = 1_786_451_696L,
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
        algorithmVersion = "test",
    )
}
