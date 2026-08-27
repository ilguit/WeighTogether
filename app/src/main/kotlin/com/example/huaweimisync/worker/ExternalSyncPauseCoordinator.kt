package com.example.huaweimisync.worker

import com.example.huaweimisync.data.ExternalSyncPauseSettingsStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface ExternalSyncPauseTransition {
    data object Paused : ExternalSyncPauseTransition

    data object Resumed : ExternalSyncPauseTransition
}

class ExternalSyncPauseCoordinator(
    private val settings: ExternalSyncPauseSettingsStore,
    private val currentSyncIds: suspend () -> List<String>,
    private val scheduler: MeasurementSyncScheduler,
    private val operations: ExternalSyncOperationSerializer = ExternalSyncOperationSerializer(),
) {
    private val transitionMutex = Mutex()

    suspend fun toggle(): ExternalSyncPauseTransition = transitionMutex.withLock {
        if (settings.externalSyncPaused) {
            resumeLocked()
            ExternalSyncPauseTransition.Resumed
        } else {
            pauseLocked()
            ExternalSyncPauseTransition.Paused
        }
    }

    suspend fun pause() = transitionMutex.withLock {
        pauseLocked()
    }

    suspend fun resume() = transitionMutex.withLock {
        resumeLocked()
    }

    private suspend fun pauseLocked() {
        // Persist first so workers already queued for the serializer observe the gate when they run.
        settings.setExternalSyncPaused(true)
        operations.runExclusive {
            scheduler.cancelAll(currentSyncIds())
        }
    }

    private suspend fun resumeLocked() {
        settings.setExternalSyncPaused(false)
        operations.runExclusive {
            scheduler.rescheduleAll(currentSyncIds(), 0L)
        }
    }
}
