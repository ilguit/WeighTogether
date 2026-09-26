package com.palixander.scalesync.measurements

import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.junit4.StateRestorationTester
import com.palixander.scalesync.MeasurementsViewModel
import com.palixander.scalesync.charts.ChartScrollOffset
import com.palixander.scalesync.core.ReferenceClassifier
import com.palixander.scalesync.core.Sex
import com.palixander.scalesync.domain.PendingMeasurementId
import com.palixander.scalesync.domain.PreliminaryDecisionReadiness
import com.palixander.scalesync.ui.accounts.AccountSelectorTestTags
import com.palixander.scalesync.ui.theme.ScaleSyncTheme
import com.palixander.scalesync.ui.theme.ReferencePalette
import com.palixander.scalesync.ui.theme.ReferenceTone
import com.palixander.scalesync.ui.reference.ReferenceComponentTestTags
import com.palixander.scalesync.ui.reference.ReferencePresentationFactory
import com.palixander.scalesync.ui.reference.toReferenceContext
import com.palixander.scalesync.ui.reference.toReferenceReadings
import androidx.test.platform.app.InstrumentationRegistry
import java.time.Instant
import java.time.LocalDate
import java.util.Locale
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MeasurementsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun manualOriginRemainsVisibleInSummaryAndCollapsedEditedHistory() {
        val item = sampleItem("manual", "2026-08-15T12:42:00Z", 4.125, syncedSync(),
            type = MeasurementUiType.WEIGHT_ONLY, isManuallyEdited = true,
        ).copy(origin = com.palixander.scalesync.domain.MeasurementOrigin.MANUAL)
        var state by mutableStateOf(MeasurementsUiState(
            isLoading = false, measurements = listOf(item), summary = buildMeasurementSummary(listOf(item)),
        ))
        composeRule.setContent { ScaleSyncTheme { MeasurementsScreen(state, MeasurementsCallbacks.None) } }
        composeRule.onNodeWithTag("summary-manual-origin").assertIsDisplayed()
        captureManualWeightEvidence("manual-weight-summary")
        composeRule.runOnIdle { state = state.copy(destination = MeasurementsDestination.HISTORY) }
        composeRule.onNodeWithTag("history-manual-indicators").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Введено вручную. Изменено вручную").assertIsDisplayed()
        composeRule.onNodeWithTag("history-manual-indicators-dismiss").performClick()
        composeRule.onNodeWithTag("history-manual-indicators").assertIsFocused()
        captureManualWeightEvidence("manual-weight-history")
    }

    private fun captureManualWeightEvidence(name: String) {
        composeRule.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        val file = java.io.File(instrumentation.targetContext.cacheDir, "$name.png")
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun accountSelectorIsVisibleOnlyOnSummaryAndHistory() {
        var state by mutableStateOf(sampleState())

        composeRule.setContent {
            ScaleSyncTheme {
                MeasurementsScreen(state = state, callbacks = MeasurementsCallbacks.None)
            }
        }

        composeRule.onNodeWithTag(AccountSelectorTestTags.Selector).assertIsDisplayed()

        composeRule.runOnIdle {
            state = state.copy(destination = MeasurementsDestination.HISTORY)
        }
        composeRule.onNodeWithTag(AccountSelectorTestTags.Selector).assertIsDisplayed()

        composeRule.runOnIdle {
            state = state.copy(
                destination = MeasurementsDestination.EDITOR,
                editor = MeasurementEditorState(
                    measurementId = "latest",
                    measuredAtEpochSecond = requireNotNull(state.summary).latest.measuredAtEpochSecond,
                    draft = MeasurementEditorDraft.from(requireNotNull(state.summary).latest.values),
                ),
            )
        }
        composeRule.onNodeWithTag(AccountSelectorTestTags.Selector).assertDoesNotExist()
    }

    @Test
    fun collapsedSummaryKeepsEssentialContentWithinCompactHeight() {
        val state = sampleState()

        composeRule.setContent {
            ScaleSyncTheme {
                Box(Modifier.width(400.dp)) {
                    MeasurementsScreen(state = state, callbacks = MeasurementsCallbacks.None)
                }
            }
        }

        val summaryCard = composeRule.onNodeWithTag("measurement-summary").assertIsDisplayed()
        val summaryBounds = summaryCard.getUnclippedBoundsInRoot()
        assertTrue(
            "Collapsed summary should leave room for the home chart within 320 dp",
            summaryBounds.bottom - summaryBounds.top <= 320.dp,
        )
        composeRule.onNodeWithText(
            formatMeasurementDateTime(requireNotNull(state.summary).latest.measuredAt),
        ).assertIsDisplayed()
        composeRule.onNodeWithText(
            formatMeasurementValue(MeasurementField.WEIGHT_KG, 72.4),
            substring = true,
        ).assertIsDisplayed()
        composeRule.onNodeWithText(
            "−${formatMeasurementValue(MeasurementField.WEIGHT_KG, 0.4)} кг",
        ).assertIsDisplayed()
        listOf("Жир", "Мышечная масса", "Вода", "Индекс массы тела").forEach { label ->
            composeRule.onNodeWithText(label, useUnmergedTree = true).assertIsDisplayed()
        }
        composeRule.onNodeWithTag("summary-sync-status").assertIsDisplayed()
        composeRule.onNodeWithTag("summary-more-actions").assertIsDisplayed()
        listOf("WEIGHT", "BODY_FAT_PERCENT", "MUSCLE_MASS", "WATER_PERCENT", "BMI").forEach { metric ->
            composeRule.onNodeWithTag("summary-reference-$metric").assertIsDisplayed()
        }
        composeRule.onNodeWithText("Норма").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Вес, 72,4 килограмма. Норма.")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Импеданс").assertDoesNotExist()
    }

    @Test
    fun compactSummaryHidesVisualStatusButKeepsUnavailableReasonInSemantics() {
        val values = weightOnlyValues(72.4)
        val latest = referenceItem("latest", "2026-08-15T12:42:00Z", values)
        val state = MeasurementsUiState(
            isLoading = false,
            measurements = listOf(latest),
            summary = buildMeasurementSummary(listOf(latest)),
        )

        composeRule.setContent {
            ScaleSyncTheme { MeasurementsScreen(state, MeasurementsCallbacks.None) }
        }

        composeRule.onAllNodesWithText("Нет данных", useUnmergedTree = true).assertCountEquals(0)
        composeRule.onAllNodesWithText("—", useUnmergedTree = true).assertCountEquals(4)
        composeRule.onNodeWithText("Норма", useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Жир. Нет данных.")
            .assertIsDisplayed()
    }

    @Test
    fun compactSummaryPublishesTheColorAppliedToNumberAndUnitForDifferentTones() {
        val metrics = referencePresentations(sampleValues(72.4)).map { presentation ->
            when (presentation.definition.metric.name) {
                "BODY_FAT_PERCENT" -> presentation.copy(tone = ReferenceTone.NORMAL)
                "BMI" -> presentation.copy(tone = ReferenceTone.VERY_HIGH)
                else -> presentation
            }
        }
        val latest = sampleItem(
            id = "latest",
            instant = "2026-08-15T12:42:00Z",
            weight = 72.4,
            sync = localOnlySync(),
        ).copy(referenceMetrics = metrics, ratingHeightCm = 175.0, referenceAge = 36)
        val state = MeasurementsUiState(
            isLoading = false,
            measurements = listOf(latest),
            summary = buildMeasurementSummary(listOf(latest)),
        )

        composeRule.setContent {
            ScaleSyncTheme { MeasurementsScreen(state, MeasurementsCallbacks.None) }
        }

        listOf(
            "BODY_FAT_PERCENT" to ReferencePalette.Normal.content,
            "BMI" to ReferencePalette.VeryHigh.content,
        ).forEach { (metric, expectedColor) ->
            listOf("value", "unit").forEach { part ->
                composeRule.onNodeWithTag(
                    "summary-reference-$part-$metric",
                    useUnmergedTree = true,
                ).assert(SemanticsMatcher.expectValue(
                    CompactSummaryReferenceContentColorKey,
                    expectedColor.value.toLong(),
                ))
            }
        }
    }

    @Test
    fun homeKgChartIsImmediatelyBelowSummaryAndSeriesHaveCheckboxSemantics() {
        val state = sampleState().copy(homeKgChart = homeChartState())

        composeRule.setContent {
            ScaleSyncTheme { MeasurementsScreen(state, MeasurementsCallbacks.None) }
        }

        val summaryBottom = composeRule.onNodeWithTag("measurement-summary")
            .getUnclippedBoundsInRoot().bottom
        val chartTop = composeRule.onNodeWithTag("home-kg-chart")
            .getUnclippedBoundsInRoot().top
        assertTrue("The home chart must follow the summary card", chartTop >= summaryBottom)
        composeRule.onNodeWithTag("home-kg-vico-chart").assertExists()
        composeRule.onNodeWithTag("home-kg-series-toggle").performScrollTo().performClick()
        HomeKgChartSeriesCatalog.forEach { metric ->
            val node = composeRule.onNodeWithTag("home-kg-legend-${metric.key}")
            node.assertExists().assertIsOn()
            node.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))
            node.assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ContentDescription))
            node.assertTextContains(metric.label)
        }
    }

    @Test
    fun homeKgChartDragScrollsViewportWithoutMovingMeasurementsList() {
        val baseChart = homeChartState()
        val weight = baseChart.series.first()
        val chart = baseChart.copy(
            series = listOf(
                weight.copy(
                    points = (0..60).map { day ->
                        HomeKgChartPoint(
                            measurementId = "weight-$day",
                            measuredAtEpochSecond = Instant.parse("2026-06-18T12:00:00Z")
                                .plusSeconds(day * 86_400L).epochSecond,
                            valueKg = 70.0 + day / 10.0,
                        )
                    },
                ),
            ),
            activeSeriesKeys = setOf(weight.key),
        )
        val state = sampleState().copy(homeKgChart = chart)
        composeRule.setContent {
            ScaleSyncTheme { MeasurementsScreen(state, MeasurementsCallbacks.None) }
        }

        val chartNode = composeRule.onNodeWithTag("home-kg-vico-chart")
            .performScrollTo()
            .assertIsDisplayed()
        val initialOffset = chartNode.fetchSemanticsNode().config[ChartScrollOffset]
        val initialTop = chartNode.getUnclippedBoundsInRoot().top

        chartNode.performTouchInput { swipeLeft(durationMillis = 500) }
        composeRule.waitForIdle()

        assertNotEquals(initialOffset, chartNode.fetchSemanticsNode().config[ChartScrollOffset])
        assertEquals(initialTop.value, chartNode.getUnclippedBoundsInRoot().top.value, 1f)
    }

    @Test
    fun homeKgLegendTapDispatchesKeyAndReflectsOwnerState() {
        var toggledKeys = emptyList<String>()
        var state by mutableStateOf(sampleState().copy(homeKgChart = homeChartState()))
        val callbacks = MeasurementsCallbacks.None.copy(
            onHomeKgChartSeriesToggled = { key ->
                toggledKeys = toggledKeys + key
                val chart = requireNotNull(state.homeKgChart)
                state = state.copy(
                    homeKgChart = chart.copy(
                        activeSeriesKeys = if (key in chart.activeSeriesKeys) {
                            chart.activeSeriesKeys - key
                        } else {
                            chart.activeSeriesKeys + key
                        },
                    ),
                )
            },
        )
        composeRule.setContent {
            ScaleSyncTheme { MeasurementsScreen(state, callbacks) }
        }

        composeRule.onNodeWithTag("home-kg-series-toggle").performScrollTo().performClick()
        val weight = composeRule.onNodeWithTag("home-kg-legend-weight_kg")
        weight.assertIsOn().performScrollTo().performClick()
        weight.assertIsOff()
        weight.performClick()
        weight.assertIsOn()
        composeRule.runOnIdle {
            assertEquals(listOf("weight_kg", "weight_kg"), toggledKeys)
        }
    }

    @Test
    fun homeKgChartShowsNoDataWhileKeepingAllEightLegendItems() {
        val state = sampleState().copy(homeKgChart = homeChartState(withData = false))

        composeRule.setContent {
            ScaleSyncTheme { MeasurementsScreen(state, MeasurementsCallbacks.None) }
        }

        composeRule.onNodeWithTag("home-kg-chart-no-data").assertExists()
        composeRule.onNodeWithText("За последние 14 дней нет данных для графика.").assertExists()
        composeRule.onNodeWithTag("home-kg-series-toggle").performScrollTo().performClick()
        HomeKgChartSeriesCatalog.forEach { metric ->
            composeRule.onNodeWithTag("home-kg-legend-${metric.key}").assertExists()
        }
    }

    @Test
    fun homeKgChartAllowsEverySeriesToBeDisabled() {
        val state = sampleState().copy(
            homeKgChart = homeChartState().copy(activeSeriesKeys = emptySet()),
        )

        composeRule.setContent {
            ScaleSyncTheme { MeasurementsScreen(state, MeasurementsCallbacks.None) }
        }

        composeRule.onNodeWithTag("home-kg-chart-no-active").assertExists()
        composeRule.onNodeWithText("Выберите показатели в списке, чтобы показать график.")
            .assertExists()
        composeRule.onNodeWithTag("home-kg-series-toggle").performScrollTo().performClick()
        HomeKgChartSeriesCatalog.forEach { metric ->
            composeRule.onNodeWithTag("home-kg-legend-${metric.key}").assertIsOff()
        }
    }

    @Test
    fun seriesSelectorRestoresExpansionAndSupportsZeroOneAndAllSelections() {
        val restoration = StateRestorationTester(composeRule)
        var chart by mutableStateOf(homeChartState(withData = false).copy(activeSeriesKeys = emptySet()))
        restoration.setContent {
            ScaleSyncTheme {
                androidx.compose.foundation.rememberScrollState().let { scroll ->
                    androidx.compose.foundation.layout.Column(
                        Modifier.verticalScroll(scroll),
                    ) {
                        HomeKgChart(chart, { key ->
                            chart = chart.copy(activeSeriesKeys = if (key in chart.activeSeriesKeys) {
                                chart.activeSeriesKeys - key
                            } else {
                                chart.activeSeriesKeys + key
                            })
                        })
                    }
                }
            }
        }
        composeRule.onNodeWithText("Показатели · 0 из 8").assertExists()
        composeRule.onNodeWithTag("home-kg-legend-weight_kg").assertDoesNotExist()
        composeRule.onNodeWithTag("home-kg-series-toggle").performScrollTo().performClick()
        composeRule.onNodeWithTag("home-kg-legend-weight_kg").performScrollTo().performClick()
        composeRule.onNodeWithText("Показатели · 1 из 8").assertExists()
        restoration.emulateSavedInstanceStateRestore()
        composeRule.onNodeWithTag("home-kg-legend-weight_kg").assertIsOn()
        HomeKgChartSeriesCatalog.drop(1).forEach { metric ->
            composeRule.onNodeWithTag("home-kg-legend-${metric.key}").performScrollTo().performClick()
        }
        composeRule.onNodeWithText("Показатели · 8 из 8").assertExists()
        HomeKgChartSeriesCatalog.forEach { metric ->
            composeRule.onNodeWithTag("home-kg-legend-${metric.key}").assertIsOn()
                .performScrollTo().performClick()
        }
        composeRule.onNodeWithText("Показатели · 0 из 8").assertExists()
        composeRule.onNodeWithTag("home-kg-series-toggle").performScrollTo().performClick()
        restoration.emulateSavedInstanceStateRestore()
        composeRule.onNodeWithTag("home-kg-legend-weight_kg").assertDoesNotExist()
    }

    @Test
    fun summaryCardExpandsWithoutLegacyHistoryActions() {
        val state = sampleState()

        composeRule.setContent {
            ScaleSyncTheme {
                MeasurementsScreen(state = state, callbacks = MeasurementsCallbacks.None)
            }
        }

        composeRule.onNodeWithText("Импеданс").assertDoesNotExist()
        composeRule.onNodeWithText(
            formatMeasurementDateTime(requireNotNull(state.summary).latest.measuredAt),
        ).assertExists()
        composeRule.onNodeWithTag("summary-expand-metrics").performClick()
        composeRule.onNodeWithText("Импеданс", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("measurements-history-cta").assertDoesNotExist()
        composeRule.onNodeWithText("История измерений").assertDoesNotExist()
        composeRule.onNodeWithText("Открыть историю").assertDoesNotExist()
    }

    @Test
    fun historyCardExpandsFromHistoryDestination() {
        val state = sampleState().copy(destination = MeasurementsDestination.HISTORY)

        composeRule.setContent {
            ScaleSyncTheme {
                MeasurementsScreen(state = state, callbacks = MeasurementsCallbacks.None)
            }
        }

        composeRule.onNodeWithTag("history-toggle-latest").performClick()
        composeRule.onNodeWithText("Импеданс", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Изменить").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun emptySummaryOmitsLegacyHistoryActions() {
        val state = MeasurementsUiState(isLoading = false)

        composeRule.setContent {
            ScaleSyncTheme {
                MeasurementsScreen(state = state, callbacks = MeasurementsCallbacks.None)
            }
        }

        composeRule.onNodeWithText("Пока нет измерений").assertIsDisplayed()
        composeRule.onNodeWithTag("measurements-history-cta").assertDoesNotExist()
        composeRule.onNodeWithText("История измерений").assertDoesNotExist()
        composeRule.onNodeWithText("Открыть историю").assertDoesNotExist()
        composeRule.onNodeWithTag("pet-measurement-action").assertDoesNotExist()
    }

    @Test
    fun historyShowsManualAndProfileMismatchNoticesAsIndependentStates() {
        val both = sampleItem(
            id = "both",
            instant = "2026-08-15T12:42:00Z",
            weight = 72.4,
            sync = syncedSync(),
            isManuallyEdited = true,
            hasProfileSyncMismatch = true,
        )
        val neither = sampleItem(
            id = "neither",
            instant = "2026-08-13T11:58:00Z",
            weight = 72.8,
            sync = localOnlySync(),
        )
        val state = MeasurementsUiState(
            destination = MeasurementsDestination.HISTORY,
            isLoading = false,
            measurements = listOf(both, neither),
            summary = buildMeasurementSummary(listOf(both, neither)),
        )

        composeRule.setContent {
            ScaleSyncTheme {
                MeasurementsScreen(state = state, callbacks = MeasurementsCallbacks.None)
            }
        }

        composeRule.onNodeWithTag("history-toggle-both").performClick()
        composeRule.onNodeWithTag("history-manual-notice-both").assertIsDisplayed()
        composeRule.onNodeWithText(MANUALLY_EDITED_HISTORY_MESSAGE).assertIsDisplayed()
        composeRule.onNodeWithTag("history-profile-mismatch-notice-both").assertIsDisplayed()
        composeRule.onNodeWithText(PROFILE_SYNC_MISMATCH_HISTORY_MESSAGE).assertIsDisplayed()

        composeRule.onNodeWithTag("history-toggle-neither").performScrollTo().performClick()
        composeRule.onNodeWithTag("history-manual-notice-neither").assertDoesNotExist()
        composeRule.onNodeWithTag("history-profile-mismatch-notice-neither").assertDoesNotExist()
    }

    @Test
    fun summaryDoesNotOwnPendingQueueEntryPoint() {
        composeRule.setContent {
            ScaleSyncTheme {
                MeasurementsScreen(
                    state = sampleState().copy(
                        pendingMeasurements = listOf(
                            pendingItem(
                                id = "pending-summary",
                                instant = "2026-08-15T12:42:00Z",
                                weight = 72.4,
                                impedance = 512,
                            ),
                        ),
                    ),
                    callbacks = MeasurementsCallbacks.None,
                )
            }
        }

        composeRule.onNodeWithTag("pending-queue-summary-card").assertDoesNotExist()
    }

    @Test
    fun pendingQueueShowsEveryReadingAndDispatchesAddressedActions() {
        val first = pendingItem(
            id = "pending-first",
            instant = "2026-08-15T12:42:00.123456789Z",
            weight = 72.4,
            impedance = 512,
        )
        val second = pendingItem(
            id = "pending-second",
            instant = "2026-08-15T12:44:00Z",
            weight = 73.1,
            impedance = null,
        )
        var assignedId: PendingMeasurementId? = null
        var previewedId: PendingMeasurementId? = null
        var deletedId: PendingMeasurementId? = null
        val state = MeasurementsUiState(
            destination = MeasurementsDestination.PENDING_QUEUE,
            pendingMeasurements = listOf(first, second),
            isLoading = false,
        )
        val callbacks = MeasurementsCallbacks.None.copy(
            onPendingAssignRequested = { assignedId = it },
            onPendingPreviewRequested = { previewedId = it },
            onPendingDeleteRequested = { deletedId = it },
        )

        composeRule.setContent {
            ScaleSyncTheme { MeasurementsScreen(state = state, callbacks = callbacks) }
        }

        composeRule.onNodeWithTag(AccountSelectorTestTags.Selector).assertDoesNotExist()
        composeRule.onNodeWithTag("pending-card-${first.id.value}").assertExists()
        composeRule.onNodeWithText(formatMeasurementDateTime(first.measuredAt)).assertExists()
        composeRule.onNodeWithText(
            "${formatMeasurementValue(MeasurementField.WEIGHT_KG, first.weightKg)} кг",
        ).assertExists()
        composeRule.onNodeWithText("512 Ом").assertExists()

        composeRule.onNodeWithTag("pending-assign-${first.id.value}").performScrollTo().performClick()
        composeRule.onNodeWithTag("pending-preview-${first.id.value}").performScrollTo().performClick()
        composeRule.onNodeWithTag("pending-delete-${first.id.value}").performScrollTo().performClick()
        composeRule.runOnIdle {
            assertEquals(first.id, assignedId)
            assertEquals(first.id, previewedId)
            assertEquals(first.id, deletedId)
        }

        composeRule.onNodeWithTag("pending-card-${second.id.value}").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(formatMeasurementDateTime(second.measuredAt))
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("—").assertExists()
    }

    @Test
    fun pendingQueueShowsEmptyStateAfterLastReadingIsHandled() {
        val state = MeasurementsUiState(
            destination = MeasurementsDestination.PENDING_QUEUE,
            isLoading = false,
        )

        composeRule.setContent {
            ScaleSyncTheme {
                MeasurementsScreen(state = state, callbacks = MeasurementsCallbacks.None)
            }
        }

        composeRule.onNodeWithTag("empty-pending-queue").assertIsDisplayed()
        composeRule.onNodeWithText("Нет неназначенных измерений").assertIsDisplayed()
        composeRule.onNodeWithText("Все измерения обработаны.").assertIsDisplayed()
    }

    @Test
    fun pendingQueueClearAllRequiresConfirmationAndShowsFailureForRetry() {
        var requested = false
        var confirmed = false
        var dismissed = false
        var state by mutableStateOf(
            MeasurementsUiState(
                destination = MeasurementsDestination.PENDING_QUEUE,
                pendingMeasurements = listOf(
                    pendingItem("pending-clear", "2026-08-15T12:42:00Z", 72.4, 512),
                ),
                isLoading = false,
            ),
        )
        val callbacks = MeasurementsCallbacks.None.copy(
            onPendingClearRequested = { requested = true },
            onPendingClearConfirmed = { confirmed = true },
            onPendingClearDismissed = { dismissed = true },
        )
        composeRule.setContent { ScaleSyncTheme { MeasurementsScreen(state, callbacks) } }

        composeRule.onNodeWithTag("pending-clear-all").performClick()
        composeRule.runOnIdle { assertTrue(requested) }
        composeRule.runOnIdle {
            state = state.copy(pendingClearConfirmation = PendingClearConfirmation(count = 1))
        }
        composeRule.onNodeWithTag("pending-clear-dialog").assertIsDisplayed()
        composeRule.onNodeWithText("Будут удалены все неназначенные измерения (1) без возможности восстановления.")
            .assertIsDisplayed()
        composeRule.onNodeWithTag("pending-clear-confirm").performClick()
        composeRule.runOnIdle { assertTrue(confirmed) }

        composeRule.runOnIdle {
            state = state.copy(
                pendingClearConfirmation = PendingClearConfirmation(
                    count = 1,
                    errorMessage = com.palixander.scalesync.ui.text.uiText(
                        com.palixander.scalesync.R.string.error_clear_measurements,
                    ),
                ),
            )
        }
        composeRule.onNodeWithTag("pending-clear-error").assertIsDisplayed()
        composeRule.onNodeWithText("Отмена").performClick()
        composeRule.runOnIdle { assertTrue(dismissed) }
    }

    @Test
    fun pendingQueueClearConfirmationLocksActionsWhileClearing() {
        val state = MeasurementsUiState(
            destination = MeasurementsDestination.PENDING_QUEUE,
            pendingMeasurements = listOf(
                pendingItem("pending-clear", "2026-08-15T12:42:00Z", 72.4, 512),
            ),
            pendingClearConfirmation = PendingClearConfirmation(count = 1, isClearing = true),
            isLoading = false,
        )
        composeRule.setContent {
            ScaleSyncTheme { MeasurementsScreen(state, MeasurementsCallbacks.None) }
        }

        composeRule.onNodeWithTag("pending-clear-confirm").assertIsNotEnabled()
        composeRule.onNodeWithTag("pending-clear-all").assertIsNotEnabled()
        composeRule.onNodeWithText("Очистка…").assertIsDisplayed()
    }

    @Test
    fun processingPendingReadingShowsStatusUntilDecisionActionsBecomeReady() {
        val id = "pending-processing"
        var state by mutableStateOf(
            MeasurementsUiState(
                destination = MeasurementsDestination.PENDING_QUEUE,
                pendingMeasurements = listOf(
                    pendingItem(
                        id = id,
                        instant = "2026-08-15T12:42:00Z",
                        weight = 72.4,
                        impedance = null,
                        decisionReadiness = PreliminaryDecisionReadiness.AGGREGATING,
                    ),
                ),
                isLoading = false,
            ),
        )

        composeRule.setContent {
            ScaleSyncTheme { MeasurementsScreen(state, MeasurementsCallbacks.None) }
        }

        composeRule.onNodeWithTag("pending-processing-$id").assertIsDisplayed()
        composeRule.onNodeWithText("Обрабатывается").assertIsDisplayed()
        composeRule.onNodeWithTag("pending-assign-$id").assertDoesNotExist()
        composeRule.onNodeWithTag("pending-preview-$id").assertDoesNotExist()
        composeRule.onNodeWithTag("pending-delete-$id").assertDoesNotExist()

        composeRule.runOnIdle {
            state = state.copy(
                pendingMeasurements = listOf(
                    pendingItem(
                        id = id,
                        instant = "2026-08-15T12:42:00Z",
                        weight = 72.4,
                        impedance = 512,
                    ),
                ),
            )
        }

        composeRule.onNodeWithTag("pending-processing-$id").assertDoesNotExist()
        composeRule.onNodeWithTag("pending-assign-$id").assertIsDisplayed()
        composeRule.onNodeWithTag("pending-preview-$id").assertIsDisplayed()
        composeRule.onNodeWithTag("pending-delete-$id").assertIsDisplayed()
    }

    @Test
    fun preliminarySummaryReplacesEmptyStateAndHidesFinalActions() {
        val preliminary = preliminaryItem("pending-summary", "2026-08-15T12:42:00Z", 72.4)
        val state = MeasurementsUiState(
            isLoading = false,
            measurements = listOf(preliminary),
            summary = buildMeasurementSummary(listOf(preliminary)),
        )

        composeRule.setContent {
            ScaleSyncTheme { MeasurementsScreen(state, MeasurementsCallbacks.None) }
        }

        composeRule.onNodeWithTag("measurement-summary").assertIsDisplayed()
        composeRule.onNodeWithTag("summary-processing-status").assertIsDisplayed()
        composeRule.onNodeWithText("Пока нет измерений").assertDoesNotExist()
        composeRule.onNodeWithTag("summary-sync-status").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Действия с последним измерением")
            .assertDoesNotExist()
    }

    @Test
    fun preliminaryHistoryShowsProcessingWithoutMutationOrSyncActions() {
        val preliminary = preliminaryItem("pending-history", "2026-08-15T12:42:00Z", 72.4)
        val state = MeasurementsUiState(
            destination = MeasurementsDestination.HISTORY,
            isLoading = false,
            measurements = listOf(preliminary),
            summary = buildMeasurementSummary(listOf(preliminary)),
        )

        composeRule.setContent {
            ScaleSyncTheme { MeasurementsScreen(state, MeasurementsCallbacks.None) }
        }

        composeRule.onNodeWithTag("history-processing-status-${preliminary.presentationKey}", useUnmergedTree = true)
            .assertIsDisplayed()
        composeRule.onNodeWithTag("history-sync-${preliminary.id}").assertDoesNotExist()
        composeRule.onNodeWithTag("history-toggle-${preliminary.id}").performClick()
        composeRule.onNodeWithText("Изменить").assertDoesNotExist()
        composeRule.onNodeWithTag("history-delete-${preliminary.id}").assertDoesNotExist()
        composeRule.onNodeWithText("Повторить отправку").assertDoesNotExist()
        composeRule.onNodeWithTag("empty-history").assertDoesNotExist()
    }

    @Test
    fun preliminaryWeightOnlySummaryUpdatesToFullFinalizedMeasurement() {
        val presentationKey = "pending-reconciled"
        var state by mutableStateOf(
            preliminaryItem(presentationKey, "2026-08-15T12:42:00Z", 72.4).let { item ->
                MeasurementsUiState(
                    isLoading = false,
                    measurements = listOf(item),
                    summary = buildMeasurementSummary(listOf(item)),
                )
            },
        )

        composeRule.setContent {
            ScaleSyncTheme { MeasurementsScreen(state, MeasurementsCallbacks.None) }
        }
        composeRule.onNodeWithTag("summary-weight-only-label").assertIsDisplayed()
        composeRule.onNodeWithTag("summary-processing-status").assertIsDisplayed()

        composeRule.runOnIdle {
            val finalized = sampleItem(
                id = "room-finalized",
                instant = "2026-08-15T12:42:00Z",
                weight = 72.4,
                sync = syncedSync(),
            ).copy(presentationKey = presentationKey)
            state = state.copy(
                measurements = listOf(finalized),
                summary = buildMeasurementSummary(listOf(finalized)),
            )
        }

        composeRule.onNodeWithTag("summary-weight-only-label").assertDoesNotExist()
        composeRule.onNodeWithTag("summary-processing-status").assertDoesNotExist()
        composeRule.onNodeWithTag("summary-sync-status").assertIsDisplayed()
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
                        measuredAtEpochSecond = item.measuredAtEpochSecond,
                        draft = MeasurementEditorDraft.from(item.values),
                    ),
                )
            },
            onDeleteRequested = { id ->
                val item = state.measurements.single { it.id == id }
                state = state.copy(
                    deleteConfirmation = MeasurementDeleteConfirmation(
                        measurementId = id,
                        measuredAtEpochSecond = item.measuredAtEpochSecond,
                        weightKg = item.values.weightKg,
                    ),
                )
            },
            onDeleteConfirmed = { confirmedId = it },
        )

        composeRule.setContent {
            ScaleSyncTheme { MeasurementsScreen(state = state, callbacks = callbacks) }
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
    fun syncStatusOpensTypedDirectionsAndRetry() {
        var retriedId: String? = null
        val state = sampleState(sync = retryableSync())
        val callbacks = callbacks(onRetryRequested = { retriedId = it })

        composeRule.setContent {
            ScaleSyncTheme { MeasurementsScreen(state = state, callbacks = callbacks) }
        }

        composeRule.onNodeWithTag("summary-sync-status").performClick()
        composeRule.onNodeWithTag("measurement-sync-sheet").assertIsDisplayed()
        composeRule.onNodeWithText("Health Connect").assertIsDisplayed()
        composeRule.onNodeWithText("Huawei Health").assertDoesNotExist()
        composeRule.onNodeWithTag("sync-retry").performClick()
        assertEquals("latest", retriedId)
    }

    @Test
    fun localOnlyMeasurementHasNoSyncActionOrSheetOnSummaryAndHistory() {
        var state by mutableStateOf(sampleState(sync = localOnlySync()))

        composeRule.setContent {
            ScaleSyncTheme { MeasurementsScreen(state = state, callbacks = MeasurementsCallbacks.None) }
        }

        composeRule.onNodeWithTag("summary-sync-status").assertDoesNotExist()
        composeRule.onNodeWithTag("measurement-sync-sheet").assertDoesNotExist()
        composeRule.onNodeWithText("Локальная запись").assertDoesNotExist()
        composeRule.onNodeWithText("Только локально").assertDoesNotExist()

        composeRule.runOnIdle {
            state = state.copy(destination = MeasurementsDestination.HISTORY)
        }
        composeRule.onNodeWithTag("history-sync-latest").assertDoesNotExist()
        composeRule.onNodeWithTag("measurement-sync-sheet").assertDoesNotExist()
    }

    @Test
    fun weightOnlySummaryShowsLabelAndDashesForMissingMetrics() {
        val latest = sampleItem(
            id = "weight-only",
            instant = "2026-08-15T12:42:00.123456789Z",
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
            ScaleSyncTheme {
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
                measuredAtEpochSecond = Instant.parse("2026-08-15T12:42:00Z").epochSecond,
                draft = MeasurementEditorDraft.fromWeight(72.4),
                type = MeasurementUiType.WEIGHT_ONLY,
            ),
        )

        composeRule.setContent {
            ScaleSyncTheme {
                MeasurementsScreen(state = state, callbacks = MeasurementsCallbacks.None)
            }
        }

        composeRule.onNodeWithTag("editor-weight-only-label").assertIsDisplayed()
        composeRule.onNodeWithTag("editor-field-WEIGHT_KG").assertIsDisplayed()
        composeRule.onNodeWithTag("editor-field-IMPEDANCE_OHM").assertDoesNotExist()
        composeRule.onNodeWithText("Состав тела").assertDoesNotExist()
    }

    @Test
    fun referenceSummaryIsCompactWithoutBoundsOrInfoAndExpandedContainsExactlySixteen() {
        val latest = referenceItem("latest", "2026-08-15T12:42:00Z")
        val state = MeasurementsUiState(
            isLoading = false,
            measurements = listOf(latest),
            summary = buildMeasurementSummary(listOf(latest)),
        )

        composeRule.setContent {
            ScaleSyncTheme { MeasurementsScreen(state, MeasurementsCallbacks.None) }
        }

        composeRule.onAllNodesWithTag(ReferenceComponentTestTags.InfoButton).assertCountEquals(0)
        composeRule.onAllNodesWithTag(ReferenceComponentTestTags.Information).assertCountEquals(0)
        composeRule.onNodeWithText("Жир", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("Импеданс").assertDoesNotExist()

        composeRule.onNodeWithTag("summary-expand-metrics").performClick()

        composeRule.onAllNodesWithTag(ReferenceComponentTestTags.InfoButton, useUnmergedTree = true)
            .assertCountEquals(16)
        composeRule.onAllNodesWithTag(ReferenceComponentTestTags.Information, useUnmergedTree = true)
            .assertCountEquals(16)
    }

    @Test
    fun historyShowsWeightOnceAndKeepsMultipleExpandedCardsOpenWithSixteenMetricsEach() {
        val first = referenceItem("first", "2026-08-15T12:42:00Z")
        val second = referenceItem("second", "2026-08-14T12:42:00Z")
        val state = MeasurementsUiState(
            destination = MeasurementsDestination.HISTORY,
            isLoading = false,
            measurements = listOf(first, second),
            summary = buildMeasurementSummary(listOf(first, second)),
        )

        composeRule.setContent {
            ScaleSyncTheme { MeasurementsScreen(state, MeasurementsCallbacks.None) }
        }

        composeRule.onNodeWithTag("history-header-weight-first").assertIsDisplayed()
        composeRule.onNodeWithTag("reference-metric-first-WEIGHT").assertDoesNotExist()
        composeRule.onAllNodesWithTag(ReferenceComponentTestTags.InfoButton, useUnmergedTree = true)
            .assertCountEquals(0)

        composeRule.onNodeWithTag("history-toggle-first").performClick()

        composeRule.onNodeWithTag("history-header-weight-first").assertDoesNotExist()
        composeRule.onNodeWithTag("reference-metric-first-WEIGHT").assertIsDisplayed()
        assertEquals(16, first.referenceMetrics.size)
        first.referenceMetrics.forEach { metric ->
            composeRule.onNode(
                hasTestTag("reference-metric-first-${metric.definition.metric.name}") and
                    hasAnyDescendant(hasTestTag(ReferenceComponentTestTags.InfoButton)),
                useUnmergedTree = true,
            ).assertExists()
        }
        composeRule.onAllNodesWithTag(ReferenceComponentTestTags.Information, useUnmergedTree = true)
            .assertCountEquals(16)
        composeRule.onAllNodesWithTag(ReferenceComponentTestTags.InfoButton, useUnmergedTree = true)
            .assertCountEquals(16)

        composeRule.onNodeWithTag("history-toggle-second").performScrollTo().performClick()

        composeRule.onNodeWithTag("history-header-weight-second").assertDoesNotExist()
        composeRule.onNodeWithTag("reference-metric-second-WEIGHT").assertIsDisplayed()
        assertEquals(16, second.referenceMetrics.size)
        second.referenceMetrics.forEach { metric ->
            composeRule.onNode(
                hasTestTag("reference-metric-second-${metric.definition.metric.name}") and
                    hasAnyDescendant(hasTestTag(ReferenceComponentTestTags.InfoButton)),
                useUnmergedTree = true,
            ).assertExists()
        }

        composeRule.onNodeWithTag("history-toggle-first").performScrollTo()
        composeRule.onNodeWithTag("reference-metric-first-WEIGHT").assertIsDisplayed()
        composeRule.onNodeWithTag("history-toggle-second").performScrollTo()
        composeRule.onNodeWithTag("reference-metric-second-WEIGHT").assertIsDisplayed()
    }

    @Test
    fun weightOnlyHistoryKeepsItsSinglePlainWeightWhenExpanded() {
        val item = sampleItem(
            id = "weight-only-history",
            instant = "2026-08-15T12:42:00Z",
            weight = 72.4,
            sync = localOnlySync(),
        ).copy(
            type = MeasurementUiType.WEIGHT_ONLY,
            values = weightOnlyValues(72.4),
            referenceMetrics = emptyList(),
        )
        val state = MeasurementsUiState(
            destination = MeasurementsDestination.HISTORY,
            isLoading = false,
            measurements = listOf(item),
            summary = buildMeasurementSummary(listOf(item)),
        )

        composeRule.setContent {
            ScaleSyncTheme { MeasurementsScreen(state, MeasurementsCallbacks.None) }
        }

        composeRule.onAllNodesWithTag("history-header-weight-weight-only-history")
            .assertCountEquals(1)
        composeRule.onNodeWithTag("history-weight-only-label-weight-only-history").assertIsDisplayed()
        composeRule.onAllNodesWithTag(ReferenceComponentTestTags.InfoButton, useUnmergedTree = true)
            .assertCountEquals(0)

        composeRule.onNodeWithTag("history-toggle-weight-only-history").performClick()

        composeRule.onAllNodesWithTag("history-header-weight-weight-only-history")
            .assertCountEquals(1)
        composeRule.onNodeWithTag("history-weight-only-label-weight-only-history").assertIsDisplayed()
        composeRule.onNodeWithTag("reference-metric-weight-only-history-WEIGHT").assertDoesNotExist()
        composeRule.onAllNodesWithTag(ReferenceComponentTestTags.InfoButton, useUnmergedTree = true)
            .assertCountEquals(0)
    }

    @Test
    fun processingMeasurementNeverExposesReferenceRangesOrHelp() {
        val preliminary = preliminaryItem("processing", "2026-08-15T12:42:00Z", 72.4)
            .copy(referenceMetrics = referencePresentations(sampleValues(72.4)))
        val state = MeasurementsUiState(
            isLoading = false,
            measurements = listOf(preliminary),
            summary = buildMeasurementSummary(listOf(preliminary)),
        )

        composeRule.setContent {
            ScaleSyncTheme { MeasurementsScreen(state, MeasurementsCallbacks.None) }
        }

        composeRule.onAllNodesWithTag(ReferenceComponentTestTags.InfoButton).assertCountEquals(0)
        composeRule.onNodeWithTag("summary-processing-status").assertIsDisplayed()
    }

    @Test
    fun helpShowsManualAndRestoredWarningsClosesWhenMeasurementDisappearsAndRestoresFocus() {
        var state by mutableStateOf(
            referenceItem("legacy", "2026-08-15T12:42:00Z").copy(
                isManuallyEdited = true,
                hasRestoredRatingHeight = true,
            ).let { item ->
                MeasurementsUiState(
                    destination = MeasurementsDestination.HISTORY,
                    isLoading = false,
                    measurements = listOf(item),
                    summary = buildMeasurementSummary(listOf(item)),
                )
            },
        )

        composeRule.setContent {
            ScaleSyncTheme { MeasurementsScreen(state, MeasurementsCallbacks.None) }
        }

        composeRule.onNodeWithTag("history-toggle-legacy").performClick()
        val action = composeRule.onNodeWithContentDescription("Подробнее о показателе Вес")
        action.performScrollTo().performClick()
        composeRule.onNodeWithText(
            "Измерение изменено вручную; связанные показатели могли не пересчитаться.",
        ).assertIsDisplayed()
        composeRule.onNodeWithText(
            "Рост для старого измерения восстановлен из профиля аккаунта-владельца.",
        ).assertIsDisplayed()
        composeRule.onNodeWithText("Закрыть").performClick()
        action.assertIsFocused()

        action.performClick()
        composeRule.runOnIdle {
            state = state.copy(measurements = emptyList(), summary = null)
        }
        composeRule.onNodeWithTag(ReferenceComponentTestTags.HelpDialog).assertDoesNotExist()
    }

    @Test
    fun compactSummaryUsesFourRowsAtLargeFontAtExactThreshold() {
        setCompactSummaryAtEffectiveGridWidth(300)
        compactSummaryReferenceBounds().zipWithNext().forEach { (previous, next) ->
            assertEquals(previous.left.value, next.left.value, 1f)
            assertTrue(next.top > previous.top)
        }
    }

    @Test
    fun compactSummaryUsesFourRowsAtLargeFontOneDpBelowThreshold() {
        setCompactSummaryAtEffectiveGridWidth(299)

        val bounds = compactSummaryReferenceBounds()
        val tolerance = 1f
        bounds.zipWithNext().forEach { (previous, next) ->
            assertEquals(previous.left.value, next.left.value, tolerance)
            assertTrue(next.top > previous.top)
        }
    }

    private fun setCompactSummaryAtEffectiveGridWidth(gridWidthDp: Int) {
        val latest = referenceItem("responsive", "2026-08-15T12:42:00Z")
        val state = MeasurementsUiState(
            isLoading = false,
            measurements = listOf(latest),
            summary = buildMeasurementSummary(listOf(latest)),
        )

        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                ScaleSyncTheme {
                    // LazyColumn and summary card each consume 16.dp on both horizontal edges.
                    Box(Modifier.width((gridWidthDp + 64).dp)) {
                        MeasurementsScreen(state, MeasurementsCallbacks.None)
                    }
                }
            }
        }
        val expectedGridTag = ReferenceComponentTestTags.GridOneColumn
        val gridBounds = composeRule.onNodeWithTag(expectedGridTag).getUnclippedBoundsInRoot()
        val gridWidth = (gridBounds.right - gridBounds.left).value
        assertEquals(gridWidthDp.toFloat(), gridWidth, 1f)
    }

    private fun compactSummaryReferenceBounds() = listOf(
        "BODY_FAT_PERCENT",
        "MUSCLE_MASS",
        "WATER_PERCENT",
        "BMI",
    ).map { metric ->
        composeRule.onNodeWithTag("summary-reference-$metric")
            .getUnclippedBoundsInRoot()
    }

    @Test
    fun summaryAndMultipleHistoryExpansionSurviveSavedStateRestoration() {
        val first = referenceItem("first", "2026-08-15T12:42:00Z")
        val second = referenceItem("second", "2026-08-14T12:42:00Z")
        var state by mutableStateOf(
            MeasurementsUiState(
                isLoading = false,
                measurements = listOf(first, second),
                summary = buildMeasurementSummary(listOf(first, second)),
            ),
        )
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent {
            ScaleSyncTheme { MeasurementsScreen(state, MeasurementsCallbacks.None) }
        }

        composeRule.onNodeWithTag("summary-expand-metrics").performClick()
        restoration.emulateSavedInstanceStateRestore()
        composeRule.onAllNodesWithTag(ReferenceComponentTestTags.InfoButton, useUnmergedTree = true)
            .assertCountEquals(16)

        composeRule.runOnIdle { state = state.copy(destination = MeasurementsDestination.HISTORY) }
        composeRule.onNodeWithTag("history-toggle-first").performClick()
        composeRule.onNodeWithTag("history-toggle-second").performScrollTo().performClick()
        restoration.emulateSavedInstanceStateRestore()
        composeRule.onAllNodesWithTag(ReferenceComponentTestTags.InfoButton, useUnmergedTree = true)
            .assertCountEquals(32)
    }

    private fun callbacks(
        onPendingQueueRequested: () -> Unit = {},
        onEditRequested: (String, MeasurementEditorOrigin) -> Unit = { _, _ -> },
        onDeleteRequested: (String) -> Unit = {},
        onDeleteConfirmed: (String) -> Unit = {},
        onRetryRequested: (String) -> Unit = {},
    ) = MeasurementsCallbacks.None.copy(
        onPendingQueueRequested = onPendingQueueRequested,
        onEditRequested = onEditRequested,
        onDeleteRequested = onDeleteRequested,
        onDeleteConfirmed = onDeleteConfirmed,
        onRetryRequested = onRetryRequested,
    )

    private fun referenceItem(
        id: String,
        instant: String,
        values: MeasurementUiValues = sampleValues(72.4),
    ): MeasurementUiItem = sampleItem(
        id = id,
        instant = instant,
        weight = values.weightKg,
        sync = localOnlySync(),
        values = values,
    ).copy(
        referenceMetrics = referencePresentations(values),
        ratingHeightCm = 175.0,
        referenceAge = 36,
    )

    private fun referencePresentations(values: MeasurementUiValues) =
        ReferencePresentationFactory(
            InstrumentationRegistry.getInstrumentation().targetContext.resources,
            Locale.forLanguageTag("ru-RU"),
        ).let { factory ->
            val readings = values.toReferenceReadings()
            val context = values.toReferenceContext(
                measurementDate = LocalDate.of(2026, 8, 15),
                birthDate = LocalDate.of(1990, 6, 12),
                sex = Sex.MALE,
                ratingHeightCm = 175.0,
            )
            factory.createAll(readings, ReferenceClassifier().classifyAll(readings, context))
        }

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
            ScaleSyncTheme {
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
    ): MeasurementsUiState {
        val latest = referenceItem("latest", "2026-08-15T12:42:00Z").copy(sync = sync)
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
        isManuallyEdited: Boolean = false,
        hasProfileSyncMismatch: Boolean = false,
    ): MeasurementUiItem {
        val measuredAt = Instant.parse(instant)
        return MeasurementUiItem(
            id = id,
            measuredAtEpochSecond = measuredAt.epochSecond,
            values = values,
            sync = sync,
            type = type,
            isManuallyEdited = isManuallyEdited,
            hasProfileSyncMismatch = hasProfileSyncMismatch,
        )
    }

    private fun homeChartState(withData: Boolean = true): HomeKgChartUiState = HomeKgChartUiState(
        period = HomeKgChartPeriod(
            startDate = LocalDate.of(2026, 7, 18),
            endDateInclusive = LocalDate.of(2026, 8, 16),
            startInclusiveEpochSecond = Instant.parse("2026-07-18T00:00:00Z").epochSecond,
            endExclusiveEpochSecond = Instant.parse("2026-08-17T00:00:00Z").epochSecond,
        ),
        series = HomeKgChartSeriesCatalog.mapIndexed { index, metric ->
            HomeKgChartSeries(
                key = metric.key,
                label = metric.label,
                unit = metric.unit,
                decimalPlaces = metric.decimalPlaces,
                color = metric.color,
                points = if (withData) {
                    listOf(
                        HomeKgChartPoint(
                            measurementId = "latest",
                            measuredAtEpochSecond = Instant.parse("2026-08-15T12:42:00Z").epochSecond,
                            valueKg = 72.4 - index,
                        ),
                    )
                } else {
                    emptyList()
                },
            )
        },
        activeSeriesKeys = DefaultHomeKgChartSeriesKeys,
    )

    private fun pendingItem(
        id: String,
        instant: String,
        weight: Double,
        impedance: Int?,
        decisionReadiness: PreliminaryDecisionReadiness =
            PreliminaryDecisionReadiness.READY_FOR_DECISION,
    ): PendingMeasurementUiItem {
        val measuredAt = Instant.parse(instant)
        return PendingMeasurementUiItem(
            id = PendingMeasurementId(id),
            measuredAtEpochSecond = measuredAt.epochSecond,
            weightKg = weight,
            impedanceOhm = impedance,
            decisionReadiness = decisionReadiness,
        )
    }

    private fun preliminaryItem(
        id: String,
        instant: String,
        weight: Double,
    ): MeasurementUiItem = MeasurementUiItem(
        id = id,
        presentationKey = id,
        finalMeasurementId = null,
        sourcePendingId = PendingMeasurementId(id),
        isPreliminary = true,
        preliminaryDecisionReadiness = PreliminaryDecisionReadiness.AGGREGATING,
        measuredAtEpochSecond = Instant.parse(instant).epochSecond,
        values = weightOnlyValues(weight),
        sync = localOnlySync(),
        type = MeasurementUiType.WEIGHT_ONLY,
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

    private fun localOnlySync() = MeasurementSyncPresentation(
        state = MeasurementSyncPresentationState.LOCAL_ONLY,
        directions = listOf(
            MeasurementSyncDirectionPresentation(
                direction = MeasurementSyncDirection.HEALTH_CONNECT,
                state = MeasurementSyncPresentationState.LOCAL_ONLY,
                message = "Данные остаются на устройстве",
                canRetry = false,
            ),
        ),
        canRetry = false,
    )

}
