package com.example.huaweimisync.worker

import com.example.huaweimisync.data.ExternalSyncPauseSettingsStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ExternalSyncPauseCoordinator(
    private val settings: ExternalSyncPauseSettingsStore,
    private val currentSyncIds: suspend () -> List<String>,
    private val scheduler: MeasurementSyncScheduler,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val operations: ExternalSyncOperationSerializer = ExternalSyncOperationSerializer(),
) {
    private val transitionMutex = Mutex()

    suspend fun pauseForFiveMinutes(): Long = transitionMutex.withLock {
        val pausedUntil = nowEpochMillis() + PAUSE_DURATION_MILLIS
        settings.setExternalSyncPausedUntilEpochMillis(pausedUntil)
        operations.runExclusive {
            scheduler.rescheduleAll(currentSyncIds(), pausedUntil)
        }
        pausedUntil
    }

    suspend fun resume() = transitionMutex.withLock {
        settings.setExternalSyncPausedUntilEpochMillis(0L)
        operations.runExclusive {
            scheduler.rescheduleAll(currentSyncIds(), 0L)
        }
    }

    companion object {
        const val PAUSE_DURATION_MILLIS: Long = 5 * 60 * 1_000L
    }
}
