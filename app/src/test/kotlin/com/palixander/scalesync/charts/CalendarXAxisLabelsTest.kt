package com.palixander.scalesync.charts

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarXAxisLabelsTest {
    @Test
    fun `embedded labels keep measured half width clear of both edges across zoom and font sizes`() {
        val min = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli().toDouble()
        for (span in listOf(3_600_000.0, 11 * 86_400_000.0)) {
            for (labelWidth in listOf(40f, 100f, 200f)) {
                val labels = insetCalendarXAxisLabelValues(min, min + span, ZoneId.of("UTC"), 240f, labelWidth)
                assertTrue(labels.isNotEmpty())
                labels.forEach { value ->
                    val projected = (value - min) / span * 240
                    assertTrue(projected >= labelWidth / 2.0 - 0.001)
                    assertTrue(projected <= 240 - labelWidth / 2.0 + 0.001)
                }
            }
        }
        assertTrue(insetCalendarXAxisLabelValues(min, min + 1_000, ZoneId.of("UTC"), 40f, 100f).isEmpty())
    }

    @Test
    fun `partial days prune calendar neighbors closer than measured label width`() {
        val min = Instant.parse("2026-04-10T00:01:00Z").toEpochMilli().toDouble()
        val max = Instant.parse("2026-04-12T23:59:00Z").toEpochMilli().toDouble()

        val labels = spacedCalendarXAxisLabelValues(min, max, ZoneId.of("UTC"), 240f, 100f)

        assertEquals(listOf(Instant.parse("2026-04-11T00:00:00Z").toEpochMilli().toDouble()), labels)
    }

    @Test
    fun `DST spacing uses elapsed distance and retains later nonoverlapping candidate`() {
        val zone = ZoneId.of("America/New_York")
        val min = LocalDate.of(2026, 3, 7).atStartOfDay(zone).toInstant().toEpochMilli().toDouble()
        val max = LocalDate.of(2026, 3, 12).atStartOfDay(zone).toInstant().toEpochMilli().toDouble()

        // A 24-hour gap fits, but the 23-hour spring transition does not.
        val labels = spacedCalendarXAxisLabelValues(min, max, zone, 600f, 120f)

        assertEquals(listOf(7, 8, 10, 12), labels.map { Instant.ofEpochMilli(it.toLong()).atZone(zone).dayOfMonth })
        assertTrue(labels.zipWithNext().all { (left, right) -> (right - left) / (max - min) * 600 >= 120 })
    }

    @Test
    fun `narrow and zoomed viewports keep a label and fit any retained neighbors`() {
        val zone = ZoneId.of("UTC")
        val min = Instant.parse("2026-04-10T10:00:00Z").toEpochMilli().toDouble()
        listOf(0.0, 1_000.0, 86_400_000.0, 300_000_000.0).forEach { span ->
            listOf(40f, 240f, 1_000f).forEach { width ->
                val labels = spacedCalendarXAxisLabelValues(min, min + span, zone, width, 100f)
                assertTrue(labels.size in 1..MaxCalendarXAxisLabelCount)
                assertTrue(labels.all { it in min..(min + span) })
                assertTrue(labels.zipWithNext().all { (left, right) -> (right - left) / span * width >= 100 })
            }
        }
    }

    @Test
    fun `candidate generation has a hard cap even for excessive requested count`() {
        val labels = calendarXAxisLabelValues(0.0, 2_000_000_000_000.0, ZoneId.of("UTC"), Int.MAX_VALUE)

        assertEquals(MaxCalendarXAxisLabelCount, labels.size)
    }

    @Test
    fun `draw label count reflects width and label size within hard bounds`() {
        assertEquals(1, calendarXAxisLabelCount(120f, 200f))
        assertEquals(2, calendarXAxisLabelCount(240f, 100f))
        assertEquals(8, calendarXAxisLabelCount(2_000f, 100f))
    }

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
    fun `fractional viewport bounds never produce labels outside viewport`() {
        val midnight = Instant.parse("2026-04-11T00:00:00Z").toEpochMilli().toDouble()
        val labels = calendarXAxisLabelValues(
            visibleMinX = midnight + 0.25,
            visibleMaxX = midnight + 86_400_000.75,
            zoneId = ZoneId.of("UTC"),
            maxLabelCount = 8,
        )

        assertEquals(listOf(midnight + 86_400_000), labels)
        assertTrue(labels.all { it in (midnight + 0.25)..(midnight + 86_400_000.75) })
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
    fun `skipped civil date produces distinct strictly increasing instants`() {
        val zone = ZoneId.of("Pacific/Apia")
        val start = LocalDate.of(2011, 12, 29)
        val end = LocalDate.of(2012, 1, 1)

        val labels = calendarXAxisLabelValues(
            start.atStartOfDay(zone).toInstant().toEpochMilli().toDouble(),
            end.atStartOfDay(zone).toInstant().toEpochMilli().toDouble(),
            zone,
            maxLabelCount = 8,
        )

        assertEquals(3, labels.size)
        assertTrue(labels.zipWithNext().all { (left, right) -> left < right })
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
