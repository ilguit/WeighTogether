package com.palixander.weightogether.charts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HourlyRadialGeometryTest {
    @Test fun `normalization always returns 24 lengths and preserves zero and maximum`() {
        val lengths = normalizedHourlyLengths(listOf(0, 2, 4))
        assertEquals(24, lengths.size)
        assertEquals(0f, lengths[0])
        assertEquals(0.5f, lengths[1])
        assertEquals(1f, lengths[2])
        assertEquals(0f, lengths[23])
    }

    @Test fun `all zero counts have zero radial lengths`() {
        assertEquals(List(24) { 0f }, normalizedHourlyLengths(emptyList()))
    }

    @Test fun `hour sectors use fifteen degree steps with a two degree gap`() {
        val midnight = hourlySectorAngles(0)
        val oneAm = hourlySectorAngles(1)

        assertEquals(-96.5f, midnight.startDegrees)
        assertEquals(13f, midnight.sweepDegrees)
        assertEquals(15f, oneAm.startDegrees - midnight.startDegrees)
        assertEquals(2f, oneAm.startDegrees - (midnight.startDegrees + midnight.sweepDegrees))
    }

    @Test fun `sector radius is normalized to the maximum hourly count`() {
        val counts = MutableList(24) { 0 }.apply {
            this[8] = 3
            this[9] = 1
            this[10] = 10
        }

        val lengths = normalizedHourlyLengths(counts)

        assertEquals(0.3f, lengths[8])
        assertEquals(0.1f, lengths[9])
        assertEquals(1f, lengths[10])
        assertEquals(0f, lengths[7])
    }

    @Test fun `cardinal directions map clockwise from midnight`() {
        assertEquals(0, hourForRadialPoint(50f, 0f, 50f, 50f))
        assertEquals(6, hourForRadialPoint(100f, 50f, 50f, 50f))
        assertEquals(12, hourForRadialPoint(50f, 100f, 50f, 50f))
        assertEquals(18, hourForRadialPoint(0f, 50f, 50f, 50f))
    }

    @Test fun `points on both sides of midnight choose nearest ray`() {
        assertEquals(0, hourForRadialPoint(49f, 0f, 50f, 50f))
        assertEquals(0, hourForRadialPoint(51f, 0f, 50f, 50f))
        assertEquals(23, hourForRadialPoint(43f, 0f, 50f, 50f))
        assertEquals(1, hourForRadialPoint(57f, 0f, 50f, 50f))
    }

    @Test fun `center dead zone does not select an hour`() {
        assertNull(hourForRadialPoint(50f, 50f, 50f, 50f, deadZoneRadius = 6f))
        assertNull(hourForRadialPoint(54f, 50f, 50f, 50f, deadZoneRadius = 6f))
        assertEquals(6, hourForRadialPoint(57f, 50f, 50f, 50f, deadZoneRadius = 6f))
    }
}
