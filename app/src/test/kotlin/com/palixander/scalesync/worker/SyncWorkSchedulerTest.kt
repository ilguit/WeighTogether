package com.palixander.scalesync.worker

import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncWorkSchedulerTest {
    @Test
    fun initialDelayPreventsExecutionBeforeDeadline() {
        assertEquals(300_000L, initialDelayMillis(400_000L, 100_000L))
    }

    @Test
    fun elapsedOrClearedDeadlineRunsImmediately() {
        assertEquals(0L, initialDelayMillis(100_000L, 400_000L))
        assertEquals(0L, initialDelayMillis(0L, 400_000L))
    }

    @Test
    fun initialEnqueueKeepsExistingWorkAndWaitsSixtySecondsFromScheduling() {
        val workManager = RecordingMeasurementSyncWorkManager()
        val scheduler = SyncWorkScheduler(
            workManager = workManager,
            isPaused = { false },
            nowEpochMillis = { 100_000L },
        )

        scheduler.enqueueInitial("measurement-initial")

        val enqueued = workManager.singleEnqueued()
        assertEquals("sync-kickoff-measurement-initial", enqueued.uniqueWorkName)
        assertEquals(ExistingWorkPolicy.KEEP, enqueued.policy)
        assertEquals(60_000L, enqueued.work.workSpec.initialDelay)
        assertEquals(
            "measurement-initial",
            enqueued.work.workSpec.input.getString("measurement_id"),
        )
    }

    @Test
    fun immediateEnqueueCancelsKickoffAndKeepsActualWork() {
        val workManager = RecordingMeasurementSyncWorkManager()
        val scheduler = SyncWorkScheduler(
            workManager = workManager,
            isPaused = { false },
            nowEpochMillis = { 100_000L },
        )

        scheduler.enqueueImmediately("measurement-retry")

        val enqueued = workManager.singleEnqueued()
        assertEquals("sync-measurement-retry", enqueued.uniqueWorkName)
        assertEquals(ExistingWorkPolicy.KEEP, enqueued.policy)
        assertEquals(0L, enqueued.work.workSpec.initialDelay)
        assertEquals(listOf("sync-kickoff-measurement-retry"), workManager.cancelled)
    }

    @Test
    fun selfDeferAppendsSuccessorWithUniqueNameInputAndEffectiveDelay() {
        val workManager = RecordingMeasurementSyncWorkManager()
        val scheduler = SyncWorkScheduler(
            workManager = workManager,
            isPaused = { false },
            nowEpochMillis = { 100_000L },
        )

        scheduler.deferCurrent("measurement-1", 400_000L)

        val enqueued = workManager.singleEnqueued()
        assertEquals("sync-measurement-1", enqueued.uniqueWorkName)
        assertEquals(ExistingWorkPolicy.APPEND_OR_REPLACE, enqueued.policy)
        assertEquals(
            "measurement-1",
            enqueued.work.workSpec.input.getString("measurement_id"),
        )
        assertEquals(300_000L, enqueued.work.workSpec.initialDelay)
    }

    @Test
    fun externalRescheduleReplacesChainAndHonorsLaterRequestedDeadline() {
        val workManager = RecordingMeasurementSyncWorkManager()
        val scheduler = SyncWorkScheduler(
            workManager = workManager,
            isPaused = { false },
            nowEpochMillis = { 100_000L },
        )

        scheduler.reschedule("measurement-2", 600_000L)

        val enqueued = workManager.singleEnqueued()
        assertEquals("sync-measurement-2", enqueued.uniqueWorkName)
        assertEquals(ExistingWorkPolicy.REPLACE, enqueued.policy)
        assertEquals(
            "measurement-2",
            enqueued.work.workSpec.input.getString("measurement_id"),
        )
        assertEquals(500_000L, enqueued.work.workSpec.initialDelay)
    }

    @Test
    fun cancellationUsesTheSameUniqueWorkName() {
        val workManager = RecordingMeasurementSyncWorkManager()
        val scheduler = SyncWorkScheduler(workManager = workManager)

        scheduler.cancel("measurement-3")

        assertEquals(
            listOf("sync-kickoff-measurement-3", "sync-measurement-3"),
            workManager.cancelled,
        )
    }

    @Test
    fun pausedSchedulerDoesNotEnqueueDeferOrReschedule() {
        val workManager = RecordingMeasurementSyncWorkManager()
        val scheduler = SyncWorkScheduler(workManager, isPaused = { true })

        scheduler.enqueue("one")
        scheduler.enqueueInitial("two")
        scheduler.enqueueImmediately("three")
        scheduler.enqueue("four", 10L)
        scheduler.deferCurrent("five", 10L)
        scheduler.reschedule("six", 10L)

        assertTrue(workManager.enqueued.isEmpty())
        assertTrue(workManager.cancelled.isEmpty())
    }
}

private class RecordingMeasurementSyncWorkManager : MeasurementSyncWorkManager {
    val enqueued = mutableListOf<EnqueuedWork>()
    val cancelled = mutableListOf<String>()

    override fun enqueueUniqueWork(
        uniqueWorkName: String,
        existingWorkPolicy: ExistingWorkPolicy,
        work: OneTimeWorkRequest,
    ) {
        enqueued += EnqueuedWork(uniqueWorkName, existingWorkPolicy, work)
    }

    override fun cancelUniqueWork(uniqueWorkName: String) {
        cancelled += uniqueWorkName
    }

    fun singleEnqueued(): EnqueuedWork = enqueued.single()
}

private data class EnqueuedWork(
    val uniqueWorkName: String,
    val policy: ExistingWorkPolicy,
    val work: OneTimeWorkRequest,
)
