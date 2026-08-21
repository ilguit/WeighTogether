package com.example.huaweimisync.worker

import com.example.huaweimisync.data.ExternalSyncPauseSettingsStore

class ExternalSyncPauseCoordinator(
    private val settings: ExternalSyncPauseSettingsStore,
    private val currentSyncIds: suspend () -> List<String>,
    private val scheduler: MeasurementSyncScheduler,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val operations: ExternalSyncOperationSerializer = ExternalSyncOperationSerializer(),
) {
    suspend fun pauseForFiveMinutes(): Long = operations.runExclusive {
        val pausedUntil = nowEpochMillis() + PAUSE_DURATION_MILLIS
        settings.setExternalSyncPausedUntilEpochMillis(pausedUntil)
        scheduler.rescheduleAll(currentSyncIds(), pausedUntil)
        pausedUntil
    }

    suspend fun resume() = operations.runExclusive {
        settings.setExternalSyncPausedUntilEpochMillis(0L)
        scheduler.rescheduleAll(currentSyncIds(), 0L)
    }

    companion object {
        const val PAUSE_DURATION_MILLIS: Long = 5 * 60 * 1_000L
    }
}
