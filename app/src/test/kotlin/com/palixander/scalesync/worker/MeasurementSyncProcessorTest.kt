package com.palixander.scalesync.worker

import com.palixander.scalesync.data.MeasurementEntity
import com.palixander.scalesync.data.SyncStatus
import com.palixander.scalesync.sync.MeasurementSyncPayload
import com.palixander.scalesync.sync.SyncResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeasurementSyncProcessorTest {
    @Test
    fun syncedHealthConnectStateNeverTriggersExternalWork() = runBlocking {
        val value = measurement().copy(
            healthConnectStatus = SyncStatus.SYNCED.name,
        )
        var wrote = false
        var applied = false
        val outcome = processor(value, { wrote = true; SyncResult.Success }) { _, _, _ ->
            applied = true
        }.sync(value.id)

        assertEquals(MeasurementSyncOutcome.Complete, outcome)
        assertFalse(wrote)
        assertFalse(applied)
    }

    @Test
    fun healthConnectPendingStateStillWritesAndAppliesResult() = runBlocking {
        val value = measurement().copy(
            healthConnectStatus = SyncStatus.PENDING.name,
        )
        var payload: MeasurementSyncPayload? = null
        var applied = false
        val outcome = processor(value, { payload = it; SyncResult.Success }) { _, _, _ ->
            applied = true
        }.sync(value.id)

        assertEquals(MeasurementSyncOutcome.Complete, outcome)
        assertTrue(payload?.includesWeight == true)
        assertTrue(applied)
    }

    private fun processor(
        value: MeasurementEntity,
        write: suspend (MeasurementSyncPayload) -> SyncResult,
        apply: suspend (String, MeasurementSyncPayload, SyncResult) -> Unit,
    ) = MeasurementSyncProcessor(
        loadMeasurement = { value },
        writeHealthConnect = write,
        applyHealthConnectResult = apply,
    )

    private fun measurement() = MeasurementEntity(
        id = "measurement-1",
        deviceAddress = "AA:BB",
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
}
