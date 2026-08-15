package com.example.huaweimisync

import com.example.huaweimisync.measurements.MeasurementEditorDraft
import com.example.huaweimisync.measurements.MeasurementEditorState
import com.example.huaweimisync.measurements.MeasurementField
import com.example.huaweimisync.measurements.MeasurementsUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
        assertEquals("Huawei.Scale", AppSection.MEASUREMENTS.icon.name)
        assertEquals("Huawei.Charts", AppSection.CHARTS.icon.name)
        assertEquals("Huawei.Settings", AppSection.SETTINGS.icon.name)
        assertEquals(AppSection.entries.size, AppSection.entries.map { it.icon.name }.distinct().size)
    }

    @Test
    fun `legacy measurements chrome hides shell while editor is open`() {
        val root = legacyMeasurementsChromePolicy.resolve(MeasurementsUiState())
        val editor = legacyMeasurementsChromePolicy.resolve(
            MeasurementsUiState(
                editor = MeasurementEditorState(
                    measurementId = "id",
                    measuredAtEpochMillis = 0L,
                    draft = MeasurementEditorDraft.fromInputs(
                        MeasurementField.entries.associateWith { "1" },
                    ),
                ),
            ),
        )

        assertTrue(root.showTopBar)
        assertTrue(root.showBottomNavigation)
        assertFalse(editor.showTopBar)
        assertFalse(editor.showBottomNavigation)
    }
}
