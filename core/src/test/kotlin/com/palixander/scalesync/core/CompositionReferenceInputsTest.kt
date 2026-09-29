package com.palixander.scalesync.core

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CompositionReferenceInputsTest {
    private val classifier = ReferenceClassifier()
    private val date = LocalDate.of(2026, 8, 30)
    private val valid = ReferenceContext(date, date.minusYears(30), Sex.FEMALE, 170.0, 70.0, 500)
    private val metrics = listOf(
        BodyMetric.BODY_FAT_PERCENT,
        BodyMetric.WATER_PERCENT,
        BodyMetric.MUSCLE_MASS,
        BodyMetric.BONE_MASS,
        BodyMetric.PROTEIN_PERCENT,
        BodyMetric.VISCERAL_FAT,
        BodyMetric.BASAL_METABOLIC_RATE,
        BodyMetric.SKELETAL_MUSCLE_MASS,
    )

    @Test
    fun `each missing profile field is reported for every composition metric`() {
        missingProfileContexts(valid).forEach { assertUnavailable(it, UnavailableReason.MISSING_PROFILE_DATA) }
    }

    @Test
    fun `each missing measurement field is reported for every composition metric`() {
        listOf(valid.copy(weightKg = null), valid.copy(impedanceOhm = null)).forEach {
            assertUnavailable(it, UnavailableReason.NO_DATA)
        }
    }

    @Test
    fun `missing profile wins over missing measurement and invalid domain`() {
        val competingFailures = listOf(
            valid.copy(weightKg = null, impedanceOhm = null),
            valid.copy(weightKg = 9.0, impedanceOhm = 79),
            valid.copy(weightKg = null, impedanceOhm = 79),
            valid.copy(weightKg = 9.0, impedanceOhm = null),
        )
        competingFailures.flatMap(::missingProfileContexts).forEach {
            assertUnavailable(it, UnavailableReason.MISSING_PROFILE_DATA)
        }
    }

    @Test
    fun `missing measurement wins over invalid profile domain or other measurement`() {
        listOf(
            valid.copy(heightCm = 99.0),
            valid.copy(birthDate = date.minusYears(9)),
            valid.copy(heightCm = Double.NaN),
        ).forEach { invalidProfile ->
            assertUnavailable(invalidProfile.copy(weightKg = null), UnavailableReason.NO_DATA)
            assertUnavailable(invalidProfile.copy(impedanceOhm = null), UnavailableReason.NO_DATA)
        }
        assertUnavailable(valid.copy(weightKg = null, impedanceOhm = 79), UnavailableReason.NO_DATA)
        assertUnavailable(valid.copy(weightKg = 9.0, impedanceOhm = null), UnavailableReason.NO_DATA)
    }

    @Test
    fun `height weight and impedance endpoints are inclusive for all composition metrics`() {
        listOf(
            valid.copy(heightCm = 100.0),
            valid.copy(heightCm = 220.0),
            valid.copy(weightKg = 10.0),
            valid.copy(weightKg = 150.0),
            valid.copy(impedanceOhm = 80),
            valid.copy(impedanceOhm = 3_000),
        ).forEach { context -> metrics.forEach { assertRated(it, context) } }
    }

    @Test
    fun `values immediately outside height weight and impedance domains are rejected`() {
        listOf(
            valid.copy(heightCm = Math.nextDown(100.0)),
            valid.copy(heightCm = Math.nextUp(220.0)),
            valid.copy(weightKg = Math.nextDown(10.0)),
            valid.copy(weightKg = Math.nextUp(150.0)),
            valid.copy(impedanceOhm = 79),
            valid.copy(impedanceOhm = 3_001),
        ).forEach { assertUnavailable(it, UnavailableReason.OUT_OF_DOMAIN) }
    }

    @Test
    fun `nonfinite height and weight are out of domain`() {
        listOf(Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY).forEach { value ->
            assertUnavailable(valid.copy(heightCm = value), UnavailableReason.OUT_OF_DOMAIN)
            assertUnavailable(valid.copy(weightKg = value), UnavailableReason.OUT_OF_DOMAIN)
        }
    }

    @Test
    fun `skeletal muscle keeps its own inclusive age range`() {
        metrics.forEach { metric ->
            val ages = if (metric == BodyMetric.SKELETAL_MUSCLE_MASS) 18..80 else 10..99
            listOf(ages.first, ages.last).forEach { age ->
                assertRated(metric, valid.copy(birthDate = date.minusYears(age.toLong())))
            }
            listOf(ages.first - 1, ages.last + 1).forEach { age ->
                assertUnavailable(valid.copy(birthDate = date.minusYears(age.toLong())), UnavailableReason.OUT_OF_DOMAIN, listOf(metric))
            }
        }
    }

    @Test
    fun `invalid reading wins over every context failure and zero remains metric specific`() {
        val contexts = listOf(
            valid,
            valid.copy(birthDate = null, sex = null, heightCm = null, weightKg = null, impedanceOhm = null),
            valid.copy(weightKg = null),
            valid.copy(heightCm = 99.0),
        )
        contexts.forEach { context ->
            listOf(null, -0.001, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach { value ->
                metrics.forEach { metric ->
                    assertEquals(
                        MetricInterpretation.Unavailable(metric, classifier.version, UnavailableReason.NO_DATA),
                        classifier.classify(metric, value, context),
                        "$metric value=$value context=$context",
                    )
                }
            }
            assertEquals(
                MetricInterpretation.Unavailable(BodyMetric.BASAL_METABOLIC_RATE, classifier.version, UnavailableReason.NO_DATA),
                classifier.classify(BodyMetric.BASAL_METABOLIC_RATE, 0.0, context),
            )
        }
        metrics.filterNot { it == BodyMetric.BASAL_METABOLIC_RATE }.forEach { metric ->
            assertIs<MetricInterpretation.Rated>(classifier.classify(metric, 0.0, valid))
        }
    }

    private fun missingProfileContexts(context: ReferenceContext) = listOf(
        context.copy(birthDate = null),
        context.copy(sex = null),
        context.copy(heightCm = null),
    )

    private fun assertUnavailable(
        context: ReferenceContext,
        reason: UnavailableReason,
        selectedMetrics: List<BodyMetric> = metrics,
    ) {
        selectedMetrics.forEach { metric ->
            assertEquals(
                MetricInterpretation.Unavailable(metric, classifier.version, reason),
                classifier.classify(metric, 20.0, context),
                "$metric context=$context",
            )
        }
    }

    private fun assertRated(metric: BodyMetric, context: ReferenceContext) {
        val rated = assertIs<MetricInterpretation.Rated>(classifier.classify(metric, 20.0, context), "$metric context=$context")
        assertEquals(metric, rated.metric)
        assertEquals(classifier.version, rated.version)
    }
}
