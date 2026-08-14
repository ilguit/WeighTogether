package com.example.huaweimisync.charts

import androidx.compose.runtime.Immutable
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.abs

@Immutable
data class ChartMetricOption(
    val key: String,
    val displayName: String,
    val unit: String,
    val decimalPlaces: Int,
)

@Immutable
data class ChartPoint(
    val measuredAtEpochMillis: Long,
    val value: Double,
)

@Immutable
data class ChartSeries(
    val metric: ChartMetricOption,
    val points: List<ChartPoint>,
)

@Immutable
data class ChartsUiState(
    val startDate: LocalDate,
    val endDateInclusive: LocalDate,
    val metricOptions: List<ChartMetricOption>,
    val selectedMetricKeys: Set<String>,
    val series: List<ChartSeries>,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
) {
    init {
        require(!endDateInclusive.isBefore(startDate)) { "The end date must not precede the start date." }
    }

    val selectedMetrics: List<ChartMetricOption>
        get() = metricOptions.filter { it.key in selectedMetricKeys }

    companion object {
        fun initial(
            metricOptions: List<ChartMetricOption>,
            defaultMetricKey: String,
            clock: Clock = Clock.systemDefaultZone(),
        ): ChartsUiState {
            val today = LocalDate.now(clock)
            return ChartsUiState(
                startDate = today.minusDays(DEFAULT_RANGE_DAYS - 1),
                endDateInclusive = today,
                metricOptions = metricOptions,
                selectedMetricKeys = setOf(defaultMetricKey),
                series = emptyList(),
            )
        }
    }
}

data class MeasurementEpochRange(
    val startInclusive: Long,
    val endExclusive: Long,
)

data class ChartYRange(val min: Double, val max: Double)

fun LocalDate.toDatePickerUtcMillis(): Long =
    atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

fun datePickerUtcMillisToLocalDate(utcMillis: Long): LocalDate =
    Instant.ofEpochMilli(utcMillis).atZone(ZoneOffset.UTC).toLocalDate()

fun inclusiveDateRangeToEpochRange(
    startDate: LocalDate,
    endDateInclusive: LocalDate,
    zoneId: ZoneId = ZoneId.systemDefault(),
): MeasurementEpochRange {
    require(!endDateInclusive.isBefore(startDate)) { "The end date must not precede the start date." }
    return MeasurementEpochRange(
        startInclusive = startDate.atStartOfDay(zoneId).toInstant().toEpochMilli(),
        endExclusive = endDateInclusive.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli(),
    )
}

fun orderedChartPoints(points: List<ChartPoint>): List<ChartPoint> =
    points.sortedBy(ChartPoint::measuredAtEpochMillis)

fun chartYRange(points: List<ChartPoint>, decimalPlaces: Int): ChartYRange? {
    if (points.isEmpty()) return null
    val min = points.minOf(ChartPoint::value)
    val max = points.maxOf(ChartPoint::value)
    val span = max - min
    val minimumPadding = 1.0 / tenToPower(decimalPlaces.coerceAtLeast(0))
    val padding = if (span == 0.0) {
        maxOf(abs(min) * CONSTANT_PADDING_FRACTION, minimumPadding)
    } else {
        maxOf(span * VARIABLE_PADDING_FRACTION, minimumPadding)
    }
    return ChartYRange(min - padding, max + padding)
}

private fun tenToPower(exponent: Int): Double {
    var value = 1.0
    repeat(exponent) { value *= 10.0 }
    return value
}

private const val DEFAULT_RANGE_DAYS = 7L
private const val CONSTANT_PADDING_FRACTION = 0.05
private const val VARIABLE_PADDING_FRACTION = 0.08
