package com.palixander.weightogether.charts

import com.palixander.weightogether.ChartFilters
import com.palixander.weightogether.data.MeasurementMetric
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartsViewModelStateTest {
    private val today = LocalDate.of(2026, 8, 15)
    private val defaults = linkedSetOf(
        MeasurementMetric.WEIGHT_KG,
        MeasurementMetric.BODY_FAT_PERCENT,
    )

    @Test
    fun `presets preserve identity and exact dates in ViewModel filter state`() {
        val expected = listOf(
            ChartRangePreset.LAST_30_DAYS to LocalDate.of(2026, 7, 17),
            ChartRangePreset.LAST_3_MONTHS to LocalDate.of(2026, 5, 16),
            ChartRangePreset.YEAR_TO_DATE to LocalDate.of(2026, 1, 1),
        )

        expected.forEach { (preset, startDate) ->
            val result = initial().openFilter(ChartFilterSheet.RANGE)
                .selectRangePreset(preset, today)

            assertEquals(preset, result.rangePreset)
            assertEquals(startDate, result.startDate)
            assertEquals(today, result.endDateInclusive)
            assertNull(result.activeFilterSheet)
            assertFalse(result.isCustomDatePickerOpen)
        }
    }

    @Test
    fun `only confirmed custom dates change preset to custom`() {
        val presetState = initial()
            .selectRangePreset(ChartRangePreset.LAST_30_DAYS, today)
            .openFilter(ChartFilterSheet.RANGE)
        val pickerState = presetState.selectRangePreset(ChartRangePreset.CUSTOM, today)

        assertEquals(ChartRangePreset.LAST_30_DAYS, pickerState.rangePreset)
        assertTrue(pickerState.isCustomDatePickerOpen)
        assertNull(pickerState.activeFilterSheet)

        val confirmed = pickerState.confirmCustomDateRange(
            LocalDate.of(2026, 2, 3),
            LocalDate.of(2026, 4, 5),
        )

        assertEquals(ChartRangePreset.CUSTOM, confirmed.rangePreset)
        assertEquals(LocalDate.of(2026, 2, 3), confirmed.startDate)
        assertEquals(LocalDate.of(2026, 4, 5), confirmed.endDateInclusive)
        assertFalse(confirmed.isCustomDatePickerOpen)
    }

    @Test
    fun `invalid custom dates leave ViewModel filter state unchanged`() {
        val pickerState = initial().selectRangePreset(ChartRangePreset.CUSTOM, today)

        val result = pickerState.confirmCustomDateRange(today, today.minusDays(1))

        assertEquals(pickerState, result)
    }

    @Test
    fun `range and metric sheets and picker are driven by filter state`() {
        val range = initial().openFilter(ChartFilterSheet.RANGE)
        val metrics = range.openFilter(ChartFilterSheet.METRICS)
        val dismissedSheet = metrics.dismissFilterSheet()
        val picker = dismissedSheet.selectRangePreset(ChartRangePreset.CUSTOM, today)
        val dismissedPicker = picker.dismissCustomDatePicker()

        assertEquals(ChartFilterSheet.RANGE, range.activeFilterSheet)
        assertEquals(ChartFilterSheet.METRICS, metrics.activeFilterSheet)
        assertNull(dismissedSheet.activeFilterSheet)
        assertTrue(picker.isCustomDatePickerOpen)
        assertFalse(dismissedPicker.isCustomDatePickerOpen)
    }

    @Test
    fun `select all clear and done update ViewModel filter state`() {
        val metrics = initial().openFilter(ChartFilterSheet.METRICS)
        val all = metrics.selectMetrics(MeasurementMetric.entries.toSet())
        val cleared = all.selectMetrics(emptySet())
        val done = cleared.doneSelectingMetrics()

        assertEquals(MeasurementMetric.entries.toSet(), all.selectedMetrics)
        assertTrue(cleared.selectedMetrics.isEmpty())
        assertNull(done.activeFilterSheet)
    }

    @Test
    fun `new ViewModel filter owner starts at seven days after former custom range`() {
        val former = initial().confirmCustomDateRange(
            LocalDate.of(2020, 1, 1),
            LocalDate.of(2020, 12, 31),
        )

        val recreatedAfterProcessDeath = ChartFilters.initial(today, former.selectedMetrics)

        assertEquals(ChartRangePreset.LAST_7_DAYS, recreatedAfterProcessDeath.rangePreset)
        assertEquals(LocalDate.of(2026, 8, 9), recreatedAfterProcessDeath.startDate)
        assertEquals(today, recreatedAfterProcessDeath.endDateInclusive)
        assertNull(recreatedAfterProcessDeath.activeFilterSheet)
        assertFalse(recreatedAfterProcessDeath.isCustomDatePickerOpen)
    }

    private fun initial(): ChartFilters = ChartFilters.initial(today, defaults)
}
