package com.palixander.scalesync.charts

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.palixander.scalesync.AppSection
import com.palixander.scalesync.ScaleSyncScaffold
import com.palixander.scalesync.MainUiState
import com.palixander.scalesync.SettingsCallbacks
import com.palixander.scalesync.measurements.MeasurementsCallbacks
import com.palixander.scalesync.measurements.MeasurementsDestination
import com.palixander.scalesync.ui.theme.ScaleSyncTheme
import java.time.LocalDate
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ChartsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun rangeFilterSheetShowsPresetsAndOpensCustomDatePickerFromState() {
        var state by mutableStateOf(chartsState())
        setContent(stateProvider = { state }, stateUpdater = { state = it })

        composeRule.onNodeWithContentDescription("Период: 7 дней").performClick()

        composeRule.onNodeWithText("Период").assertIsDisplayed()
        composeRule.onNodeWithText("7 дней").assertIsDisplayed()
        composeRule.onNodeWithText("30 дней").assertIsDisplayed()
        composeRule.onNodeWithText("3 месяца").assertIsDisplayed()
        composeRule.onNodeWithText("С начала года").assertIsDisplayed()
        composeRule.onNodeWithText("Свои даты").performClick()

        composeRule.runOnIdle {
            assertEquals(null, state.activeFilterSheet)
            assertEquals(true, state.isCustomDatePickerOpen)
        }
        composeRule.onNodeWithText("Диапазон дат").assertIsDisplayed()
        composeRule.onNodeWithText("Отмена").performClick()
        composeRule.onNodeWithText("Диапазон дат").assertDoesNotExist()
    }

    @Test
    fun bothSheetsDismissAndMetricSheetSelectsAllClearsAndFinishesFromState() {
        var state by mutableStateOf(chartsState())
        setContent(stateProvider = { state }, stateUpdater = { state = it })

        composeRule.onNodeWithContentDescription("Период: 7 дней").performClick()
        composeRule.onNodeWithText("Период").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Закрыть").performClick()
        composeRule.onNodeWithText("Свои даты").assertDoesNotExist()

        composeRule.onNodeWithContentDescription("Показатели: 2 из 16").performClick()
        composeRule.onNodeWithText("Показатели").assertIsDisplayed()
        composeRule.onNodeWithText("Выбрать все").performClick()
        composeRule.onNodeWithText("Выбрано: 16").assertIsDisplayed()
        composeRule.onNodeWithText("Очистить").performClick()
        composeRule.onNodeWithText("Выбрано: 0").assertIsDisplayed()
        composeRule.onNodeWithText("Готово").performClick()

        composeRule.runOnIdle {
            assertEquals(emptySet<String>(), state.selectedMetricKeys)
            assertEquals(null, state.activeFilterSheet)
        }
        composeRule.onNodeWithText("Готово").assertDoesNotExist()
        composeRule.onNodeWithText("Показатели не выбраны").assertIsDisplayed()
    }

    @Test
    fun emptyStateActionOpensMetricSheetThroughCallback() {
        var state by mutableStateOf(chartsState(selectedMetricKeys = emptySet()))
        setContent(stateProvider = { state }, stateUpdater = { state = it })

        composeRule.onNodeWithContentDescription("Показатели не выбраны").assertIsDisplayed()
        composeRule.onNodeWithText("Выбрать показатели").performClick()

        composeRule.runOnIdle {
            assertEquals(ChartFilterSheet.METRICS, state.activeFilterSheet)
        }
        composeRule.onNodeWithText("Показатели").assertIsDisplayed()
        composeRule.onNodeWithText("Выбрано: 0").assertIsDisplayed()
    }

    @Test
    fun metricCardShowsMinimumMaximumAndAverageStatistics() {
        val metric = ChartMetricOption("weightKg", "Вес", "кг", 2)
        val state = chartsState().copy(
            selectedMetricKeys = setOf(metric.key),
            series = listOf(
                ChartSeries(
                    metric = metric,
                    points = listOf(
                        ChartPoint(2L, 71.25),
                        ChartPoint(1L, 70.0),
                    ),
                ),
            ),
        )
        setContent(stateProvider = { state }, stateUpdater = {})

        composeRule.onNodeWithContentDescription(
            "Минимум: ${formatChartStatistic(70.0, metric, Locale.getDefault())}",
        ).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(
            "Максимум: ${formatChartStatistic(71.25, metric, Locale.getDefault())}",
        ).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(
            "Среднее: ${formatChartStatistic(70.625, metric, Locale.getDefault())}",
        ).assertIsDisplayed()
    }

    @Test
    fun presetsAndConfirmedCustomRangeSurviveSettingsRoundTripAndScreenRecreation() {
        var state by mutableStateOf(chartsState())
        var section by mutableStateOf(AppSection.CHARTS)
        setShellContent(
            stateProvider = { state },
            stateUpdater = { state = it },
            sectionProvider = { section },
            sectionUpdater = { section = it },
        )

        selectPresetAndRoundTrip(
            presetText = "30 дней",
            expectedDescription = "Период: 30 дней",
            expectedPreset = ChartRangePreset.LAST_30_DAYS,
            expectedStart = LocalDate.of(2026, 7, 17),
            stateProvider = { state },
        )
        selectPresetAndRoundTrip(
            presetText = "3 месяца",
            expectedDescription = "Период: 3 месяца",
            expectedPreset = ChartRangePreset.LAST_3_MONTHS,
            expectedStart = LocalDate.of(2026, 5, 16),
            stateProvider = { state },
        )
        selectPresetAndRoundTrip(
            presetText = "С начала года",
            expectedDescription = "Период: С начала года",
            expectedPreset = ChartRangePreset.YEAR_TO_DATE,
            expectedStart = LocalDate.of(2026, 1, 1),
            stateProvider = { state },
        )

        composeRule.onNode(hasContentDescription("Период:", substring = true)).performClick()
        composeRule.onNodeWithText("Свои даты").performClick()
        composeRule.onNodeWithText("Применить").performClick()
        composeRule.runOnIdle {
            assertEquals(ChartRangePreset.CUSTOM, state.rangePreset)
            assertEquals(LocalDate.of(2026, 1, 1), state.startDate)
            assertEquals(LocalDate.of(2026, 8, 15), state.endDateInclusive)
        }
        roundTripThroughSettings("Период: 01.01.2026 — 15.08.2026")
    }

    private fun selectPresetAndRoundTrip(
        presetText: String,
        expectedDescription: String,
        expectedPreset: ChartRangePreset,
        expectedStart: LocalDate,
        stateProvider: () -> ChartsUiState,
    ) {
        composeRule.onNode(hasContentDescription("Период:", substring = true)).performClick()
        composeRule.onNodeWithText(presetText).performClick()
        composeRule.runOnIdle {
            val state = stateProvider()
            assertEquals(expectedPreset, state.rangePreset)
            assertEquals(expectedStart, state.startDate)
            assertEquals(LocalDate.of(2026, 8, 15), state.endDateInclusive)
        }
        roundTripThroughSettings(expectedDescription)
    }

    private fun roundTripThroughSettings(expectedDescription: String) {
        composeRule.onNodeWithContentDescription("Настройки").performClick()
        composeRule.onNodeWithText("Интеграции").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Графики").performClick()
        composeRule.onNodeWithContentDescription(expectedDescription).assertIsDisplayed()
    }

    private fun setShellContent(
        stateProvider: () -> ChartsUiState,
        stateUpdater: (ChartsUiState) -> Unit,
        sectionProvider: () -> AppSection,
        sectionUpdater: (AppSection) -> Unit,
    ) {
        composeRule.setContent {
            val state = stateProvider()
            ScaleSyncScaffold(
                state = MainUiState(),
                currentSection = sectionProvider(),
                measurementsDestination = MeasurementsDestination.SUMMARY,
                measurementsCallbacks = MeasurementsCallbacks.None,
                snackbarHostState = remember { SnackbarHostState() },
                onSectionSelected = sectionUpdater,
                onCloseProfile = {},
                onSaveProfile = {},
                onProfileHeightChanged = {},
                onProfileBirthDateChanged = {},
                onProfileSexChanged = {},
                settingsCallbacks = settingsCallbacks(),
                measurementsContent = { _, _ -> },
                chartsContent = { padding ->
                    ChartsScreen(
                        state = state,
                        callbacks = callbacks(stateProvider, stateUpdater),
                        modifier = Modifier.fillMaxSize().padding(padding),
                    )
                },
            )
        }
    }

    private fun setContent(
        stateProvider: () -> ChartsUiState,
        stateUpdater: (ChartsUiState) -> Unit,
    ) {
        composeRule.setContent {
            ScaleSyncTheme {
                ChartsScreen(
                    state = stateProvider(),
                    callbacks = callbacks(stateProvider, stateUpdater),
                )
            }
        }
    }

    private fun callbacks(
        stateProvider: () -> ChartsUiState,
        stateUpdater: (ChartsUiState) -> Unit,
    ) = ChartsCallbacks(
        openRangeFilter = {
            stateUpdater(
                stateProvider().copy(
                    activeFilterSheet = ChartFilterSheet.RANGE,
                    isCustomDatePickerOpen = false,
                ),
            )
        },
        openMetricFilter = {
            stateUpdater(
                stateProvider().copy(
                    activeFilterSheet = ChartFilterSheet.METRICS,
                    isCustomDatePickerOpen = false,
                ),
            )
        },
        dismissFilterSheet = {
            stateUpdater(stateProvider().copy(activeFilterSheet = null))
        },
        selectRangePreset = { preset ->
            val state = stateProvider()
            if (preset == ChartRangePreset.CUSTOM) {
                stateUpdater(
                    state.copy(
                        activeFilterSheet = null,
                        isCustomDatePickerOpen = true,
                    ),
                )
            } else {
                val range = requireNotNull(preset.rangeEndingOn(FixedToday))
                stateUpdater(
                    state.copy(
                        startDate = range.startDate,
                        endDateInclusive = range.endDateInclusive,
                        rangePreset = preset,
                        activeFilterSheet = null,
                        isCustomDatePickerOpen = false,
                    ),
                )
            }
        },
        dismissCustomDatePicker = {
            stateUpdater(stateProvider().copy(isCustomDatePickerOpen = false))
        },
        setDateRange = { startDate, endDateInclusive ->
            stateUpdater(
                stateProvider().copy(
                    startDate = startDate,
                    endDateInclusive = endDateInclusive,
                    rangePreset = ChartRangePreset.CUSTOM,
                    activeFilterSheet = null,
                    isCustomDatePickerOpen = false,
                ),
            )
        },
        setMetricSelected = { key, selected ->
            val state = stateProvider()
            val keys = if (selected) {
                state.selectedMetricKeys + key
            } else {
                state.selectedMetricKeys - key
            }
            stateUpdater(state.copy(selectedMetricKeys = keys))
        },
        selectAll = {
            val state = stateProvider()
            stateUpdater(
                state.copy(
                    selectedMetricKeys = state.metricOptions.mapTo(linkedSetOf()) { it.key },
                ),
            )
        },
        clearSelection = {
            stateUpdater(stateProvider().copy(selectedMetricKeys = emptySet()))
        },
        doneSelectingMetrics = {
            stateUpdater(stateProvider().copy(activeFilterSheet = null))
        },
    )

    private fun settingsCallbacks() = SettingsCallbacks(
        onOpenProfile = {},
        onHealthConnectAuthorization = {},
        onHealthConnectAccessManagement = {},
        onManualScan = {},
        onReliabilityMode = {},
        openBatterySettings = {},
        openApplicationSettings = {},
    )

    private fun chartsState(
        selectedMetricKeys: Set<String> = DefaultChartMetricKeys,
    ): ChartsUiState = ChartsUiState(
        startDate = LocalDate.of(2026, 8, 9),
        endDateInclusive = FixedToday,
        currentDate = FixedToday,
        metricOptions = chartMetricOptions(),
        selectedMetricKeys = selectedMetricKeys,
        series = emptyList(),
    )

    private companion object {
        val FixedToday: LocalDate = LocalDate.of(2026, 8, 15)
    }
}
