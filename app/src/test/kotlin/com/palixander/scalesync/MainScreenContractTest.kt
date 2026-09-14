package com.palixander.scalesync

import com.palixander.scalesync.measurements.MeasurementsDestination
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
            "Вернуться к профилям",
            mainBackContentDescription(changelogOpen = false, petProfileOpen = true),
        )
    }

    @Test
    fun settingsDetailBackButtonDescribesReturningToSettings() {
        assertEquals(
            "Вернуться к настройкам",
            mainBackContentDescription(
                changelogOpen = false,
                petProfileOpen = false,
                settingsDetailOpen = true,
            ),
        )
    }
}
