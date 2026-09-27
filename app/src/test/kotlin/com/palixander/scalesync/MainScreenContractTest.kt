package com.palixander.scalesync

import com.palixander.scalesync.measurements.MeasurementsDestination
import com.palixander.scalesync.ui.text.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainScreenContractTest {
    @Test
    fun petProfileUsesFullSafeDrawingInsets() {
        assertTrue(
            shellContentUsesFullSafeDrawingInsets(
                manualDraftOpen = false,
                petProfileOpen = true,
                profileEditorOpen = false,
                currentSection = AppSection.MEASUREMENTS,
                measurementsChrome = measurementsChromeFor(MeasurementsDestination.SUMMARY),
            ),
        )
    }

    @Test
    fun rootScreenLeavesBottomInsetToAppNavigation() {
        assertFalse(
            shellContentUsesFullSafeDrawingInsets(
                manualDraftOpen = false,
                petProfileOpen = false,
                profileEditorOpen = false,
                currentSection = AppSection.MEASUREMENTS,
                measurementsChrome = measurementsChromeFor(MeasurementsDestination.SUMMARY),
            ),
        )
    }

    @Test
    fun fullScreenMeasurementsDestinationKeepsFullSafeDrawingInsets() {
        assertTrue(
            shellContentUsesFullSafeDrawingInsets(
                manualDraftOpen = false,
                petProfileOpen = false,
                profileEditorOpen = false,
                currentSection = AppSection.MEASUREMENTS,
                measurementsChrome = measurementsChromeFor(MeasurementsDestination.HISTORY),
            ),
        )
    }

    @Test
    fun petProfileBackButtonDescribesReturningToProfiles() {
        assertEquals(
            UiText.Resource(R.string.action_back_to_profiles),
            mainBackContentDescription(changelogOpen = false, petProfileOpen = true),
        )
    }

    @Test
    fun settingsDetailBackButtonDescribesReturningToSettings() {
        assertEquals(
            UiText.Resource(R.string.action_back_to_settings),
            mainBackContentDescription(
                changelogOpen = false,
                petProfileOpen = false,
                settingsDetailOpen = true,
            ),
        )
    }
}
