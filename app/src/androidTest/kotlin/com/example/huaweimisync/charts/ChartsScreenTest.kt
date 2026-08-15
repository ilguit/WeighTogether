package com.example.huaweimisync.charts

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.huaweimisync.ui.theme.HuaweiMiSyncTheme
import java.time.LocalDate
import org.junit.Rule
import org.junit.Test

class ChartsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun rangeFilterSheetShowsPresetsAndOpensCustomDatePicker() {
        var state by mutableStateOf(chartsState())
        setContent(stateProvider = { state }, stateUpdater = { state = it })

        composeRule.onNodeWithContentDescription("Период: 7 дней").performClick()

        composeRule.onNodeWithText("Период").assertIsDisplayed()
        composeRule.onNodeWithText("7 дней").assertIsDisplayed()
        composeRule.onNodeWithText("30 дней").assertIsDisplayed()
        composeRule.onNodeWithText("3 месяца").assertIsDisplayed()
        composeRule.onNodeWithText("С начала года").assertIsDisplayed()
        composeRule.onNodeWithText("Свои даты").performClick()

        composeRule.onNodeWithText("Диапазон дат").assertIsDisplayed()
    }

    @Test
    fun metricFilterSheetClearsSelectionAndDoneShowsEmptyState() {
        var state by mutableStateOf(chartsState())
        setContent(stateProvider = { state }, stateUpdater = { state = it })

        composeRule.onNodeWithContentDescription("Показатели: 2 из 16").performClick()

        composeRule.onNodeWithText("Показатели").assertIsDisplayed()
        composeRule.onNodeWithText("Выбрать все").assertIsDisplayed()
        composeRule.onNodeWithText("Очистить").performClick()
        composeRule.onNodeWithText("Выбрано: 0").assertIsDisplayed()
        composeRule.onNodeWithText("Готово").performClick()

        composeRule.onNodeWithText("Готово").assertDoesNotExist()
        composeRule.onNodeWithText("Показатели не выбраны").assertIsDisplayed()
        composeRule.onNodeWithText("Выбрать показатели").assertIsDisplayed()
    }

    @Test
    fun emptyStateActionReopensMetricFilterSheet() {
        var state by mutableStateOf(chartsState(selectedMetricKeys = emptySet()))
        setContent(stateProvider = { state }, stateUpdater = { state = it })

        composeRule.onNodeWithContentDescription("Показатели не выбраны").assertIsDisplayed()
        composeRule.onNodeWithText("Выбрать показатели").performClick()

        composeRule.onNodeWithText("Показатели").assertIsDisplayed()
        composeRule.onNodeWithText("Выбрано: 0").assertIsDisplayed()
    }

    private fun setContent(
        stateProvider: () -> ChartsUiState,
        stateUpdater: (ChartsUiState) -> Unit,
    ) {
        composeRule.setContent {
            HuaweiMiSyncTheme {
                val state = stateProvider()
                ChartsScreen(
                    state = state,
                    onDateRangeChange = { startDate, endDateInclusive ->
                        stateUpdater(
                            state.copy(
                                startDate = startDate,
                                endDateInclusive = endDateInclusive,
                                rangePreset = ChartRangePreset.CUSTOM,
                                isCustomDatePickerOpen = false,
                            ),
                        )
                    },
                    onMetricSelectionChange = { key, selected ->
                        val keys = if (selected) {
                            state.selectedMetricKeys + key
                        } else {
                            state.selectedMetricKeys - key
                        }
                        stateUpdater(state.copy(selectedMetricKeys = keys))
                    },
                    onSelectAll = {
                        stateUpdater(
                            state.copy(
                                selectedMetricKeys = state.metricOptions.mapTo(linkedSetOf()) { it.key },
                            ),
                        )
                    },
                    onClearSelection = {
                        stateUpdater(state.copy(selectedMetricKeys = emptySet()))
                    },
                    onOpenRangeFilter = {
                        stateUpdater(state.copy(activeFilterSheet = ChartFilterSheet.RANGE))
                    },
                    onOpenMetricFilter = {
                        stateUpdater(state.copy(activeFilterSheet = ChartFilterSheet.METRICS))
                    },
                    onDismissFilterSheet = {
                        stateUpdater(state.copy(activeFilterSheet = null))
                    },
                    onRangePresetSelected = { preset ->
                        if (preset == ChartRangePreset.CUSTOM) {
                            stateUpdater(
                                state.copy(
                                    activeFilterSheet = null,
                                    isCustomDatePickerOpen = true,
                                ),
                            )
                        } else {
                            val range = requireNotNull(preset.rangeEndingOn(state.endDateInclusive))
                            stateUpdater(
                                state.copy(
                                    startDate = range.startDate,
                                    endDateInclusive = range.endDateInclusive,
                                    rangePreset = preset,
                                    activeFilterSheet = null,
                                ),
                            )
                        }
                    },
                    onDismissCustomDatePicker = {
                        stateUpdater(state.copy(isCustomDatePickerOpen = false))
                    },
                    onDoneSelectingMetrics = {
                        stateUpdater(state.copy(activeFilterSheet = null))
                    },
                )
            }
        }
    }

    private fun chartsState(
        selectedMetricKeys: Set<String> = DefaultChartMetricKeys,
    ): ChartsUiState = ChartsUiState(
        startDate = LocalDate.of(2026, 8, 9),
        endDateInclusive = LocalDate.of(2026, 8, 15),
        metricOptions = chartMetricOptions(),
        selectedMetricKeys = selectedMetricKeys,
        series = emptyList(),
    )
}
