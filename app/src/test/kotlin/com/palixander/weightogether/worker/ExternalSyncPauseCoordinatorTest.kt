package com.palixander.weightogether.worker

import com.palixander.weightogether.data.ExternalSyncPauseSettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalSyncPauseCoordinatorTest {
    @Test
    fun pausePersistsBooleanAndCancelsCurrentRows() = runBlocking {
        val settings = FakePauseSettings()
        val scheduler = RecordingScheduler()
        val coordinator = ExternalSyncPauseCoordinator(
            settings = settings,
            currentSyncIds = { listOf("first", "second") },
            scheduler = scheduler,
        )

        coordinator.pause()

        assertTrue(settings.externalSyncPaused)
        assertEquals(listOf("first", "second"), scheduler.cancelled)
    }

    @Test
    fun resumeClearsBooleanAndReschedulesCurrentRows() = runBlocking {
        val settings = FakePauseSettings(true)
        val scheduler = RecordingScheduler()
        val coordinator = ExternalSyncPauseCoordinator(
            settings = settings,
            currentSyncIds = { listOf("first", "second") },
            scheduler = scheduler,
        )

        coordinator.resume()

        assertFalse(settings.externalSyncPaused)
        assertEquals(listOf("first" to 0L, "second" to 0L), scheduler.rescheduled)
    }

    @Test
    fun concurrentTogglesAreSerializedWithoutLosingTransitions() = runBlocking {
        val settings = FakePauseSettings()
        val scheduler = RecordingScheduler()
        val coordinator = ExternalSyncPauseCoordinator(
            settings = settings,
            currentSyncIds = { listOf("only") },
            scheduler = scheduler,
        )

        List(20) { async(Dispatchers.Default) { coordinator.toggle() } }.awaitAll()

        assertFalse(settings.externalSyncPaused)
        assertEquals(10, scheduler.cancelled.size)
        assertEquals(10, scheduler.rescheduled.size)
    }
}

private class FakePauseSettings(
    initialPaused: Boolean = false,
) : ExternalSyncPauseSettingsStore {
    private var paused = initialPaused
    override val externalSyncPaused: Boolean
        get() = paused

    override fun setExternalSyncPaused(value: Boolean) {
        paused = value
    }
}

private class RecordingScheduler : MeasurementSyncScheduler {
    val cancelled = mutableListOf<String>()
    val rescheduled = mutableListOf<Pair<String, Long>>()

    override fun enqueue(measurementId: String) = Unit
    override fun deferCurrent(measurementId: String, notBeforeEpochMillis: Long) = Unit
    override fun cancel(measurementId: String) {
        cancelled += measurementId
    }
    override fun reschedule(measurementId: String, notBeforeEpochMillis: Long) {
        rescheduled += measurementId to notBeforeEpochMillis
    }
}
