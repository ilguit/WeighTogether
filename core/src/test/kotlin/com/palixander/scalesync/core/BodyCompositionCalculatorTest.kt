package com.palixander.scalesync.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BodyCompositionCalculatorTest {
    private val calculator = BodyCompositionCalculator(ZoneId.of("UTC"))
    private val raw = RawScaleMeasurement(
        deviceAddress = "AA:BB:CC:DD:EE:FF",
        measuredAt = Instant.parse("2026-08-11T12:34:56Z"),
        weightKg = 70.0,
        impedanceOhm = 500,
        isStable = true,
        hasImpedance = true,
        rawPayload = ByteArray(13),
    )
    private val profile = UserProfile(
        heightCm = 175.0,
        birthDate = LocalDate.of(1990, 1, 1),
        sex = Sex.MALE,
    )

    @Test
    fun matchesGoldenMaleProfile() {
        val result = calculator.calculate(raw, profile)

        assertEquals(22.8571, result.bmi, 0.0002)
        assertEquals(57.0149, result.leanBodyMassKg, 0.0002)
        assertEquals(19.6930, result.bodyFatPercent, 0.0002)
        assertEquals(55.0906, result.waterPercent, 0.0002)
        assertEquals(2.8607, result.boneMassKg, 0.0002)
        assertEquals(53.3543, result.muscleMassKg, 0.0002)
        assertEquals(30.9323, result.skeletalMuscleMassKg, 0.0002)
        assertEquals(21.1298, result.proteinPercent, 0.001)
        assertEquals(1_471.734, result.basalMetabolicRateKcal, 0.001)
        assertEquals(10.55, result.visceralFatLevel, 0.001)
        assertEquals(29, result.metabolicAge)
        assertEquals(BodyCompositionCalculator.ALGORITHM_VERSION, result.algorithmVersion)
        assertEquals(64, result.measurementId.length)
    }

    @Test
    fun measurementIdIsDeterministic() {
        val first = calculator.calculate(raw, profile)
        val second = calculator.calculate(
            raw.copy(
                impedanceOhm = 650,
                rawPayload = ByteArray(13) { 1 },
            ),
            profile,
        )

        assertEquals(first.measurementId, second.measurementId)
        assertEquals(
            measurementFingerprint(raw),
            measurementFingerprint(
                raw.copy(
                    impedanceOhm = 650,
                    isStable = false,
                    hasImpedance = false,
                    rawPayload = ByteArray(13) { 2 },
                ),
            ),
        )
    }

    @Test
    fun fingerprintUsesNormalizedMacScaleSecondAndRawWeightOnly() {
        val candidate = raw.copy(
            deviceAddress = "aa:bb:cc:dd:ee:ff",
            measuredAt = Instant.parse("2026-08-11T12:34:56.987Z"),
            rawWeight = 14_001,
        )

        assertEquals(
            "AA:BB:CC:DD:EE:FF|1786451696|14001",
            measurementFingerprint(candidate),
        )
        assertEquals(
            measurementFingerprint(candidate),
            measurementFingerprint(
                candidate.copy(
                    impedanceOhm = 2_999,
                    isStable = false,
                    hasImpedance = false,
                    rawPayload = ByteArray(13) { 0x7f },
                ),
            ),
        )
    }

    @Test
    fun rejectsUnstableMeasurement() {
        assertFailsWith<IllegalArgumentException> {
            calculator.calculate(raw.copy(isStable = false), profile)
        }
    }

    @Test
    fun calculatedMassesRemainPhysiological() {
        val result = calculator.calculate(raw, profile)

        assertTrue(result.bodyFatMassKg > 0)
        assertTrue(result.waterMassKg > 0)
        assertTrue(result.muscleMassKg < result.weightKg)
        assertTrue(result.proteinPercent in 5.0..32.0)
    }
}
