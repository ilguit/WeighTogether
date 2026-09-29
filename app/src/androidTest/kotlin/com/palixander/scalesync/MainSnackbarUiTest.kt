package com.palixander.scalesync

import androidx.activity.ComponentActivity
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.palixander.scalesync.domain.PendingMeasurementId
import com.palixander.scalesync.ui.theme.ScaleSyncTheme
import com.palixander.scalesync.ui.text.UiText
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
        assertSnackbarMessage(composeRule.activity.getString(R.string.message_external_sync_paused))
    }

    @Test
    fun externalSyncResumeFeedbackIsShownInSnackbar() {
        assertSnackbarMessage(composeRule.activity.getString(R.string.message_external_sync_resumed))
    }

    @Test
    fun unavailableScaleRefreshFeedbackIsShownInSnackbar() {
        assertSnackbarMessage(composeRule.activity.getString(R.string.error_scale_unavailable))
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
            ScaleSyncTheme {
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
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.message_pending_discarded)).assertIsDisplayed()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.action_undo)).assertIsDisplayed().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { results.isNotEmpty() }

        composeRule.runOnIdle {
            assertEquals(listOf(event.snackbarId to true), results)
        }
    }

    private fun assertSnackbarMessage(message: String) {
        val channel = Channel<MainUiEvent>(Channel.UNLIMITED)
        composeRule.setContent {
            val snackbarHostState = remember { SnackbarHostState() }
            ScaleSyncTheme {
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
            channel.trySend(MainUiEvent.ShowSnackbar(UiText.Raw(message))).getOrThrow()
        }
        composeRule.onNodeWithText(message).assertIsDisplayed()
    }
}
