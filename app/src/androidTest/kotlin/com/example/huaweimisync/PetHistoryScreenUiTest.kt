package com.example.huaweimisync

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.huaweimisync.charts.ChartRangePreset
import com.example.huaweimisync.charts.ChartSeries
import com.example.huaweimisync.charts.ChartPoint
import com.example.huaweimisync.charts.MetricChartTestTags
import com.example.huaweimisync.domain.Pet
import com.example.huaweimisync.domain.PetId
import com.example.huaweimisync.ui.profiles.PetHistoryCallbacks
import com.example.huaweimisync.ui.profiles.PetHistoryContent
import com.example.huaweimisync.ui.profiles.PetHistoryDeleteConfirmation
import com.example.huaweimisync.ui.profiles.PetHistoryMeasurementUi
import com.example.huaweimisync.ui.profiles.PetHistoryUiState
import com.example.huaweimisync.ui.profiles.PetProfileScreen
import com.example.huaweimisync.ui.profiles.PetProfileScreenTestTags
import com.example.huaweimisync.ui.profiles.PetWeightChartMetric
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PetHistoryScreenUiTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test fun emptyHistoryStillOffersExactPetMeasurementAndPeriodFilter() {
        var starts = 0
        var selected: ChartRangePreset? = null
        setScreen(state(PetHistoryContent.Empty), { selected = it }) { starts++ }

        composeRule.onNodeWithTag(PetProfileScreenTestTags.Empty).assertIsDisplayed()
        composeRule.onNodeWithTag(PetProfileScreenTestTags.StartMeasurement).performClick()
        composeRule.onNodeWithTag(PetProfileScreenTestTags.preset(ChartRangePreset.LAST_7_DAYS)).performClick()
        composeRule.runOnIdle {
            assertEquals(1, starts)
            assertEquals(ChartRangePreset.LAST_7_DAYS, selected)
        }
    }

    @Test fun oneAndMultipleMeasurementsRenderStableRows() {
        val first = row("one")
        val second = row("two")
        setScreen(state(PetHistoryContent.Single(first)))
        composeRule.onNodeWithTag(PetProfileScreenTestTags.measurement("one")).assertIsDisplayed()
        composeRule.runOnIdle { }
        setScreen(state(PetHistoryContent.Multiple(listOf(first, second))))
        composeRule.onNodeWithTag(PetProfileScreenTestTags.measurement("one")).assertIsDisplayed()
        composeRule.onNodeWithTag(PetProfileScreenTestTags.measurement("two")).assertIsDisplayed()
    }

    @Test fun oneMeasurementInThirtyDayProfileReachesIdleWithoutChartHost() {
        val measurement = row("one")
        setScreen(
            state(PetHistoryContent.Single(measurement)).copy(
                series = ChartSeries(PetWeightChartMetric, listOf(ChartPoint(1_000L, 4.2))),
            ),
        )

        composeRule.waitForIdle()
        composeRule.onNodeWithTag(PetProfileScreenTestTags.measurement("one")).assertIsDisplayed()
        composeRule.onNodeWithTag(MetricChartTestTags.InsufficientInterval).assertIsDisplayed()
        composeRule.onNodeWithTag(MetricChartTestTags.ChartHost).assertDoesNotExist()
    }

    @Test fun twoMeasurementsAtDistinctTimesShowChart() {
        val first = row("one")
        val second = row("two")
        setScreen(
            state(PetHistoryContent.Multiple(listOf(first, second))).copy(
                series = ChartSeries(
                    PetWeightChartMetric,
                    listOf(ChartPoint(1_000L, 4.2), ChartPoint(2_000L, 4.3)),
                ),
            ),
        )

        composeRule.onNodeWithTag(MetricChartTestTags.ChartHost).assertIsDisplayed()
    }

    @Test fun deleteActionOpensDialogForExactRowAndCancelDoesNotConfirm() {
        val first = row("one")
        val second = row("two")
        var requested: String? = null
        var confirmations = 0
        var dismissals = 0
        val callbacks = callbacks(
            requestDelete = { requested = it },
            confirmDelete = { confirmations++ },
            dismissDelete = { dismissals++ },
        )
        setScreen(state(PetHistoryContent.Multiple(listOf(first, second))), callbacks)

        composeRule.onNodeWithTag(PetProfileScreenTestTags.deleteMeasurement("two"))
            .assertIsDisplayed().performClick()
        composeRule.runOnIdle { assertEquals("two", requested) }

        setScreen(
            state(PetHistoryContent.Multiple(listOf(first, second))).copy(
                deleteConfirmation = PetHistoryDeleteConfirmation(PetId("pet-exact"), second),
            ),
            callbacks,
        )
        composeRule.onNodeWithTag(PetProfileScreenTestTags.DeleteDialog).assertIsDisplayed()
        composeRule.onNodeWithText("27.08.2026 15:00 · 4,20 кг").assertIsDisplayed()
        composeRule.onNodeWithText("Измерение будет удалено без возможности восстановления.").assertIsDisplayed()
        composeRule.onNodeWithTag(PetProfileScreenTestTags.DeleteCancel).performClick()
        composeRule.runOnIdle {
            assertEquals(0, confirmations)
            assertEquals(1, dismissals)
        }
    }

    @Test fun confirmIsSubmittedOnceAndLoadingDisablesDialogActionsAccessibly() {
        val selected = row("one")
        var confirmations = 0
        var screenState by mutableStateOf(
            state(PetHistoryContent.Single(selected)).copy(
                deleteConfirmation = PetHistoryDeleteConfirmation(PetId("pet-exact"), selected),
            ),
        )
        val callbacks = callbacks(confirmDelete = {
            confirmations++
            screenState = screenState.copy(
                deleteConfirmation = screenState.deleteConfirmation?.copy(isDeleting = true),
            )
        })
        composeRule.setContent { PetProfileScreen(screenState, callbacks, PaddingValues()) {} }
        composeRule.onNodeWithTag(PetProfileScreenTestTags.DeleteConfirm).performClick()
        composeRule.onNodeWithTag(PetProfileScreenTestTags.DeleteConfirm).assertIsNotEnabled()
        composeRule.onNodeWithTag(PetProfileScreenTestTags.DeleteCancel).assertIsNotEnabled()
        composeRule.onNodeWithText("Удаление…").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(1, confirmations) }
    }

    @Test fun deleteErrorKeepsRowsOffersRetryAndCanBeDismissed() {
        val selected = row("one")
        var confirmations = 0
        val callbacks = callbacks(confirmDelete = { confirmations++ })
        setScreen(
            state(PetHistoryContent.Single(selected)).copy(
                deleteConfirmation = PetHistoryDeleteConfirmation(PetId("pet-exact"), selected),
                actionErrorMessage = "Не удалось сохранить изменения",
            ),
            callbacks,
        )

        composeRule.onNodeWithTag(PetProfileScreenTestTags.measurement("one")).assertIsDisplayed()
        composeRule.onNodeWithText("Не удалось сохранить изменения").assertIsDisplayed()
        composeRule.onNodeWithTag(PetProfileScreenTestTags.DeleteConfirm).assertIsEnabled().performClick()
        composeRule.runOnIdle { assertEquals(1, confirmations) }

        var errorDismissals = 0
        setScreen(
            state(PetHistoryContent.Single(selected)).copy(actionErrorMessage = "Не удалось сохранить изменения"),
            callbacks(dismissActionError = { errorDismissals++ }),
        )
        composeRule.onNodeWithTag(PetProfileScreenTestTags.ActionError).assertIsDisplayed()
        composeRule.onNodeWithTag(PetProfileScreenTestTags.measurement("one")).assertIsDisplayed()
        composeRule.onNodeWithTag(PetProfileScreenTestTags.ActionErrorDismiss).performClick()
        composeRule.runOnIdle { assertEquals(1, errorDismissals) }
    }

    @Test fun updatedStateRemovesDeletedRowAndShowsEmptyState() {
        val selected = row("one")
        var screenState by mutableStateOf(state(PetHistoryContent.Single(selected)))
        composeRule.setContent {
            PetProfileScreen(screenState, callbacks(), PaddingValues()) {}
        }
        composeRule.onNodeWithTag(PetProfileScreenTestTags.measurement("one")).assertIsDisplayed()

        composeRule.runOnIdle { screenState = state(PetHistoryContent.Empty) }
        composeRule.onNodeWithTag(PetProfileScreenTestTags.measurement("one")).assertDoesNotExist()
        composeRule.onNodeWithTag(PetProfileScreenTestTags.Empty).assertIsDisplayed()
    }

    @Test fun missingPetIsSafeAndDoesNotExposeHistoryRows() {
        setScreen(state(PetHistoryContent.Empty).copy(pet = null, isNotFound = true))
        composeRule.onNodeWithTag(PetProfileScreenTestTags.NotFound).assertIsDisplayed()
        composeRule.onNodeWithTag(PetProfileScreenTestTags.measurement("one")).assertDoesNotExist()
    }

    private fun setScreen(
        state: PetHistoryUiState,
        onPreset: (ChartRangePreset) -> Unit = {},
        onStart: () -> Unit = {},
    ) = composeRule.setContent {
        PetProfileScreen(state, PetHistoryCallbacks(onPreset, { _, _ -> }), PaddingValues(), onStart)
    }

    private fun setScreen(
        state: PetHistoryUiState,
        callbacks: PetHistoryCallbacks,
    ) = composeRule.setContent { PetProfileScreen(state, callbacks, PaddingValues()) {} }

    private fun callbacks(
        requestDelete: (String) -> Unit = {},
        confirmDelete: () -> Unit = {},
        dismissDelete: () -> Unit = {},
        dismissActionError: () -> Unit = {},
    ) = PetHistoryCallbacks(
        selectRangePreset = {},
        setDateRange = { _, _ -> },
        requestDelete = requestDelete,
        confirmDelete = confirmDelete,
        dismissDelete = dismissDelete,
        dismissActionError = dismissActionError,
    )

    private fun state(content: PetHistoryContent): PetHistoryUiState {
        val id = PetId("pet-exact")
        val now = Instant.parse("2026-08-27T10:00:00Z")
        return PetHistoryUiState(
            petId = id,
            pet = Pet(id, "Барсик", createdAt = now, updatedAt = now),
            startDate = LocalDate.of(2026, 8, 1),
            endDateInclusive = LocalDate.of(2026, 8, 27),
            rangePreset = ChartRangePreset.LAST_30_DAYS,
            content = content,
            series = ChartSeries(PetWeightChartMetric, emptyList()),
            isLoading = false,
        )
    }

    private fun row(id: String) = PetHistoryMeasurementUi(id, 1, "27.08.2026 15:00", 4.2, "4,20 кг")
}
