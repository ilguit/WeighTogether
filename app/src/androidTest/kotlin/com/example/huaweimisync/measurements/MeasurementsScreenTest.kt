package com.example.huaweimisync.measurements

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.huaweimisync.MeasurementsViewModel
import com.example.huaweimisync.ui.theme.HuaweiMiSyncTheme
import java.time.Instant
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class MeasurementsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun summaryAndHistoryCardsExpand() {
        var state by mutableStateOf(sampleState())
        val callbacks = callbacks(
            onHistoryRequested = {
                state = state.copy(destination = MeasurementsDestination.HISTORY)
            },
        )

        composeRule.setContent {
            HuaweiMiSyncTheme { MeasurementsScreen(state = state, callbacks = callbacks) }
        }

        composeRule.onNodeWithText("Импеданс").assertDoesNotExist()
        composeRule.onNodeWithTag("summary-expand-metrics").performClick()
        composeRule.onNodeWithText("Импеданс").assertIsDisplayed()

        composeRule.onNodeWithTag("measurements-history-cta").performClick()
        composeRule.onNodeWithTag("history-toggle-latest").performClick()
        composeRule.onNodeWithText("Импеданс").assertIsDisplayed()
        composeRule.onNodeWithText("Изменить").assertIsDisplayed()
    }

    @Test
    fun summaryMenuOpensEditAndDeleteActions() {
        var edited: Pair<String, MeasurementEditorOrigin>? = null
        var confirmedId: String? = null
        var state by mutableStateOf(sampleState())
        val callbacks = callbacks(
            onEditRequested = { id, origin ->
                edited = id to origin
                val item = state.measurements.single { it.id == id }
                state = state.copy(
                    destination = MeasurementsDestination.EDITOR,
                    editorOrigin = origin,
                    editor = MeasurementEditorState(
                        measurementId = id,
                        measuredAtEpochMillis = item.measuredAtEpochMillis,
                        draft = MeasurementEditorDraft.from(item.values),
                    ),
                )
            },
            onDeleteRequested = { id ->
                val item = state.measurements.single { it.id == id }
                state = state.copy(
                    deleteConfirmation = MeasurementDeleteConfirmation(
                        measurementId = id,
                        measuredAtEpochMillis = item.measuredAtEpochMillis,
                        weightKg = item.values.weightKg,
                    ),
                )
            },
            onDeleteConfirmed = { confirmedId = it },
        )

        composeRule.setContent {
            HuaweiMiSyncTheme { MeasurementsScreen(state = state, callbacks = callbacks) }
        }

        composeRule.onNodeWithContentDescription("Действия с последним измерением").performClick()
        composeRule.onNodeWithText("Изменить").performClick()
        assertEquals("latest" to MeasurementEditorOrigin.SUMMARY, edited)
        composeRule.onNodeWithTag("measurement-editor").assertIsDisplayed()

        composeRule.runOnIdle { state = sampleState() }

        composeRule.onNodeWithContentDescription("Действия с последним измерением").performClick()
        composeRule.onNodeWithTag("summary-delete-measurement").performClick()
        composeRule.onNodeWithTag("delete-measurement-dialog").assertIsDisplayed()
        composeRule.onNodeWithText("Удалить измерение?").assertIsDisplayed()
        composeRule.onNodeWithTag("delete-measurement-confirm").performClick()
        composeRule.runOnIdle { assertEquals("latest", confirmedId) }
    }

    @Test
    fun protectedSummaryDeleteShowsRequiredSnackbarWithoutDialog() {
        var requestedId: String? = null
        val messages = Channel<String>(Channel.BUFFERED)
        val state = sampleState(isLatestDeleteProtected = true)
        val callbacks = callbacks(
            onDeleteRequested = {
                requestedId = it
                messages.trySend(MeasurementsViewModel.PROTECTED_LATEST_MESSAGE)
            },
        )
        setContentWithSnackbar(state, callbacks, messages)

        composeRule.onNodeWithContentDescription("Действия с последним измерением").performClick()
        composeRule.onNodeWithTag("summary-delete-measurement").performClick()

        composeRule.runOnIdle { assertEquals("latest", requestedId) }
        composeRule.onNodeWithTag("delete-measurement-dialog").assertDoesNotExist()
        composeRule.onNodeWithText(PROTECTED_LATEST_MESSAGE).assertIsDisplayed()
    }

    @Test
    fun protectedHistoryDeleteShowsRequiredSnackbarWithoutDialog() {
        var requestedId: String? = null
        val messages = Channel<String>(Channel.BUFFERED)
        val state = sampleState(isLatestDeleteProtected = true).copy(
            destination = MeasurementsDestination.HISTORY,
        )
        val callbacks = callbacks(
            onDeleteRequested = {
                requestedId = it
                messages.trySend(MeasurementsViewModel.PROTECTED_LATEST_MESSAGE)
            },
        )
        setContentWithSnackbar(state, callbacks, messages)

        composeRule.onNodeWithTag("history-toggle-latest").performClick()
        composeRule.onNodeWithTag("history-delete-latest").performClick()

        composeRule.runOnIdle { assertEquals("latest", requestedId) }
        composeRule.onNodeWithTag("delete-measurement-dialog").assertDoesNotExist()
        composeRule.onNodeWithText(PROTECTED_LATEST_MESSAGE).assertIsDisplayed()
    }

    @Test
    fun syncStatusOpensTypedDirectionsAndRetry() {
        var retriedId: String? = null
        val state = sampleState(sync = retryableSync())
        val callbacks = callbacks(onRetryRequested = { retriedId = it })

        composeRule.setContent {
            HuaweiMiSyncTheme { MeasurementsScreen(state = state, callbacks = callbacks) }
        }

        composeRule.onNodeWithTag("summary-sync-status").performClick()
        composeRule.onNodeWithTag("measurement-sync-sheet").assertIsDisplayed()
        composeRule.onNodeWithText("Health Connect").assertIsDisplayed()
        composeRule.onNodeWithText("Huawei Health").assertDoesNotExist()
        composeRule.onNodeWithTag("sync-retry").performClick()
        assertEquals("latest", retriedId)
    }

    @Test
    fun weightOnlySummaryShowsLabelAndDashesForMissingMetrics() {
        val latest = sampleItem(
            id = "weight-only",
            instant = "2026-08-15T12:42:00Z",
            weight = 72.4,
            sync = syncedSync(),
            type = MeasurementUiType.WEIGHT_ONLY,
            values = weightOnlyValues(72.4),
        )
        val previous = sampleItem("previous", "2026-08-13T11:58:00Z", 72.8, syncedSync())
        val measurements = listOf(latest, previous)
        val state = MeasurementsUiState(
            isLoading = false,
            measurements = measurements,
            summary = buildMeasurementSummary(measurements),
        )

        composeRule.setContent {
            HuaweiMiSyncTheme {
                MeasurementsScreen(state = state, callbacks = MeasurementsCallbacks.None)
            }
        }

        composeRule.onNodeWithTag("summary-weight-only-label").assertIsDisplayed()
        composeRule.onNodeWithText("Только вес").assertIsDisplayed()
        composeRule.onAllNodesWithText("—").assertCountEquals(4)
    }

    @Test
    fun weightOnlyEditorContainsOnlyWeightField() {
        val state = MeasurementsUiState(
            destination = MeasurementsDestination.EDITOR,
            isLoading = false,
            editor = MeasurementEditorState(
                measurementId = "weight-only",
                measuredAtEpochMillis = Instant.parse("2026-08-15T12:42:00Z").toEpochMilli(),
                draft = MeasurementEditorDraft.fromWeight(72.4),
                type = MeasurementUiType.WEIGHT_ONLY,
            ),
        )

        composeRule.setContent {
            HuaweiMiSyncTheme {
                MeasurementsScreen(state = state, callbacks = MeasurementsCallbacks.None)
            }
        }

        composeRule.onNodeWithTag("editor-weight-only-label").assertIsDisplayed()
        composeRule.onNodeWithTag("editor-field-WEIGHT_KG").assertIsDisplayed()
        composeRule.onNodeWithTag("editor-field-IMPEDANCE_OHM").assertDoesNotExist()
        composeRule.onNodeWithText("Состав тела").assertDoesNotExist()
    }

    private fun callbacks(
        onHistoryRequested: () -> Unit = {},
        onEditRequested: (String, MeasurementEditorOrigin) -> Unit = { _, _ -> },
        onDeleteRequested: (String) -> Unit = {},
        onDeleteConfirmed: (String) -> Unit = {},
        onRetryRequested: (String) -> Unit = {},
    ) = MeasurementsCallbacks.None.copy(
        onHistoryRequested = onHistoryRequested,
        onEditRequested = onEditRequested,
        onDeleteRequested = onDeleteRequested,
        onDeleteConfirmed = onDeleteConfirmed,
        onRetryRequested = onRetryRequested,
    )

    private fun setContentWithSnackbar(
        state: MeasurementsUiState,
        callbacks: MeasurementsCallbacks,
        messages: Channel<String>,
    ) {
        composeRule.setContent {
            val snackbarHostState = remember { SnackbarHostState() }
            LaunchedEffect(messages, snackbarHostState) {
                for (message in messages) snackbarHostState.showSnackbar(message)
            }
            HuaweiMiSyncTheme {
                Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
                    MeasurementsScreen(
                        state = state,
                        callbacks = callbacks,
                        modifier = Modifier.padding(padding),
                    )
                }
            }
        }
    }

    private fun sampleState(
        sync: MeasurementSyncPresentation = syncedSync(),
        isLatestDeleteProtected: Boolean = false,
    ): MeasurementsUiState {
        val latest = sampleItem(
            id = "latest",
            instant = "2026-08-15T12:42:00Z",
            weight = 72.4,
            sync = sync,
            isDeleteProtected = isLatestDeleteProtected,
        )
        val previous = sampleItem("previous", "2026-08-13T11:58:00Z", 72.8, syncedSync())
        val measurements = listOf(latest, previous)
        return MeasurementsUiState(
            isLoading = false,
            measurements = measurements,
            summary = buildMeasurementSummary(measurements),
        )
    }

    private fun sampleItem(
        id: String,
        instant: String,
        weight: Double,
        sync: MeasurementSyncPresentation,
        type: MeasurementUiType = MeasurementUiType.FULL,
        values: MeasurementUiValues = sampleValues(weight),
        isDeleteProtected: Boolean = false,
    ) = MeasurementUiItem(
        id = id,
        measuredAtEpochMillis = Instant.parse(instant).toEpochMilli(),
        values = values,
        sync = sync,
        type = type,
        isDeleteProtected = isDeleteProtected,
    )

    private fun sampleValues(weight: Double) = MeasurementUiValues(
        weightKg = weight,
        impedanceOhm = 512,
        bmi = 22.9,
        bodyFatPercent = 18.7,
        bodyFatMassKg = 13.5,
        waterPercent = 57.3,
        waterMassKg = 41.5,
        muscleMassKg = 54.1,
        skeletalMuscleMassKg = 29.8,
        boneMassKg = 3.2,
        proteinPercent = 18.2,
        proteinMassKg = 13.2,
        visceralFatLevel = 7.0,
        basalMetabolicRateKcal = 1_568.0,
        metabolicAge = 31,
        leanBodyMassKg = 58.9,
    )

    private fun weightOnlyValues(weight: Double) = MeasurementUiValues(
        weightKg = weight,
        impedanceOhm = null,
        bmi = null,
        bodyFatPercent = null,
        bodyFatMassKg = null,
        waterPercent = null,
        waterMassKg = null,
        muscleMassKg = null,
        skeletalMuscleMassKg = null,
        boneMassKg = null,
        proteinPercent = null,
        proteinMassKg = null,
        visceralFatLevel = null,
        basalMetabolicRateKcal = null,
        metabolicAge = null,
        leanBodyMassKg = null,
    )

    private fun syncedSync() = MeasurementSyncPresentation(
        state = MeasurementSyncPresentationState.SYNCED,
        directions = listOf(
            MeasurementSyncDirectionPresentation(
                direction = MeasurementSyncDirection.HEALTH_CONNECT,
                state = MeasurementSyncPresentationState.SYNCED,
                message = "Данные отправлены",
                canRetry = false,
            ),
        ),
        canRetry = false,
    )

    private fun retryableSync() = MeasurementSyncPresentation(
        state = MeasurementSyncPresentationState.ERROR,
        directions = listOf(
            MeasurementSyncDirectionPresentation(
                direction = MeasurementSyncDirection.HEALTH_CONNECT,
                state = MeasurementSyncPresentationState.ERROR,
                message = "Health Connect временно недоступен",
                canRetry = true,
            ),
        ),
        canRetry = true,
    )

    private companion object {
        const val PROTECTED_LATEST_MESSAGE =
            "Последнее измерение хранится в памяти весов и будет добавлено снова, поэтому удалить его нельзя"
    }
}
