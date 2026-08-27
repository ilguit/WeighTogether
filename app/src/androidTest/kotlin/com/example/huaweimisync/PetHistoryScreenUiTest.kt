package com.example.huaweimisync

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.example.huaweimisync.charts.ChartRangePreset
import com.example.huaweimisync.charts.ChartSeries
import com.example.huaweimisync.domain.Pet
import com.example.huaweimisync.domain.PetId
import com.example.huaweimisync.ui.profiles.PetHistoryCallbacks
import com.example.huaweimisync.ui.profiles.PetHistoryContent
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
