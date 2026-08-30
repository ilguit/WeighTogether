package com.palixander.scalesync.core

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit

enum class BodyMetric {
    WEIGHT,
    IMPEDANCE,
    BMI,
    BODY_FAT_PERCENT,
    BODY_FAT_MASS,
    WATER_PERCENT,
    WATER_MASS,
    MUSCLE_MASS,
    SKELETAL_MUSCLE_MASS,
    BONE_MASS,
    PROTEIN_PERCENT,
    PROTEIN_MASS,
    VISCERAL_FAT,
    BASAL_METABOLIC_RATE,
    METABOLIC_AGE,
    LEAN_BODY_MASS,
}

enum class ReferenceVersion(
    val setLabel: String,
    val publicName: String,
) {
    SCALE_SYNC_1("ScaleSync 1", "Справочные нормы ScaleSync, версия 1"),
}

enum class ReferenceCategory {
    VERY_LOW,
    LOW,
    BELOW_NORMAL,
    NORMAL,
    ABOVE_NORMAL,
    GOOD,
    VERY_GOOD,
    HIGH,
    VERY_HIGH,
    HIGH_BMI,
    VERY_HIGH_BMI,
    YOUNGER,
    MATCHES,
    OLDER,
}

enum class UnavailableReason {
    NO_REFERENCE,
    OUT_OF_DOMAIN,
    MISSING_PROFILE_DATA,
    NO_DATA,
}

enum class ZoneBasis {
    METRIC_VALUE,
    BMI_DERIVED_WEIGHT,
    FAT_MASS_INDEX,
    SKELETAL_MUSCLE_PERCENT,
    CHRONOLOGICAL_AGE,
}

data class ReferenceZone(
    val category: ReferenceCategory,
    val lowerInclusive: Double?,
    val upperExclusive: Double?,
)

data class MetricReading(
    val metric: BodyMetric,
    val value: Double?,
)

data class ReferenceContext(
    val measurementDate: LocalDate,
    val birthDate: LocalDate?,
    val sex: Sex?,
    val heightCm: Double?,
    val weightKg: Double?,
    val impedanceOhm: Int?,
) {
    init {
        require(birthDate == null || !birthDate.isAfter(measurementDate)) {
            "Birth date cannot be after measurement date"
        }
    }
}

sealed interface MetricInterpretation {
    val metric: BodyMetric
    val version: ReferenceVersion

    data class Rated(
        override val metric: BodyMetric,
        override val version: ReferenceVersion,
        val category: ReferenceCategory,
        val zones: List<ReferenceZone>,
        val basis: ZoneBasis,
        /** Value actually compared with [zones]. It is raw except for the OMRON scale. */
        val classifiedValue: Double,
    ) : MetricInterpretation

    data class Unavailable(
        override val metric: BodyMetric,
        override val version: ReferenceVersion,
        val reason: UnavailableReason,
    ) : MetricInterpretation
}

fun chronologicalAge(birthDate: LocalDate, measurementDate: LocalDate): Int {
    require(!birthDate.isAfter(measurementDate)) { "Birth date cannot be after measurement date" }
    return ChronoUnit.YEARS.between(birthDate, measurementDate).toInt()
}

