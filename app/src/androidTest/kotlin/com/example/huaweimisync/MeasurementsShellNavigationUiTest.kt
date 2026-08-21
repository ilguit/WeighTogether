package com.example.huaweimisync

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
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import com.example.huaweimisync.core.Sex
import com.example.huaweimisync.measurements.MeasurementEditorDraft
import com.example.huaweimisync.measurements.MeasurementEditorState
import com.example.huaweimisync.measurements.MeasurementSyncDirection
import com.example.huaweimisync.measurements.MeasurementSyncDirectionPresentation
import com.example.huaweimisync.measurements.MeasurementSyncPresentation
import com.example.huaweimisync.measurements.MeasurementSyncPresentationState
import com.example.huaweimisync.measurements.MeasurementUiItem
import com.example.huaweimisync.measurements.MeasurementUiValues
import com.example.huaweimisync.measurements.MeasurementsCallbacks
import com.example.huaweimisync.measurements.MeasurementsDestination
import com.example.huaweimisync.measurements.MeasurementsNavigationState
import com.example.huaweimisync.measurements.MeasurementsScreen
import com.example.huaweimisync.measurements.MeasurementsUiState
import com.example.huaweimisync.measurements.buildMeasurementSummary
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class MeasurementsShellNavigationUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun summaryTopBarOmitsBluetoothAndKeepsPausePlayActionWithSemantics() {
        var pauseClicks = 0
        val paused = mutableStateOf(false)

        composeRule.setContent {
            HuaweiMiSyncScaffold(
                state = MainUiState(isExternalSyncPaused = paused.value),
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
                onToggleExternalSyncPause = {
                    pauseClicks += 1
                    paused.value = !paused.value
                },
                measurementsContent = {},
                chartsContent = {},
            )
        }

        composeRule.onNodeWithContentDescription("Подключиться к весам по Bluetooth")
            .assertDoesNotExist()
        composeRule.onNodeWithTag(MainScreenTestTags.ExternalSyncAction)
            .assertContentDescriptionEquals("Приостановить внешнюю синхронизацию на 5 минут")
            .performClick()
        composeRule.onNodeWithTag(MainScreenTestTags.ExternalSyncAction)
            .assertContentDescriptionEquals("Возобновить внешнюю синхронизацию")
            .performClick()

        composeRule.runOnIdle {
            assertEquals(2, pauseClicks)
        }
    }

    @Test
    fun summaryPullGestureCallsRefreshExactlyOnce() {
        var refreshCalls = 0

        composeRule.setContent {
            HuaweiMiSyncScaffold(
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
                measurementsContent = { padding ->
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
                start = Offset(center.x, top + 1),
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
            HuaweiMiSyncScaffold(
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
                measurementsContent = {},
                chartsContent = {},
            )
        }

        composeRule.onNodeWithTag(MainScreenTestTags.PullToRefreshIndicator)
            .assertIsNotDisplayed()

        composeRule.runOnIdle { refreshing.value = true }
        composeRule.onNodeWithTag(MainScreenTestTags.PullToRefreshIndicator)
            .assertIsDisplayed()

        composeRule.runOnIdle { refreshing.value = false }
        composeRule.onNodeWithTag(MainScreenTestTags.PullToRefreshIndicator)
            .assertIsNotDisplayed()
    }

    @Test
    fun pendingQueueOwnsChromeAndSystemBackReturnsToSummary() {
        setMeasurementsShell(MeasurementsNavigationState().showPendingQueue())

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
        composeRule.onNodeWithTag("measurements-history-cta").performClick()

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
    fun editorSystemBackReturnsToSummaryAndHistoryOrigins() {
        setMeasurementsShell()

        composeRule.onNodeWithContentDescription("Действия с последним измерением").performClick()
        composeRule.onNodeWithText("Изменить").performClick()
        assertNestedEditorChrome()

        pressSystemBack()

        composeRule.onNodeWithTag("measurement-summary").assertIsDisplayed()
        composeRule.onNodeWithTag(MainScreenTestTags.BottomNavigation).assertIsDisplayed()

        composeRule.onNodeWithTag("measurements-history-cta").performClick()
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
            HuaweiMiSyncScaffold(
                state = MainUiState(profileEditor = profileEditor.value),
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
                measurementsContent = { padding ->
                    MeasurementsScreen(
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
        onHuaweiAuthorization = {},
        onHuaweiPermissionRefresh = {},
        onHealthConnectAuthorization = {},
        onHealthConnectAccessManagement = {},
        onManualTest = { _, _ -> },
        onManualScan = {},
        onReliabilityMode = {},
        openBatterySettings = {},
        openApplicationSettings = {},
    )

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
