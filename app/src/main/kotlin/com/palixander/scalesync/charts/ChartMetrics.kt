package com.palixander.scalesync.charts

import com.palixander.scalesync.R
import com.palixander.scalesync.data.MeasurementEntity
import com.palixander.scalesync.data.MeasurementMetric

val DefaultChartMetricKeys: Set<String> = linkedSetOf(
    MeasurementMetric.WEIGHT_KG.name,
    MeasurementMetric.BODY_FAT_PERCENT.name,
)

fun chartMetricOptions(resolveString: (Int) -> String): List<ChartMetricOption> = MeasurementMetric.entries.map { metric ->
    ChartMetricOption(
        key = metric.name,
        displayName = resolveString(metric.displayNameRes),
        unit = resolveString(metric.unitRes),
        decimalPlaces = metric.decimalPlaces,
        deltaUnit = resolveString(
            if (metric.unitRes == R.string.unit_percent) R.string.unit_percentage_point else metric.unitRes,
        ),
    )
}

fun chartPointsForMetric(
    measurements: List<MeasurementEntity>,
    metric: MeasurementMetric,
): List<ChartPoint> = measurements.mapNotNull { measurement ->
    metric.valueOf(measurement)?.let { value ->
        ChartPoint(
            measuredAtEpochSecond = measurement.measuredAtEpochSecond,
            value = value,
        )
    }
}

/**
 * Resolves the nullable SharedPreferences contract for chart metric selection.
 *
 * A missing value means first launch and uses the two defaults. An explicitly saved empty set
 * remains empty. Unknown keys are ignored, but a non-empty persisted set containing no known keys
 * is treated as corrupted/obsolete data and falls back to the defaults.
 */
fun restoreChartMetricSelection(persistedKeys: Set<String>?): Set<MeasurementMetric> {
    if (persistedKeys == null) return defaultChartMetrics()
    if (persistedKeys.isEmpty()) return emptySet()

    val restored = MeasurementMetric.entries
        .filterTo(linkedSetOf()) { it.name in persistedKeys }
    return restored.ifEmpty(::defaultChartMetrics)
}

fun Set<MeasurementMetric>.toPersistedChartMetricKeys(): Set<String> =
    mapTo(linkedSetOf(), MeasurementMetric::name)

private fun defaultChartMetrics(): Set<MeasurementMetric> = linkedSetOf(
    MeasurementMetric.WEIGHT_KG,
    MeasurementMetric.BODY_FAT_PERCENT,
)
