package com.palixander.scalesync

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.palixander.scalesync.core.Sex
import com.palixander.scalesync.domain.PendingMeasurement
import com.palixander.scalesync.domain.PendingMeasurementId
import com.palixander.scalesync.measurements.MeasurementEditorDraft
import com.palixander.scalesync.measurements.MeasurementEditorState
import com.palixander.scalesync.measurements.MeasurementSyncDirection
import com.palixander.scalesync.measurements.MeasurementSyncDirectionPresentation
import com.palixander.scalesync.measurements.MeasurementSyncPresentation
import com.palixander.scalesync.measurements.MeasurementSyncPresentationState
import com.palixander.scalesync.measurements.MeasurementUiItem
import com.palixander.scalesync.measurements.MeasurementUiValues
import com.palixander.scalesync.measurements.MeasurementsCallbacks
import com.palixander.scalesync.measurements.MeasurementsDestination
import com.palixander.scalesync.measurements.MeasurementsNavigationState
import com.palixander.scalesync.measurements.MeasurementsScreen
import com.palixander.scalesync.measurements.MeasurementsUiState
import com.palixander.scalesync.measurements.buildMeasurementSummary
import com.palixander.scalesync.ui.routing.ResolverQueueState
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MeasurementsShellNavigationUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun summaryTopBarShowsPauseHistoryAndQueueActionsInOrderWithSemantics() {
        var pendingQueueClicks = 0
        var historyClicks = 0
        var petMeasurementClicks = 0
        var pauseClicks = 0
        val paused = mutableStateOf(false)
        val callbacks = MeasurementsCallbacks.None.copy(
            onPendingQueueRequested = { pendingQueueClicks += 1 },
            onHistoryRequested = { historyClicks += 1 },
        )

        composeRule.setContent {
            ScaleSyncScaffold(
                state = MainUiState(isExternalSyncPaused = paused.value),
                currentSection = AppSection.MEASUREMENTS,
                measurementsDestination = MeasurementsDestination.SUMMARY,
                measurementsCallbacks = callbacks,
                petMeasurementCallbacks = PetMeasurementCallbacks.None.copy(
                    onOpen = { petMeasurementClicks += 1 },
                ),
                snackbarHostState = remember { SnackbarHostState() },
                onSectionSelected = {},
                onCloseProfile = {},
                onSaveProfile = {},
                onProfileHeightChanged = {},
                onProfileBirthDateChanged = {},
                onProfileSexChanged = {},
                settingsCallbacks = settingsCallbacks(),
                onToggleExternalSyncPause = {
                    pauseClicks += 1
                    paused.value = !paused.value
                },
                measurementsContent = { _, _ -> },
                chartsContent = {},
            )
        }

        composeRule.onNodeWithContentDescription("Подключиться к весам по Bluetooth")
            .assertDoesNotExist()
        val pendingQueueAction = composeRule.onNodeWithTag(MainScreenTestTags.PendingQueueAction)
        val historyAction = composeRule.onNodeWithTag(MainScreenTestTags.HistoryAction)
        val petMeasurementAction = composeRule.onNodeWithTag(MainScreenTestTags.PetMeasurementAction)
        val externalSyncAction = composeRule.onNodeWithTag(MainScreenTestTags.ExternalSyncAction)
        petMeasurementAction.assertDoesNotExist()
        assertTrue(externalSyncAction.getUnclippedBoundsInRoot().right <= historyAction.getUnclippedBoundsInRoot().left)
        assertTrue(historyAction.getUnclippedBoundsInRoot().right <= pendingQueueAction.getUnclippedBoundsInRoot().left)
        pendingQueueAction
            .assertContentDescriptionEquals("Открыть неназначенные измерения. Очередь пуста")
            .performClick()
        historyAction
            .assertContentDescriptionEquals("Открыть историю измерений")
            .performClick()
        externalSyncAction
            .assertContentDescriptionEquals("Приостановить внешнюю синхронизацию")
            .assert(SemanticsMatcher.expectValue(ExternalSyncPausedSemanticsKey, false))
            .performClick()
        composeRule.onNodeWithTag(MainScreenTestTags.ExternalSyncAction)
            .assertContentDescriptionEquals("Возобновить внешнюю синхронизацию")
            .assert(SemanticsMatcher.expectValue(ExternalSyncPausedSemanticsKey, true))
            .performClick()
        composeRule.onNodeWithTag(MainScreenTestTags.ExternalSyncAction)
            .assertContentDescriptionEquals("Приостановить внешнюю синхронизацию")
            .assert(SemanticsMatcher.expectValue(ExternalSyncPausedSemanticsKey, false))

        composeRule.runOnIdle {
            assertEquals(1, pendingQueueClicks)
            assertEquals(1, historyClicks)
            assertEquals(0, petMeasurementClicks)
            assertEquals(2, pauseClicks)
        }
    }

    @Test
    fun summaryTopBarKeepsAllActionsUnclippedAtNarrowSupportedWidth() {
        composeRule.setContent {
            DeviceConfigurationOverride(
                override = DeviceConfigurationOverride.ForcedSize(DpSize(320.dp, 640.dp)),
            ) {
                ScaleSyncScaffold(
                    state = MainUiState(),
                    currentSection = AppSection.MEASUREMENTS,
                    measurementsDestination = MeasurementsDestination.SUMMARY,
                    measurementsCallbacks = MeasurementsCallbacks.None,
                    petMeasurementCallbacks = PetMeasurementCallbacks.None,
                    snackbarHostState = remember { SnackbarHostState() },
                    onSectionSelected = {},
                    onCloseProfile = {},
                    onSaveProfile = {},
                    onProfileHeightChanged = {},
                    onProfileBirthDateChanged = {},
                    onProfileSexChanged = {},
                    settingsCallbacks = settingsCallbacks(),
                    onToggleExternalSyncPause = {},
                    measurementsContent = { _, _ -> },
                    chartsContent = {},
                )
            }
        }

        val topBarBounds = composeRule.onNodeWithTag(MainScreenTestTags.TopBar)
            .assertIsDisplayed()
            .getUnclippedBoundsInRoot()
        val titleBounds = composeRule.onNodeWithTag(MainScreenTestTags.TopBarTitle, useUnmergedTree = true)
            .assertIsDisplayed()
            .getUnclippedBoundsInRoot()
        val actionBounds = listOf(
            MainScreenTestTags.ExternalSyncAction,
            MainScreenTestTags.HistoryAction,
            MainScreenTestTags.PendingQueueAction,
        ).map { tag ->
            composeRule.onNodeWithTag(tag)
                .assertIsDisplayed()
                .assertIsEnabled()
                .getUnclippedBoundsInRoot()
        }

        assertTrue("Top bar title must have a non-empty width", titleBounds.right > titleBounds.left)
        assertTrue("Top bar title must have a non-empty height", titleBounds.bottom > titleBounds.top)
        assertTrue("Top bar title must stay within the left edge", titleBounds.left >= topBarBounds.left)
        assertTrue("Top bar title must stay within the right edge", titleBounds.right <= topBarBounds.right)
        assertTrue("Top bar title must stay within the top edge", titleBounds.top >= topBarBounds.top)
        assertTrue("Top bar title must stay within the bottom edge", titleBounds.bottom <= topBarBounds.bottom)
        assertTrue("Top bar title must not overlap the first action", titleBounds.right <= actionBounds.first().left)

        actionBounds.forEach { bounds ->
            assertTrue("Action touch target must be at least 48 dp wide", bounds.right - bounds.left >= 48.dp)
            assertTrue("Action touch target must be at least 48 dp high", bounds.bottom - bounds.top >= 48.dp)
            assertTrue("Action must not be clipped at the left edge", bounds.left >= topBarBounds.left)
            assertTrue("Action must not be clipped at the right edge", bounds.right <= topBarBounds.right)
            assertTrue("Action must not be clipped at the top edge", bounds.top >= topBarBounds.top)
            assertTrue("Action must not be clipped at the bottom edge", bounds.bottom <= topBarBounds.bottom)
        }
        actionBounds.zipWithNext().forEach { (left, right) ->
            assertTrue("Adjacent top bar actions must not overlap", left.right <= right.left)
        }
    }

    @Test
    fun petMeasurementTopActionIsAbsentWhileBleWorkIsActive() {
        var clicks = 0

        composeRule.setContent {
            ScaleSyncScaffold(
                state = MainUiState(scanning = true),
                currentSection = AppSection.MEASUREMENTS,
                measurementsDestination = MeasurementsDestination.SUMMARY,
                measurementsCallbacks = MeasurementsCallbacks.None,
                petMeasurementCallbacks = PetMeasurementCallbacks.None.copy(
                    onOpen = { clicks += 1 },
                ),
                snackbarHostState = remember { SnackbarHostState() },
                onSectionSelected = {},
                onCloseProfile = {},
                onSaveProfile = {},
                onProfileHeightChanged = {},
                onProfileBirthDateChanged = {},
                onProfileSexChanged = {},
                settingsCallbacks = settingsCallbacks(),
                measurementsContent = { _, _ -> },
                chartsContent = {},
            )
        }

        composeRule.onNodeWithTag(MainScreenTestTags.PetMeasurementAction)
            .assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(0, clicks) }
    }

    @Test
    fun pendingQueueBadgeReactivelyShowsCountsThenReturnsToEmptyState() {
        val pendingCount = mutableStateOf(0)

        composeRule.setContent {
            ScaleSyncScaffold(
                state = MainUiState(
                    resolverQueue = ResolverQueueState(
                        pending = pendingMeasurements(pendingCount.value),
                    ),
                ),
                currentSection = AppSection.MEASUREMENTS,
                measurementsDestination = MeasurementsDestination.SUMMARY,
                measurementsCallbacks = MeasurementsCallbacks.None,
                snackbarHostState = remember { SnackbarHostState() },
                onSectionSelected = {},
                onCloseProfile = {},
                onSaveProfile = {},
                onProfileHeightChanged = {},
                onProfileBirthDateChanged = {},
                onProfileSexChanged = {},
                settingsCallbacks = settingsCallbacks(),
                onToggleExternalSyncPause = {},
                measurementsContent = { _, _ -> },
                chartsContent = {},
            )
        }

        val action = composeRule.onNodeWithTag(MainScreenTestTags.PendingQueueAction)
        val badge = composeRule.onNodeWithTag(MainScreenTestTags.PendingQueueBadge)
        action.assertContentDescriptionEquals("Открыть неназначенные измерения. Очередь пуста")
        badge.assertDoesNotExist()

        setPendingCount(pendingCount, 1)
        action.assertContentDescriptionEquals(
            "Открыть неназначенные измерения. Ожидают назначения: 1",
        )
        badge.assertIsDisplayed()

        setPendingCount(pendingCount, 9)
        action.assertContentDescriptionEquals(
            "Открыть неназначенные измерения. Ожидают назначения: 9",
        )
        badge.assertIsDisplayed()

        setPendingCount(pendingCount, 10)
        action.assertContentDescriptionEquals(
            "Открыть неназначенные измерения. Ожидают назначения: 10",
        )
        badge.assertIsDisplayed()
        composeRule.onNodeWithText("10", useUnmergedTree = true).assertIsDisplayed()

        setPendingCount(pendingCount, 135)
        action.assertContentDescriptionEquals("Открыть неназначенные измерения. Ожидают назначения: 135")
        composeRule.onNodeWithText("99+", useUnmergedTree = true).assertIsDisplayed()

        setPendingCount(pendingCount, 0)
        action.assertContentDescriptionEquals("Открыть неназначенные измерения. Очередь пуста")
        badge.assertDoesNotExist()
    }

    @Test
    fun summaryPullGestureCallsRefreshExactlyOnce() {
        var refreshCalls = 0

        composeRule.setContent {
            ScaleSyncScaffold(
                state = MainUiState(),
                currentSection = AppSection.MEASUREMENTS,
                measurementsDestination = MeasurementsDestination.SUMMARY,
                measurementsCallbacks = MeasurementsCallbacks.None,
                snackbarHostState = remember { SnackbarHostState() },
                onSectionSelected = {},
                onCloseProfile = {},
                onSaveProfile = {},
                onProfileHeightChanged = {},
                onProfileBirthDateChanged = {},
                onProfileSexChanged = {},
                settingsCallbacks = settingsCallbacks(),
                onRefreshFromScale = { refreshCalls += 1 },
                measurementsContent = { padding, summaryHeader ->
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().padding(padding),
                    ) {
                        item { Spacer(Modifier.height(2_000.dp)) }
                    }
                },
                chartsContent = {},
            )
        }

        composeRule.onNodeWithTag(MainScreenTestTags.PullToRefresh).performTouchInput {
            swipe(
                start = Offset(center.x, top + height * 0.25f),
                end = Offset(center.x, bottom - 1),
                durationMillis = 1_000,
            )
        }

        composeRule.runOnIdle { assertEquals(1, refreshCalls) }
    }

    @Test
    fun summaryPullIndicatorFollowsRefreshingState() {
        val refreshing = mutableStateOf(false)

        composeRule.setContent {
            ScaleSyncScaffold(
                state = MainUiState(isRefreshing = refreshing.value),
                currentSection = AppSection.MEASUREMENTS,
                measurementsDestination = MeasurementsDestination.SUMMARY,
                measurementsCallbacks = MeasurementsCallbacks.None,
                snackbarHostState = remember { SnackbarHostState() },
                onSectionSelected = {},
                onCloseProfile = {},
                onSaveProfile = {},
                onProfileHeightChanged = {},
                onProfileBirthDateChanged = {},
                onProfileSexChanged = {},
                settingsCallbacks = settingsCallbacks(),
                measurementsContent = { _, _ -> },
                chartsContent = {},
            )
        }

        composeRule.onNode(androidx.compose.ui.test.hasProgressBarRangeInfo(
            androidx.compose.ui.semantics.ProgressBarRangeInfo.Indeterminate,
        )).assertDoesNotExist()

        composeRule.runOnIdle { refreshing.value = true }
        composeRule.onNode(androidx.compose.ui.test.hasProgressBarRangeInfo(
            androidx.compose.ui.semantics.ProgressBarRangeInfo.Indeterminate,
        )).assertIsDisplayed()

        composeRule.runOnIdle { refreshing.value = false }
        composeRule.onNode(androidx.compose.ui.test.hasProgressBarRangeInfo(
            androidx.compose.ui.semantics.ProgressBarRangeInfo.Indeterminate,
        )).assertDoesNotExist()
    }

    @Test
    fun pendingQueueOwnsChromeAndSystemBackReturnsToSummary() {
        setMeasurementsShell(pendingCount = 1)

        composeRule.onNodeWithTag(MainScreenTestTags.PendingQueueAction).performClick()

        composeRule.onNodeWithTag("pending-queue").assertIsDisplayed()
        composeRule.onNodeWithText("Не назначено").assertIsDisplayed()
        composeRule.onNodeWithTag(MainScreenTestTags.TopBar).assertDoesNotExist()
        composeRule.onNodeWithTag(MainScreenTestTags.BottomNavigation).assertDoesNotExist()

        pressSystemBack()

        composeRule.onNodeWithTag("measurement-summary").assertIsDisplayed()
        composeRule.onNodeWithTag(MainScreenTestTags.TopBar).assertIsDisplayed()
        composeRule.onNodeWithTag(MainScreenTestTags.BottomNavigation).assertIsDisplayed()
    }

    @Test
    fun historyOwnsChromeAndSystemBackReturnsToSummary() {
        setMeasurementsShell()

        composeRule.onNodeWithTag(MainScreenTestTags.TopBar).assertIsDisplayed()
        composeRule.onNodeWithTag(MainScreenTestTags.BottomNavigation).assertIsDisplayed()
        composeRule.onNodeWithTag(MainScreenTestTags.HistoryAction).performClick()

        composeRule.onNodeWithTag("measurement-history").assertIsDisplayed()
        composeRule.onNodeWithText("История").assertIsDisplayed()
        composeRule.onNodeWithTag(MainScreenTestTags.TopBar).assertDoesNotExist()
        composeRule.onNodeWithTag(MainScreenTestTags.BottomNavigation).assertDoesNotExist()

        pressSystemBack()

        composeRule.onNodeWithTag("measurement-summary").assertIsDisplayed()
        composeRule.onNodeWithTag(MainScreenTestTags.TopBar).assertIsDisplayed()
        composeRule.onNodeWithTag(MainScreenTestTags.BottomNavigation).assertIsDisplayed()
    }

    @Test
    fun cardAndTopBarHistoryEntriesOpenTheSameHistory() {
        setMeasurementsShell()
        composeRule.onNodeWithTag("summary-history").performScrollTo().performClick()
        composeRule.onNodeWithTag("measurement-history").assertIsDisplayed()
        pressSystemBack()
        composeRule.onNodeWithTag(MainScreenTestTags.HistoryAction).performClick()
        composeRule.onNodeWithTag("measurement-history").assertIsDisplayed()
    }

    @Test
    fun editorSystemBackReturnsToSummaryAndHistoryOrigins() {
        setMeasurementsShell()

        composeRule.onNodeWithContentDescription("Действия с последним измерением").performClick()
        composeRule.onNodeWithText("Изменить").performClick()
        assertNestedEditorChrome()

        pressSystemBack()

        composeRule.onNodeWithTag("measurement-summary").assertIsDisplayed()
        composeRule.onNodeWithTag(MainScreenTestTags.BottomNavigation).assertIsDisplayed()

        composeRule.onNodeWithTag(MainScreenTestTags.HistoryAction).performClick()
        composeRule.onNodeWithTag("history-toggle-latest").performClick()
        composeRule.onNodeWithText("Изменить").performClick()
        assertNestedEditorChrome()

        pressSystemBack()

        composeRule.onNodeWithTag("measurement-history").assertIsDisplayed()
        composeRule.onNodeWithTag(MainScreenTestTags.TopBar).assertDoesNotExist()
        composeRule.onNodeWithTag(MainScreenTestTags.BottomNavigation).assertDoesNotExist()
    }

    @Test
    fun profileEditorBackHasPriorityAndPreservesMeasurementsDestination() {
        val harness = setMeasurementsShell(MeasurementsNavigationState().showHistory())
        composeRule.onNodeWithTag("measurement-history").assertIsDisplayed()

        composeRule.runOnIdle { harness.openProfileEditor() }
        composeRule.onNodeWithTag(SettingsScreenTestTags.ProfileEditor).assertIsDisplayed()
        composeRule.onNodeWithTag(MainScreenTestTags.TopBar).assertIsDisplayed()

        pressSystemBack()

        composeRule.onNodeWithTag(SettingsScreenTestTags.ProfileEditor).assertDoesNotExist()
        composeRule.onNodeWithTag("measurement-history").assertIsDisplayed()
        composeRule.onNodeWithTag(MainScreenTestTags.TopBar).assertDoesNotExist()
        assertEquals(MeasurementsDestination.HISTORY, harness.navigation.value.destination)
    }

    private fun assertNestedEditorChrome() {
        composeRule.onNodeWithTag("measurement-editor").assertIsDisplayed()
        composeRule.onNodeWithText("Изменить измерение").assertIsDisplayed()
        composeRule.onNodeWithTag(MainScreenTestTags.TopBar).assertDoesNotExist()
        composeRule.onNodeWithTag(MainScreenTestTags.BottomNavigation).assertDoesNotExist()
    }

    private fun pressSystemBack() {
        composeRule.runOnIdle {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }
    }

    private fun setMeasurementsShell(
        initialNavigation: MeasurementsNavigationState = MeasurementsNavigationState(),
        pendingCount: Int = 0,
    ): MeasurementsShellHarness {
        val navigation = mutableStateOf(initialNavigation)
        val profileEditor = mutableStateOf(ProfileEditorUiState())
        val item = sampleItem()
        val callbacks = MeasurementsCallbacks.None.copy(
            onSummaryRequested = {
                navigation.value = navigation.value.showSummary()
            },
            onHistoryRequested = {
                navigation.value = navigation.value.showHistory()
            },
            onPendingQueueRequested = {
                navigation.value = navigation.value.showPendingQueue()
            },
            onBackRequested = {
                navigation.value = navigation.value.back()
            },
            onEditRequested = { _, origin ->
                navigation.value = navigation.value.showEditor(origin)
            },
            onEditorDismissed = {
                navigation.value = navigation.value.back()
            },
        )
        val harness = MeasurementsShellHarness(navigation, profileEditor)

        composeRule.setContent {
            val currentNavigation = navigation.value
            val measurementState = MeasurementsUiState(
                destination = currentNavigation.destination,
                editorOrigin = currentNavigation.editorOrigin,
                measurements = listOf(item),
                summary = buildMeasurementSummary(listOf(item)),
                isLoading = false,
                editor = currentNavigation.takeIf {
                    it.destination == MeasurementsDestination.EDITOR
                }?.let {
                    MeasurementEditorState(
                        measurementId = item.id,
                        measuredAtEpochSecond = item.measuredAtEpochSecond,
                        draft = MeasurementEditorDraft.from(item.values),
                    )
                },
            )
            ScaleSyncScaffold(
                state = MainUiState(
                    profileEditor = profileEditor.value,
                    resolverQueue = ResolverQueueState(
                        pending = pendingMeasurements(pendingCount),
                    ),
                ),
                currentSection = AppSection.MEASUREMENTS,
                measurementsDestination = measurementState.destination,
                measurementsCallbacks = callbacks,
                snackbarHostState = remember { SnackbarHostState() },
                onSectionSelected = {},
                onCloseProfile = { profileEditor.value = ProfileEditorUiState() },
                onSaveProfile = {},
                onProfileHeightChanged = {},
                onProfileBirthDateChanged = {},
                onProfileSexChanged = {},
                settingsCallbacks = settingsCallbacks(),
                measurementsContent = { padding, summaryHeader ->
                    MeasurementsScreen(
                        summaryHeader = summaryHeader,
                        state = measurementState,
                        callbacks = callbacks,
                        modifier = Modifier.fillMaxSize().padding(padding),
                    )
                },
                chartsContent = {},
            )
        }
        return harness
    }

    private fun settingsCallbacks() = SettingsCallbacks(
        onOpenProfile = {},
        onHealthConnectAuthorization = {},
        onHealthConnectAccessManagement = {},
        onManualScan = {},
        onReliabilityMode = {},
        openBatterySettings = {},
        openApplicationSettings = {},
    )

    private fun setPendingCount(state: MutableState<Int>, count: Int) {
        composeRule.runOnIdle { state.value = count }
        composeRule.waitForIdle()
    }

    private fun pendingMeasurements(count: Int): List<PendingMeasurement> = List(count) { index ->
        PendingMeasurement(
            id = PendingMeasurementId("pending-$index"),
            deviceAddress = "AA:BB:CC:DD:EE:FF",
            measuredAt = Instant.parse("2026-08-15T12:42:00Z").plusSeconds(index.toLong()),
            weightKg = 72.4,
            impedanceOhm = 512,
            isStable = true,
            hasImpedance = true,
            rawPayload = byteArrayOf(index.toByte()),
            deduplicationHash = "hash-$index",
            enqueuedAt = Instant.parse("2026-08-15T12:43:00Z").plusSeconds(index.toLong()),
        )
    }

    private fun sampleItem(): MeasurementUiItem {
        val values = MeasurementUiValues(
            weightKg = 72.4,
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
        return MeasurementUiItem(
            id = "latest",
            measuredAtEpochSecond = Instant.parse("2026-08-15T12:42:00Z").epochSecond,
            values = values,
            sync = MeasurementSyncPresentation(
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
            ),
        )
    }
}

private class MeasurementsShellHarness(
    val navigation: MutableState<MeasurementsNavigationState>,
    private val profileEditor: MutableState<ProfileEditorUiState>,
) {
    fun openProfileEditor() {
        profileEditor.value = ProfileEditorUiState(
            isOpen = true,
            height = "180",
            birthDate = "1990-01-01",
            sex = Sex.MALE,
        )
    }
}
