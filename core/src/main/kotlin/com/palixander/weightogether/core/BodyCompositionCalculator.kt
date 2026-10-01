package com.palixander.weightogether.core

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

/**
 * Xiaomi-compatible foot-to-foot BIA estimates.
 *
 * The equations are an independent Kotlin implementation based on published physiological
 * equations and the Apache-2.0 BodyMiScale project. They are useful for trends and are not a
 * medical diagnosis or a byte-for-byte reproduction of Xiaomi's proprietary current algorithm.
 */
class BodyCompositionCalculator(
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) {
    fun calculate(raw: RawScaleMeasurement, profile: UserProfile): BodyComposition {
        require(raw.hasFullBodyComposition) {
            "Only stable measurements with valid impedance can be calculated"
        }

        val height = profile.heightCm
        val weight = raw.weightKg
        val age = ageAt(profile.birthDate, raw.measuredAt.atZone(zoneId).toLocalDate())
        val impedance = raw.impedanceOhm.toDouble()
        val bmi = clamp(weight / square(height / 100.0), 10.0, 90.0)

        val leanBodyMass = minOf(
            (height * 9.058 / 100.0) * (height / 100.0) +
                weight * 0.32 + 12.226 - impedance * 0.0068 - age * 0.0542,
            weight * 0.98,
        )
        val bodyFatPercent = calculateBodyFatPercent(profile.sex, height, weight, age, leanBodyMass)
        val bodyFatMass = weight * bodyFatPercent / 100.0
        val waterPercent = clamp(
            (100.0 - bodyFatPercent) * 0.7 *
                if ((100.0 - bodyFatPercent) * 0.7 <= 50.0) 1.02 else 0.98,
            35.0,
            75.0,
        )
        val waterMass = weight * waterPercent / 100.0
        val boneMass = calculateBoneMass(profile.sex, leanBodyMass)
        val muscleMass = clamp(weight - bodyFatMass - boneMass, 10.0, 120.0)
        val skeletalMuscleMass = maxOf(
            0.0,
            (square(height) / impedance * 0.401) +
                (if (profile.sex == Sex.MALE) 3.825 else 0.0) - age * 0.071 + 5.102,
        )
        val proteinPercent = clamp(muscleMass / weight * 100.0 - waterPercent, 5.0, 32.0)
        val proteinMass = weight * proteinPercent / 100.0
        val bmr = calculateBmr(profile.sex, height, weight, age)
        val visceralFat = calculateVisceralFat(profile.sex, height, weight, age)
        val metabolicAge = calculateMetabolicAge(profile.sex, height, weight, age, impedance)

        return BodyComposition(
            measurementId = measurementId(raw),
            deviceAddress = raw.deviceAddress,
            measuredAt = raw.measuredAt,
            weightKg = weight,
            impedanceOhm = raw.impedanceOhm,
            bmi = bmi,
            bodyFatPercent = bodyFatPercent,
            bodyFatMassKg = bodyFatMass,
            waterPercent = waterPercent,
            waterMassKg = waterMass,
            muscleMassKg = muscleMass,
            skeletalMuscleMassKg = skeletalMuscleMass,
            boneMassKg = boneMass,
            proteinPercent = proteinPercent,
            proteinMassKg = proteinMass,
            visceralFatLevel = visceralFat,
            basalMetabolicRateKcal = bmr,
            metabolicAge = metabolicAge,
            leanBodyMassKg = leanBodyMass,
            algorithmVersion = ALGORITHM_VERSION,
        )
    }

    private fun calculateBodyFatPercent(
        sex: Sex,
        height: Double,
        weight: Double,
        age: Int,
        leanBodyMass: Double,
    ): Double {
        val (adjustment, coefficient) = when (sex) {
            Sex.MALE -> 0.8 to if (weight < 61.0) 0.98 else 1.0
            Sex.FEMALE -> {
                val adjustment = if (age <= 49) 9.25 else 7.25
                val coefficient = when {
                    weight > 60.0 -> 0.96 * if (height > 160.0) 1.03 else 1.0
                    weight < 50.0 -> 1.02 * if (height > 160.0) 1.03 else 1.0
                    else -> 1.0
                }
                adjustment to coefficient
            }
        }
        return clamp((1.0 - ((leanBodyMass - adjustment) * coefficient / weight)) * 100.0, 5.0, 75.0)
    }

    private fun calculateBoneMass(sex: Sex, leanBodyMass: Double): Double {
        val base = if (sex == Sex.FEMALE) 0.245691014 else 0.18016894
        var value = (base - leanBodyMass * 0.05158) * -1.0
        value += if (value > 2.2) 0.1 else -0.1
        if ((sex == Sex.FEMALE && value > 5.1) || (sex == Sex.MALE && value > 5.2)) {
            value = 8.0
        }
        return clamp(value, 0.5, 8.0)
    }

    private fun calculateBmr(sex: Sex, height: Double, weight: Double, age: Int): Double =
        clamp(
            when (sex) {
                Sex.MALE -> 877.8 + weight * 14.916 - height * 0.726 - age * 8.976
                Sex.FEMALE -> 864.6 + weight * 10.2036 - height * 0.39336 - age * 6.204
            },
            500.0,
            5_000.0,
        )

    private fun calculateVisceralFat(
        sex: Sex,
        height: Double,
        weight: Double,
        age: Int,
    ): Double {
        val value = when (sex) {
            Sex.MALE -> if (height < weight * 1.6 + 63.0) {
                age * 0.15 + weight * 305.0 / (height * 0.0826 * height - height * 0.4 + 48.0) - 2.9
            } else {
                age * 0.15 + weight * (height * -0.0015 + 0.765) - height * 0.143 - 5.0
            }
            Sex.FEMALE -> if (weight <= height * 0.5 - 13.0) {
                age * 0.07 + weight * (height * -0.0024 + 0.691) - height * 0.027 - 10.5
            } else {
                age * 0.07 + weight * 500.0 / (height * 1.45 + height * 0.1158 * height - 120.0) - 6.0
            }
        }
        return clamp(value, 1.0, 50.0)
    }

    private fun calculateMetabolicAge(
        sex: Sex,
        height: Double,
        weight: Double,
        age: Int,
        impedance: Double,
    ): Int {
        val value = when (sex) {
            Sex.MALE -> height * -0.7471 + weight * 0.9161 + age * 0.4184 + impedance * 0.0517 + 54.2267
            Sex.FEMALE -> height * -1.1165 + weight * 1.5784 + age * 0.4615 + impedance * 0.0415 + 83.2548
        }
        return clamp(value, 15.0, 80.0).roundToInt()
    }

    private fun ageAt(birthDate: LocalDate, date: LocalDate): Int {
        require(!birthDate.isAfter(date)) { "Birth date cannot be after measurement date" }
        return ChronoUnit.YEARS.between(birthDate, date).toInt().coerceIn(10, 100)
    }

    private fun square(value: Double): Double = value * value

    private fun clamp(value: Double, minimum: Double, maximum: Double): Double =
        value.coerceIn(minimum, maximum)

    companion object {
        const val ALGORITHM_VERSION = "xiaomi-foot-bia-1"
    }
}

/** Identity of the weighing event; deliberately excludes packet flags and impedance. */
fun measurementFingerprint(raw: RawScaleMeasurement): String = buildString {
    append(raw.deviceAddress.uppercase())
    append('|')
    append(raw.measuredAt.epochSecond)
    append('|')
    append(raw.rawWeight)
}

/** Stable opaque primary key for new measurements. */
fun measurementId(raw: RawScaleMeasurement): String = MessageDigest.getInstance("SHA-256")
    .digest(measurementFingerprint(raw).toByteArray(StandardCharsets.UTF_8))
    .joinToString("") { "%02x".format(it) }
