package com.palixander.scalesync.ui.reference

import com.palixander.scalesync.measurements.formatWeight

import android.content.res.Resources
import androidx.annotation.StringRes
import com.palixander.scalesync.R
import com.palixander.scalesync.core.BodyMetric
import com.palixander.scalesync.core.MetricInterpretation
import com.palixander.scalesync.core.MetricReading
import com.palixander.scalesync.core.ReferenceCategory
import com.palixander.scalesync.core.ReferenceContext
import com.palixander.scalesync.core.ReferenceZone
import com.palixander.scalesync.core.Sex
import com.palixander.scalesync.core.UnavailableReason
import com.palixander.scalesync.core.ZoneBasis
import com.palixander.scalesync.measurements.MeasurementUiValues
import com.palixander.scalesync.ui.theme.ReferenceTone
import com.palixander.scalesync.ui.theme.referenceTone
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

data class ReferenceZonePresentation(
    val category: ReferenceCategory,
    val label: String,
    val range: String,
    val spokenRange: String,
    val isCurrent: Boolean,
    val tone: ReferenceTone,
    val lowerInclusive: Double? = null,
    val upperExclusive: Double? = null,
    val lowerBoundaryLabel: String? = null,
    val upperBoundaryLabel: String? = null,
)

data class ReferenceMetricPresentation(
    val definition: ReferenceMetricDefinition,
    val title: String,
    val visualNumber: String?,
    val visualValue: String,
    val visibleUnit: String,
    val spokenValue: String?,
    val status: String,
    val tone: ReferenceTone,
    val zones: List<ReferenceZonePresentation>,
    val isPreliminary: Boolean,
    val compactAccessibilityDescription: String,
    val accessibilityDescription: String,
    val scaleValue: Double? = null,
)

/** Position on an equal-width segmented scale, expressed in the 0..1 range. */
internal fun referenceScaleMarkerFraction(
    value: Double,
    zones: List<ReferenceZonePresentation>,
): Double? {
    if (!value.isFinite() || zones.isEmpty()) return null
    val currentIndex = zones.indexOfFirst(ReferenceZonePresentation::isCurrent)
    if (currentIndex < 0) return null
    val zone = zones[currentIndex]
    val withinSegment = when {
        zone.lowerInclusive != null && zone.upperExclusive != null -> {
            val width = zone.upperExclusive - zone.lowerInclusive
            if (!width.isFinite() || width <= 0.0) return null
            ((value - zone.lowerInclusive) / width).coerceIn(0.0, 1.0)
        }
        zone.lowerInclusive == null && zone.upperExclusive != null -> 0.75
        zone.lowerInclusive != null && zone.upperExclusive == null -> 0.25
        else -> 0.5
    }
    return ((currentIndex + withinSegment) / zones.size).coerceIn(0.0, 1.0)
}

data class ReferenceGroupPresentation(
    val group: ReferenceMetricGroup,
    val title: String?,
    val metrics: List<ReferenceMetricPresentation>,
)

fun MeasurementUiValues.toReferenceReadings(): List<MetricReading> =
    ReferenceMetricCatalog.metrics.map { definition ->
        MetricReading(definition.metric, referenceValue(definition.metric))
    }

fun MeasurementUiValues.toReferenceContext(
    measurementDate: LocalDate,
    birthDate: LocalDate?,
    sex: Sex?,
    ratingHeightCm: Double?,
): ReferenceContext = ReferenceContext(
    measurementDate = measurementDate,
    birthDate = birthDate,
    sex = sex,
    heightCm = ratingHeightCm,
    weightKg = weightKg,
    impedanceOhm = impedanceOhm,
)

private fun MeasurementUiValues.referenceValue(metric: BodyMetric): Double? = when (metric) {
    BodyMetric.WEIGHT -> weightKg
    BodyMetric.IMPEDANCE -> impedanceOhm?.toDouble()
    BodyMetric.BMI -> bmi
    BodyMetric.BODY_FAT_PERCENT -> bodyFatPercent
    BodyMetric.BODY_FAT_MASS -> bodyFatMassKg
    BodyMetric.WATER_PERCENT -> waterPercent
    BodyMetric.WATER_MASS -> waterMassKg
    BodyMetric.MUSCLE_MASS -> muscleMassKg
    BodyMetric.SKELETAL_MUSCLE_MASS -> skeletalMuscleMassKg
    BodyMetric.BONE_MASS -> boneMassKg
    BodyMetric.PROTEIN_PERCENT -> proteinPercent
    BodyMetric.PROTEIN_MASS -> proteinMassKg
    BodyMetric.VISCERAL_FAT -> visceralFatLevel
    BodyMetric.BASAL_METABOLIC_RATE -> basalMetabolicRateKcal
    BodyMetric.METABOLIC_AGE -> metabolicAge?.toDouble()
    BodyMetric.LEAN_BODY_MASS -> leanBodyMassKg
}

