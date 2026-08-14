package com.example.huaweimisync.charts

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartsContractTest {
    @Test
    fun `initial range contains seven days and selects weight`() {
        val weight = ChartMetricOption("weightKg", "Вес", "кг", 2)
        val clock = Clock.fixed(Instant.parse("2026-08-14T12:00:00Z"), ZoneOffset.UTC)

        val state = ChartsUiState.initial(listOf(weight), weight.key, clock)

        assertEquals(LocalDate.of(2026, 8, 8), state.startDate)
        assertEquals(LocalDate.of(2026, 8, 14), state.endDateInclusive)
        assertEquals(setOf("weightKg"), state.selectedMetricKeys)
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

        val range = chartXRange(date, date, zone)

        assertEquals(date.atStartOfDay(zone).toInstant().toEpochMilli().toDouble(), range.minX, 0.0)
        assertEquals(
            date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli().toDouble(),
            range.maxX,
            0.0,
        )
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
    fun `marker contains full local date time line break value and complete unit`() {
        val metric = ChartMetricOption(
            key = "pressure",
            displayName = "Давление",
            unit = "миллиметры ртутного столба",
            decimalPlaces = 2,
        )
        val instant = Instant.parse("2026-08-14T21:07:00Z")

        val text = formatChartMarkerText(
            measuredAtEpochMillis = instant.toEpochMilli(),
            value = 123.4,
            metric = metric,
            zoneId = ZoneId.of("Europe/Moscow"),
            locale = Locale.US,
        )

        assertEquals("15.08.2026 00:07\n123.40 миллиметры ртутного столба", text)
    }

    @Test
    fun `ordering preserves exact sub-day intervals`() {
        val ordered = orderedChartPoints(
            listOf(
                ChartPoint(66_432L, 71.0),
                ChartPoint(1_000L, 70.0),
                ChartPoint(4_500L, 70.5),
            ),
        )

        assertEquals(listOf(1_000L, 4_500L, 66_432L), ordered.map { it.measuredAtEpochMillis })
        assertEquals(3_500L, ordered[1].measuredAtEpochMillis - ordered[0].measuredAtEpochMillis)
        assertEquals(61_932L, ordered[2].measuredAtEpochMillis - ordered[1].measuredAtEpochMillis)
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
}
