package com.example.huaweimisync

import com.example.huaweimisync.worker.ExternalSyncPauseTransition
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
}
