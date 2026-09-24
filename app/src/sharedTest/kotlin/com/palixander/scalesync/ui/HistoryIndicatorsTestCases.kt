package com.palixander.scalesync.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.palixander.scalesync.domain.MeasurementOrigin
import com.palixander.scalesync.measurements.*
import com.palixander.scalesync.ui.components.HuaweiSurface
import com.palixander.scalesync.ui.profiles.PetHistoryMeasurementDetails
import com.palixander.scalesync.ui.profiles.PetHistoryMeasurementUi
import com.palixander.scalesync.ui.theme.ScaleSyncTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

abstract class HistoryIndicatorsTestCases {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun originAndEditMatrixKeepsHumanAndPetGeometryAtNarrowWidthsAndLargeFont() {
        val origin = mutableStateOf(MeasurementOrigin.SCALE)
        val edited = mutableStateOf(false)
        val width = mutableStateOf(320.dp)
        val fontScale = mutableStateOf(1f)
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale.value)) {
                ScaleSyncTheme {
                    Column(Modifier.width(width.value)) {
                        MeasurementHistoryCard(human(origin.value, edited.value), false, {}, {},
                            MeasurementsCallbacks.None, { _, _ -> }, mutableMapOf())
                        HuaweiSurface(Modifier.testTag("pet-card")) {
                            PetHistoryMeasurementDetails(pet(origin.value, edited.value), true, {}, {})
                        }
                    }
                }
            }
        }
        for (w in listOf(320.dp, 360.dp)) for (scale in listOf(1f, 2f)) {
            composeRule.runOnIdle { width.value = w; fontScale.value = scale; origin.value = MeasurementOrigin.SCALE; edited.value = false }
            val humanBounds = composeRule.onNodeWithTag("history-card-human").getUnclippedBoundsInRoot()
            val petBounds = composeRule.onNodeWithTag("pet-card").getUnclippedBoundsInRoot()
            val weightBounds = composeRule.onNodeWithTag("history-header-weight-human", true).getUnclippedBoundsInRoot()
            for (o in MeasurementOrigin.entries) for (e in listOf(false, true)) {
                composeRule.runOnIdle { origin.value = o; edited.value = e }
                assertEquals(humanBounds, composeRule.onNodeWithTag("history-card-human").getUnclippedBoundsInRoot())
                assertEquals(petBounds, composeRule.onNodeWithTag("pet-card").getUnclippedBoundsInRoot())
                assertEquals(weightBounds, composeRule.onNodeWithTag("history-header-weight-human", true).getUnclippedBoundsInRoot())
                for (prefix in listOf("history-human", "pet-history-pet")) {
                    val manual = composeRule.onNodeWithTag("$prefix-manual-origin", true)
                    val edit = composeRule.onNodeWithTag("$prefix-manually-edited", true)
                    if (o == MeasurementOrigin.MANUAL) manual.assertExists() else manual.assertDoesNotExist()
                    if (e) edit.assertExists() else edit.assertDoesNotExist()
                    val button = composeRule.onNodeWithTag("$prefix-indicators")
                    if (o == MeasurementOrigin.MANUAL || e) {
                        val expected = listOfNotNull("Введено вручную".takeIf { o == MeasurementOrigin.MANUAL }, "Изменено вручную".takeIf { e }).joinToString(". ")
                        button.assertContentDescriptionEquals(expected)
                        val bounds = button.getUnclippedBoundsInRoot()
                        assertEquals(48.dp, bounds.right - bounds.left)
                        assertEquals(24.dp, bounds.bottom - bounds.top)
                    } else button.assertDoesNotExist()
                }
            }
        }
    }

    @Test
    fun expandedTouchTargetExplainsBothFlagsRestoresFocusAndLeavesOtherActionsReachable() {
        val expanded = mutableStateOf(false)
        var edits = 0
        var deletes = 0
        composeRule.setContent {
            ScaleSyncTheme {
                Column(Modifier.width(360.dp)) {
                    MeasurementHistoryCard(human(MeasurementOrigin.MANUAL, true), expanded.value,
                        { expanded.value = it }, {}, MeasurementsCallbacks.None, { _, _ -> }, mutableMapOf())
                    HuaweiSurface {
                        PetHistoryMeasurementDetails(pet(MeasurementOrigin.MANUAL, true), true, { edits++ }, { deletes++ })
                    }
                }
            }
        }
        val humanButton = composeRule.onNodeWithTag("history-human-indicators")
        // Ten pixels above the visible 24 dp control: exercise actual expanded hit testing.
        humanButton.performTouchInput { click(Offset(center.x, -10f)) }
        composeRule.onNodeWithText("Введено вручную. Изменено вручную").assertIsDisplayed()
        composeRule.onNodeWithTag("history-human-indicators-dismiss").performClick()
        humanButton.assertIsFocused()
        composeRule.runOnIdle { assertEquals(false, expanded.value) }
        composeRule.onNodeWithTag("history-toggle-human").performTouchInput { click(Offset(8f, 8f)) }
        composeRule.runOnIdle { assertEquals(true, expanded.value) }
        humanButton.assertIsDisplayed()
        composeRule.onNodeWithTag("history-manual-notice-human").assertExists()
        composeRule.onNodeWithTag("history-toggle-human").performTouchInput { click(Offset(8f, 8f)) }
        val petButton = composeRule.onNodeWithTag("pet-history-pet-indicators")
        petButton.performTouchInput { click(Offset(center.x, height + 10f)) }
        composeRule.onNodeWithText("Введено вручную. Изменено вручную").assertIsDisplayed()
        composeRule.onNodeWithTag("pet-history-pet-indicators-dismiss").performClick()
        petButton.assertIsFocused()
        composeRule.onNodeWithTag("pet-history-edit-pet").performTouchInput { click() }
        composeRule.onNodeWithTag("pet-history-delete-pet").performTouchInput { click() }
        composeRule.runOnIdle { assertEquals(1, edits); assertEquals(1, deletes) }
        val button = petButton.getUnclippedBoundsInRoot()
        val edit = composeRule.onNodeWithTag("pet-history-edit-pet").getUnclippedBoundsInRoot()
        assertTrue(button.right <= edit.left)
    }

    protected fun human(origin: MeasurementOrigin, edited: Boolean) = MeasurementUiItem(
        id = "human", measuredAtEpochSecond = 1_789_000_000,
        values = MeasurementUiValues(72.125, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null),
        sync = MeasurementSyncPresentation(MeasurementSyncPresentationState.LOCAL_ONLY, emptyList(), false),
        origin = origin, isManuallyEdited = edited,
    )

    protected fun pet(origin: MeasurementOrigin, edited: Boolean) = PetHistoryMeasurementUi(
        "pet", 1_789_000_000, "09.09.2026, 12:00", 4.125, "4,125 кг", origin, edited,
    )
}
