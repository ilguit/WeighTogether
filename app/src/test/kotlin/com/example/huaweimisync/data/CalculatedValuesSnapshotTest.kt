package com.example.huaweimisync.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalculatedValuesSnapshotTest {
    @Test
    fun encodingRoundTripsEveryCalculatedValueExactly() {
        val snapshot = fullMeasurement().currentCalculatedValuesSnapshot(
            ExternalSyncDestination.HUAWEI,
        )!!

        assertEquals(snapshot, CalculatedValuesSnapshot.decode(snapshot.encode()))
    }

    @Test
    fun mismatchIsIndependentForEachDestination() {
        val original = fullMeasurement()
        val snapshot = original.currentCalculatedValuesSnapshot(
            ExternalSyncDestination.HUAWEI,
        )!!.encode()

        assertFalse(original.copy(huaweiSyncedCalculatedValues = snapshot).hasProfileSyncMismatch)
        assertTrue(
            original.copy(
                huaweiSyncedCalculatedValues = snapshot,
                healthConnectSyncedCalculatedValues = original.copy(bodyFatPercent = 99.0)
                    .currentCalculatedValuesSnapshot(ExternalSyncDestination.HEALTH_CONNECT)!!
                    .encode(),
            ).hasProfileSyncMismatch,
        )
    }

    @Test
    fun missingLegacySnapshotDoesNotClaimMismatchButMalformedSnapshotDoes() {
        assertFalse(fullMeasurement().hasProfileSyncMismatch)
        assertTrue(
            fullMeasurement().copy(huaweiSyncedCalculatedValues = "unsupported").hasProfileSyncMismatch,
        )
    }

    @Test
    fun emptyAndNonFiniteSnapshotsAreRejectedInsteadOfHidingMismatch() {
        val emptySnapshot = "v1|_|_|_|_|_|_|_|_|_|_|_|_|_|_"
        val nonFiniteSnapshot = "v1|NaN|_|_|_|_|_|_|_|_|_|_|_|_|_"

        assertEquals(null, CalculatedValuesSnapshot.decode(emptySnapshot))
        assertEquals(null, CalculatedValuesSnapshot.decode(nonFiniteSnapshot))
        assertTrue(
            fullMeasurement().copy(huaweiSyncedCalculatedValues = emptySnapshot)
                .hasProfileSyncMismatch,
        )
        assertTrue(
            fullMeasurement().copy(huaweiSyncedCalculatedValues = nonFiniteSnapshot)
                .hasProfileSyncMismatch,
        )
    }

    @Test
    fun healthConnectSnapshotIgnoresValuesThatDestinationDoesNotStore() {
        val original = fullMeasurement()
        val snapshot = original.currentCalculatedValuesSnapshot(
            ExternalSyncDestination.HEALTH_CONNECT,
        )!!.encode()

        assertFalse(
            original.copy(
                bmi = 99.0,
                healthConnectSyncedCalculatedValues = snapshot,
            ).hasProfileSyncMismatch,
        )
    }

    @Test
    fun legacyBackfillCapturesEachSyncedDestinationIndependently() {
        val original = fullMeasurement().copy(
            huaweiStatus = SyncStatus.SYNCED.name,
            healthConnectStatus = SyncStatus.FAILED.name,
        )

        val huaweiOnly = original.backfillMissingSyncedCalculatedValues()
        assertEquals(
            original.fullValues?.toCalculatedValuesSnapshot(ExternalSyncDestination.HUAWEI)
                ?.encode(),
            huaweiOnly.huaweiSyncedCalculatedValues,
        )
        assertEquals(null, huaweiOnly.healthConnectSyncedCalculatedValues)

        val healthConnectOnly = original.copy(
            huaweiStatus = SyncStatus.PENDING.name,
            healthConnectStatus = SyncStatus.SYNCED.name,
        ).backfillMissingSyncedCalculatedValues()
        assertEquals(null, healthConnectOnly.huaweiSyncedCalculatedValues)
        assertEquals(
            original.fullValues?.toCalculatedValuesSnapshot(
                ExternalSyncDestination.HEALTH_CONNECT,
            )?.encode(),
            healthConnectOnly.healthConnectSyncedCalculatedValues,
        )
    }

    @Test
    fun legacyBackfillPreservesExistingNonblankSnapshotsIncludingMalformedValues() {
        val original = fullMeasurement().copy(
            huaweiStatus = SyncStatus.SYNCED.name,
            healthConnectStatus = SyncStatus.SYNCED.name,
            huaweiSyncedCalculatedValues = "existing",
            healthConnectSyncedCalculatedValues = "malformed",
        )

        assertEquals(original, original.backfillMissingSyncedCalculatedValues())
    }

    @Test
    fun legacyBackfillReplacesBlankButIgnoresEveryNonSyncedStatus() {
        val expectedHuawei = fullMeasurement().fullValues
            ?.toCalculatedValuesSnapshot(ExternalSyncDestination.HUAWEI)
            ?.encode()
        assertEquals(
            expectedHuawei,
            fullMeasurement().copy(
                huaweiStatus = SyncStatus.SYNCED.name,
                huaweiSyncedCalculatedValues = "  ",
            ).backfillMissingSyncedCalculatedValues().huaweiSyncedCalculatedValues,
        )

        SyncStatus.entries.filterNot { it == SyncStatus.SYNCED }.forEach { status ->
            val original = fullMeasurement().copy(
                huaweiStatus = status.name,
                healthConnectStatus = status.name,
            )
            assertEquals(original, original.backfillMissingSyncedCalculatedValues())
        }
    }

    private fun fullMeasurement() = MeasurementEntity(
        id = "measurement",
        deviceAddress = "AA:BB:CC:DD:EE:FF",
        measuredAtEpochSecond = 123,
        rawPayloadHex = "00",
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
