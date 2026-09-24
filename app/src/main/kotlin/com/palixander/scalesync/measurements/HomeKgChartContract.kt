package com.palixander.scalesync.measurements

import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

/** Stable presentation-boundary colors shared by chart lines, markers, and the legend. */
enum class HomeKgChartColorToken(
    val argb: Int,
) {
    BLUE(0xFF1A73E8.toInt()),
    RED(0xFFE53935.toInt()),
    CYAN(0xFF00ACC1.toInt()),
    GREEN(0xFF43A047.toInt()),
    PURPLE(0xFF8E24AA.toInt()),
    ORANGE(0xFFFB8C00.toInt()),
    MAGENTA(0xFFD81B60.toInt()),
    TEAL(0xFF00897B.toInt()),
}

/**
 * The fixed catalog for the home chart. Explicit keys are persisted instead of enum names so
 * refactoring a Kotlin symbol cannot silently change the on-device preference contract.
 */
enum class HomeKgChartMetric(
    val key: String,
    val label: String,
    val color: HomeKgChartColorToken,
    internal val measurementField: MeasurementField,
) {
    WEIGHT(
        key = "weight_kg",
        label = "Вес",
        color = HomeKgChartColorToken.BLUE,
        measurementField = MeasurementField.WEIGHT_KG,
    ),
    BODY_FAT_MASS(
        key = "body_fat_mass_kg",
        label = "Масса жира",
        color = HomeKgChartColorToken.RED,
        measurementField = MeasurementField.BODY_FAT_MASS_KG,
    ),
    WATER_MASS(
        key = "water_mass_kg",
        label = "Масса воды",
        color = HomeKgChartColorToken.CYAN,
        measurementField = MeasurementField.WATER_MASS_KG,
    ),
    MUSCLE_MASS(
        key = "muscle_mass_kg",
        label = "Мышечная масса",
        color = HomeKgChartColorToken.GREEN,
        measurementField = MeasurementField.MUSCLE_MASS_KG,
    ),
    SKELETAL_MUSCLE_MASS(
        key = "skeletal_muscle_mass_kg",
        label = "Скелетные мышцы",
        color = HomeKgChartColorToken.PURPLE,
        measurementField = MeasurementField.SKELETAL_MUSCLE_MASS_KG,
    ),
    BONE_MASS(
        key = "bone_mass_kg",
        label = "Костная масса",
        color = HomeKgChartColorToken.ORANGE,
        measurementField = MeasurementField.BONE_MASS_KG,
    ),
    PROTEIN_MASS(
        key = "protein_mass_kg",
        label = "Масса белка",
        color = HomeKgChartColorToken.MAGENTA,
        measurementField = MeasurementField.PROTEIN_MASS_KG,
    ),
    LEAN_BODY_MASS(
        key = "lean_body_mass_kg",
        label = "Безжировая масса",
        color = HomeKgChartColorToken.TEAL,
        measurementField = MeasurementField.LEAN_BODY_MASS_KG,
    ),
    ;

    val unit: String
        get() = measurementField.unit

    val decimalPlaces: Int
        get() = measurementField.decimalPlaces
}

data class HomeKgChartPeriod(
    val startDate: LocalDate,
    val endDateInclusive: LocalDate,
    val startInclusiveEpochSecond: Long,
    val endExclusiveEpochSecond: Long,
) {
    init {
        require(!endDateInclusive.isBefore(startDate)) { "The chart end date must not precede its start." }
        require(endExclusiveEpochSecond > startInclusiveEpochSecond) { "The chart period must not be empty." }
    }

    fun contains(epochSecond: Long): Boolean =
        epochSecond >= startInclusiveEpochSecond && epochSecond < endExclusiveEpochSecond
}

data class HomeKgChartPoint(
    val measurementId: String,
    val measuredAtEpochSecond: Long,
    val valueKg: Double,
)

data class HomeKgChartSeries(
    val key: String,
    val label: String,
    val unit: String,
    val decimalPlaces: Int,
    val color: HomeKgChartColorToken,
    val points: List<HomeKgChartPoint>,
)

data class HomeKgChartUiState(
    val period: HomeKgChartPeriod,
    val series: List<HomeKgChartSeries>,
    val activeSeriesKeys: Set<String>,
) {
    init {
        require(series.map(HomeKgChartSeries::key).distinct().size == series.size) {
            "Home chart series keys must be unique."
        }
        require(activeSeriesKeys.all { activeKey -> series.any { it.key == activeKey } }) {
            "Only catalog series can be active."
        }
    }
}

val HomeKgChartSeriesCatalog: List<HomeKgChartMetric> = HomeKgChartMetric.entries

