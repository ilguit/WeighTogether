package com.palixander.scalesync

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.palixander.scalesync.core.Sex
import com.palixander.scalesync.domain.Account
import com.palixander.scalesync.domain.AccountId
import com.palixander.scalesync.domain.AccountProfile
import com.palixander.scalesync.domain.Pet
import com.palixander.scalesync.domain.PetId
import com.palixander.scalesync.domain.PetSpecies
import com.palixander.scalesync.domain.PetWithLatestWeight
import com.palixander.scalesync.ui.profiles.ProfileKey
import com.palixander.scalesync.ui.profiles.ProfilePresentation
import com.palixander.scalesync.ui.profiles.ProfileSelectionFallback
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import com.palixander.scalesync.measurements.MeasurementsCallbacks
import com.palixander.scalesync.measurements.MeasurementsDestination
import com.palixander.scalesync.ui.profiles.HomePetShortcutsTestTags
import com.palixander.scalesync.ui.profiles.ProfileSelectionUiState
import com.palixander.scalesync.ui.profiles.ProfileSelectorTestTags
import com.palixander.scalesync.ui.theme.HuaweiDimensions
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

class ProfileSelectorShellPaddingUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun summaryPassesPetHeaderIntoMeasurementsContent() {
        setShell(AppSection.MEASUREMENTS)

