package com.palixander.scalesync.ui.routing

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.palixander.scalesync.core.Sex
import com.palixander.scalesync.domain.PendingMeasurement
import com.palixander.scalesync.domain.PendingMeasurementId
import com.palixander.scalesync.ui.reference.ReferenceComponentTestTags
import com.palixander.scalesync.ui.reference.ReferenceSourceLauncher
import com.palixander.scalesync.ui.theme.ScaleSyncTheme
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class UnsavedMeasurementReferencePreviewTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun resultShowsExactlySixteenPreliminaryMetrics() {
        setPreview(resultState())

        composeRule.onAllNodesWithTag(ReferenceComponentTestTags.InfoButton)
            .assertCountEquals(16)
        composeRule.onAllNodesWithText("Предварительно:", substring = true, useUnmergedTree = true)
            .assertCountEquals(16)
    }

    @Test
    fun calculationFailureIsInlineFocusedRetryAndInvalidDraftNeverShowsResult() {
        val pending = pending()
        val valid = UnsavedMeasurementPreviewState(
            pending = pending,
            step = UnsavedPreviewStep.PROFILE_EDITOR,
            profileDraft = validDraft(),
            calculationError = UnsavedPreviewCalculationError.CALCULATION_FAILED,
        )
        var requested: PendingMeasurementId? = null
        setPreview(
            valid,
            callbacks = UnsavedPreviewCallbacks.None.copy(onCalculate = { requested = it }),
        )

        composeRule.onNodeWithTag(UnsavedPreviewTestTags.CalculationError)
            .assertIsDisplayed()
            .assertIsFocused()
        composeRule.onNodeWithText("Не удалось рассчитать показатели. Проверьте данные и повторите.")
            .assertIsDisplayed()
        composeRule.onNodeWithTag(UnsavedPreviewTestTags.Calculate)
            .assertIsEnabled()
            .performClick()
        composeRule.runOnIdle { assertEquals(pending.id, requested) }

        setPreview(
            valid.copy(
                profileDraft = UnsavedPreviewProfileDraft(),
                calculationError = null,
            ),
        )
        composeRule.onNodeWithTag(UnsavedPreviewTestTags.Calculate).assertIsNotEnabled()
        composeRule.onNodeWithTag(UnsavedPreviewTestTags.Result).assertDoesNotExist()
    }

    @Test
    fun helpReplacesPreviewSourceFailureKeepsHelpAndCloseRestoresInfoFocus() {
        val state = resultState()
        lateinit var snackbarHostState: SnackbarHostState
        composeRule.setContent {
            snackbarHostState = remember { SnackbarHostState() }
            Box {
                SnackbarHost(snackbarHostState)
                ScaleSyncTheme {
                    UnsavedMeasurementPreviewDialog(
                        state = state,
                        callbacks = UnsavedPreviewCallbacks.None,
                        zoneId = ZoneOffset.UTC,
                        snackbarHostState = snackbarHostState,
                        sourceLauncher = ReferenceSourceLauncher { false },
                    )
                }
            }
        }

        val weightInfo = "Подробнее о показателе: Вес"
        composeRule.onNodeWithContentDescription(weightInfo).performClick()
        composeRule.onNodeWithTag(UnsavedPreviewTestTags.Dialog).assertDoesNotExist()
        composeRule.onNodeWithTag(ReferenceComponentTestTags.HelpDialog).assertIsDisplayed()

        composeRule.onNodeWithTag(ReferenceComponentTestTags.SourceAction).performClick()
        composeRule.waitUntil { snackbarHostState.currentSnackbarData != null }
        composeRule.onNodeWithText("Не удалось открыть источник.").assertIsDisplayed()
        composeRule.onNodeWithTag(ReferenceComponentTestTags.HelpDialog).assertIsDisplayed()

        composeRule.onNodeWithText("Закрыть").performClick()
        composeRule.onNodeWithTag(UnsavedPreviewTestTags.Result).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(weightInfo).assertIsFocused()
    }

    @Test
    fun resultUsesOneColumnAtNarrowWidthAndTwoHundredPercentFont() {
        val state = resultState()
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                ScaleSyncTheme {
                    UnsavedMeasurementPreviewDialog(
                        state = state,
                        callbacks = UnsavedPreviewCallbacks.None,
                        modifier = Modifier.width(320.dp),
                        zoneId = ZoneOffset.UTC,
                    )
                }
            }
        }

        composeRule.onNodeWithTag(ReferenceComponentTestTags.GridOneColumn).assertExists()
        composeRule.onNodeWithTag(ReferenceComponentTestTags.GridTwoColumns).assertDoesNotExist()
    }

    private fun setPreview(
        state: UnsavedMeasurementPreviewState,
        callbacks: UnsavedPreviewCallbacks = UnsavedPreviewCallbacks.None,
    ) {
        composeRule.setContent {
            ScaleSyncTheme {
                UnsavedMeasurementPreviewDialog(
                    state = state,
                    callbacks = callbacks,
                    zoneId = ZoneOffset.UTC,
                )
            }
        }
    }

    private fun resultState(): UnsavedMeasurementPreviewState {
        val pending = pending()
        val result = requireNotNull(calculateUnsavedPreview(pending, validDraft(), ZoneOffset.UTC))
        return UnsavedMeasurementPreviewState(
            pending = pending,
            step = UnsavedPreviewStep.RESULT,
            profileDraft = validDraft(),
            result = result,
        )
    }

    private fun validDraft() = UnsavedPreviewProfileDraft(
        heightCm = "170",
        birthDate = LocalDate.of(1990, 1, 1),
        sex = Sex.FEMALE,
    )

    private fun pending() = PendingMeasurement(
        id = PendingMeasurementId("preview-reference"),
        deviceAddress = "AA:BB:CC:DD:EE:FF",
        measuredAt = Instant.parse("2026-08-15T09:59:00Z"),
        weightKg = 70.0,
        impedanceOhm = 500,
        isStable = true,
        hasImpedance = true,
        rawPayload = byteArrayOf(1, 2, 3),
        deduplicationHash = "preview-reference-hash",
        enqueuedAt = Instant.parse("2026-08-15T10:00:00Z"),
    )
}