class ReferencePresentationFactory(
    private val resources: Resources,
    private val locale: Locale = resources.configuration.locales[0] ?: Locale.getDefault(),
) {
    fun createAll(
        readings: Collection<MetricReading>,
        interpretations: Map<BodyMetric, MetricInterpretation>,
        preliminary: Boolean = false,
    ): List<ReferenceMetricPresentation> {
        val values = readings.associate { it.metric to it.value }
        return ReferenceMetricCatalog.metrics.map { definition ->
            create(
                definition = definition,
                value = values[definition.metric],
                interpretation = requireNotNull(interpretations[definition.metric]) {
                    "Missing interpretation for ${definition.metric}"
                },
                weightKg = values[BodyMetric.WEIGHT],
                preliminary = preliminary,
            )
        }
    }

    fun create(
        definition: ReferenceMetricDefinition,
        value: Double?,
        interpretation: MetricInterpretation,
        weightKg: Double? = null,
        preliminary: Boolean = false,
    ): ReferenceMetricPresentation {
        require(definition.metric == interpretation.metric)
        val title = resources.getString(definition.nameRes)
        val visibleUnit = resources.getString(definition.visibleUnitRes)
        val displayableValue = value?.takeIf { it.isFinite() && it >= 0.0 }
        val visualNumber = displayableValue?.let {
            if (definition.metric == BodyMetric.WEIGHT) formatWeight(it, locale) else formatValue(it, definition.decimalPlaces)
        }
        val spokenValue = displayableValue?.let {
            val displayedValue = BigDecimal.valueOf(it)
                .setScale(if (definition.metric == BodyMetric.WEIGHT) 3 else definition.decimalPlaces, RoundingMode.HALF_EVEN)
                .toDouble()
            "$visualNumber ${spokenUnit(definition.unitKind, displayedValue)}"
        }

        val status: String
        val tone: ReferenceTone
        val zones: List<ReferenceZonePresentation>
        when (interpretation) {
            is MetricInterpretation.Rated -> {
                status = categoryLabel(interpretation.category)
                tone = interpretation.category.referenceTone()
                zones = presentationZones(interpretation, weightKg).map { zone ->
                    zonePresentation(definition, interpretation, zone)
                }
            }
            is MetricInterpretation.Unavailable -> {
                status = unavailableLabel(interpretation.reason)
                tone = ReferenceTone.UNAVAILABLE
                zones = emptyList()
            }
        }
        val visibleStatus = if (preliminary) {
            resources.getString(R.string.reference_preliminary_status, status)
        } else {
            status
        }
        val accessibility = accessibilityDescription(
            title = title,
            spokenValue = spokenValue,
            status = status,
            visibleStatus = visibleStatus,
            zones = zones,
            preliminary = preliminary,
        )
        val compactAccessibility = compactAccessibilityDescription(
            title = title,
            spokenValue = spokenValue,
            status = visibleStatus,
        )
        return ReferenceMetricPresentation(
            definition = definition,
            title = title,
            visualNumber = visualNumber,
            visualValue = visualNumber?.let { "$it $visibleUnit" }
                ?: resources.getString(R.string.reference_missing_value_symbol),
            visibleUnit = visibleUnit,
            spokenValue = spokenValue,
            status = visibleStatus,
            tone = tone,
            zones = zones,
            isPreliminary = preliminary,
            compactAccessibilityDescription = compactAccessibility,
            accessibilityDescription = accessibility,
            scaleValue = when (interpretation) {
                is MetricInterpretation.Rated -> interpretation.classifiedValue.toPresentationScaleValue(
                    interpretation.basis,
                    weightKg,
                )
                is MetricInterpretation.Unavailable -> null
            },
        )
    }

    fun group(presentations: List<ReferenceMetricPresentation>): List<ReferenceGroupPresentation> =
        ReferenceMetricGroup.entries.mapNotNull { group ->
            presentations.filter { it.definition.group == group }
                .takeIf { it.isNotEmpty() }
                ?.let { metrics ->
                    ReferenceGroupPresentation(
                        group = group,
                        title = group.titleRes?.let(resources::getString),
                        metrics = metrics,
                    )
                }
        }

    private fun zonePresentation(
        definition: ReferenceMetricDefinition,
        interpretation: MetricInterpretation.Rated,
        zone: ReferenceZone,
    ): ReferenceZonePresentation {
        val decimals = when (interpretation.basis) {
            ZoneBasis.CHRONOLOGICAL_AGE -> 0
            ZoneBasis.SKELETAL_MUSCLE_PERCENT,
            ZoneBasis.METRIC_VALUE,
            ZoneBasis.BMI_DERIVED_WEIGHT,
            ZoneBasis.FAT_MASS_INDEX,
            -> definition.decimalPlaces
        }
        val lower = zone.lowerInclusive?.let { formatBoundary(it, decimals) }
        val upper = zone.upperExclusive?.let { formatBoundary(it, decimals) }
        return ReferenceZonePresentation(
            category = zone.category,
            label = categoryLabel(zone.category),
            range = rangeText(lower, upper, spoken = false),
            spokenRange = rangeText(lower, upper, spoken = true),
            isCurrent = zone.category == interpretation.category,
            tone = zone.category.referenceTone(),
            lowerInclusive = zone.lowerInclusive,
            upperExclusive = zone.upperExclusive,
            lowerBoundaryLabel = lower,
            upperBoundaryLabel = upper,
        )
    }

    private fun accessibilityDescription(
        title: String,
        spokenValue: String?,
        status: String,
        visibleStatus: String,
        zones: List<ReferenceZonePresentation>,
        preliminary: Boolean,
    ): String {
        if (spokenValue == null) {
            return resources.getString(R.string.reference_accessibility_missing, title, visibleStatus)
        }
        if (zones.isEmpty()) {
            return resources.getString(R.string.reference_accessibility_unavailable, title, spokenValue, visibleStatus)
        }
        val spokenZones = zones.joinToString("; ") {
            resources.getString(R.string.reference_accessibility_zone, it.label, it.spokenRange)
        }
        val template = if (preliminary) {
            R.string.reference_accessibility_preview
        } else {
            R.string.reference_accessibility_rated
        }
        return resources.getString(template, title, spokenValue, status, spokenZones)
    }

    private fun compactAccessibilityDescription(
        title: String,
        spokenValue: String?,
        status: String,
    ): String = if (spokenValue == null) {
        resources.getString(R.string.reference_accessibility_missing, title, status)
    } else {
        resources.getString(R.string.reference_accessibility_unavailable, title, spokenValue, status)
    }

    private fun rangeText(lower: String?, upper: String?, spoken: Boolean): String = when {
        lower == null && upper != null -> resources.getString(
            if (spoken) R.string.reference_spoken_range_less_than else R.string.reference_range_less_than,
            upper,
        )
        lower != null && upper != null -> resources.getString(
            if (spoken) R.string.reference_spoken_range_between else R.string.reference_range_between,
            lower,
            upper,
        )
        lower != null -> resources.getString(
            if (spoken) R.string.reference_spoken_range_from else R.string.reference_range_from,
            lower,
        )
        else -> error("Reference zone must have at least one boundary")
    }

    private fun formatValue(value: Double, decimals: Int): String = numberFormat(decimals).apply {
        minimumFractionDigits = if (decimals == 1) 1 else 0
    }.format(value)

    private fun formatBoundary(value: Double, decimals: Int): String = numberFormat(decimals).format(value)

    private fun numberFormat(decimals: Int): NumberFormat = NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = 0
        maximumFractionDigits = decimals
        isGroupingUsed = true
    }

    private fun categoryLabel(category: ReferenceCategory): String = resources.getString(category.labelRes())

    private fun unavailableLabel(reason: UnavailableReason): String = resources.getString(
        when (reason) {
            UnavailableReason.NO_REFERENCE -> R.string.reference_unavailable_no_reference
            UnavailableReason.OUT_OF_DOMAIN -> R.string.reference_unavailable_out_of_domain
            UnavailableReason.MISSING_PROFILE_DATA -> R.string.reference_unavailable_missing_profile
            UnavailableReason.NO_DATA -> R.string.reference_unavailable_no_data
        },
    )

    private fun spokenUnit(kind: ReferenceUnitKind, value: Double): String = when (kind) {
        ReferenceUnitKind.KILOGRAM -> resources.getString(russianForm(value, R.string.reference_spoken_kg_one, R.string.reference_spoken_kg_few, R.string.reference_spoken_kg_many))
        ReferenceUnitKind.OHM -> resources.getString(russianForm(value, R.string.reference_spoken_ohm_one, R.string.reference_spoken_ohm_few, R.string.reference_spoken_ohm_many))
        ReferenceUnitKind.PERCENT -> resources.getString(russianForm(value, R.string.reference_spoken_percent_one, R.string.reference_spoken_percent_few, R.string.reference_spoken_percent_many))
        ReferenceUnitKind.YEAR -> resources.getString(russianForm(value, R.string.reference_spoken_year_one, R.string.reference_spoken_year_few, R.string.reference_spoken_year_many))
        ReferenceUnitKind.BMI -> resources.getString(R.string.reference_spoken_bmi)
        ReferenceUnitKind.LEVEL -> resources.getString(R.string.reference_spoken_level)
        ReferenceUnitKind.KCAL_PER_DAY -> resources.getString(R.string.reference_spoken_kcal_day)
    }

    @StringRes
    private fun russianForm(value: Double, one: Int, few: Int, many: Int): Int {
        val rounded = value.roundToLong()
        if (abs(value - rounded.toDouble()) > 0.000_001) return few
        val absolute = abs(rounded)
        val lastTwo = absolute % 100
        val last = absolute % 10
        return when {
            lastTwo in 11..14 -> many
            last == 1L -> one
            last in 2..4 -> few
            else -> many
        }
    }
}

