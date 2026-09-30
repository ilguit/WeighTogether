package com.palixander.scalesync.charts

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
