package com.palixander.weightogether.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
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
    fun matchesGoldenFemaleProfile() {
        val result = calculator.calculate(
            raw.copy(weightKg = 60.0, rawWeight = 12_000),
            profile.copy(heightCm = 165.0, sex = Sex.FEMALE),
        )

        assertEquals(22.038567, result.bmi, 0.000001)
        assertEquals(50.735205, result.leanBodyMassKg, 0.000001)
        assertEquals(30.857992, result.bodyFatPercent, 0.000001)
        assertEquals(18.514795, result.bodyFatMassKg, 0.000001)
        assertEquals(49.367394, result.waterPercent, 0.000001)
        assertEquals(29.620436, result.waterMassKg, 0.000001)
        assertEquals(2.471231, result.boneMassKg, 0.000001)
        assertEquals(39.013974, result.muscleMassKg, 0.000001)
        assertEquals(24.380450, result.skeletalMuscleMassKg, 0.000001)
        assertEquals(15.655896, result.proteinPercent, 0.000001)
        assertEquals(9.393538, result.proteinMassKg, 0.000001)
        assertEquals(1_188.5676, result.basalMetabolicRateKcal, 0.000001)
        assertEquals(5.265, result.visceralFatLevel, 0.000001)
        assertEquals(31, result.metabolicAge)
        assertEquals("xiaomi-foot-bia-1", result.algorithmVersion)
    }

    @Test
    fun ageChangesOnBirthdayInTheCalculatorTimeZone() {
        val birthdayProfile = profile.copy(birthDate = LocalDate.of(1990, 8, 12))
        val beforeMidnight = raw.copy(measuredAt = Instant.parse("2026-08-11T18:59:59Z"))
        val atMidnight = raw.copy(measuredAt = Instant.parse("2026-08-11T19:00:00Z"))
        val localCalculator = BodyCompositionCalculator(ZoneId.of("Asia/Yekaterinburg"))

        assertEquals(1_480.71, localCalculator.calculate(beforeMidnight, birthdayProfile).basalMetabolicRateKcal, 0.000001)
        assertEquals(1_471.734, localCalculator.calculate(atMidnight, birthdayProfile).basalMetabolicRateKcal, 0.000001)
        assertEquals(1_480.71, calculator.calculate(atMidnight, birthdayProfile).basalMetabolicRateKcal, 0.000001)
    }

    @Test
    fun agesBelowTenUseTheTenYearOldResult() {
        val atTen = calculator.calculate(raw, profile.copy(birthDate = LocalDate.of(2016, 8, 11)))

        for (birthDate in listOf(LocalDate.of(2016, 8, 12), LocalDate.of(2026, 8, 11))) {
            assertEquals(atTen, calculator.calculate(raw, profile.copy(birthDate = birthDate)))
        }
        assertNotEquals(atTen, calculator.calculate(raw, profile.copy(birthDate = LocalDate.of(2015, 8, 11))))
    }

    @Test
    fun agesAboveOneHundredUseTheHundredYearOldResult() {
        val atHundred = calculator.calculate(raw, profile.copy(birthDate = LocalDate.of(1926, 8, 11)))

        assertEquals(atHundred, calculator.calculate(raw, profile.copy(birthDate = LocalDate.of(1900, 1, 1))))
        assertNotEquals(atHundred, calculator.calculate(raw, profile.copy(birthDate = LocalDate.of(1926, 8, 12))))
    }

    @Test
    fun rejectsBirthDateAfterLocalMeasurementDate() {
        assertFailsWith<IllegalArgumentException> {
            calculator.calculate(raw, profile.copy(birthDate = LocalDate.of(2026, 8, 12)))
        }
    }

    @Test
    fun rejectsMissingOrOutOfRangeImpedance() {
        val candidates = listOf(
            raw.copy(hasImpedance = false),
            raw.copy(impedanceOhm = 0),
            raw.copy(impedanceOhm = 79),
            raw.copy(impedanceOhm = 3_001),
        )

        for (candidate in candidates) {
            assertFailsWith<IllegalArgumentException>("impedance=${candidate.impedanceOhm}, flag=${candidate.hasImpedance}") {
                calculator.calculate(candidate, profile)
            }
        }
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
        val expectedId = "7121e35e6001b48d6b5955a5f21133466fdf5972acd2b451736ac275bd90905f"
        assertEquals(expectedId, measurementId(candidate))
        assertEquals(
            expectedId,
            measurementId(
                candidate.copy(
                    deviceAddress = "AA:BB:CC:DD:EE:FF",
                    measuredAt = Instant.parse("2026-08-11T12:34:56Z"),
                    weightKg = 70.004,
                    impedanceOhm = 2_999,
                    isStable = false,
                    hasImpedance = false,
                    rawPayload = ByteArray(13) { 0x7f },
                ),
            ),
        )
        assertNotEquals(expectedId, measurementId(candidate.copy(rawWeight = 14_002)))
        assertNotEquals(expectedId, measurementId(candidate.copy(measuredAt = candidate.measuredAt.plusSeconds(1))))
        assertNotEquals(expectedId, measurementId(candidate.copy(deviceAddress = "AA:BB:CC:DD:EE:00")))
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
