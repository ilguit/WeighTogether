package com.palixander.scalesync.charts

import com.palixander.scalesync.measurements.formatWeight

import androidx.compose.runtime.Immutable
import com.palixander.scalesync.domain.AccountId
import com.palixander.scalesync.measurements.formatMeasurementDateTime
import com.palixander.scalesync.ui.accounts.AccountSelectorUiState
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import kotlin.math.abs

@Immutable
data class ChartMetricOption(
    val key: String,
    val displayName: String,
    val unit: String,
    val decimalPlaces: Int,
    val deltaUnit: String = unit,
)

private val ChartMetricOption.isWeight: Boolean
    get() = key == "WEIGHT_KG" || key == "petWeightKg" || key == "weight_kg"

enum class ChartRangePreset {
    LAST_7_DAYS,
    LAST_30_DAYS,
    LAST_3_MONTHS,
    YEAR_TO_DATE,
    CUSTOM,
    ;

    fun rangeEndingOn(today: LocalDate): ChartDateRange? = when (this) {
        LAST_7_DAYS -> ChartDateRange(today.minusDays(6), today)
        LAST_30_DAYS -> ChartDateRange(today.minusDays(29), today)
        LAST_3_MONTHS -> ChartDateRange(today.minusMonths(3).plusDays(1), today)
        YEAR_TO_DATE -> ChartDateRange(today.withDayOfYear(1), today)
        CUSTOM -> null
    }
}

data class ChartDateRange(
    val startDate: LocalDate,
    val endDateInclusive: LocalDate,
) {
    init {
        require(!endDateInclusive.isBefore(startDate)) {
            "The end date must not precede the start date."
        }
    }
}

enum class ChartFilterSheet {
    RANGE,
    METRICS,
}

@Immutable
data class ChartPoint(
    val measuredAtEpochSecond: Long,
    val value: Double,
) {
    val measuredAt: Instant
        get() = Instant.ofEpochSecond(measuredAtEpochSecond)

    /** Vico uses millisecond x coordinates; invalid presentation values are rejected safely. */
    val xEpochMillis: Long?
        get() = measuredAtEpochSecond
            .takeIf { it in MIN_EPOCH_SECOND_FOR_MILLIS..MAX_EPOCH_SECOND_FOR_MILLIS }
            ?.times(1_000L)

    private companion object {
        const val MIN_EPOCH_SECOND_FOR_MILLIS = Long.MIN_VALUE / 1_000L
        const val MAX_EPOCH_SECOND_FOR_MILLIS = Long.MAX_VALUE / 1_000L
    }
}

@Immutable
data class ChartSeries(
    val metric: ChartMetricOption,
    val points: List<ChartPoint>,
)

/** Vico needs an actual x interval to derive a finite horizontal step safely. */
fun isChartRenderable(points: List<ChartPoint>): Boolean {
    val distinctTimestamps = HashSet<Long>(2)
    points.forEach { point ->
        val xEpochMillis = point.xEpochMillis ?: return false
        distinctTimestamps += xEpochMillis
    }
    return distinctTimestamps.size >= 2
}

data class ChartValueSummary(
    val current: ChartPoint?,
    val previous: ChartPoint?,
) {
    val delta: Double?
        get() = if (current != null && previous != null) current.value - previous.value else null
}

@Immutable
data class ChartStatistics(
    val minimum: Double,
    val maximum: Double,
    val average: Double,
)

@Immutable
data class ChartsUiState(
    val startDate: LocalDate,
    val endDateInclusive: LocalDate,
    val currentDate: LocalDate,
    val metricOptions: List<ChartMetricOption>,
    val selectedMetricKeys: Set<String>,
    val series: List<ChartSeries>,
    val rangePreset: ChartRangePreset = ChartRangePreset.LAST_7_DAYS,
    val activeFilterSheet: ChartFilterSheet? = null,
    val isCustomDatePickerOpen: Boolean = false,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val accountSelector: AccountSelectorUiState = AccountSelectorUiState(
        accounts = emptyList(),
        selectedAccountId = null,
        primaryAccountId = null,
    ),
) {
    init {
        require(!endDateInclusive.isBefore(startDate)) { "The end date must not precede the start date." }
    }

    val selectedMetrics: List<ChartMetricOption>
        get() = metricOptions.filter { it.key in selectedMetricKeys }

    companion object {
        fun initial(
            metricOptions: List<ChartMetricOption>,
            defaultMetricKeys: Set<String>,
            clock: Clock = Clock.systemDefaultZone(),
        ): ChartsUiState {
            val today = LocalDate.now(clock)
            val range = requireNotNull(ChartRangePreset.LAST_7_DAYS.rangeEndingOn(today))
            return ChartsUiState(
                startDate = range.startDate,
                endDateInclusive = range.endDateInclusive,
                currentDate = today,
                metricOptions = metricOptions,
                selectedMetricKeys = defaultMetricKeys,
                series = emptyList(),
            )
        }
    }
}

/** All chart intents are handled by the owner of [ChartsUiState]. */
data class ChartsCallbacks(
    val openRangeFilter: () -> Unit,
    val openMetricFilter: () -> Unit,
    val dismissFilterSheet: () -> Unit,
    val selectRangePreset: (ChartRangePreset) -> Unit,
    val dismissCustomDatePicker: () -> Unit,
    val setDateRange: (startDate: LocalDate, endDateInclusive: LocalDate) -> Unit,
    val setMetricSelected: (key: String, selected: Boolean) -> Unit,
    val selectAll: () -> Unit,
    val clearSelection: () -> Unit,
    val doneSelectingMetrics: () -> Unit,
    val shiftDateWindowByDays: (Long) -> Unit = {},
    val onAccountSelected: (AccountId) -> Unit = {},
)

