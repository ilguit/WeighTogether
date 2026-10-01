package com.palixander.weightogether.ui.reference

import com.palixander.weightogether.R
import com.palixander.weightogether.core.BodyMetric
import com.palixander.weightogether.core.Sex
import com.palixander.weightogether.measurements.MeasurementUiValues
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReferenceMetricCatalogTest {
    @Test
    fun catalogContainsTheCanonicalSixteenMetricsOnceInApprovedOrder() {
        val metrics = ReferenceMetricCatalog.metrics

        assertEquals(16, metrics.size)
        assertEquals(16, metrics.map { it.metric }.toSet().size)
        assertEquals(
            listOf(
                BodyMetric.WEIGHT,
                BodyMetric.IMPEDANCE,
                BodyMetric.BMI,
                BodyMetric.BODY_FAT_PERCENT,
                BodyMetric.BODY_FAT_MASS,
                BodyMetric.WATER_PERCENT,
                BodyMetric.WATER_MASS,
                BodyMetric.PROTEIN_PERCENT,
                BodyMetric.PROTEIN_MASS,
                BodyMetric.LEAN_BODY_MASS,
                BodyMetric.MUSCLE_MASS,
                BodyMetric.SKELETAL_MUSCLE_MASS,
                BodyMetric.BONE_MASS,
                BodyMetric.VISCERAL_FAT,
                BodyMetric.BASAL_METABOLIC_RATE,
                BodyMetric.METABOLIC_AGE,
            ),
            metrics.map { it.metric },
        )
        assertEquals(BodyMetric.entries.toSet(), metrics.mapTo(linkedSetOf()) { it.metric })
    }

    @Test
    fun namesUnitsAndGroupsUseTheApprovedResourceContract() {
        val expected = listOf(
            Triple(BodyMetric.WEIGHT, R.string.reference_metric_weight, R.string.reference_unit_kg),
            Triple(BodyMetric.IMPEDANCE, R.string.reference_metric_impedance, R.string.reference_unit_ohm),
            Triple(BodyMetric.BMI, R.string.reference_metric_bmi, R.string.reference_unit_bmi),
            Triple(BodyMetric.BODY_FAT_PERCENT, R.string.reference_metric_body_fat, R.string.reference_unit_percent),
            Triple(BodyMetric.BODY_FAT_MASS, R.string.reference_metric_body_fat_mass, R.string.reference_unit_kg),
            Triple(BodyMetric.WATER_PERCENT, R.string.reference_metric_water, R.string.reference_unit_percent),
            Triple(BodyMetric.WATER_MASS, R.string.reference_metric_water_mass, R.string.reference_unit_kg),
            Triple(BodyMetric.PROTEIN_PERCENT, R.string.reference_metric_protein, R.string.reference_unit_percent),
            Triple(BodyMetric.PROTEIN_MASS, R.string.reference_metric_protein_mass, R.string.reference_unit_kg),
            Triple(BodyMetric.LEAN_BODY_MASS, R.string.reference_metric_lean_body_mass, R.string.reference_unit_kg),
            Triple(BodyMetric.MUSCLE_MASS, R.string.reference_metric_muscle_mass, R.string.reference_unit_kg),
            Triple(BodyMetric.SKELETAL_MUSCLE_MASS, R.string.reference_metric_skeletal_muscle, R.string.reference_unit_kg),
            Triple(BodyMetric.BONE_MASS, R.string.reference_metric_bone_mass, R.string.reference_unit_kg),
            Triple(BodyMetric.VISCERAL_FAT, R.string.reference_metric_visceral_fat, R.string.reference_unit_level),
            Triple(BodyMetric.BASAL_METABOLIC_RATE, R.string.reference_metric_bmr, R.string.reference_unit_kcal_day),
            Triple(BodyMetric.METABOLIC_AGE, R.string.reference_metric_metabolic_age, R.string.reference_unit_years),
        )

        expected.forEach { (metric, name, unit) ->
            val definition = ReferenceMetricCatalog.byMetric.getValue(metric)
            assertEquals(name, definition.nameRes)
            assertEquals(unit, definition.visibleUnitRes)
        }
        assertEquals(1, ReferenceMetricCatalog.metrics.count { it.group == ReferenceMetricGroup.WEIGHT })
        assertEquals(2, ReferenceMetricCatalog.metrics.count { it.group == ReferenceMetricGroup.MAIN })
        assertEquals(7, ReferenceMetricCatalog.metrics.count { it.group == ReferenceMetricGroup.BODY_COMPOSITION })
        assertEquals(3, ReferenceMetricCatalog.metrics.count { it.group == ReferenceMetricGroup.MUSCLES_AND_BONES })
        assertEquals(3, ReferenceMetricCatalog.metrics.count { it.group == ReferenceMetricGroup.METABOLISM })
    }

    @Test
    fun everyHelpEntryHasOneApprovedSourceActionAndCorrectBiaScope() {
        val descriptions = setOf(
            BodyMetric.IMPEDANCE,
            BodyMetric.WATER_MASS,
            BodyMetric.PROTEIN_MASS,
            BodyMetric.LEAN_BODY_MASS,
        )
        val noBia = setOf(BodyMetric.WEIGHT, BodyMetric.BMI, BodyMetric.IMPEDANCE)

        ReferenceMetricCatalog.metrics.forEach { definition ->
            assertTrue(definition.help.sourceUrl.startsWith("https://"))
            assertEquals(
                if (definition.metric in descriptions) ReferenceSourceKind.DESCRIPTION else ReferenceSourceKind.RATING,
                definition.help.sourceKind,
            )
            assertEquals(definition.metric !in noBia, definition.help.showBiaDisclaimer)
            assertEquals(definition.metric !in setOf(BodyMetric.WEIGHT, BodyMetric.BMI), definition.help.showBiaContraindications)
        }
        assertEquals(16, ReferenceMetricCatalog.metrics.map { it.help }.size)
        assertFalse(ReferenceMetricCatalog.byMetric.getValue(BodyMetric.BONE_MASS).help.secondaryCitationRes == null)
    }

    @Test
    fun measurementValuesAdapterProducesAllReadingsInCatalogOrder() {
        val values = MeasurementUiValues(
            weightKg = 1.0,
            impedanceOhm = 2,
            bmi = 3.0,
            bodyFatPercent = 4.0,
            bodyFatMassKg = 5.0,
            waterPercent = 6.0,
            waterMassKg = 7.0,
            muscleMassKg = 8.0,
            skeletalMuscleMassKg = 9.0,
            boneMassKg = 10.0,
            proteinPercent = 11.0,
            proteinMassKg = 12.0,
            visceralFatLevel = 13.0,
            basalMetabolicRateKcal = 14.0,
            metabolicAge = 15,
            leanBodyMassKg = 16.0,
        )

        val readings = values.toReferenceReadings()

        assertEquals(ReferenceMetricCatalog.metrics.map { it.metric }, readings.map { it.metric })
        assertEquals(
            listOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 11.0, 12.0, 16.0, 8.0, 9.0, 10.0, 13.0, 14.0, 15.0),
            readings.map { it.value },
        )
    }

    @Test
    fun measurementContextAdapterPreservesTheSuppliedHistoricalContext() {
        val values = MeasurementUiValues(
            weightKg = 71.25,
            impedanceOhm = 487,
            bmi = null,
            bodyFatPercent = null,
            bodyFatMassKg = null,
            waterPercent = null,
            waterMassKg = null,
            muscleMassKg = null,
            skeletalMuscleMassKg = null,
            boneMassKg = null,
            proteinPercent = null,
            proteinMassKg = null,
            visceralFatLevel = null,
            basalMetabolicRateKcal = null,
            metabolicAge = null,
            leanBodyMassKg = null,
        )
        val measurementDate = LocalDate.of(2024, 2, 29)
        val birthDate = LocalDate.of(1990, 6, 12)

        val context = values.toReferenceContext(
            measurementDate = measurementDate,
            birthDate = birthDate,
            sex = Sex.FEMALE,
            ratingHeightCm = 168.4,
        )

        assertEquals(measurementDate, context.measurementDate)
        assertEquals(birthDate, context.birthDate)
        assertEquals(Sex.FEMALE, context.sex)
        assertEquals(168.4, context.heightCm)
        assertEquals(71.25, context.weightKg)
        assertEquals(487, context.impedanceOhm)
    }
}
