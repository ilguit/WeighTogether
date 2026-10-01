package com.palixander.weightogether.worker

import com.palixander.weightogether.data.MeasurementEntity
import com.palixander.weightogether.data.MeasurementType
import com.palixander.weightogether.domain.ExternalSyncPolicy
import com.palixander.weightogether.data.SyncStatus
import com.palixander.weightogether.sync.MeasurementSyncPayload
import com.palixander.weightogether.sync.SyncResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
import org.junit.Assert.fail
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

    @Test
    fun missingMeasurementStopsBeforeEligibilityAndSettings() = runBlocking {
        val fixture = Fixture(null)
        assertEquals(MeasurementSyncOutcome.Complete, fixture.sync())
        assertEquals(listOf("load"), fixture.events)
    }

    @Test
    fun localPoliciesStopBeforeEligibilityAndSettings() = runBlocking {
        for (policy in listOf(ExternalSyncPolicy.ACCOUNT_LOCAL.name, ExternalSyncPolicy.USER_LOCAL.name, "unknown")) {
            val fixture = Fixture(measurement().copy(externalSyncPolicy = policy))
            assertEquals(policy, MeasurementSyncOutcome.Complete, fixture.sync())
            assertEquals(policy, listOf("load"), fixture.events)
        }
    }

    @Test
    fun ineligibleMeasurementStopsBeforeSettingsAndPause() = runBlocking {
        val fixture = Fixture(measurement(), eligible = false)
        assertEquals(MeasurementSyncOutcome.Complete, fixture.sync())
        assertEquals(listOf("load", "eligible"), fixture.events)
    }

    @Test
    fun terminalStatusesCheckEligibilityButStopBeforeSettings() = runBlocking {
        for (status in listOf(SyncStatus.SYNCED, SyncStatus.LOCAL_ONLY)) {
            val fixture = Fixture(measurement().copy(healthConnectStatus = status.name))
            assertEquals(status.name, MeasurementSyncOutcome.Complete, fixture.sync())
            assertEquals(status.name, listOf("load", "eligible"), fixture.events)
        }
    }

    @Test
    fun disabledHealthConnectStopsBeforePauseAndExternalEffects() = runBlocking {
        val fixture = Fixture(measurement(), enabled = false, paused = true)
        assertEquals(MeasurementSyncOutcome.Complete, fixture.sync())
        assertEquals(listOf("load", "eligible", "enabled"), fixture.events)
    }

    @Test
    fun pausePreventsBothWriteAndApplyEvenForEmptyPayload() = runBlocking {
        for (value in listOf(measurement(), emptyMeasurement())) {
            val fixture = Fixture(value, paused = true)
            assertEquals(MeasurementSyncOutcome.Paused, fixture.sync())
            assertEquals(listOf("load", "eligible", "enabled", "paused"), fixture.events)
        }
    }

    @Test
    fun everyWriterResultIsAppliedBeforeReturningAndOnlyRetryableRetries() = runBlocking {
        val cases = listOf(
            SyncResult.Success to MeasurementSyncOutcome.Complete,
            SyncResult.Disabled("disabled") to MeasurementSyncOutcome.Complete,
            SyncResult.Blocked("permission") to MeasurementSyncOutcome.Complete,
            SyncResult.Retryable("temporary") to MeasurementSyncOutcome.Retry,
        )
        for ((result, expected) in cases) {
            val value = measurement()
            val fixture = Fixture(value, result = result)
            assertEquals(expected, fixture.sync())
            fixture.events += "returned"
            assertEquals(listOf("load", "eligible", "enabled", "paused", "write", "apply", "returned"), fixture.events)
            assertEquals(listOf(MeasurementSyncPayload(value, includesWeight = true)), fixture.writes)
            assertEquals(listOf(Triple(value.id, fixture.writes.single(), result)), fixture.applied)
            assertSame(result, fixture.applied.single().third)
        }
    }

    @Test
    fun nonterminalStatusesStillAttemptSynchronization() = runBlocking {
        for (status in listOf(SyncStatus.PENDING, SyncStatus.BLOCKED, SyncStatus.DISABLED, SyncStatus.FAILED)) {
            val fixture = Fixture(measurement().copy(healthConnectStatus = status.name))
            assertEquals(status.name, MeasurementSyncOutcome.Complete, fixture.sync())
            assertEquals(status.name, listOf("load", "eligible", "enabled", "paused", "write", "apply"), fixture.events)
        }
    }

    @Test
    fun emptyPayloadAppliesSuccessWithoutCallingWriter() = runBlocking {
        val value = emptyMeasurement()
        val fixture = Fixture(value, result = SyncResult.Retryable("must not be used"))
        assertEquals(MeasurementSyncOutcome.Complete, fixture.sync())
        assertEquals(listOf("load", "eligible", "enabled", "paused", "apply"), fixture.events)
        assertTrue(fixture.writes.isEmpty())
        assertEquals(listOf(Triple(value.id, MeasurementSyncPayload(value, false), SyncResult.Success)), fixture.applied)
    }

    @Test
    fun syncedWeightStillWritesRemainingComposition() = runBlocking {
        val value = measurement().copy(healthConnectWeightSynced = true)
        val fixture = Fixture(value)
        assertEquals(MeasurementSyncOutcome.Complete, fixture.sync())
        assertEquals(listOf(MeasurementSyncPayload(value, false)), fixture.writes)
        assertEquals(value.fullValues, fixture.writes.single().composition)
        assertFalse(fixture.writes.single().isEmpty)
    }

    @Test
    fun repeatedSyncReloadsUpdatedMeasurementAndHonorsNewPolicy() = runBlocking {
        val original = measurement()
        val fixture = Fixture(original)
        fixture.sync()
        val edited = original.copy(weightKg = 74.0, healthConnectWeightSynced = true)
        fixture.value = edited
        fixture.sync()
        fixture.value = edited.copy(externalSyncPolicy = ExternalSyncPolicy.USER_LOCAL.name)
        fixture.sync()
        assertEquals(listOf(original, edited), fixture.writes.map { it.measurement })
        assertEquals(listOf(true, false), fixture.writes.map { it.includesWeight })
        assertEquals(2, fixture.applied.size)
        assertEquals(
            listOf("load", "eligible", "enabled", "paused", "write", "apply",
                "load", "eligible", "enabled", "paused", "write", "apply", "load"),
            fixture.events,
        )
    }

    @Test
    fun writerExceptionsAndCancellationPropagateWithoutApplying() = runBlocking {
        for (failure in listOf(IllegalStateException("writer failed"), CancellationException("writer cancelled"))) {
            val fixture = Fixture(measurement(), writerFailure = failure)
            assertPropagates(failure) { fixture.sync() }
            assertEquals(listOf("load", "eligible", "enabled", "paused", "write"), fixture.events)
            assertTrue(fixture.applied.isEmpty())
        }
    }

    @Test
    fun applyExceptionsAndCancellationPropagateAfterWrite() = runBlocking {
        for (failure in listOf(IllegalStateException("apply failed"), CancellationException("apply cancelled"))) {
            val fixture = Fixture(measurement(), applyFailure = failure)
            assertPropagates(failure) { fixture.sync() }
            assertEquals(listOf("load", "eligible", "enabled", "paused", "write", "apply"), fixture.events)
            assertEquals(1, fixture.writes.size)
        }
    }

    private suspend fun assertPropagates(expected: Throwable, action: suspend () -> Unit) {
        try {
            action()
            fail("Expected callback failure to propagate")
        } catch (actual: Throwable) {
            assertSame(expected, actual)
        }
    }

    private class Fixture(
        var value: MeasurementEntity?,
        eligible: Boolean = true,
        enabled: Boolean = true,
        paused: Boolean = false,
        result: SyncResult = SyncResult.Success,
        writerFailure: Throwable? = null,
        applyFailure: Throwable? = null,
    ) {
        val events = mutableListOf<String>()
        val writes = mutableListOf<MeasurementSyncPayload>()
        val applied = mutableListOf<Triple<String, MeasurementSyncPayload, SyncResult>>()
        private val processor = MeasurementSyncProcessor(
            loadMeasurement = { id ->
                assertEquals("measurement-1", id)
                events += "load"
                value
            },
            isEligible = { loaded ->
                assertSame(value, loaded)
                events += "eligible"
                eligible
            },
            isHealthConnectEnabled = { events += "enabled"; enabled },
            isPaused = { events += "paused"; paused },
            writeHealthConnect = { payload ->
                events += "write"
                writes += payload
                writerFailure?.let { throw it }
                result
            },
            applyHealthConnectResult = { id, payload, syncResult ->
                events += "apply"
                applied += Triple(id, payload, syncResult)
                applyFailure?.let { throw it }
            },
        )

        suspend fun sync() = processor.sync("measurement-1")
    }

    private fun emptyMeasurement() = measurement().copy(
        measurementType = MeasurementType.WEIGHT_ONLY,
        healthConnectWeightSynced = true,
    )

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
