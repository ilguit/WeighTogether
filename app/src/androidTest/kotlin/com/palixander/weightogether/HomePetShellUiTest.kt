package com.palixander.weightogether

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import com.palixander.weightogether.domain.Pet
import com.palixander.weightogether.domain.PetId
import com.palixander.weightogether.domain.PetWithLatestWeight
import com.palixander.weightogether.measurements.MeasurementsCallbacks
import com.palixander.weightogether.measurements.MeasurementsDestination
import com.palixander.weightogether.measurements.MeasurementsScreen
import com.palixander.weightogether.measurements.MeasurementsUiState
import com.palixander.weightogether.ui.profiles.HomePetShortcutsTestTags as Tags
import com.palixander.weightogether.ui.profiles.ProfileDestination
import com.palixander.weightogether.ui.profiles.ProfileKey
import com.palixander.weightogether.ui.profiles.ProfilePresentation
import com.palixander.weightogether.ui.profiles.ProfileSelectionFallback
import com.palixander.weightogether.ui.profiles.ProfileSelectionUiState
import com.palixander.weightogether.ui.profiles.ProfileSelectorTestTags
import com.palixander.weightogether.ui.theme.ScaleSyncTheme
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class HomePetShellUiTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test fun loadingDoesNotOfferFalseEmptyPetsAndFallbackSurvivesLoading() {
        val loaded = mutableStateOf(false)
        setShell(loaded = loaded)
        composeRule.onNodeWithTag(Tags.Toggle).assertDoesNotExist()
        composeRule.runOnIdle { loaded.value = true }
        composeRule.onNodeWithTag(Tags.Toggle).assertIsDisplayed()
        composeRule.onNodeWithTag(ProfileSelectorTestTags.Fallback).assertIsDisplayed()
        composeRule.onNodeWithTag(ProfileSelectorTestTags.Selector).assertDoesNotExist()
    }

    @Test fun emptyPetAddUsesExistingCallbackAndControlDragDoesNotRefresh() {
        var refreshes = 0
        var adds = 0
        setShell(onRefresh = { refreshes++ }, onAdd = { adds++ })
        composeRule.onNodeWithTag(Tags.Toggle).performTouchInput {
            swipe(center, center + Offset(0f, 400f), 500)
        }
        composeRule.onNodeWithTag(Tags.Add).assertIsDisplayed().performClick()
        composeRule.runOnIdle {
            assertEquals(1, adds)
            assertEquals(0, refreshes)
        }
        composeRule.onNodeWithTag(Tags.Toggle).performClick()
        // The gesture begins on the empty measurement content, outside the pet control.
        composeRule.onNodeWithText("Пока нет измерений").performTouchInput {
            swipe(center, center + Offset(0f, with(composeRule.density) { 300.dp.toPx() }), 800)
        }
        composeRule.runOnIdle { assertEquals(1, refreshes) }
    }

    @Test fun petSelectionOpensExistingShellAndSystemBackRestoresHome() {
        val now = Instant.parse("2026-01-01T00:00:00Z")
        val pet = PetWithLatestWeight(Pet(PetId("fixture-pet"), "Барсик", createdAt = now, updatedAt = now), null)
        val destination = mutableStateOf<ProfileDestination>(ProfileDestination.HumanShell)
        setShell(pets = listOf(pet), destination = destination)
        composeRule.onNodeWithTag(Tags.pet("fixture-pet")).performClick()
        composeRule.runOnIdle { assertEquals(ProfileDestination.PetShell(pet.pet.id), destination.value) }
        composeRule.onNodeWithTag(Tags.Block).assertDoesNotExist()
        composeRule.runOnIdle { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.onNodeWithTag(Tags.pet("fixture-pet")).assertIsDisplayed()
    }

    private fun setShell(
        loaded: MutableState<Boolean> = mutableStateOf(true),
        pets: List<PetWithLatestWeight> = emptyList(),
        destination: MutableState<ProfileDestination> = mutableStateOf(ProfileDestination.HumanShell),
        onRefresh: () -> Unit = {},
        onAdd: () -> Unit = {},
    ) {
        composeRule.setContent {
            ScaleSyncTheme {
                ScaleSyncScaffold(
                    state = MainUiState(profilesLoaded = loaded.value, pets = pets),
                    currentSection = AppSection.MEASUREMENTS,
                    profileSelection = ProfileSelectionUiState(
                        profiles = pets.map { ProfilePresentation.Pet(it) },
                        selectedKey = null,
                        primaryAccountId = null,
                        fallback = ProfileSelectionFallback.NO_PROFILES,
                    ),
                    profileDestination = destination.value,
                    onProfileSelected = { key ->
                        if (key is ProfileKey.Pet) destination.value = ProfileDestination.PetShell(key.petId)
                    },
                    onPetBack = { destination.value = ProfileDestination.HumanShell },
                    measurementsDestination = MeasurementsDestination.SUMMARY,
                    measurementsCallbacks = MeasurementsCallbacks.None,
                    snackbarHostState = remember { SnackbarHostState() },
                    onSectionSelected = {}, onCloseProfile = {}, onSaveProfile = {},
                    onProfileHeightChanged = {}, onProfileBirthDateChanged = {}, onProfileSexChanged = {},
                    onRefreshFromScale = onRefresh,
                    settingsCallbacks = SettingsCallbacks(
                        onHealthConnectAuthorization = {}, onHealthConnectAccessManagement = {},
                        onManualScan = {}, onReliabilityMode = {}, openBatterySettings = {},
                        openApplicationSettings = {}, onCreatePet = onAdd,
                    ),
                    measurementsContent = { padding, header ->
                        MeasurementsScreen(
                            state = MeasurementsUiState(isLoading = !loaded.value),
                            callbacks = MeasurementsCallbacks.None,
                            showAccountSelector = false,
                            summaryHeader = header,
                            modifier = Modifier.fillMaxSize().padding(padding),
                        )
                    },
                    chartsContent = {},
                )
            }
        }
    }
}
