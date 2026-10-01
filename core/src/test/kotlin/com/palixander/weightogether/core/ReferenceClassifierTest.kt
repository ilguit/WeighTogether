package com.palixander.weightogether.core

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ReferenceClassifierTest {
    private val classifier = ReferenceClassifier()

    @Test
    fun `chronological age is exact and is never clamped`() {
        val birthDate = LocalDate.of(2000, 8, 30)

        assertEquals(24, chronologicalAge(birthDate, LocalDate.of(2025, 8, 29)))
        assertEquals(25, chronologicalAge(birthDate, LocalDate.of(2025, 8, 30)))
        assertEquals(6, chronologicalAge(LocalDate.of(2019, 1, 1), LocalDate.of(2025, 1, 1)))
        assertEquals(100, chronologicalAge(LocalDate.of(1925, 1, 1), LocalDate.of(2025, 1, 1)))
    }

    @Test
    fun `classification always returns exactly the 16 canonical metric keys`() {
        val result = classifier.classifyAll(emptyList(), context())

        assertEquals("ScaleSync 1", classifier.version.setLabel)
        assertEquals("Справочные нормы ScaleSync, версия 1", classifier.version.publicName)
        assertEquals(16, BodyMetric.entries.size)
        assertEquals(BodyMetric.entries.toSet(), result.keys)
        result.values.forEach { assertUnavailable(it, UnavailableReason.NO_DATA) }
    }

    @Test
    fun `BMI boundaries are raw half-open and weight has the identical category`() {
        listOf(
            18.5 to ReferenceCategory.NORMAL,
            25.0 to ReferenceCategory.ABOVE_NORMAL,
            28.0 to ReferenceCategory.HIGH_BMI,
            32.0 to ReferenceCategory.VERY_HIGH_BMI,
        ).forEach { (boundary, atCategory) ->
            val height = 2.0
            val weight = boundary * height * height
            val ctx = context(heightCm = 200.0, weightKg = weight)
            assertEquals(category(BodyMetric.WEIGHT, weight, ctx), category(BodyMetric.BMI, boundary, ctx))
            assertEquals(atCategory, category(BodyMetric.BMI, boundary, ctx))
            assertEquals(previous(atCategory), category(BodyMetric.BMI, boundary - 0.000001, ctx.copy(weightKg = (boundary - 0.000001) * 4)))
        }

        val all = classifier.classifyAll(
            listOf(MetricReading(BodyMetric.WEIGHT, 128.0), MetricReading(BodyMetric.BMI, 1.0)),
            context(heightCm = 200.0, weightKg = 70.0),
        )
        assertEquals(
            assertIs<MetricInterpretation.Rated>(all.getValue(BodyMetric.WEIGHT)).category,
            assertIs<MetricInterpretation.Rated>(all.getValue(BodyMetric.BMI)).category,
        )
    }

    @Test
    fun `weight and BMI do not require impedance but enforce age height and weight domains`() {
        val noImpedance = context(impedanceOhm = null)
        assertEquals(ReferenceCategory.NORMAL, category(BodyMetric.WEIGHT, 70.0, noImpedance))
        assertEquals(ReferenceCategory.NORMAL, category(BodyMetric.BMI, 22.0, noImpedance))

        assertUnavailable(classify(BodyMetric.WEIGHT, 70.0, context(age = 19)), UnavailableReason.OUT_OF_DOMAIN)
        assertUnavailable(classify(BodyMetric.WEIGHT, 70.0, context(age = 100)), UnavailableReason.OUT_OF_DOMAIN)
        assertUnavailable(classify(BodyMetric.WEIGHT, 70.0, context(heightCm = 99.999)), UnavailableReason.OUT_OF_DOMAIN)
        assertUnavailable(classify(BodyMetric.WEIGHT, 70.0, context(heightCm = 220.001)), UnavailableReason.OUT_OF_DOMAIN)
        assertUnavailable(classify(BodyMetric.WEIGHT, 9.999, context()), UnavailableReason.OUT_OF_DOMAIN)
        assertUnavailable(classify(BodyMetric.WEIGHT, 150.001, context()), UnavailableReason.OUT_OF_DOMAIN)
        assertUnavailable(classify(BodyMetric.BMI, 22.0, context(heightCm = null)), UnavailableReason.MISSING_PROFILE_DATA)
        assertUnavailable(classify(BodyMetric.BMI, 22.0, context(weightKg = null)), UnavailableReason.NO_DATA)
    }

    @Test
    fun `all body-fat age-sex rows use every half-open transition`() {
        val rows = listOf(
            FatRow(Sex.FEMALE, 10, listOf(12.0, 21.0, 30.0, 34.0)),
            FatRow(Sex.FEMALE, 11, listOf(12.0, 21.0, 30.0, 34.0)),
            FatRow(Sex.FEMALE, 12, listOf(15.0, 24.0, 33.0, 37.0)),
            FatRow(Sex.FEMALE, 13, listOf(15.0, 24.0, 33.0, 37.0)),
            FatRow(Sex.FEMALE, 14, listOf(18.0, 27.0, 36.0, 40.0)),
            FatRow(Sex.FEMALE, 15, listOf(18.0, 27.0, 36.0, 40.0)),
            FatRow(Sex.FEMALE, 16, listOf(20.0, 28.0, 37.0, 41.0)),
            FatRow(Sex.FEMALE, 17, listOf(20.0, 28.0, 37.0, 41.0)),
            FatRow(Sex.FEMALE, 18, listOf(21.0, 28.0, 35.0, 40.0)),
            FatRow(Sex.FEMALE, 39, listOf(21.0, 28.0, 35.0, 40.0)),
            FatRow(Sex.FEMALE, 40, listOf(22.0, 29.0, 36.0, 41.0)),
            FatRow(Sex.FEMALE, 59, listOf(22.0, 29.0, 36.0, 41.0)),
            FatRow(Sex.FEMALE, 60, listOf(23.0, 30.0, 37.0, 42.0)),
            FatRow(Sex.FEMALE, 99, listOf(23.0, 30.0, 37.0, 42.0)),
            FatRow(Sex.MALE, 10, listOf(7.0, 16.0, 25.0, 30.0)),
            FatRow(Sex.MALE, 17, listOf(7.0, 16.0, 25.0, 30.0)),
            FatRow(Sex.MALE, 18, listOf(11.0, 17.0, 22.0, 27.0)),
            FatRow(Sex.MALE, 39, listOf(11.0, 17.0, 22.0, 27.0)),
            FatRow(Sex.MALE, 40, listOf(12.0, 18.0, 23.0, 28.0)),
            FatRow(Sex.MALE, 59, listOf(12.0, 18.0, 23.0, 28.0)),
            FatRow(Sex.MALE, 60, listOf(14.0, 20.0, 25.0, 30.0)),
            FatRow(Sex.MALE, 99, listOf(14.0, 20.0, 25.0, 30.0)),
        )
        val categories = listOf(ReferenceCategory.VERY_LOW, ReferenceCategory.LOW, ReferenceCategory.NORMAL, ReferenceCategory.HIGH, ReferenceCategory.VERY_HIGH)

        rows.forEach { row -> assertTransitions(BodyMetric.BODY_FAT_PERCENT, row.transitions, categories, context(age = row.age, sex = row.sex)) }
        assertUnavailable(classify(BodyMetric.BODY_FAT_PERCENT, 20.0, context(age = 9)), UnavailableReason.OUT_OF_DOMAIN)
        assertUnavailable(classify(BodyMetric.BODY_FAT_PERCENT, 20.0, context(age = 100)), UnavailableReason.OUT_OF_DOMAIN)
    }

    @Test
    fun `water muscle and bone tables cover every sex and selector boundary`() {
        assertTransitions(BodyMetric.WATER_PERCENT, listOf(55.0, 65.1), lowNormalGood, context(sex = Sex.MALE))
        assertTransitions(BodyMetric.WATER_PERCENT, listOf(45.0, 60.1), lowNormalGood, context(sex = Sex.FEMALE))

        val muscleRows = listOf(
            Triple(Sex.FEMALE, 149.999, listOf(29.1, 34.8)),
            Triple(Sex.FEMALE, 150.0, listOf(32.9, 37.6)),
            Triple(Sex.FEMALE, 160.0, listOf(36.5, 42.6)),
            Triple(Sex.MALE, 159.999, listOf(38.5, 46.6)),
            Triple(Sex.MALE, 160.0, listOf(44.0, 52.5)),
            Triple(Sex.MALE, 170.0, listOf(49.4, 59.5)),
        )
        muscleRows.forEach { (sex, height, transitions) ->
            assertTransitions(BodyMetric.MUSCLE_MASS, transitions, lowNormalGood, context(sex = sex, heightCm = height))
        }

        val boneRows = listOf(
            Triple(Sex.FEMALE, 44.999, listOf(1.3, 3.6)),
            Triple(Sex.FEMALE, 45.0, listOf(1.5, 3.8)),
            Triple(Sex.FEMALE, 60.0, listOf(1.8, 3.9)),
            Triple(Sex.MALE, 59.999, listOf(1.6, 3.9)),
            Triple(Sex.MALE, 60.0, listOf(1.9, 4.1)),
            Triple(Sex.MALE, 75.0, listOf(2.0, 4.2)),
        )
        boneRows.forEach { (sex, weight, transitions) ->
            assertTransitions(BodyMetric.BONE_MASS, transitions, lowNormalGood, context(sex = sex, weightKg = weight))
        }
    }

    @Test
    fun `protein visceral fat and BMR use all exact boundaries and coefficients`() {
        assertTransitions(BodyMetric.PROTEIN_PERCENT, listOf(16.0, 20.0), lowNormalGood, context())
        assertTransitions(
            BodyMetric.VISCERAL_FAT,
            listOf(10.0, 15.0),
            listOf(ReferenceCategory.NORMAL, ReferenceCategory.HIGH, ReferenceCategory.VERY_HIGH),
            context(),
        )
        val coefficients = listOf(
            Triple(Sex.FEMALE, 10, 21.24), Triple(Sex.FEMALE, 29, 21.24), Triple(Sex.FEMALE, 30, 19.53),
            Triple(Sex.FEMALE, 49, 19.53), Triple(Sex.FEMALE, 50, 18.63), Triple(Sex.FEMALE, 99, 18.63),
            Triple(Sex.MALE, 10, 21.6), Triple(Sex.MALE, 29, 21.6), Triple(Sex.MALE, 30, 20.07),
            Triple(Sex.MALE, 49, 20.07), Triple(Sex.MALE, 50, 19.35), Triple(Sex.MALE, 99, 19.35),
        )
        coefficients.forEach { (sex, age, coefficient) ->
            val threshold = 70.0 * coefficient
            assertEquals(ReferenceCategory.BELOW_NORMAL, category(BodyMetric.BASAL_METABOLIC_RATE, threshold - 0.000001, context(sex = sex, age = age)))
            assertEquals(ReferenceCategory.NORMAL, category(BodyMetric.BASAL_METABOLIC_RATE, threshold, context(sex = sex, age = age)))
        }
    }

    @Test
    fun `FMI covers all age-sex rows including the deliberate 75 to 76 boundary`() {
        val rows = listOf(
            FmiRow(Sex.MALE, 18, 2.2, 7.0), FmiRow(Sex.MALE, 34, 2.2, 7.0),
            FmiRow(Sex.MALE, 35, 2.5, 7.9), FmiRow(Sex.MALE, 54, 2.5, 7.9),
            FmiRow(Sex.MALE, 55, 2.8, 9.3), FmiRow(Sex.MALE, 74, 2.8, 9.3), FmiRow(Sex.MALE, 75, 2.8, 9.3),
            FmiRow(Sex.MALE, 76, 3.7, 10.1), FmiRow(Sex.MALE, 98, 3.7, 10.1),
            FmiRow(Sex.FEMALE, 18, 3.5, 8.7), FmiRow(Sex.FEMALE, 34, 3.5, 8.7),
            FmiRow(Sex.FEMALE, 35, 3.4, 9.9), FmiRow(Sex.FEMALE, 54, 3.4, 9.9),
            FmiRow(Sex.FEMALE, 55, 4.5, 13.5), FmiRow(Sex.FEMALE, 74, 4.5, 13.5), FmiRow(Sex.FEMALE, 75, 4.5, 13.5),
            FmiRow(Sex.FEMALE, 76, 4.9, 14.3), FmiRow(Sex.FEMALE, 98, 4.9, 14.3),
        )
        rows.forEach { row ->
            val heightSquared = 1.75 * 1.75
            val ctx = context(sex = row.sex, age = row.age, heightCm = 175.0)
            assertTransitions(BodyMetric.BODY_FAT_MASS, listOf(row.p5 * heightSquared, row.p95 * heightSquared), listOf(ReferenceCategory.BELOW_NORMAL, ReferenceCategory.NORMAL, ReferenceCategory.ABOVE_NORMAL), ctx)
        }
        listOf(17, 99).forEach { age ->
            assertUnavailable(classify(BodyMetric.BODY_FAT_MASS, 20.0, context(age = age)), UnavailableReason.OUT_OF_DOMAIN)
        }
    }

    @Test
    fun `OMRON covers all age-sex rows and uses decimal HALF_UP before half-open classification`() {
        val rows = listOf(
            OmronRow(Sex.FEMALE, 18, listOf(24.3, 30.4, 35.4)),
            OmronRow(Sex.FEMALE, 39, listOf(24.3, 30.4, 35.4)),
            OmronRow(Sex.FEMALE, 40, listOf(24.1, 30.2, 35.2)),
            OmronRow(Sex.FEMALE, 59, listOf(24.1, 30.2, 35.2)),
            OmronRow(Sex.FEMALE, 60, listOf(23.9, 30.0, 35.0)),
            OmronRow(Sex.FEMALE, 80, listOf(23.9, 30.0, 35.0)),
            OmronRow(Sex.MALE, 18, listOf(33.3, 39.4, 44.1)),
            OmronRow(Sex.MALE, 39, listOf(33.3, 39.4, 44.1)),
            OmronRow(Sex.MALE, 40, listOf(33.1, 39.2, 43.9)),
            OmronRow(Sex.MALE, 59, listOf(33.1, 39.2, 43.9)),
            OmronRow(Sex.MALE, 60, listOf(32.9, 39.0, 43.7)),
            OmronRow(Sex.MALE, 80, listOf(32.9, 39.0, 43.7)),
        )
        val categories = listOf(ReferenceCategory.BELOW_NORMAL, ReferenceCategory.NORMAL, ReferenceCategory.GOOD, ReferenceCategory.VERY_GOOD)
        rows.forEach { row ->
            row.transitions.forEachIndexed { index, threshold ->
                val ctx = context(sex = row.sex, age = row.age, weightKg = 100.0)
                assertEquals(categories[index], category(BodyMetric.SKELETAL_MUSCLE_MASS, threshold - 0.06, ctx))
                assertEquals(categories[index + 1], category(BodyMetric.SKELETAL_MUSCLE_MASS, threshold, ctx))
                assertEquals(categories[index + 1], category(BodyMetric.SKELETAL_MUSCLE_MASS, threshold + 0.04, ctx))
            }
        }

        val ctx = context(sex = Sex.FEMALE, age = 40, weightKg = 100.0)
        assertEquals(30.3, rated(BodyMetric.SKELETAL_MUSCLE_MASS, 30.34, ctx).classifiedValue)
        assertEquals(30.4, rated(BodyMetric.SKELETAL_MUSCLE_MASS, 30.35, ctx).classifiedValue)
        assertEquals(30.4, rated(BodyMetric.SKELETAL_MUSCLE_MASS, 30.39, ctx).classifiedValue)
        assertUnavailable(classify(BodyMetric.SKELETAL_MUSCLE_MASS, 30.0, context(age = 17)), UnavailableReason.OUT_OF_DOMAIN)
        assertUnavailable(classify(BodyMetric.SKELETAL_MUSCLE_MASS, 30.0, context(age = 81)), UnavailableReason.OUT_OF_DOMAIN)
    }

    @Test
    fun `metabolic age compares without tolerance`() {
        val ctx = context(age = 42)
        assertEquals(ReferenceCategory.YOUNGER, category(BodyMetric.METABOLIC_AGE, 41.999, ctx))
        assertEquals(ReferenceCategory.MATCHES, category(BodyMetric.METABOLIC_AGE, 42.0, ctx))
        assertEquals(ReferenceCategory.OLDER, category(BodyMetric.METABOLIC_AGE, 42.001, ctx))
        assertUnavailable(classify(BodyMetric.METABOLIC_AGE, 42.0, context(age = 17)), UnavailableReason.OUT_OF_DOMAIN)
        assertUnavailable(classify(BodyMetric.METABOLIC_AGE, 42.0, context(age = 100)), UnavailableReason.OUT_OF_DOMAIN)
    }

    @Test
    fun `no-rating metrics have no zones and distinguish missing values`() {
        listOf(BodyMetric.IMPEDANCE, BodyMetric.WATER_MASS, BodyMetric.PROTEIN_MASS, BodyMetric.LEAN_BODY_MASS).forEach { metric ->
            assertUnavailable(classify(metric, 1.0, context()), UnavailableReason.NO_REFERENCE)
            assertUnavailable(classify(metric, null, context()), UnavailableReason.NO_DATA)
            assertUnavailable(classify(metric, Double.NaN, context()), UnavailableReason.NO_DATA)
            assertUnavailable(classify(metric, Double.POSITIVE_INFINITY, context()), UnavailableReason.NO_DATA)
        }
    }

    @Test
    fun `G0 keeps scales independent and never clamps or borrows a neighboring table`() {
        val missingHeight = context(heightCm = null)
        assertUnavailable(classify(BodyMetric.BODY_FAT_PERCENT, 22.0, missingHeight), UnavailableReason.MISSING_PROFILE_DATA)
        assertUnavailable(classify(BodyMetric.WEIGHT, 70.0, missingHeight), UnavailableReason.MISSING_PROFILE_DATA)
        assertUnavailable(classify(BodyMetric.IMPEDANCE, 500.0, missingHeight), UnavailableReason.NO_REFERENCE)

        val missingImpedance = context(impedanceOhm = null)
        assertEquals(ReferenceCategory.NORMAL, category(BodyMetric.WEIGHT, 70.0, missingImpedance))
        assertEquals(ReferenceCategory.NORMAL, category(BodyMetric.BMI, 22.0, missingImpedance))
        assertUnavailable(classify(BodyMetric.BODY_FAT_PERCENT, 22.0, missingImpedance), UnavailableReason.NO_DATA)

        assertUnavailable(classify(BodyMetric.WATER_PERCENT, 55.0, context(age = 9)), UnavailableReason.OUT_OF_DOMAIN)
        assertEquals(ReferenceCategory.NORMAL, category(BodyMetric.BMI, 22.0, context(age = 20)))
        assertUnavailable(classify(BodyMetric.WATER_PERCENT, 55.0, context(impedanceOhm = 79)), UnavailableReason.OUT_OF_DOMAIN)
        assertUnavailable(classify(BodyMetric.WATER_PERCENT, 55.0, context(impedanceOhm = 3_001)), UnavailableReason.OUT_OF_DOMAIN)
        assertUnavailable(classify(BodyMetric.WATER_PERCENT, 55.0, context(weightKg = 9.999)), UnavailableReason.OUT_OF_DOMAIN)
        assertUnavailable(classify(BodyMetric.WATER_PERCENT, 55.0, context(weightKg = 150.001)), UnavailableReason.OUT_OF_DOMAIN)

        assertEquals(ReferenceCategory.VERY_LOW, category(BodyMetric.BODY_FAT_PERCENT, 0.0, context(age = 10)))
        assertUnavailable(classify(BodyMetric.BODY_FAT_PERCENT, -0.001, context()), UnavailableReason.NO_DATA)
        assertUnavailable(classify(BodyMetric.WEIGHT, 0.0, context()), UnavailableReason.NO_DATA)
        assertEquals(ReferenceCategory.OLDER, category(BodyMetric.METABOLIC_AGE, 42.5, context()))

        listOf(10.0, 150.0).forEach { weight ->
            val expected = if (weight == 10.0) ReferenceCategory.BELOW_NORMAL else ReferenceCategory.VERY_HIGH_BMI
            assertEquals(expected, category(BodyMetric.WEIGHT, weight, context(weightKg = weight)))
        }
        listOf(100.0, 220.0).forEach { height ->
            assertIs<MetricInterpretation.Rated>(classify(BodyMetric.WATER_PERCENT, 55.0, context(heightCm = height)))
        }
        listOf(10, 99).forEach { age ->
            assertIs<MetricInterpretation.Rated>(classify(BodyMetric.WATER_PERCENT, 55.0, context(age = age)))
        }
    }

    private fun assertTransitions(
        metric: BodyMetric,
        transitions: List<Double>,
        categories: List<ReferenceCategory>,
        context: ReferenceContext,
    ) {
        transitions.forEachIndexed { index, transition ->
            assertEquals(categories[index], category(metric, transition - 0.000001, context), "$metric below $transition")
            assertEquals(categories[index + 1], category(metric, transition, context), "$metric at $transition")
            assertEquals(categories[index + 1], category(metric, transition + 0.000001, context), "$metric above $transition")
        }
    }

    private fun context(
        sex: Sex? = Sex.FEMALE,
        age: Int = 30,
        heightCm: Double? = 170.0,
        weightKg: Double? = 70.0,
        impedanceOhm: Int? = 500,
    ): ReferenceContext {
        val date = LocalDate.of(2026, 8, 30)
        return ReferenceContext(date, date.minusYears(age.toLong()), sex, heightCm, weightKg, impedanceOhm)
    }

    private fun classify(metric: BodyMetric, value: Double?, context: ReferenceContext) =
        classifier.classify(metric, value, context)

    private fun rated(metric: BodyMetric, value: Double, context: ReferenceContext) =
        assertIs<MetricInterpretation.Rated>(classify(metric, value, context))

    private fun category(metric: BodyMetric, value: Double, context: ReferenceContext) = rated(metric, value, context).category

    private fun assertUnavailable(actual: MetricInterpretation, reason: UnavailableReason) {
        assertEquals(reason, assertIs<MetricInterpretation.Unavailable>(actual).reason)
    }

    private fun previous(category: ReferenceCategory) = when (category) {
        ReferenceCategory.NORMAL -> ReferenceCategory.BELOW_NORMAL
        ReferenceCategory.ABOVE_NORMAL -> ReferenceCategory.NORMAL
        ReferenceCategory.HIGH_BMI -> ReferenceCategory.ABOVE_NORMAL
        ReferenceCategory.VERY_HIGH_BMI -> ReferenceCategory.HIGH_BMI
        else -> error("Not a BMI transition category")
    }

    private data class FatRow(val sex: Sex, val age: Int, val transitions: List<Double>)
    private data class FmiRow(val sex: Sex, val age: Int, val p5: Double, val p95: Double)
    private data class OmronRow(val sex: Sex, val age: Int, val transitions: List<Double>)

    private companion object {
        val lowNormalGood = listOf(ReferenceCategory.BELOW_NORMAL, ReferenceCategory.NORMAL, ReferenceCategory.GOOD)
    }
}
