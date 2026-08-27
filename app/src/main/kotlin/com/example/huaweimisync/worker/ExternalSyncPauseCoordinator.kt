package com.example.huaweimisync.worker

import com.example.huaweimisync.data.ExternalSyncPauseSettingsStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface ExternalSyncPauseTransition {
    data class Paused(
        @Deprecated("Persistent pause no longer has a deadline")
        val pausedUntilEpochMillis: Long = Long.MAX_VALUE,
    ) : ExternalSyncPauseTransition

    data object Resumed : ExternalSyncPauseTransition
}

class ExternalSyncPauseCoordinator(
    private val settings: ExternalSyncPauseSettingsStore,
    private val currentSyncIds: suspend () -> List<String>,
    private val scheduler: MeasurementSyncScheduler,
    @Suppress("UNUSED_PARAMETER")
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val operations: ExternalSyncOperationSerializer = ExternalSyncOperationSerializer(),
) {
    private val transitionMutex = Mutex()

    suspend fun toggle(): ExternalSyncPauseTransition = transitionMutex.withLock {
        if (settings.externalSyncPaused) {
            resumeLocked()
            ExternalSyncPauseTransition.Resumed
        } else {
            pauseLocked()
            ExternalSyncPauseTransition.Paused()
        }
    }

    suspend fun pause() = transitionMutex.withLock {
        pauseLocked()
    }

    @Deprecated("Use pause")
    suspend fun pauseForFiveMinutes(): Long {
        pause()
        return Long.MAX_VALUE
    }

    suspend fun resume() = transitionMutex.withLock {
        resumeLocked()
    }

    private suspend fun pauseLocked() {
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
