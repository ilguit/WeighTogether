package com.palixander.scalesync.charts

import com.palixander.scalesync.data.MeasurementEntity
import com.palixander.scalesync.measurements.DefaultHomeKgChartSeriesKeys
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import kotlin.math.max

enum class AnalyticalChartType {
    MORNING,
    DAILY_MINIMUM,
    HOURLY,
}
enum class MorningFilterMode {
    MANUAL,
    AUTOMATIC,
}

/** Local minute boundaries, with an exclusive end. 1440 represents midnight at day's end. */
data class MorningWindow(val startMinute: Int = 300, val endMinute: Int = 720) {
    init {
        require(startMinute in 0..1439 && endMinute in 1..1440 && startMinute < endMinute)
    }
    val durationMinutes: Int get() = endMinute - startMinute
}

data class AnalyticalChartSettings(
    val type: AnalyticalChartType,
    val activeSeriesKeys: Set<String> = DefaultHomeKgChartSeriesKeys,
    val morningWindow: MorningWindow = MorningWindow(),
    val morningMode: MorningFilterMode = MorningFilterMode.MANUAL,
)

data class OutlierClassification(
    val excluded: List<MeasurementEntity>,
    val sufficientHistory: Boolean,
    val thresholdKg: Double?,
)

data class AnalyticalMeasurements(
    val retained: List<MeasurementEntity>,
    val excluded: List<MeasurementEntity>,
    val sufficientHistory: Boolean,
)

data class MorningPreview(
    val window: MorningWindow,
    val baseCount: Int,
    val retained: List<MeasurementEntity>,
    val excludedByTimeCount: Int,
    val excludedByFilter: List<MeasurementEntity>,
    val sufficientHistory: Boolean,
)

sealed interface MorningSelectionResult {
    data class Success(val preview: MorningPreview, val acceptedWindows: List<MorningWindow>) : MorningSelectionResult
    data object InsufficientData : MorningSelectionResult
}

private val measurementOrder = compareBy(MeasurementEntity::measuredAtEpochSecond, MeasurementEntity::id)
private const val MAX_NEIGHBOR_SECONDS = 7L * 24 * 60 * 60
private fun MeasurementEntity.hasUsableWeight() = weightKg.isFinite() && weightKg > 0
private fun MeasurementEntity.localTime(zone: ZoneId) = Instant.ofEpochSecond(measuredAtEpochSecond).atZone(zone)
private fun MorningWindow.contains(row: MeasurementEntity, zone: ZoneId): Boolean {
    val local = row.localTime(zone)
    val minute = local.hour * 60 + local.minute
    return minute >= startMinute && minute < endMinute
}
private fun neighboring(a: MeasurementEntity, b: MeasurementEntity): Boolean =
    b.measuredAtEpochSecond > a.measuredAtEpochSecond &&
        b.measuredAtEpochSecond - a.measuredAtEpochSecond <= MAX_NEIGHBOR_SECONDS

private fun weightDistance(a: Double, b: Double): Double =
    BigDecimal.valueOf(a).subtract(BigDecimal.valueOf(b)).abs().toDouble()

private fun differences(rows: List<MeasurementEntity>): List<Double> = rows.zipWithNext()
    .filter { (a, b) -> neighboring(a, b) }
    .map { (a, b) -> weightDistance(a.weightKg, b.weightKg) }

private fun median(values: List<Double>): Double {
    val sorted = values.sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[middle] else sorted[middle - 1] / 2 + sorted[middle] / 2
}
private fun sufficient(rows: List<MeasurementEntity>, deltas: List<Double>): Boolean =
    rows.map { it.measuredAtEpochSecond }.distinct().size >= 7 && deltas.size >= 6

/** All flags are computed simultaneously; adjacent candidates are intentionally retained. */
fun classifyAnalyticalOutliers(measurements: List<MeasurementEntity>): OutlierClassification {
    val rows = measurements.filter { it.hasUsableWeight() }.sortedWith(measurementOrder)
    val deltas = differences(rows)
    if (!sufficient(rows, deltas)) return OutlierClassification(emptyList(), false, null)
    val threshold = max(0.5, 3 * median(deltas))
    val candidates = BooleanArray(rows.size)
    for (index in 1 until rows.lastIndex) {
        val a = rows[index - 1]
        val b = rows[index]
        val c = rows[index + 1]
        candidates[index] = neighboring(a, b) && neighboring(b, c) &&
            ((b.weightKg > a.weightKg && b.weightKg > c.weightKg) ||
                (b.weightKg < a.weightKg && b.weightKg < c.weightKg)) &&
            weightDistance(b.weightKg, a.weightKg) > threshold && weightDistance(b.weightKg, c.weightKg) > threshold &&
            weightDistance(a.weightKg, c.weightKg) <= threshold
    }
    return OutlierClassification(
        rows.filterIndexed { i, _ -> candidates[i] && !candidates[i - 1] && !candidates[i + 1] },
        true,
        threshold,
    )
}

