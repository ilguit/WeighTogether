package com.example.huaweimisync.measurements

import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class MeasurementFormattingTest {
    @Test
    fun dateTimeUsesProvidedZoneAndLocale() {
        val instant = Instant.parse("2026-08-14T23:30:00.123456789Z")

        assertEquals(
            "15.08.2026 01:30:00.123456789",
            formatMeasurementDateTime(
                instant = instant,
                zoneId = ZoneId.of("Europe/Berlin"),
                locale = Locale.US,
            ),
        )
    }

    @Test
    fun legacyMillisFormattingStillRendersNineFractionDigits() {
        assertEquals(
            "01.01.1970 00:00:00.123000000",
            formatMeasurementDateTime(123L, ZoneId.of("UTC"), Locale.US),
        )
    }

    @Test
    fun measurementPrecisionComesFromFieldContract() {
        assertEquals("70.13", formatMeasurementValue(MeasurementField.WEIGHT_KG, 70.126, Locale.US))
        assertEquals("18.5", formatMeasurementValue(MeasurementField.BODY_FAT_PERCENT, 18.46, Locale.US))
        assertEquals("500", formatMeasurementValue(MeasurementField.IMPEDANCE_OHM, 500.0, Locale.US))
        assertEquals("70,13", formatMeasurementValue(MeasurementField.WEIGHT_KG, 70.126, Locale.GERMANY))
    }
}