class ReferenceClassifier(
    val version: ReferenceVersion = ReferenceVersion.SCALE_SYNC_1,
) {
    fun classifyAll(
        readings: Collection<MetricReading>,
        context: ReferenceContext,
    ): Map<BodyMetric, MetricInterpretation> {
        val values = readings.associate { it.metric to it.value }
        val measurementContext = context.copy(weightKg = values[BodyMetric.WEIGHT] ?: context.weightKg)
        return BodyMetric.entries.associateWith { metric -> classify(metric, values[metric], measurementContext) }
    }

    fun classify(
        metric: BodyMetric,
        value: Double?,
        context: ReferenceContext,
    ): MetricInterpretation {
        if (value == null || !isValidValue(metric, value)) return unavailable(metric, UnavailableReason.NO_DATA)

        return when (metric) {
            BodyMetric.WEIGHT -> classifyWeight(metric, value, context)
            BodyMetric.IMPEDANCE,
            BodyMetric.WATER_MASS,
            BodyMetric.PROTEIN_MASS,
            BodyMetric.LEAN_BODY_MASS,
            -> unavailable(metric, UnavailableReason.NO_REFERENCE)
            BodyMetric.BMI -> classifyBmi(metric, context)
            BodyMetric.BODY_FAT_PERCENT -> classifyBodyFat(metric, value, context)
            BodyMetric.BODY_FAT_MASS -> classifyFatMass(metric, value, context)
            BodyMetric.WATER_PERCENT -> classifyWater(metric, value, context)
            BodyMetric.MUSCLE_MASS -> classifyMuscle(metric, value, context)
            BodyMetric.SKELETAL_MUSCLE_MASS -> classifySkeletalMuscle(metric, value, context)
            BodyMetric.BONE_MASS -> classifyBone(metric, value, context)
            BodyMetric.PROTEIN_PERCENT -> classifyProtein(metric, value, context)
            BodyMetric.VISCERAL_FAT -> classifyVisceralFat(metric, value, context)
            BodyMetric.BASAL_METABOLIC_RATE -> classifyBmr(metric, value, context)
            BodyMetric.METABOLIC_AGE -> classifyMetabolicAge(metric, value, context)
        }
    }

    private fun classifyWeight(metric: BodyMetric, value: Double, context: ReferenceContext): MetricInterpretation {
        val height = context.heightCm ?: return missingProfile(metric)
        val age = age(context) ?: return missingProfile(metric)
        if (age !in 20..99 || height !in 100.0..220.0 || value !in 10.0..150.0) return outOfDomain(metric)
        val heightMetersSquared = square(height / 100.0)
        val bmi = value / heightMetersSquared
        val zones = bmiZones().map {
            ReferenceZone(
                category = it.category,
                lowerInclusive = it.lowerInclusive?.times(heightMetersSquared),
                upperExclusive = it.upperExclusive?.times(heightMetersSquared),
            )
        }
        return rated(metric, value, zones, ZoneBasis.BMI_DERIVED_WEIGHT)
    }

    private fun classifyBmi(metric: BodyMetric, context: ReferenceContext): MetricInterpretation {
        val height = context.heightCm ?: return missingProfile(metric)
        val age = age(context) ?: return missingProfile(metric)
        val weight = context.weightKg ?: return noData(metric)
        if (age !in 20..99 || height !in 100.0..220.0 || weight !in 10.0..150.0) return outOfDomain(metric)
        return rated(metric, weight / square(height / 100.0), bmiZones(), ZoneBasis.METRIC_VALUE)
    }

    private fun classifyBodyFat(
        metric: BodyMetric,
        value: Double,
        context: ReferenceContext,
    ): MetricInterpretation {
        val common = compositionInputs(context, requireSex = true) ?: return inputFailure(metric, context, true)
        val (age, sex) = common
        val transitions = when (sex) {
            Sex.FEMALE -> when (age) {
                in 10..11 -> listOf(12.0, 21.0, 30.0, 34.0)
                in 12..13 -> listOf(15.0, 24.0, 33.0, 37.0)
                in 14..15 -> listOf(18.0, 27.0, 36.0, 40.0)
                in 16..17 -> listOf(20.0, 28.0, 37.0, 41.0)
                in 18..39 -> listOf(21.0, 28.0, 35.0, 40.0)
                in 40..59 -> listOf(22.0, 29.0, 36.0, 41.0)
                else -> listOf(23.0, 30.0, 37.0, 42.0)
            }
            Sex.MALE -> when (age) {
                in 10..17 -> listOf(7.0, 16.0, 25.0, 30.0)
                in 18..39 -> listOf(11.0, 17.0, 22.0, 27.0)
                in 40..59 -> listOf(12.0, 18.0, 23.0, 28.0)
                else -> listOf(14.0, 20.0, 25.0, 30.0)
            }
        }
        return rated(
            metric,
            value,
            zones(transitions, listOf(ReferenceCategory.VERY_LOW, ReferenceCategory.LOW, ReferenceCategory.NORMAL, ReferenceCategory.HIGH, ReferenceCategory.VERY_HIGH)),
        )
    }

    private fun classifyWater(metric: BodyMetric, value: Double, context: ReferenceContext): MetricInterpretation {
        val (_, sex) = compositionInputs(context, requireSex = true) ?: return inputFailure(metric, context, true)
        val transitions = if (sex == Sex.MALE) listOf(55.0, 65.1) else listOf(45.0, 60.1)
        return rated(metric, value, zones(transitions, listOf(ReferenceCategory.BELOW_NORMAL, ReferenceCategory.NORMAL, ReferenceCategory.GOOD)))
    }

    private fun classifyMuscle(metric: BodyMetric, value: Double, context: ReferenceContext): MetricInterpretation {
        val (_, sex) = compositionInputs(context, requireSex = true) ?: return inputFailure(metric, context, true)
        val height = context.heightCm!!
        val transitions = when (sex) {
            Sex.FEMALE -> when {
                height < 150.0 -> listOf(29.1, 34.8)
                height < 160.0 -> listOf(32.9, 37.6)
                else -> listOf(36.5, 42.6)
            }
            Sex.MALE -> when {
                height < 160.0 -> listOf(38.5, 46.6)
                height < 170.0 -> listOf(44.0, 52.5)
                else -> listOf(49.4, 59.5)
            }
        }
        return rated(metric, value, zones(transitions, lowNormalGood))
    }

    private fun classifyBone(metric: BodyMetric, value: Double, context: ReferenceContext): MetricInterpretation {
        val (_, sex) = compositionInputs(context, requireSex = true) ?: return inputFailure(metric, context, true)
        val weight = context.weightKg!!
        val transitions = when (sex) {
            Sex.FEMALE -> when {
                weight < 45.0 -> listOf(1.3, 3.6)
                weight < 60.0 -> listOf(1.5, 3.8)
                else -> listOf(1.8, 3.9)
            }
            Sex.MALE -> when {
                weight < 60.0 -> listOf(1.6, 3.9)
                weight < 75.0 -> listOf(1.9, 4.1)
                else -> listOf(2.0, 4.2)
            }
        }
        return rated(metric, value, zones(transitions, lowNormalGood))
    }

    private fun classifyProtein(metric: BodyMetric, value: Double, context: ReferenceContext): MetricInterpretation {
        compositionInputs(context, requireSex = true) ?: return inputFailure(metric, context, true)
        return rated(metric, value, zones(listOf(16.0, 20.0), lowNormalGood))
    }

    private fun classifyVisceralFat(metric: BodyMetric, value: Double, context: ReferenceContext): MetricInterpretation {
        compositionInputs(context, requireSex = true) ?: return inputFailure(metric, context, true)
        return rated(metric, value, zones(listOf(10.0, 15.0), listOf(ReferenceCategory.NORMAL, ReferenceCategory.HIGH, ReferenceCategory.VERY_HIGH)))
    }

    private fun classifyBmr(metric: BodyMetric, value: Double, context: ReferenceContext): MetricInterpretation {
        val (age, sex) = compositionInputs(context, requireSex = true) ?: return inputFailure(metric, context, true)
        val coefficient = when (sex) {
            Sex.FEMALE -> when (age) {
                in 10..29 -> 21.24
                in 30..49 -> 19.53
                else -> 18.63
            }
            Sex.MALE -> when (age) {
                in 10..29 -> 21.6
                in 30..49 -> 20.07
                else -> 19.35
            }
        }
        val threshold = context.weightKg!! * coefficient
        return rated(metric, value, zones(listOf(threshold), listOf(ReferenceCategory.BELOW_NORMAL, ReferenceCategory.NORMAL)))
    }

    private fun classifyFatMass(metric: BodyMetric, value: Double, context: ReferenceContext): MetricInterpretation {
        val height = context.heightCm ?: return missingProfile(metric)
        val sex = context.sex ?: return missingProfile(metric)
        val age = age(context) ?: return missingProfile(metric)
        if (age !in 18..98 || height !in 100.0..220.0) return outOfDomain(metric)
        val limits = when (sex) {
            Sex.MALE -> when (age) {
                in 18..34 -> 2.2 to 7.0
                in 35..54 -> 2.5 to 7.9
                in 55..75 -> 2.8 to 9.3
                else -> 3.7 to 10.1
            }
            Sex.FEMALE -> when (age) {
                in 18..34 -> 3.5 to 8.7
                in 35..54 -> 3.4 to 9.9
                in 55..75 -> 4.5 to 13.5
                else -> 4.9 to 14.3
            }
        }
        val heightSquared = square(height / 100.0)
        val massZones = zones(
            listOf(limits.first * heightSquared, limits.second * heightSquared),
            listOf(ReferenceCategory.BELOW_NORMAL, ReferenceCategory.NORMAL, ReferenceCategory.ABOVE_NORMAL),
        )
        return rated(metric, value, massZones, ZoneBasis.FAT_MASS_INDEX)
    }

    private fun classifySkeletalMuscle(
        metric: BodyMetric,
        value: Double,
        context: ReferenceContext,
    ): MetricInterpretation {
        val (age, sex) = compositionInputs(context, requireSex = true, ageRange = 18..80)
            ?: return inputFailure(metric, context, true, 18..80)
        val transitions = when (sex) {
            Sex.FEMALE -> when (age) {
                in 18..39 -> listOf(24.3, 30.4, 35.4)
                in 40..59 -> listOf(24.1, 30.2, 35.2)
                else -> listOf(23.9, 30.0, 35.0)
            }
            Sex.MALE -> when (age) {
                in 18..39 -> listOf(33.3, 39.4, 44.1)
                in 40..59 -> listOf(33.1, 39.2, 43.9)
                else -> listOf(32.9, 39.0, 43.7)
            }
        }
        val percent = value * 100.0 / context.weightKg!!
        val decimalPercent = BigDecimal.valueOf(percent).setScale(1, RoundingMode.HALF_UP).toDouble()
        return rated(
            metric,
            decimalPercent,
            zones(transitions, listOf(ReferenceCategory.BELOW_NORMAL, ReferenceCategory.NORMAL, ReferenceCategory.GOOD, ReferenceCategory.VERY_GOOD)),
            ZoneBasis.SKELETAL_MUSCLE_PERCENT,
        )
    }

    private fun classifyMetabolicAge(metric: BodyMetric, value: Double, context: ReferenceContext): MetricInterpretation {
        val age = age(context) ?: return missingProfile(metric)
        if (age !in 18..99) return outOfDomain(metric)
        val categories = listOf(ReferenceCategory.YOUNGER, ReferenceCategory.MATCHES, ReferenceCategory.OLDER)
        val zones = listOf(
            ReferenceZone(categories[0], null, age.toDouble()),
            ReferenceZone(categories[1], age.toDouble(), age + 1.0),
            ReferenceZone(categories[2], age + 1.0, null),
        )
        val category = when {
            value < age -> categories[0]
            value == age.toDouble() -> categories[1]
            else -> categories[2]
        }
        return MetricInterpretation.Rated(metric, version, category, zones, ZoneBasis.CHRONOLOGICAL_AGE, value)
    }

    private fun compositionInputs(
        context: ReferenceContext,
        requireSex: Boolean,
        ageRange: IntRange = 10..99,
    ): Pair<Int, Sex>? {
        val birthDate = context.birthDate ?: return null
        val sex = context.sex ?: if (requireSex) return null else Sex.MALE
        val height = context.heightCm ?: return null
        val weight = context.weightKg ?: return null
        val impedance = context.impedanceOhm ?: return null
        val age = chronologicalAge(birthDate, context.measurementDate)
        if (age !in ageRange || height !in 100.0..220.0 || weight !in 10.0..150.0 || impedance !in 80..3_000) return null
        return age to sex
    }

    private fun inputFailure(
        metric: BodyMetric,
        context: ReferenceContext,
        requireSex: Boolean,
        ageRange: IntRange = 10..99,
    ): MetricInterpretation {
        if (context.birthDate == null || context.heightCm == null || (requireSex && context.sex == null)) return missingProfile(metric)
        if (context.weightKg == null || context.impedanceOhm == null) return noData(metric)
        val age = chronologicalAge(context.birthDate, context.measurementDate)
        return if (
            age !in ageRange || context.heightCm !in 100.0..220.0 ||
            context.weightKg !in 10.0..150.0 || context.impedanceOhm !in 80..3_000
        ) outOfDomain(metric) else error("Inputs unexpectedly valid")
    }

    private fun age(context: ReferenceContext): Int? =
        context.birthDate?.let { chronologicalAge(it, context.measurementDate) }

    private fun isValidValue(metric: BodyMetric, value: Double): Boolean {
        if (!value.isFinite() || value < 0.0) return false
        return when (metric) {
            BodyMetric.WEIGHT,
            BodyMetric.IMPEDANCE,
            BodyMetric.BMI,
            BodyMetric.BASAL_METABOLIC_RATE,
            BodyMetric.METABOLIC_AGE,
            -> value > 0.0
            else -> true
        }
    }

    private fun bmiZones(): List<ReferenceZone> = zones(
        listOf(18.5, 25.0, 28.0, 32.0),
        listOf(
            ReferenceCategory.BELOW_NORMAL,
            ReferenceCategory.NORMAL,
            ReferenceCategory.ABOVE_NORMAL,
            ReferenceCategory.HIGH_BMI,
            ReferenceCategory.VERY_HIGH_BMI,
        ),
    )

    private fun zones(transitions: List<Double>, categories: List<ReferenceCategory>): List<ReferenceZone> {
        require(categories.size == transitions.size + 1)
        return categories.mapIndexed { index, category ->
            ReferenceZone(category, transitions.getOrNull(index - 1), transitions.getOrNull(index))
        }
    }

    private fun rated(
        metric: BodyMetric,
        value: Double,
        zones: List<ReferenceZone>,
        basis: ZoneBasis = ZoneBasis.METRIC_VALUE,
    ): MetricInterpretation.Rated {
        val category = zones.first { zone ->
            (zone.lowerInclusive == null || value >= zone.lowerInclusive) &&
                (zone.upperExclusive == null || value < zone.upperExclusive)
        }.category
        return MetricInterpretation.Rated(metric, version, category, zones, basis, value)
    }

    private fun unavailable(metric: BodyMetric, reason: UnavailableReason) =
        MetricInterpretation.Unavailable(metric, version, reason)

    private fun missingProfile(metric: BodyMetric) = unavailable(metric, UnavailableReason.MISSING_PROFILE_DATA)
    private fun outOfDomain(metric: BodyMetric) = unavailable(metric, UnavailableReason.OUT_OF_DOMAIN)
    private fun noData(metric: BodyMetric) = unavailable(metric, UnavailableReason.NO_DATA)

    private fun square(value: Double): Double = value * value

    private companion object {
        val lowNormalGood = listOf(ReferenceCategory.BELOW_NORMAL, ReferenceCategory.NORMAL, ReferenceCategory.GOOD)
    }
}