fun dailyMinimumMeasurements(measurements: List<MeasurementEntity>, zone: ZoneId): AnalyticalMeasurements {
    val minima = measurements.filter { it.hasUsableWeight() }
        .groupBy { it.localTime(zone).toLocalDate() }
        .values.map { day -> day.minWith(compareBy<MeasurementEntity> { it.weightKg }.then(measurementOrder)) }
        .sortedWith(measurementOrder)
    val classification = classifyAnalyticalOutliers(minima)
    val excludedIds = classification.excluded.mapTo(hashSetOf()) { it.id }
    return AnalyticalMeasurements(
        retained = minima.filter { it.id !in excludedIds },
        excluded = classification.excluded,
        sufficientHistory = classification.sufficientHistory,
    )
}

/** All source records count, including records with unusable weight. */
fun hourlyMeasurementCounts(measurements: List<MeasurementEntity>, zone: ZoneId): List<Int> {
    val counts = IntArray(24)
    measurements.forEach { counts[it.localTime(zone).hour]++ }
    return counts.toList()
}

fun morningMeasurements(
    measurements: List<MeasurementEntity>,
    window: MorningWindow,
    mode: MorningFilterMode,
    zone: ZoneId,
): MorningPreview {
    val baseWindow = if (mode == MorningFilterMode.AUTOMATIC) MorningWindow() else window
    val base = measurements.filter { baseWindow.contains(it, zone) }.sortedWith(measurementOrder)
    return morningPreview(base, window, classifyAnalyticalOutliers(base), zone)
}

private fun morningPreview(
    base: List<MeasurementEntity>,
    window: MorningWindow,
    classification: OutlierClassification,
    zone: ZoneId,
): MorningPreview {
    val inside = base.filter { window.contains(it, zone) }
    val excludedIds = classification.excluded.mapTo(hashSetOf()) { it.id }
    return MorningPreview(
        window = window,
        baseCount = base.size,
        retained = inside.filter { it.id !in excludedIds },
        excludedByTimeCount = base.size - inside.size,
        excludedByFilter = inside.filter { it.id in excludedIds },
        sufficientHistory = classification.sufficientHistory,
    )
}

private data class WindowScore(
    val window: MorningWindow,
    val excludedCount: Int,
    val usableCount: Int,
    val medianDelta: Double,
    val dateCount: Int,
    val retainedCount: Int,
) {
    fun compareFraction(other: WindowScore): Int =
        (excludedCount.toLong() * other.usableCount).compareTo(other.excludedCount.toLong() * usableCount)
    fun improvesStability(other: WindowScore): Boolean =
        compareFraction(other) < 0 || (compareFraction(other) == 0 && medianDelta < other.medianDelta)
}
private val scoreOrder = Comparator<WindowScore> { a, b ->
    a.compareFraction(b).takeIf { it != 0 }
        ?: a.medianDelta.compareTo(b.medianDelta).takeIf { it != 0 }
        ?: b.dateCount.compareTo(a.dateCount).takeIf { it != 0 }
        ?: b.retainedCount.compareTo(a.retainedCount).takeIf { it != 0 }
        ?: a.window.startMinute.compareTo(b.window.startMinute)
}

/** Starts afresh at 05:00–12:00, evaluates two candidates per step, and never reclassifies. */
fun selectMorningWindow(measurements: List<MeasurementEntity>, zone: ZoneId): MorningSelectionResult {
    val base = measurements.filter { MorningWindow().contains(it, zone) }.sortedWith(measurementOrder)
    val classification = classifyAnalyticalOutliers(base)
    if (!classification.sufficientHistory) return MorningSelectionResult.InsufficientData
    val excludedIds = classification.excluded.mapTo(hashSetOf()) { it.id }
    fun score(window: MorningWindow): WindowScore? {
        val usable = base.filter { it.hasUsableWeight() && window.contains(it, zone) }
        val retained = usable.filter { it.id !in excludedIds }
        val deltas = differences(retained)
        if (!sufficient(retained, deltas)) return null
        return WindowScore(
            window = window,
            excludedCount = usable.size - retained.size,
            usableCount = usable.size,
            medianDelta = median(deltas),
            dateCount = retained.map { it.localTime(zone).toLocalDate() }.distinct().size,
            retainedCount = retained.size,
        )
    }
    var current = score(MorningWindow()) ?: return MorningSelectionResult.InsufficientData
    val trace = mutableListOf<MorningWindow>()
    while (current.window.durationMinutes > 120) {
        val window = current.window
        val next = listOf(
            MorningWindow(window.startMinute + 30, window.endMinute),
            MorningWindow(window.startMinute, window.endMinute - 30),
        ).mapNotNull(::score).minWithOrNull(scoreOrder)
        if (next == null) {
            if (window.durationMinutes > 180) return MorningSelectionResult.InsufficientData
            break
        }
        if (window.durationMinutes <= 180 && !next.improvesStability(current)) break
        current = next
        trace += current.window
    }
    return MorningSelectionResult.Success(morningPreview(base, current.window, classification, zone), trace)
}