private fun Double.toPresentationScaleValue(basis: ZoneBasis, weightKg: Double?): Double? = when (basis) {
    ZoneBasis.SKELETAL_MUSCLE_PERCENT -> weightKg
        ?.takeIf { it.isFinite() && it > 0.0 }
        ?.let { this * it / 100.0 }
    else -> this
}.takeIf { it?.isFinite() == true }

internal fun presentationZones(
    interpretation: MetricInterpretation.Rated,
    weightKg: Double?,
): List<ReferenceZone> {
    if (interpretation.basis != ZoneBasis.SKELETAL_MUSCLE_PERCENT) return interpretation.zones
    val validWeightKg = weightKg?.takeIf { it.isFinite() && it > 0.0 } ?: return emptyList()
    return interpretation.zones.map { zone ->
        zone.copy(
            lowerInclusive = zone.lowerInclusive?.let { it * validWeightKg / 100.0 },
            upperExclusive = zone.upperExclusive?.let { it * validWeightKg / 100.0 },
        )
    }
}

@StringRes
private fun ReferenceCategory.labelRes(): Int = when (this) {
    ReferenceCategory.VERY_LOW -> R.string.reference_category_very_low
    ReferenceCategory.LOW -> R.string.reference_category_low
    ReferenceCategory.BELOW_NORMAL -> R.string.reference_category_below_normal
    ReferenceCategory.NORMAL -> R.string.reference_category_normal
    ReferenceCategory.ABOVE_NORMAL -> R.string.reference_category_above_normal
    ReferenceCategory.GOOD -> R.string.reference_category_good
    ReferenceCategory.VERY_GOOD -> R.string.reference_category_very_good
    ReferenceCategory.HIGH -> R.string.reference_category_high
    ReferenceCategory.VERY_HIGH -> R.string.reference_category_very_high
    ReferenceCategory.HIGH_BMI -> R.string.reference_category_high_bmi
    ReferenceCategory.VERY_HIGH_BMI -> R.string.reference_category_very_high_bmi
    ReferenceCategory.YOUNGER -> R.string.reference_category_younger
    ReferenceCategory.MATCHES -> R.string.reference_category_matches
    ReferenceCategory.OLDER -> R.string.reference_category_older
}
