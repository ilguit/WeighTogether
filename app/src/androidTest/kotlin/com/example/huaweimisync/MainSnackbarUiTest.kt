package com.example.huaweimisync

import androidx.activity.ComponentActivity
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.huaweimisync.domain.PendingMeasurementId
import com.example.huaweimisync.ui.theme.HuaweiMiSyncTheme
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class MainSnackbarUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun externalSyncPauseFeedbackIsShownInSnackbar() {
        assertSnackbarMessage(EXTERNAL_SYNC_PAUSED_MESSAGE)
    }

    @Test
    fun externalSyncResumeFeedbackIsShownInSnackbar() {
        assertSnackbarMessage(EXTERNAL_SYNC_RESUMED_MESSAGE)
    }

    @Test
    fun unavailableScaleRefreshFeedbackIsShownInSnackbar() {
        assertSnackbarMessage(SCALE_REFRESH_UNAVAILABLE_MESSAGE)
    }

    @Test
    fun pendingDiscardSnackbarShowsUndoAndReportsItsAddressedAction() {
        val channel = Channel<MainUiEvent>(Channel.UNLIMITED)
        val events = channel.receiveAsFlow()
        val results = mutableListOf<Pair<Long, Boolean>>()
        val event = MainUiEvent.ShowPendingDiscardUndo(
            snackbarId = 42L,
            pendingId = PendingMeasurementId("pending-42"),
        )

        composeRule.setContent {
            val snackbarHostState = remember { SnackbarHostState() }
            HuaweiMiSyncTheme {
                SnackbarHost(snackbarHostState)
                MainUiEventHandler(
                    events = events,
                    snackbarHostState = snackbarHostState,
                    onPendingResolutionCompleted = {},
                    onPendingDiscardSnackbarResult = { snackbarId, undoRequested ->
                        results += snackbarId to undoRequested
                    },
                )
            }
        }

        composeRule.runOnIdle { channel.trySend(event).getOrThrow() }
        composeRule.onNodeWithText(PENDING_DISCARDED_MESSAGE).assertIsDisplayed()
        composeRule.onNodeWithText(PENDING_DISCARD_UNDO_ACTION).assertIsDisplayed().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { results.isNotEmpty() }

        composeRule.runOnIdle {
            assertEquals(listOf(event.snackbarId to true), results)
        }
    }

    private fun assertSnackbarMessage(message: String) {
        val channel = Channel<MainUiEvent>(Channel.UNLIMITED)
        composeRule.setContent {
            val snackbarHostState = remember { SnackbarHostState() }
            HuaweiMiSyncTheme {
                SnackbarHost(snackbarHostState)
                MainUiEventHandler(
                    events = channel.receiveAsFlow(),
                    snackbarHostState = snackbarHostState,
                    onPendingResolutionCompleted = {},
                    onPendingDiscardSnackbarResult = { _, _ -> },
                )
            }
        }

        composeRule.runOnIdle {
            channel.trySend(MainUiEvent.ShowSnackbar(message)).getOrThrow()
        }
        composeRule.onNodeWithText(message).assertIsDisplayed()
    }
}