val DefaultHomeKgChartSeriesKeys: Set<String> = HomeKgChartSeriesCatalog
    .mapTo(linkedSetOf(), HomeKgChartMetric::key)

/** Missing persisted state enables all series; explicit empty state keeps every series disabled. */
fun restoreHomeKgChartSeriesKeys(persistedKeys: Set<String>?): Set<String> {
    if (persistedKeys == null) return DefaultHomeKgChartSeriesKeys
    return HomeKgChartSeriesCatalog
        .map(HomeKgChartMetric::key)
        .filterTo(linkedSetOf()) { it in persistedKeys }
}

/** Returns null for an unknown key so callers do not overwrite preferences for stale UI events. */
fun toggleHomeKgChartSeriesKey(
    persistedKeys: Set<String>?,
    toggledKey: String,
): Set<String>? {
    if (HomeKgChartSeriesCatalog.none { it.key == toggledKey }) return null
    val current = restoreHomeKgChartSeriesKeys(persistedKeys)
    return HomeKgChartSeriesCatalog
        .map(HomeKgChartMetric::key)
        .filterTo(linkedSetOf()) { key ->
            if (key == toggledKey) key !in current else key in current
        }
}

fun homeKgChartPeriod(
    zoneId: ZoneId = ZoneId.systemDefault(),
    clock: Clock = Clock.system(zoneId),
): HomeKgChartPeriod = homeKgChartPeriod(
    today = LocalDate.now(clock.withZone(zoneId)),
    zoneId = zoneId,
)

internal fun homeKgChartPeriod(
    today: LocalDate,
    zoneId: ZoneId,
): HomeKgChartPeriod {
    val startDate = today.minusDays(HOME_KG_CHART_PREVIOUS_DAY_COUNT)
    return HomeKgChartPeriod(
        startDate = startDate,
        endDateInclusive = today,
        startInclusiveEpochSecond = startDate.atStartOfDay(zoneId).toEpochSecond(),
        endExclusiveEpochSecond = today.plusDays(1).atStartOfDay(zoneId).toEpochSecond(),
    )
}

/** Builds all eight catalog series, including empty ones, in stable catalog order. */
fun buildHomeKgChartUiState(
    measurements: List<MeasurementUiItem>,
    persistedActiveSeriesKeys: Set<String>? = null,
    zoneId: ZoneId = ZoneId.systemDefault(),
    clock: Clock = Clock.system(zoneId),
): HomeKgChartUiState {
    val period = homeKgChartPeriod(zoneId = zoneId, clock = clock)
    return buildHomeKgChartUiState(
        measurements = measurements,
        persistedActiveSeriesKeys = persistedActiveSeriesKeys,
        period = period,
    )
}

internal fun buildHomeKgChartUiState(
    measurements: List<MeasurementUiItem>,
    persistedActiveSeriesKeys: Set<String>?,
    currentDate: LocalDate,
    zoneId: ZoneId,
): HomeKgChartUiState = buildHomeKgChartUiState(
    measurements = measurements,
    persistedActiveSeriesKeys = persistedActiveSeriesKeys,
    period = homeKgChartPeriod(today = currentDate, zoneId = zoneId),
)

private fun buildHomeKgChartUiState(
    measurements: List<MeasurementUiItem>,
    persistedActiveSeriesKeys: Set<String>?,
    period: HomeKgChartPeriod,
): HomeKgChartUiState {
    val orderedMeasurements = measurements
        .asSequence()
        .sortedBy(MeasurementUiItem::measuredAtEpochSecond)
        .toList()
    val series = HomeKgChartSeriesCatalog.map { metric ->
        HomeKgChartSeries(
            key = metric.key,
            label = metric.label,
            unit = metric.unit,
            decimalPlaces = metric.decimalPlaces,
            color = metric.color,
            points = orderedMeasurements.mapNotNull { measurement ->
                metric.valueOf(measurement)?.takeIf(Double::isFinite)?.let { value ->
                    HomeKgChartPoint(
                        measurementId = measurement.presentationKey,
                        measuredAtEpochSecond = measurement.measuredAtEpochSecond,
                        valueKg = value,
                    )
                }
            },
        )
    }
    return HomeKgChartUiState(
        period = period,
        series = series,
        activeSeriesKeys = restoreHomeKgChartSeriesKeys(persistedActiveSeriesKeys),
    )
}

private fun HomeKgChartMetric.valueOf(measurement: MeasurementUiItem): Double? {
    if (this == HomeKgChartMetric.WEIGHT) return measurement.values.weightKg
    if (measurement.type == MeasurementUiType.WEIGHT_ONLY) return null
    return measurement.values[measurementField]
}

private const val HOME_KG_CHART_PREVIOUS_DAY_COUNT = 13L
