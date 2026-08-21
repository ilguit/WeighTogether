package com.example.huaweimisync

import com.example.huaweimisync.data.AppSettings
import com.example.huaweimisync.worker.ExternalSyncPauseTransition
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalSyncPauseUiContractTest {
    @Test
    fun pauseIsActiveOnlyWhilePersistedDeadlineIsInFuture() {
        assertTrue(isExternalSyncPaused(pausedUntilEpochMillis = 10_001L, nowEpochMillis = 10_000L))
        assertFalse(isExternalSyncPaused(pausedUntilEpochMillis = 10_000L, nowEpochMillis = 10_000L))
        assertFalse(isExternalSyncPaused(pausedUntilEpochMillis = 0L, nowEpochMillis = 10_000L))
    }

    @Test
    fun pauseStateAutomaticallyChangesToResumedAtDeadline() = runBlocking {
        var clockReads = 0
        val deadline = 200L

        val states = flowOf(AppSettings(externalSyncPausedUntilEpochMillis = deadline))
            .externalSyncPausedState(
                nowEpochMillis = {
                    if (clockReads++ == 0) 100L else deadline
                },
            )
            .take(2)
            .toList()

        assertEquals(listOf(true, false), states)
    }

    @Test
    fun pauseToggleSnackbarUsesCompletedTransitionResult() {
        assertEquals(
            EXTERNAL_SYNC_PAUSED_MESSAGE,
            ExternalSyncPauseTransition.Paused(301_000L).snackbarMessage(),
        )
        assertEquals(
            EXTERNAL_SYNC_RESUMED_MESSAGE,
            ExternalSyncPauseTransition.Resumed.snackbarMessage(),
        )
    }
}
