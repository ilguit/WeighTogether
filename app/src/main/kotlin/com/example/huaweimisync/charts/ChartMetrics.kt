package com.example.huaweimisync.charts

import com.example.huaweimisync.data.MeasurementEntity
import com.example.huaweimisync.data.MeasurementMetric

val DefaultChartMetricKeys: Set<String> = linkedSetOf(
    MeasurementMetric.WEIGHT_KG.name,
    MeasurementMetric.BODY_FAT_PERCENT.name,
)

fun chartMetricOptions(): List<ChartMetricOption> = MeasurementMetric.entries.map { metric ->
    ChartMetricOption(
        key = metric.name,
        displayName = metric.displayName,
        unit = metric.unit,
        decimalPlaces = metric.decimalPlaces,
        deltaUnit = if (metric.unit == PercentUnit) PercentagePointUnit else metric.unit,
    )
}

fun chartPointsForMetric(
    measurements: List<MeasurementEntity>,
    metric: MeasurementMetric,
): List<ChartPoint> = measurements.mapNotNull { measurement ->
    metric.valueOf(measurement)?.let { value ->
        ChartPoint(
            measuredAtEpochMillis = measurement.measuredAtEpochMillis,
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

private const val PercentUnit = "%"
const val PercentagePointUnit = "п.п."
