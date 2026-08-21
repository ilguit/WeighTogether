package com.example.huaweimisync.worker

import com.example.huaweimisync.data.ExternalSyncPauseSettingsStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
}

private class FakePauseSettings(
    initialPausedUntilEpochMillis: Long = 0L,
) : ExternalSyncPauseSettingsStore {
    private var pausedUntilEpochMillis = initialPausedUntilEpochMillis
    override val externalSyncPausedUntilEpochMillis: Long
        get() = pausedUntilEpochMillis

    override fun setExternalSyncPausedUntilEpochMillis(value: Long) {
        pausedUntilEpochMillis = value
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