data class MeasurementEpochRange(
    val startInclusiveEpochSecond: Long,
    val endExclusiveEpochSecond: Long,
)

data class ChartXRange(
    val minX: Double,
    val maxX: Double,
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
        startInclusiveEpochSecond = startDate.atStartOfDay(zoneId).toEpochSecond(),
        endExclusiveEpochSecond = endDateInclusive.plusDays(1).atStartOfDay(zoneId).toEpochSecond(),
    )
}

/**
 * Returns the complete local-calendar interval selected by the user as Vico x coordinates.
 *
 * The upper bound is the start of the day after [endDateInclusive], rather than a fixed number
 * of elapsed hours after [startDate]. This keeps the selected calendar days intact across DST.
 */
fun chartXRange(
    startDate: LocalDate,
    endDateInclusive: LocalDate,
    zoneId: ZoneId = ZoneId.systemDefault(),
): ChartXRange {
    val epochRange = inclusiveDateRangeToEpochRange(startDate, endDateInclusive, zoneId)
    return ChartXRange(
        minX = Math.multiplyExact(epochRange.startInclusiveEpochSecond, 1_000L).toDouble(),
        maxX = Math.multiplyExact(epochRange.endExclusiveEpochSecond, 1_000L).toDouble(),
    )
}

fun formatChartMarkerText(
    measuredAtEpochSecond: Long,
    value: Double,
    metric: ChartMetricOption,
    zoneId: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): String {
    return formatChartMarkerText(
        measuredAt = Instant.ofEpochSecond(measuredAtEpochSecond),
        value = value,
        metric = metric,
        zoneId = zoneId,
        locale = locale,
    )
}

fun formatChartMarkerText(
    point: ChartPoint,
    metric: ChartMetricOption,
    zoneId: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): String = formatChartMarkerText(
    measuredAt = point.measuredAt,
    value = point.value,
    metric = metric,
    zoneId = zoneId,
    locale = locale,
)

private fun formatChartMarkerText(
    measuredAt: Instant,
    value: Double,
    metric: ChartMetricOption,
    zoneId: ZoneId,
    locale: Locale,
): String {
    val dateTime = formatMeasurementDateTime(measuredAt, zoneId, locale)
    val formattedValue = if (metric.isWeight) formatWeight(value, locale) else decimalFormat(metric.decimalPlaces, locale).format(value)
    return buildString {
        append(dateTime)
        append('\n')
        append(formattedValue)
        if (metric.unit.isNotBlank()) append(" ${metric.unit}")
    }
}

fun orderedChartPoints(points: List<ChartPoint>): List<ChartPoint> =
    points.sortedBy(ChartPoint::measuredAtEpochSecond)

fun chartValueSummary(points: List<ChartPoint>): ChartValueSummary {
    val ordered = orderedChartPoints(points)
    return ChartValueSummary(
        current = ordered.lastOrNull(),
        previous = ordered.getOrNull(ordered.lastIndex - 1),
    )
}

fun currentChartPoint(points: List<ChartPoint>): ChartPoint? = chartValueSummary(points).current

fun previousChartPoint(points: List<ChartPoint>): ChartPoint? = chartValueSummary(points).previous

fun chartDelta(points: List<ChartPoint>): Double? = chartValueSummary(points).delta

fun chartStatistics(points: List<ChartPoint>): ChartStatistics? {
    if (points.isEmpty()) return null
    return ChartStatistics(
        minimum = points.minOf(ChartPoint::value),
        maximum = points.maxOf(ChartPoint::value),
        average = points.map(ChartPoint::value).average(),
    )
}

fun formatChartCurrentValue(
    value: Double?,
    metric: ChartMetricOption,
    locale: Locale = Locale.getDefault(),
): String = if (metric.isWeight && value != null) "${formatWeight(value, locale)} ${metric.unit}" else
    formatChartValue(value, metric.unit, metric.decimalPlaces, locale)

fun formatChartStatistic(
    value: Double?,
    metric: ChartMetricOption,
    locale: Locale = Locale.getDefault(),
): String = if (metric.isWeight && value != null) "${formatWeight(value, locale)} ${metric.unit}" else
    formatChartValue(value, metric.unit, metric.decimalPlaces, locale)

fun formatChartDelta(
    delta: Double?,
    metric: ChartMetricOption,
    locale: Locale = Locale.getDefault(),
): String {
    if (delta == null) return MissingChartValue
    val absoluteValue = decimalFormat(metric.decimalPlaces, locale).format(abs(delta))
    val sign = when {
        delta > 0.0 -> "+"
        delta < 0.0 -> "−"
        else -> ""
    }
    return buildString {
        append(sign)
        append(absoluteValue)
        if (metric.deltaUnit.isNotBlank()) append(" ${metric.deltaUnit}")
    }
}

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

private fun decimalFormat(decimalPlaces: Int, locale: Locale): DecimalFormat {
    val pattern = buildString {
        append('0')
        if (decimalPlaces > 0) {
            append('.')
            repeat(decimalPlaces) { append('0') }
        }
    }
    return DecimalFormat(pattern, DecimalFormatSymbols(locale))
}

private fun formatChartValue(
    value: Double?,
    unit: String,
    decimalPlaces: Int,
    locale: Locale,
): String {
    if (value == null) return MissingChartValue
    return buildString {
        append(decimalFormat(decimalPlaces, locale).format(value))
        if (unit.isNotBlank()) append(" $unit")
    }
}

private fun tenToPower(exponent: Int): Double {
    var value = 1.0
    repeat(exponent) { value *= 10.0 }
    return value
}

private const val CONSTANT_PADDING_FRACTION = 0.05
private const val VARIABLE_PADDING_FRACTION = 0.08
private const val MissingChartValue = "—"
