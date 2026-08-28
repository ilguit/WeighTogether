package com.palixander.scalesync

import com.palixander.scalesync.worker.ExternalSyncPauseTransition
import org.junit.Assert.assertEquals
import org.junit.Test

class ExternalSyncPauseUiContractTest {
    @Test
    fun pauseToggleSnackbarUsesCompletedTransitionResult() {
        assertEquals(
            EXTERNAL_SYNC_PAUSED_MESSAGE,
            ExternalSyncPauseTransition.Paused.snackbarMessage(),
        )
        assertEquals(
            EXTERNAL_SYNC_RESUMED_MESSAGE,
            ExternalSyncPauseTransition.Resumed.snackbarMessage(),
        )
    }

    @Test
    fun pauseMessagesDescribePersistentStateWithoutLegacyDuration() {
        val messages = listOf(
            EXTERNAL_SYNC_PAUSED_MESSAGE,
            EXTERNAL_SYNC_RESUMED_MESSAGE,
        )

        messages.forEach { message ->
            assertEquals(false, message.contains("5 минут", ignoreCase = true))
        }
    }
}
