package com.palixander.scalesync

import com.palixander.scalesync.measurements.MeasurementsDestination
import org.junit.Assert.assertEquals
import org.junit.Test

class AppSectionTest {
    @Test
    fun `sections have the expected order`() {
        assertEquals(
            listOf(AppSection.MEASUREMENTS, AppSection.CHARTS, AppSection.SETTINGS),
            AppSection.entries,
        )
    }

    @Test
    fun `measurements is the default section`() {
        assertEquals(AppSection.MEASUREMENTS, defaultAppSection)
    }

    @Test
    fun `navigation uses labelled vector icons from the redesign`() {
        assertEquals("ScaleSync.Scale", AppSection.MEASUREMENTS.icon.name)
        assertEquals("ScaleSync.Charts", AppSection.CHARTS.icon.name)
        assertEquals("ScaleSync.Settings", AppSection.SETTINGS.icon.name)
        assertEquals(AppSection.entries.size, AppSection.entries.map { it.icon.name }.distinct().size)
    }

    @Test
    fun `measurements destination is the only source for shell chrome`() {
        val expected = mapOf(
            MeasurementsDestination.SUMMARY to MeasurementsChrome(
                showTopBar = true,
                showBottomNavigation = true,
                contentUsesSafeDrawingInsets = false,
            ),
            MeasurementsDestination.HISTORY to MeasurementsChrome(
                showTopBar = false,
                showBottomNavigation = false,
                contentUsesSafeDrawingInsets = true,
            ),
            MeasurementsDestination.PENDING_QUEUE to MeasurementsChrome(
                showTopBar = false,
                showBottomNavigation = false,
                contentUsesSafeDrawingInsets = true,
            ),
            MeasurementsDestination.EDITOR to MeasurementsChrome(
                showTopBar = false,
                showBottomNavigation = false,
                contentUsesSafeDrawingInsets = true,
            ),
        )

        assertEquals(MeasurementsDestination.entries.toSet(), expected.keys)
        expected.forEach { (destination, chrome) ->
            assertEquals(destination.name, chrome, measurementsChromeFor(destination))
        }
    }
}
