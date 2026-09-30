package com.palixander.scalesync.charts

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarXAxisLabelsTest {
    @Test
    fun `singleton epoch millis range returns its exact coordinate once`() {
        val x = Instant.parse("2026-08-14T12:34:56Z").toEpochMilli().toDouble()

        assertEquals(listOf(x), calendarXAxisLabelValues(x, x, ZoneId.of("UTC"), maxLabelCount = 6))
    }

    @Test
    fun `narrow range without midnight keeps one label inside visible subrange`() {
        val zone = ZoneId.of("Asia/Yekaterinburg")
        val min = Instant.parse("2026-08-14T07:15:00Z").toEpochMilli().toDouble()
        val max = Instant.parse("2026-08-14T08:00:00Z").toEpochMilli().toDouble()

        val labels = calendarXAxisLabelValues(min, max, zone, maxLabelCount = 6)

        assertEquals(listOf(min), labels)
    }

    @Test
    fun `wide range samples local calendar boundaries and obeys strict cap`() {
        val zone = ZoneId.of("Europe/Berlin")
        val start = LocalDate.of(2024, 1, 1)
        val end = LocalDate.of(2026, 12, 31)
        val min = start.atStartOfDay(zone).toInstant().toEpochMilli().toDouble()
        val max = end.atStartOfDay(zone).toInstant().toEpochMilli().toDouble()

        val labels = calendarXAxisLabelValues(min, max, zone, maxLabelCount = 7)

        assertEquals(7, labels.size)
        assertEquals(min, labels.first(), 0.0)
        assertEquals(max, labels.last(), 0.0)
        assertTrue(labels.zipWithNext().all { (left, right) -> left < right })
        assertTrue(labels.all { x ->
            val instant = Instant.ofEpochMilli(x.toLong())
            instant == instant.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant()
        })
    }

    @Test
    fun `zoomed and scrolled subrange ignores model range outside viewport`() {
        val zone = ZoneId.of("UTC")
        val visibleStart = Instant.parse("2026-04-10T10:00:00Z").toEpochMilli().toDouble()
        val visibleEnd = Instant.parse("2026-04-13T18:00:00Z").toEpochMilli().toDouble()

        val labels = calendarXAxisLabelValues(visibleStart, visibleEnd, zone, maxLabelCount = 8)

        assertEquals(
            listOf("2026-04-11T00:00:00Z", "2026-04-12T00:00:00Z", "2026-04-13T00:00:00Z"),
            labels.map { Instant.ofEpochMilli(it.toLong()).toString() },
        )
        assertTrue(labels.all { it in visibleStart..visibleEnd })
    }

    @Test
    fun `local day labels follow spring DST boundary instead of fixed elapsed days`() {
        val zone = ZoneId.of("America/New_York")
        val start = LocalDate.of(2026, 3, 7)
        val end = LocalDate.of(2026, 3, 10)

        val labels = calendarXAxisLabelValues(
            visibleMinX = start.atStartOfDay(zone).toInstant().toEpochMilli().toDouble(),
            visibleMaxX = end.atStartOfDay(zone).toInstant().toEpochMilli().toDouble(),
            zoneId = zone,
            maxLabelCount = 8,
        )

        assertEquals(listOf(start, start.plusDays(1), start.plusDays(2), end), labels.map {
            Instant.ofEpochMilli(it.toLong()).atZone(zone).toLocalDate()
        })
        assertEquals(23 * 60 * 60 * 1000L, labels[2].toLong() - labels[1].toLong())
    }

    @Test
    fun `local day labels follow repeated fall DST boundary`() {
        val zone = ZoneId.of("America/New_York")
        val start = LocalDate.of(2026, 10, 31)
        val end = LocalDate.of(2026, 11, 3)

        val labels = calendarXAxisLabelValues(
            start.atStartOfDay(zone).toInstant().toEpochMilli().toDouble(),
            end.atStartOfDay(zone).toInstant().toEpochMilli().toDouble(),
            zone,
            maxLabelCount = 8,
        )

        assertEquals(25 * 60 * 60 * 1000L, labels[2].toLong() - labels[1].toLong())
        assertTrue(labels.all { it in labels.first()..labels.last() })
    }

    @Test
    fun `every range shape respects one-label cap`() {
        val zone = ZoneId.of("Pacific/Chatham")
        val ranges = listOf(
            1_800_000_000_000.0 to 1_800_000_000_000.0,
            1_800_000_000_000.0 to 1_800_000_001_000.0,
            1_700_000_000_000.0 to 1_900_000_000_000.0,
        )

        ranges.forEach { (min, max) ->
            val labels = calendarXAxisLabelValues(min, max, zone, maxLabelCount = 1)
            assertEquals(1, labels.size)
            assertTrue(labels.single() in min..max)
        }
    }
}
