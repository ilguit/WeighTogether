package com.palixander.scalesync

import com.palixander.scalesync.worker.ExternalSyncPauseTransition
import com.palixander.scalesync.ui.text.UiText
import org.junit.Assert.assertEquals
import org.junit.Test

class ExternalSyncPauseUiContractTest {
    @Test
    fun pauseToggleSnackbarUsesCompletedTransitionResult() {
        assertEquals(
            UiText.Resource(R.string.message_external_sync_paused),
            ExternalSyncPauseTransition.Paused.snackbarMessage(),
        )
        assertEquals(
            UiText.Resource(R.string.message_external_sync_resumed),
            ExternalSyncPauseTransition.Resumed.snackbarMessage(),
        )
    }

    @Test
    fun pauseMessagesDescribePersistentStateWithoutLegacyDuration() {
        assertEquals(UiText.Resource(R.string.message_external_sync_paused), ExternalSyncPauseTransition.Paused.snackbarMessage())
        assertEquals(UiText.Resource(R.string.message_external_sync_resumed), ExternalSyncPauseTransition.Resumed.snackbarMessage())
    }
}