        composeRule.onNodeWithTag(ProfileSelectorTestTags.Selector).assertDoesNotExist()
        composeRule.onNodeWithTag(HomePetShortcutsTestTags.Toggle).assertExists()
    }

    @Test
    fun chartsContentStartsImmediatelyAfterProfileSelector() {
        setShell(AppSection.CHARTS)

        assertContentStartsAfterSelector("charts-content")
    }

    @Test
    fun settingsDoesNotShowProfileSelector() {
        setShell(AppSection.SETTINGS)

        composeRule.onNodeWithTag(ProfileSelectorTestTags.Selector).assertDoesNotExist()
        composeRule.onNodeWithTag(SettingsScreenTestTags.List).assertExists()
    }

    @Test
    fun measurementHistoryDoesNotShowProfileSelector() {
        setShell(
            section = AppSection.MEASUREMENTS,
            destination = MeasurementsDestination.HISTORY,
        )

        composeRule.onNodeWithTag(ProfileSelectorTestTags.Selector).assertDoesNotExist()
        composeRule.onNodeWithTag("measurements-content").getUnclippedBoundsInRoot()
    }

    @Test
    fun chartsOnlyOfferHumansAndPreserveSelectionWhenSwitching() {
        val first = human("first")
        val second = human("second")
        val selection = mutableStateOf(
            ProfileSelectionUiState(
                profiles = listOf(first, pet(), second),
                selectedKey = first.key,
                primaryAccountId = first.account.id,
            ),
        )
        var dispatched: ProfileKey? = null
        setShell(
            section = AppSection.CHARTS,
            selection = { selection.value },
            onProfileSelected = {
                dispatched = it
                selection.value = selection.value.copy(selectedKey = it)
            },
        )

        composeRule.onNodeWithTag(ProfileSelectorTestTags.pet("cat")).assertDoesNotExist()
        composeRule.onNodeWithContentDescription("cat, питомец").assertDoesNotExist()
        composeRule.onNodeWithTag(ProfileSelectorTestTags.human("first")).assertIsSelected()
        composeRule.onNodeWithTag(ProfileSelectorTestTags.human("second")).performClick()
        composeRule.onNodeWithTag(ProfileSelectorTestTags.human("second")).assertIsSelected()
        composeRule.runOnIdle {
            assertEquals(second.key, dispatched)
            assertEquals(listOf(first, pet(), second), selection.value.profiles)
        }
        assertContentStartsAfterSelector("charts-content")
    }

    @Test
    fun chartsWithOnlyPetsPreserveUnavailablePrimaryExplanation() {
        setShell(
            section = AppSection.CHARTS,
            selection = {
                ProfileSelectionUiState(
                    profiles = listOf(pet()),
                    selectedKey = null,
                    primaryAccountId = null,
                    fallback = ProfileSelectionFallback.PRIMARY_ACCOUNT_UNAVAILABLE,
                )
            },
        )

        composeRule.onNodeWithTag(ProfileSelectorTestTags.pet("cat")).assertDoesNotExist()
        composeRule.onNodeWithContentDescription("cat, питомец").assertDoesNotExist()
        composeRule.onNodeWithTag(ProfileSelectorTestTags.Fallback).assertExists()
        composeRule.onNodeWithTag("charts-content").assertExists()
    }

    @Test
    fun chartsWithNoProfilesStillShowChartsShell() {
        setShell(AppSection.CHARTS)

        composeRule.onNodeWithTag(ProfileSelectorTestTags.Selector).assertExists()
        composeRule.onNodeWithTag(ProfileSelectorTestTags.pet("cat")).assertDoesNotExist()
        composeRule.onNodeWithTag("charts-content").assertExists()
    }

    private fun human(id: String) = ProfilePresentation.Human(
        Account(
            id = AccountId(id),
            displayName = id,
            profile = AccountProfile.Complete(170.0, LocalDate.of(1990, 1, 1), Sex.MALE),
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
        ),
    )

    private fun pet() = ProfilePresentation.Pet(
        PetWithLatestWeight(
            pet = Pet(
                PetId("cat"),
                "cat",
                species = PetSpecies.CAT,
                createdAt = Instant.EPOCH,
                updatedAt = Instant.EPOCH,
            ),
            latestMeasurement = null,
        ),
    )

    private fun assertContentStartsAfterSelector(contentTag: String) {
        val selectorBottom = composeRule.onNodeWithTag(ProfileSelectorTestTags.Selector)
            .getUnclippedBoundsInRoot().bottom
        val contentTop = composeRule.onNodeWithTag(contentTag)
            .getUnclippedBoundsInRoot().top
        val actualPaddingPx = with(composeRule.density) {
            (contentTop - selectorBottom).toPx().roundToInt()
        }
        val expectedPaddingPx = with(composeRule.density) {
            HuaweiDimensions.CompactContentPadding.roundToPx()
        }

        assertTrue(
            "Expected content padding within 1 px of $expectedPaddingPx px, " +
                "but was $actualPaddingPx px",
            abs(actualPaddingPx - expectedPaddingPx) <= 1,
        )
    }

    private fun setShell(
        section: AppSection,
        destination: MeasurementsDestination = MeasurementsDestination.SUMMARY,
        selection: () -> ProfileSelectionUiState = {
            ProfileSelectionUiState(
                profiles = emptyList(),
                selectedKey = null,
                primaryAccountId = null,
            )
        },
        onProfileSelected: (ProfileKey) -> Unit = {},
    ) {
        composeRule.setContent {
            ScaleSyncScaffold(
                state = MainUiState(profilesLoaded = true),
                currentSection = section,
                profileSelection = selection(),
                onProfileSelected = onProfileSelected,
                measurementsDestination = destination,
                measurementsCallbacks = MeasurementsCallbacks.None,
                snackbarHostState = remember { SnackbarHostState() },
                onSectionSelected = {},
                onCloseProfile = {},
                onSaveProfile = {},
                onProfileHeightChanged = {},
                onProfileBirthDateChanged = {},
                onProfileSexChanged = {},
                settingsCallbacks = settingsCallbacks(),
                measurementsContent = { padding, summaryHeader ->
                    Column(
                        Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .testTag("measurements-content"),
                    ) { summaryHeader() }
                },
                chartsContent = { padding ->
                    Box(
                        Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .testTag("charts-content"),
                    )
                },
            )
        }
    }

    private fun settingsCallbacks() = SettingsCallbacks(
        onHealthConnectAuthorization = {},
        onHealthConnectAccessManagement = {},
        onManualScan = {},
        onReliabilityMode = {},
        openBatterySettings = {},
        openApplicationSettings = {},
    )
}
