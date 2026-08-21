package com.example.huaweimisync.worker

import com.example.huaweimisync.data.ExternalSyncPauseSettingsStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalSyncPauseCoordinatorTest {
    @Test
    fun pausePersistsFiveMinuteDeadlineAndReschedulesCurrentRoomRows() = runBlocking {
        val settings = FakePauseSettings()
        val scheduler = RecordingScheduler()
        val coordinator = ExternalSyncPauseCoordinator(
            settings = settings,
            currentSyncIds = { listOf("first", "second") },
            scheduler = scheduler,
            nowEpochMillis = { 1_000L },
        )

        val pausedUntil = coordinator.pauseForFiveMinutes()

        assertEquals(301_000L, pausedUntil)
        assertEquals(pausedUntil, settings.externalSyncPausedUntilEpochMillis)
        assertEquals(
            listOf("first" to pausedUntil, "second" to pausedUntil),
            scheduler.rescheduled,
        )
    }

    @Test
    fun resumeClearsDeadlineAndReadsFreshRoomRows() = runBlocking {
        val settings = FakePauseSettings(301_000L)
        val scheduler = RecordingScheduler()
        var roomIds = listOf("deleted", "kept")
        val coordinator = ExternalSyncPauseCoordinator(
            settings = settings,
            currentSyncIds = { roomIds },
            scheduler = scheduler,
        )
        roomIds = listOf("kept", "created-during-pause")

        coordinator.resume()

        assertEquals(0L, settings.externalSyncPausedUntilEpochMillis)
        assertEquals(
            listOf("kept" to 0L, "created-during-pause" to 0L),
            scheduler.rescheduled,
        )
    }

    @Test
    fun pausePersistsBeforeWaitingForWorkerAndSerializesFollowingResume() = runBlocking {
        val pausePersisted = CompletableDeferred<Unit>()
        val settings = FakePauseSettings { value ->
            if (value > 0L) pausePersisted.complete(Unit)
        }
        val scheduler = RecordingScheduler()
        val operations = ExternalSyncOperationSerializer()
        val releaseWorker = CompletableDeferred<Unit>()
        val worker = async(start = CoroutineStart.UNDISPATCHED) {
            operations.runExclusive { releaseWorker.await() }
        }
        val coordinator = ExternalSyncPauseCoordinator(
            settings = settings,
            currentSyncIds = { listOf("measurement") },
            scheduler = scheduler,
            nowEpochMillis = { 1_000L },
            operations = operations,
        )

        val pause = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.pauseForFiveMinutes()
        }
        pausePersisted.await()

        assertEquals(301_000L, settings.externalSyncPausedUntilEpochMillis)
        assertFalse(pause.isCompleted)
        assertTrue(scheduler.rescheduled.isEmpty())

        val resume = async(start = CoroutineStart.UNDISPATCHED) { coordinator.resume() }
        assertEquals(301_000L, settings.externalSyncPausedUntilEpochMillis)
        assertFalse(resume.isCompleted)

        releaseWorker.complete(Unit)
        worker.await()
        assertEquals(301_000L, pause.await())
        resume.await()

        assertEquals(0L, settings.externalSyncPausedUntilEpochMillis)
        assertEquals(
            listOf("measurement" to 301_000L, "measurement" to 0L),
            scheduler.rescheduled,
        )
    }
}

private class FakePauseSettings(
    initialPausedUntilEpochMillis: Long = 0L,
    private val onSet: (Long) -> Unit = {},
) : ExternalSyncPauseSettingsStore {
    private var pausedUntilEpochMillis = initialPausedUntilEpochMillis
    override val externalSyncPausedUntilEpochMillis: Long
        get() = pausedUntilEpochMillis

    override fun setExternalSyncPausedUntilEpochMillis(value: Long) {
        pausedUntilEpochMillis = value
        onSet(value)
    }
}

private class RecordingScheduler : MeasurementSyncScheduler {
    val rescheduled = mutableListOf<Pair<String, Long>>()

    override fun enqueue(measurementId: String) = Unit

    override fun cancel(measurementId: String) = Unit

    override fun reschedule(measurementId: String, notBeforeEpochMillis: Long) {
        rescheduled += measurementId to notBeforeEpochMillis
    }
}
