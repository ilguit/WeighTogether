package com.example.huaweimisync.worker

import com.example.huaweimisync.data.MeasurementEntity
import com.example.huaweimisync.data.SyncStatus
import com.example.huaweimisync.domain.ExternalSyncPolicy
import com.example.huaweimisync.sync.SyncResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MeasurementSyncProcessorTest {
    @Test
    fun localOnlyIsTerminalForTheWholeMeasurement() = runBlocking {
        val store = FakeSyncStore(
            measurement(huaweiStatus = SyncStatus.DISABLED, healthConnectStatus = SyncStatus.LOCAL_ONLY),
        )

        val outcome = store.processor().sync(ID)

        assertEquals(MeasurementSyncOutcome.COMPLETE, outcome)
        assertEquals(2, store.loadCount)
        assertTrue(store.externalWrites.isEmpty())
        assertTrue(store.statusUpdates.isEmpty())
    }

    @Test
    fun reloadsMeasurementBeforeSecondExternalWrite() = runBlocking {
        val store = FakeSyncStore(measurement())
        store.huaweiResult = SyncResult.Success
        store.afterHuaweiWrite = {
            store.value = store.value?.copy(
                huaweiStatus = SyncStatus.LOCAL_ONLY.name,
                healthConnectStatus = SyncStatus.LOCAL_ONLY.name,
            )
        }

        val outcome = store.processor().sync(ID)

        assertEquals(MeasurementSyncOutcome.COMPLETE, outcome)
        assertEquals(2, store.loadCount)
        assertEquals(listOf("huawei"), store.externalWrites)
        assertEquals(SyncStatus.LOCAL_ONLY.name, store.value?.huaweiStatus)
        assertEquals(SyncStatus.LOCAL_ONLY.name, store.value?.healthConnectStatus)
    }

    @Test
    fun reloadsAutoPolicyBeforeSecondExternalWrite() = runBlocking {
        val store = FakeSyncStore(measurement())
        store.afterHuaweiWrite = {
            store.value = store.value?.copy(
                externalSyncPolicy = ExternalSyncPolicy.USER_LOCAL.name,
            )
        }

        val outcome = store.processor().sync(ID)

        assertEquals(MeasurementSyncOutcome.COMPLETE, outcome)
        assertEquals(listOf("huawei"), store.externalWrites)
        assertEquals(2, store.loadCount)
        assertEquals(1, store.eligibilityChecks)
    }

    @Test
    fun secondaryAndUserLocalPoliciesNeverReachAnyGateway() = runBlocking {
        listOf(ExternalSyncPolicy.ACCOUNT_LOCAL, ExternalSyncPolicy.USER_LOCAL).forEach { policy ->
            val store = FakeSyncStore(measurement(externalSyncPolicy = policy))

            assertEquals(MeasurementSyncOutcome.COMPLETE, store.processor().sync(ID))
            assertTrue("Gateway write for $policy", store.externalWrites.isEmpty())
            assertTrue(store.statusUpdates.isEmpty())
        }
    }

    @Test
    fun primaryChangeBetweenDestinationsBlocksTheSecondGateway() = runBlocking {
        val store = FakeSyncStore(measurement())
        store.afterHuaweiWrite = { store.eligible = false }

        val outcome = store.processor().sync(ID)

        assertEquals(MeasurementSyncOutcome.COMPLETE, outcome)
        assertEquals(listOf("huawei"), store.externalWrites)
        assertEquals(2, store.loadCount)
        assertEquals(2, store.eligibilityChecks)
    }

    @Test
    fun terminalDestinationStatusesDoNotCallGatewaysOrWriteStatus() = runBlocking {
        val store = FakeSyncStore(
            measurement(
                huaweiStatus = SyncStatus.DISABLED,
                healthConnectStatus = SyncStatus.SYNCED,
            ),
        )

        val outcome = store.processor().sync(ID)

        assertEquals(MeasurementSyncOutcome.COMPLETE, outcome)
        assertEquals(2, store.loadCount)
        assertTrue(store.externalWrites.isEmpty())
        assertTrue(store.statusUpdates.isEmpty())
    }

    @Test
    fun retryableResultRequestsWorkerRetry() = runBlocking {
        val store = FakeSyncStore(measurement())
        store.huaweiResult = SyncResult.Retryable("temporary Huawei failure")
        store.healthConnectResult = SyncResult.Success

        val outcome = store.processor().sync(ID)

        assertEquals(MeasurementSyncOutcome.RETRY, outcome)
        assertEquals(listOf("huawei", "health-connect"), store.externalWrites)
        assertEquals(2, store.statusUpdates.size)
    }

    @Test
    fun missingMeasurementCompletesWithoutExternalWrites() = runBlocking {
        val store = FakeSyncStore(null)

        val outcome = store.processor().sync(ID)

        assertEquals(MeasurementSyncOutcome.COMPLETE, outcome)
        assertEquals(2, store.loadCount)
        assertTrue(store.externalWrites.isEmpty())
        assertTrue(store.statusUpdates.isEmpty())
    }

    private companion object {
        const val ID = "measurement-1"
    }
}

private class FakeSyncStore(initialValue: MeasurementEntity?) {
    var value: MeasurementEntity? = initialValue
    var loadCount: Int = 0
    var huaweiResult: SyncResult = SyncResult.Success
    var healthConnectResult: SyncResult = SyncResult.Success
    var afterHuaweiWrite: () -> Unit = {}
    var eligible: Boolean = true
    var eligibilityChecks: Int = 0
    val externalWrites = mutableListOf<String>()
    val statusUpdates = mutableListOf<Pair<String, SyncResult>>()

    fun processor() = MeasurementSyncProcessor(
        loadMeasurement = {
            loadCount += 1
            value
        },
        isEligible = {
            eligibilityChecks += 1
            eligible
        },
        writeHuawei = {
            externalWrites += "huawei"
            afterHuaweiWrite()
            huaweiResult
        },
        writeHealthConnect = {
            externalWrites += "health-connect"
            healthConnectResult
        },
        applyHuaweiResult = { _, result ->
            statusUpdates += "huawei" to result
            if (value?.huaweiStatus != SyncStatus.LOCAL_ONLY.name) {
                value = value?.copy(huaweiStatus = result.statusName())
            }
        },
        applyHealthConnectResult = { _, result ->
            statusUpdates += "health-connect" to result
            if (value?.healthConnectStatus != SyncStatus.LOCAL_ONLY.name) {
                value = value?.copy(healthConnectStatus = result.statusName())
            }
        },
    )
}

private fun SyncResult.statusName(): String = when (this) {
    SyncResult.Success -> SyncStatus.SYNCED.name
    is SyncResult.Disabled -> SyncStatus.DISABLED.name
    is SyncResult.Blocked -> SyncStatus.BLOCKED.name
    is SyncResult.Retryable -> SyncStatus.PENDING.name
}

private fun measurement(
    huaweiStatus: SyncStatus = SyncStatus.PENDING,
    healthConnectStatus: SyncStatus = SyncStatus.PENDING,
    externalSyncPolicy: ExternalSyncPolicy = ExternalSyncPolicy.AUTO,
) = MeasurementEntity(
    id = "measurement-1",
    deviceAddress = "AA:BB:CC:DD:EE:FF",
    measuredAtEpochMillis = 1_754_912_096_000,
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
    huaweiStatus = huaweiStatus.name,
    healthConnectStatus = healthConnectStatus.name,
    externalSyncPolicy = externalSyncPolicy.name,
)
