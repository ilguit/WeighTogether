package com.palixander.weightogether

import com.palixander.weightogether.worker.ExternalSyncPauseTransition
import com.palixander.weightogether.ui.text.UiText
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
