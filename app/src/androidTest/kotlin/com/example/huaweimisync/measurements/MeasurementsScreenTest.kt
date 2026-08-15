package com.example.huaweimisync.measurements

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.huaweimisync.ui.theme.HuaweiMiSyncTheme
import java.time.Instant
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
        composeRule.onNodeWithText("Удалить").performClick()
        composeRule.onNodeWithTag("delete-measurement-dialog").assertIsDisplayed()
        composeRule.onNodeWithText("Удалить измерение?").assertIsDisplayed()
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

    private fun callbacks(
        onHistoryRequested: () -> Unit = {},
        onEditRequested: (String, MeasurementEditorOrigin) -> Unit = { _, _ -> },
        onDeleteRequested: (String) -> Unit = {},
        onRetryRequested: (String) -> Unit = {},
    ) = MeasurementsCallbacks.None.copy(
        onHistoryRequested = onHistoryRequested,
        onEditRequested = onEditRequested,
        onDeleteRequested = onDeleteRequested,
        onRetryRequested = onRetryRequested,
    )

    private fun sampleState(
        sync: MeasurementSyncPresentation = syncedSync(),
    ): MeasurementsUiState {
        val latest = sampleItem("latest", "2026-08-15T12:42:00Z", 72.4, sync)
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
    ) = MeasurementUiItem(
        id = id,
        measuredAtEpochMillis = Instant.parse(instant).toEpochMilli(),
        values = MeasurementUiValues(
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
        ),
        sync = sync,
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
}
