package com.example.huaweimisync.worker

import com.example.huaweimisync.data.MeasurementEntity
import com.example.huaweimisync.data.MeasurementType
import com.example.huaweimisync.data.SyncStatus
import com.example.huaweimisync.domain.ExternalSyncPolicy
import com.example.huaweimisync.sync.MeasurementSyncPayload
import com.example.huaweimisync.sync.SyncResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MeasurementSyncProcessorTest {
    @Test
    fun allTerminalDirectionsCompleteWithoutWrites() = runBlocking {
        val store = FakeSyncStore(
            measurement(huaweiStatus = SyncStatus.DISABLED, healthConnectStatus = SyncStatus.LOCAL_ONLY),
        )

        val outcome = store.processor().sync(ID)

        assertEquals(MeasurementSyncOutcome.Complete, outcome)
        assertEquals(2, store.loadCount)
        assertTrue(store.externalWrites.isEmpty())
        assertTrue(store.statusUpdates.isEmpty())
    }

    @Test
    fun localOnlyDirectionDoesNotBlockOtherAvailableDirection() = runBlocking {
        val store = FakeSyncStore(
            measurement(
                huaweiStatus = SyncStatus.LOCAL_ONLY,
                healthConnectStatus = SyncStatus.PENDING,
            ),
        )

        val outcome = store.processor().sync(ID)

        assertEquals(MeasurementSyncOutcome.Complete, outcome)
        assertEquals(listOf("health-connect"), store.externalWrites)
        assertEquals(listOf("health-connect"), store.statusUpdates.map { it.first })
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

        assertEquals(MeasurementSyncOutcome.Complete, outcome)
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

        assertEquals(MeasurementSyncOutcome.Complete, outcome)
        assertEquals(listOf("huawei"), store.externalWrites)
        assertEquals(2, store.loadCount)
        assertEquals(1, store.eligibilityChecks)
    }

    @Test
    fun secondaryAndUserLocalPoliciesNeverReachAnyGateway() = runBlocking {
        listOf(ExternalSyncPolicy.ACCOUNT_LOCAL, ExternalSyncPolicy.USER_LOCAL).forEach { policy ->
            val store = FakeSyncStore(measurement(externalSyncPolicy = policy))

            assertEquals(MeasurementSyncOutcome.Complete, store.processor().sync(ID))
            assertTrue("Gateway write for $policy", store.externalWrites.isEmpty())
            assertTrue(store.statusUpdates.isEmpty())
        }
    }

    @Test
    fun primaryChangeBetweenDestinationsBlocksTheSecondGateway() = runBlocking {
        val store = FakeSyncStore(measurement())
        store.afterHuaweiWrite = { store.eligible = false }

        val outcome = store.processor().sync(ID)

        assertEquals(MeasurementSyncOutcome.Complete, outcome)
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

        assertEquals(MeasurementSyncOutcome.Complete, outcome)
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

        assertEquals(MeasurementSyncOutcome.Retry, outcome)
        assertEquals(listOf("huawei", "health-connect"), store.externalWrites)
        assertEquals(2, store.statusUpdates.size)
    }

    @Test
    fun weightOnlyMeasurementPlansOnlyWeightForBothDestinations() = runBlocking {
        val store = FakeSyncStore(weightOnlyMeasurement())

        val outcome = store.processor().sync(ID)

        assertEquals(MeasurementSyncOutcome.Complete, outcome)
        assertEquals(listOf("huawei", "health-connect"), store.payloads.map { it.first })
        store.payloads.forEach { (_, payload) ->
            assertTrue(payload.includesWeight)
            assertEquals(null, payload.composition)
        }
    }

    @Test
    fun fullMeasurementPlansWeightAndCompositionForBothDestinations() = runBlocking {
        val store = FakeSyncStore(measurement())

        store.processor().sync(ID)

        store.payloads.forEach { (_, payload) ->
            assertTrue(payload.includesWeight)
            assertTrue(payload.composition != null)
        }
    }

    @Test
    fun upgradedPendingDestinationsDoNotPlanAlreadySyncedWeight() = runBlocking {
        val store = FakeSyncStore(
            measurement(
                huaweiWeightSynced = true,
                healthConnectWeightSynced = true,
            ),
        )

        store.processor().sync(ID)

        assertEquals(listOf("huawei", "health-connect"), store.payloads.map { it.first })
        store.payloads.forEach { (_, payload) ->
            assertTrue(!payload.includesWeight)
            assertTrue(payload.composition != null)
        }
    }

    @Test
    fun eachDestinationKeepsIndependentWeightProgressAfterUpgrade() = runBlocking {
        val store = FakeSyncStore(
            measurement(
                huaweiWeightSynced = true,
                healthConnectWeightSynced = false,
            ),
        )

        store.processor().sync(ID)

        assertTrue(!store.payloads.single { it.first == "huawei" }.second.includesWeight)
        assertTrue(store.payloads.single { it.first == "health-connect" }.second.includesWeight)
    }

    @Test
    fun resultFromInFlightPartialWriteDoesNotCompleteUpgradedMeasurement() = runBlocking {
        val store = FakeSyncStore(weightOnlyMeasurement())
        store.afterHuaweiWrite = {
            store.value = measurement(
                huaweiStatus = SyncStatus.PENDING,
                healthConnectStatus = SyncStatus.PENDING,
            )
        }

        store.processor().sync(ID)

        assertEquals(SyncStatus.PENDING.name, store.value?.huaweiStatus)
        assertTrue(store.value?.huaweiWeightSynced == true)
        val healthPayload = store.payloads.single { it.first == "health-connect" }.second
        assertTrue(healthPayload.includesWeight)
        assertTrue(healthPayload.composition != null)
    }

    @Test
    fun missingMeasurementCompletesWithoutExternalWrites() = runBlocking {
        val store = FakeSyncStore(null)

        val outcome = store.processor().sync(ID)

        assertEquals(MeasurementSyncOutcome.Complete, outcome)
        assertEquals(2, store.loadCount)
        assertTrue(store.externalWrites.isEmpty())
        assertTrue(store.statusUpdates.isEmpty())
    }

    @Test
    fun activePauseDefersBeforeFirstGatewayWithoutUpdatingStatus() = runBlocking {
        val store = FakeSyncStore(measurement()).also {
            it.nowEpochMillis = 1_000L
            it.pausedUntilEpochMillis = 301_000L
        }

        val outcome = store.processor().sync(ID)

        assertEquals(MeasurementSyncOutcome.Deferred(301_000L), outcome)
        assertEquals(1, store.loadCount)
        assertTrue(store.externalWrites.isEmpty())
        assertTrue(store.statusUpdates.isEmpty())
    }

    @Test
    fun pauseStartingBetweenGatewaysDefersSecondGateway() = runBlocking {
        val store = FakeSyncStore(measurement()).also { value ->
            value.nowEpochMillis = 1_000L
            value.afterHuaweiWrite = { value.pausedUntilEpochMillis = 301_000L }
        }

        val outcome = store.processor().sync(ID)

        assertEquals(MeasurementSyncOutcome.Deferred(301_000L), outcome)
        assertEquals(listOf("huawei"), store.externalWrites)
        assertEquals(listOf("huawei"), store.statusUpdates.map { it.first })
    }

    @Test
    fun canceledWorkerStartingAfterDeletionCompletesWithoutGateway() = runBlocking {
        val store = FakeSyncStore(null)

        assertEquals(MeasurementSyncOutcome.Complete, store.processor().sync(ID))
        assertTrue(store.externalWrites.isEmpty())
        assertTrue(store.statusUpdates.isEmpty())
    }

    @Test
    fun workerWaitingBehindDeletionReloadsRoomAndNeverCallsGateway() = runBlocking {
        val store = FakeSyncStore(measurement())
        val operations = ExternalSyncOperationSerializer()
        val deletionStarted = CompletableDeferred<Unit>()
        val allowDeletion = CompletableDeferred<Unit>()

        val deletion = async {
            operations.runExclusive {
                deletionStarted.complete(Unit)
                allowDeletion.await()
                store.value = null
            }
        }
        deletionStarted.await()
        val worker = async {
            operations.runExclusive { store.processor().sync(ID) }
        }
        allowDeletion.complete(Unit)

        deletion.await()
        assertEquals(MeasurementSyncOutcome.Complete, worker.await())
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
    var pausedUntilEpochMillis: Long = 0L
    var nowEpochMillis: Long = 0L
    val externalWrites = mutableListOf<String>()
    val payloads = mutableListOf<Pair<String, MeasurementSyncPayload>>()
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
        writeHuawei = { payload ->
            externalWrites += "huawei"
            payloads += "huawei" to payload
            afterHuaweiWrite()
            huaweiResult
        },
        writeHealthConnect = { payload ->
            externalWrites += "health-connect"
            payloads += "health-connect" to payload
            healthConnectResult
        },
        applyHuaweiResult = { _, payload, result ->
            statusUpdates += "huawei" to result
            value?.takeIf { it.huaweiStatus != SyncStatus.LOCAL_ONLY.name }?.let { current ->
                value = current.copy(
                    huaweiStatus = if (current.measurementType == payload.measurement.measurementType) {
                        result.statusName()
                    } else {
                        current.huaweiStatus
                    },
                    huaweiWeightSynced = current.huaweiWeightSynced ||
                        (payload.includesWeight && result is SyncResult.Success),
                )
            }
        },
        applyHealthConnectResult = { _, payload, result ->
            statusUpdates += "health-connect" to result
            value?.takeIf { it.healthConnectStatus != SyncStatus.LOCAL_ONLY.name }?.let { current ->
                value = current.copy(
                    healthConnectStatus = if (
                        current.measurementType == payload.measurement.measurementType
                    ) {
                        result.statusName()
                    } else {
                        current.healthConnectStatus
                    },
                    healthConnectWeightSynced = current.healthConnectWeightSynced ||
                        (payload.includesWeight && result is SyncResult.Success),
                )
            }
        },
        pausedUntilProvider = { pausedUntilEpochMillis },
        nowEpochMillis = { nowEpochMillis },
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
    huaweiWeightSynced: Boolean = false,
    healthConnectWeightSynced: Boolean = false,
) = MeasurementEntity(
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
    huaweiStatus = huaweiStatus.name,
    healthConnectStatus = healthConnectStatus.name,
    externalSyncPolicy = externalSyncPolicy.name,
    huaweiWeightSynced = huaweiWeightSynced,
    healthConnectWeightSynced = healthConnectWeightSynced,
)

private fun weightOnlyMeasurement() = measurement().copy(
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
