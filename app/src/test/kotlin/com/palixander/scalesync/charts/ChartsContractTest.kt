package com.palixander.scalesync.charts

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartsContractTest {
    @Test
    fun `marker line policy preserves finite limits and maps unlimited to compose sentinel`() {
        assertEquals(2, chartMarkerRenderedLineLimit(2))
        assertEquals(Int.MAX_VALUE, chartMarkerRenderedLineLimit(null))
    }

    @Test
    fun `initial range contains seven days and uses supplied metric defaults`() {
        val weight = ChartMetricOption("weightKg", "Вес", "кг", 2)
        val fat = ChartMetricOption("fatPercent", "Жир", "%", 1, PercentagePointUnit)
        val today = LocalDate.of(2026, 8, 14)

        val state = ChartsUiState.initial(
            metricOptions = listOf(weight, fat),
            defaultMetricKeys = setOf(weight.key, fat.key),
            today = today,
        )

        assertEquals(LocalDate.of(2026, 8, 8), state.startDate)
        assertEquals(LocalDate.of(2026, 8, 14), state.endDateInclusive)
        assertEquals(today, state.currentDate)
        assertEquals(setOf("weightKg", "fatPercent"), state.selectedMetricKeys)
        assertEquals(ChartRangePreset.LAST_7_DAYS, state.rangePreset)
    }

    @Test
    fun `new state owner after process recreation starts at seven days`() {
        val metric = ChartMetricOption("weightKg", "Вес", "кг", 2)
        val today = LocalDate.of(2026, 8, 14)
        val stateBeforeProcessDeath = ChartsUiState.initial(
            metricOptions = listOf(metric),
            defaultMetricKeys = setOf(metric.key),
            today = today,
        ).copy(
            startDate = LocalDate.of(2020, 1, 1),
            rangePreset = ChartRangePreset.CUSTOM,
            activeFilterSheet = ChartFilterSheet.METRICS,
            isCustomDatePickerOpen = true,
        )

        val newOwnerState = ChartsUiState.initial(
            metricOptions = stateBeforeProcessDeath.metricOptions,
            defaultMetricKeys = stateBeforeProcessDeath.selectedMetricKeys,
            today = today,
        )

        assertEquals(ChartRangePreset.LAST_7_DAYS, newOwnerState.rangePreset)
        assertEquals(LocalDate.of(2026, 8, 8), newOwnerState.startDate)
        assertEquals(LocalDate.of(2026, 8, 14), newOwnerState.endDateInclusive)
        assertNull(newOwnerState.activeFilterSheet)
        assertEquals(false, newOwnerState.isCustomDatePickerOpen)
    }

    @Test
    fun `all range presets produce exact inclusive dates`() {
        val today = LocalDate.of(2026, 8, 14)

        assertEquals(
            ChartDateRange(LocalDate.of(2026, 8, 8), today),
            ChartRangePreset.LAST_7_DAYS.rangeEndingOn(today),
        )
        assertEquals(
            ChartDateRange(LocalDate.of(2026, 7, 16), today),
            ChartRangePreset.LAST_30_DAYS.rangeEndingOn(today),
        )
        assertEquals(
            ChartDateRange(LocalDate.of(2026, 5, 15), today),
            ChartRangePreset.LAST_3_MONTHS.rangeEndingOn(today),
        )
        assertEquals(
            ChartDateRange(LocalDate.of(2026, 1, 1), today),
            ChartRangePreset.YEAR_TO_DATE.rangeEndingOn(today),
        )
        assertNull(ChartRangePreset.CUSTOM.rangeEndingOn(today))
        assertNull(ChartRangePreset.ALL.rangeEndingOn(today))
    }

    @Test
    fun `three month preset follows calendar month and leap boundaries`() {
        assertEquals(
            ChartDateRange(LocalDate.of(2024, 3, 1), LocalDate.of(2024, 5, 31)),
            ChartRangePreset.LAST_3_MONTHS.rangeEndingOn(LocalDate.of(2024, 5, 31)),
        )
        assertEquals(
            ChartDateRange(LocalDate.of(2023, 11, 30), LocalDate.of(2024, 2, 29)),
            ChartRangePreset.LAST_3_MONTHS.rangeEndingOn(LocalDate.of(2024, 2, 29)),
        )
        assertEquals(
            ChartDateRange(LocalDate.of(2023, 12, 31), LocalDate.of(2024, 3, 30)),
            ChartRangePreset.LAST_3_MONTHS.rangeEndingOn(LocalDate.of(2024, 3, 30)),
        )
    }

    @Test
    fun `short presets cross month and leap day boundaries without truncation`() {
        assertEquals(
            ChartDateRange(LocalDate.of(2024, 2, 24), LocalDate.of(2024, 3, 1)),
            ChartRangePreset.LAST_7_DAYS.rangeEndingOn(LocalDate.of(2024, 3, 1)),
        )
        assertEquals(
            ChartDateRange(LocalDate.of(2024, 2, 1), LocalDate.of(2024, 3, 1)),
            ChartRangePreset.LAST_30_DAYS.rangeEndingOn(LocalDate.of(2024, 3, 1)),
        )
    }

    @Test
    fun `picker date is interpreted through UTC`() {
        val date = LocalDate.of(2026, 8, 14)
        val pickerMillis = date.toDatePickerUtcMillis()

        assertEquals(date, datePickerUtcMillisToLocalDate(pickerMillis))
        assertEquals(
            LocalDate.of(2026, 8, 13),
            Instant.ofEpochMilli(pickerMillis).atZone(ZoneId.of("America/New_York")).toLocalDate(),
        )
    }

    @Test
    fun `one day x range starts at local midnight and ends at next local midnight`() {
        val zone = ZoneId.of("Asia/Kathmandu")
        val date = LocalDate.of(2026, 8, 14)

        val repositoryRange = inclusiveDateRangeToEpochRange(date, date, zone)
        val range = chartXRange(date, date, zone)

        assertEquals(
            date.atStartOfDay(zone).toEpochSecond(),
            repositoryRange.startInclusiveEpochSecond,
        )
        assertEquals(
            date.plusDays(1).atStartOfDay(zone).toEpochSecond(),
            repositoryRange.endExclusiveEpochSecond,
        )
        assertEquals(date.atStartOfDay(zone).toInstant().toEpochMilli().toDouble(), range.minX, 0.0)
        assertEquals(
            date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli().toDouble(),
            range.maxX,
            0.0,
        )
    }

    @Test
    fun `maximum local date has an overflow safe exclusive endpoint`() {
        val range = inclusiveDateRangeToEpochRange(LocalDate.MAX, LocalDate.MAX, ZoneId.of("UTC"))

        assertTrue(range.endExclusiveEpochSecond > range.startInclusiveEpochSecond)
    }

    @Test
    fun `multi day x range includes the complete final date`() {
        val zone = ZoneId.of("Europe/Berlin")
        val startDate = LocalDate.of(2026, 8, 10)
        val endDate = LocalDate.of(2026, 8, 14)

        val range = chartXRange(startDate, endDate, zone)
        val lastInstantOfFinalDate = endDate.plusDays(1)
            .atStartOfDay(zone)
            .toInstant()
            .minusMillis(1)
            .toEpochMilli()

        assertEquals(
            startDate.atStartOfDay(zone).toInstant().toEpochMilli().toDouble(),
            range.minX,
            0.0,
        )
        assertTrue(lastInstantOfFinalDate.toDouble() < range.maxX)
        assertEquals(
            endDate.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli().toDouble(),
            range.maxX,
            0.0,
        )
    }

    @Test
    fun `x range uses system default time zone`() {
        val previousTimeZone = TimeZone.getDefault()
        val systemZone = TimeZone.getTimeZone("Pacific/Chatham")
        try {
            TimeZone.setDefault(systemZone)
            val date = LocalDate.of(2026, 8, 14)

            val range = chartXRange(date, date)

            assertEquals(
                date.atStartOfDay(systemZone.toZoneId()).toInstant().toEpochMilli().toDouble(),
                range.minX,
                0.0,
            )
            assertEquals(
                date.plusDays(1).atStartOfDay(systemZone.toZoneId()).toInstant().toEpochMilli().toDouble(),
                range.maxX,
                0.0,
            )
        } finally {
            TimeZone.setDefault(previousTimeZone)
        }
    }

    @Test
    fun `inclusive range uses next local midnight across DST`() {
        val zone = ZoneId.of("America/New_York")
        val range = chartXRange(
            LocalDate.of(2026, 3, 8),
            LocalDate.of(2026, 3, 8),
            zone,
        )

        assertEquals(23.0 * 60 * 60 * 1000, range.maxX - range.minX, 0.0)
        assertEquals(
            LocalDate.of(2026, 3, 9).atStartOfDay(zone).toInstant().toEpochMilli().toDouble(),
            range.maxX,
            0.0,
        )
    }

    @Test
    fun `inclusive range includes repeated hour when DST ends`() {
        val zone = ZoneId.of("America/New_York")
        val range = chartXRange(
            LocalDate.of(2026, 11, 1),
            LocalDate.of(2026, 11, 1),
            zone,
        )

        assertEquals(25.0 * 60 * 60 * 1000, range.maxX - range.minX, 0.0)
    }

    @Test
    fun `marker contains full local date time line break value and complete unit`() {
        val metric = ChartMetricOption(
            key = "pressure",
            displayName = "Давление",
            unit = "миллиметры ртутного столба",
            decimalPlaces = 2,
        )
        val instant = Instant.parse("2026-08-14T21:07:00Z")

        val text = formatChartMarkerText(
            measuredAtEpochSecond = instant.epochSecond,
            value = 123.4,
            metric = metric,
            zoneId = ZoneId.of("Europe/Moscow"),
            locale = Locale.US,
        )

        assertEquals(
            "15.08.2026 00:07:00\n123.40 миллиметры ртутного столба",
            text,
        )
    }

    @Test
    fun `chart marker discards fractional seconds from measurement input`() {
        val metric = ChartMetricOption("weight", "Вес", "кг", 2)
        val instant = Instant.parse("2026-08-14T21:07:00.123456789Z")
        val point = ChartPoint(
            measuredAtEpochSecond = instant.epochSecond,
            value = 70.25,
        )

        assertEquals(
            "14.08.2026 21:07:00\n70.25 кг",
            formatChartMarkerText(point, metric, ZoneId.of("UTC"), Locale.US),
        )
    }

    @Test
    fun `ordering preserves exact sub-day intervals`() {
        val ordered = orderedChartPoints(
            listOf(
                ChartPoint(66L, 71.0),
                ChartPoint(1L, 70.0),
                ChartPoint(4L, 70.5),
            ),
        )

        assertEquals(listOf(1L, 4L, 66L), ordered.map { it.measuredAtEpochSecond })
        assertEquals(
            3_000L,
            requireNotNull(ordered[1].xEpochMillis) - requireNotNull(ordered[0].xEpochMillis),
        )
        assertEquals(
            62_000L,
            requireNotNull(ordered[2].xEpochMillis) - requireNotNull(ordered[1].xEpochMillis),
        )
    }

    @Test
    fun `summary finds current previous and delta by real timestamp`() {
        val first = ChartPoint(1_000L, 72.9)
        val previous = ChartPoint(8_000L, 72.8)
        val current = ChartPoint(9_000L, 72.4)
        val points = listOf(current, first, previous)

        val summary = chartValueSummary(points)

        assertEquals(current, currentChartPoint(points))
        assertEquals(previous, previousChartPoint(points))
        assertEquals(current, summary.current)
        assertEquals(previous, summary.previous)
        assertEquals(-0.4, summary.delta!!, 0.000_001)
        assertEquals(-0.4, chartDelta(points)!!, 0.000_001)
        assertNull(chartDelta(listOf(current)))
    }

    @Test
    fun `current and delta formatting use metric units and percentage points`() {
        val weight = ChartMetricOption("weight", "Вес", "кг", 2)
        val fat = ChartMetricOption("fat", "Жир", "%", 1, PercentagePointUnit)

        assertEquals("72.40 кг", formatChartCurrentValue(72.4, weight, Locale.US))
        assertEquals("+0.40 кг", formatChartDelta(0.4, weight, Locale.US))
        assertEquals("−0.2 п.п.", formatChartDelta(-0.2, fat, Locale.US))
        assertEquals("0.0 п.п.", formatChartDelta(0.0, fat, Locale.US))
        assertEquals("—", formatChartCurrentValue(null, weight, Locale.US))
        assertEquals("—", formatChartDelta(null, weight, Locale.US))
    }

    @Test
    fun `statistics are absent for an empty series`() {
        assertNull(chartStatistics(emptyList()))
    }

    @Test
    fun `single point is minimum maximum and average`() {
        val statistics = chartStatistics(listOf(ChartPoint(10L, 72.45)))!!

        assertEquals(72.45, statistics.minimum, 0.0)
        assertEquals(72.45, statistics.maximum, 0.0)
        assertEquals(72.45, statistics.average, 0.0)
    }

    @Test
    fun `statistics use all unsorted negative and fractional values`() {
        val statistics = chartStatistics(
            listOf(
                ChartPoint(30L, -1.25),
                ChartPoint(10L, 2.75),
                ChartPoint(20L, -0.5),
            ),
        )!!

        assertEquals(-1.25, statistics.minimum, 0.0)
        assertEquals(2.75, statistics.maximum, 0.0)
        assertEquals(1.0 / 3.0, statistics.average, 0.000_000_001)
    }

    @Test
    fun `statistics formatting rounds average with metric precision and unit`() {
        val metric = ChartMetricOption("weight", "Вес", "кг", 2)
        val statistics = chartStatistics(
            listOf(
                ChartPoint(1L, 1.234),
                ChartPoint(2L, 1.238),
            ),
        )!!

        assertEquals(1.236, statistics.average, 0.000_000_001)
        assertEquals("1.23 кг", formatChartStatistic(statistics.minimum, metric, Locale.US))
        assertEquals("1.24 кг", formatChartStatistic(statistics.maximum, metric, Locale.US))
        assertEquals("1.24 кг", formatChartStatistic(statistics.average, metric, Locale.US))
        assertEquals("—", formatChartStatistic(null, metric, Locale.US))
    }

    @Test
    fun `empty single and constant series have safe y ranges`() {
        assertNull(chartYRange(emptyList(), 2))

        val single = chartYRange(listOf(ChartPoint(1L, 70.0)), 2)!!
        val constant = chartYRange(listOf(ChartPoint(1L, 0.0), ChartPoint(2L, 0.0)), 1)!!

        assertTrue(single.min < 70.0 && single.max > 70.0)
        assertEquals(-0.1, constant.min, 0.0001)
        assertEquals(0.1, constant.max, 0.0001)
    }

    @Test
    fun `chart renderability requires two distinct timestamps`() {
        assertFalse(isChartRenderable(emptyList()))
        assertFalse(isChartRenderable(listOf(ChartPoint(1L, 70.0))))
        assertFalse(isChartRenderable(listOf(ChartPoint(1L, 70.0), ChartPoint(1L, 71.0))))
        assertTrue(isChartRenderable(listOf(ChartPoint(1L, 70.0), ChartPoint(2L, 71.0))))
    }

    @Test
    fun `chart renderability rejects epoch seconds outside millisecond range`() {
        val maximumSafeEpochSecond = Long.MAX_VALUE / 1_000L
        val minimumSafeEpochSecond = Long.MIN_VALUE / 1_000L

        assertTrue(
            isChartRenderable(
                listOf(
                    ChartPoint(minimumSafeEpochSecond, 70.0),
                    ChartPoint(maximumSafeEpochSecond, 71.0),
                ),
            ),
        )
        assertFalse(
            isChartRenderable(
                listOf(
                    ChartPoint(1L, 70.0),
                    ChartPoint(maximumSafeEpochSecond + 1L, 71.0),
                ),
            ),
        )
        assertFalse(
            isChartRenderable(
                listOf(
                    ChartPoint(minimumSafeEpochSecond - 1L, 70.0),
                    ChartPoint(1L, 71.0),
                ),
            ),
        )
    }
}
