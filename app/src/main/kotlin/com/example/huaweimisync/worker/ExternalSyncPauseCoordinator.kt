package com.example.huaweimisync.worker

import com.example.huaweimisync.data.ExternalSyncPauseSettingsStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface ExternalSyncPauseTransition {
    data class Paused(val pausedUntilEpochMillis: Long) : ExternalSyncPauseTransition

    data object Resumed : ExternalSyncPauseTransition
}

class ExternalSyncPauseCoordinator(
    private val settings: ExternalSyncPauseSettingsStore,
    private val currentSyncIds: suspend () -> List<String>,
    private val scheduler: MeasurementSyncScheduler,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val operations: ExternalSyncOperationSerializer = ExternalSyncOperationSerializer(),
) {
    private val transitionMutex = Mutex()

    suspend fun toggle(): ExternalSyncPauseTransition = transitionMutex.withLock {
        if (settings.externalSyncPausedUntilEpochMillis > nowEpochMillis()) {
            resumeLocked()
            ExternalSyncPauseTransition.Resumed
        } else {
            ExternalSyncPauseTransition.Paused(pauseForFiveMinutesLocked())
        }
    }

    suspend fun pauseForFiveMinutes(): Long = transitionMutex.withLock {
        pauseForFiveMinutesLocked()
    }

    suspend fun resume() = transitionMutex.withLock {
        resumeLocked()
    }

    private suspend fun pauseForFiveMinutesLocked(): Long {
        val pausedUntil = nowEpochMillis() + PAUSE_DURATION_MILLIS
        settings.setExternalSyncPausedUntilEpochMillis(pausedUntil)
        operations.runExclusive {
            scheduler.rescheduleAll(currentSyncIds(), pausedUntil)
        }
        return pausedUntil
    }

    private suspend fun resumeLocked() {
        settings.setExternalSyncPausedUntilEpochMillis(0L)
        operations.runExclusive {
            scheduler.rescheduleAll(currentSyncIds(), 0L)
        }
    }

    companion object {
        const val PAUSE_DURATION_MILLIS: Long = 5 * 60 * 1_000L
    }
}
