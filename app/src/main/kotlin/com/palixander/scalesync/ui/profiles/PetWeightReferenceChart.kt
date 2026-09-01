package com.palixander.scalesync.ui.profiles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.palixander.scalesync.charts.ChartPoint
import com.palixander.scalesync.charts.ChartSeries
import com.palixander.scalesync.charts.chartXRange
import com.palixander.scalesync.charts.rememberChartBottomAxis
import com.palixander.scalesync.charts.rememberChartMarker
import com.palixander.scalesync.charts.rememberChartStartAxis
import com.palixander.scalesync.charts.rememberSmoothChartLine
import com.palixander.scalesync.charts.rememberSmoothLineLayer
import com.palixander.scalesync.ui.components.HuaweiSurface
import com.palixander.scalesync.ui.theme.HuaweiDimensions
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.CartesianDrawingContext
import com.patrykandpatrick.vico.compose.cartesian.Zoom
import com.patrykandpatrick.vico.compose.cartesian.axis.Axis
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.compose.cartesian.data.lineModel
import com.patrykandpatrick.vico.compose.cartesian.decoration.Decoration
import com.patrykandpatrick.vico.compose.cartesian.marker.DefaultCartesianMarker
import com.patrykandpatrick.vico.compose.cartesian.marker.LineCartesianLayerMarkerTarget
import com.patrykandpatrick.vico.compose.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import com.patrykandpatrick.vico.compose.common.data.ExtraStore
import com.patrykandpatrick.vico.compose.common.Fill
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal object PetWeightChartTestTags {
    const val Chart = "pet-weight-reference-chart"
    const val ReferenceDetails = "pet-weight-reference-details"
    const val Unavailable = "pet-weight-reference-unavailable"
}

internal data class PetWeightChartRange(val min: Double, val max: Double)

internal enum class PetWeightReferenceSeriesKind {
    LOWER,
    MEDIAN_LOWER,
    MEDIAN_UPPER,
    UPPER,
}

internal data class PetWeightReferenceChartSeries(
    val kind: PetWeightReferenceSeriesKind,
    val points: List<Pair<LocalDate, Double>>,
)

internal data class BreedWeightReferenceChartSeries(
    val kind: BreedWeightReferenceSeriesKind,
    val points: List<Pair<LocalDate, Double>>,
    val showsPointMarkers: Boolean = false,
    val xEpochMillis: List<Long> = points.map { (date, _) ->
        date.atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()
    },
)

internal enum class BreedWeightReferenceSeriesKind {
    LOWER_BOUNDARY,
    UPPER_BOUNDARY,
    CENTER,
}

internal data class BreedWeightReferenceSeriesPresentation(
    val strokeWidthDp: Int,
    val pointSizeDp: Int,
    val lowEmphasis: Boolean,
)

internal fun breedWeightReferenceSeriesPresentation(
    kind: BreedWeightReferenceSeriesKind,
): BreedWeightReferenceSeriesPresentation = when (kind) {
    BreedWeightReferenceSeriesKind.LOWER_BOUNDARY,
    BreedWeightReferenceSeriesKind.UPPER_BOUNDARY,
    -> BreedWeightReferenceSeriesPresentation(strokeWidthDp = 1, pointSizeDp = 0, lowEmphasis = true)
    BreedWeightReferenceSeriesKind.CENTER ->
        BreedWeightReferenceSeriesPresentation(strokeWidthDp = 1, pointSizeDp = 0, lowEmphasis = true)
}

internal data class BreedWeightReferenceBandPoint(
    val xEpochMillis: Long,
    val lowerKg: Double,
    val upperKg: Double,
)

internal data class BreedWeightReferenceBand(val points: List<BreedWeightReferenceBandPoint>) {
    init {
        require(points.size >= 2)
        require(points.zipWithNext().all { (first, second) -> first.xEpochMillis < second.xEpochMillis })
    }
}

/** Produces one polygon source per value and continuous interval segment. */
internal fun breedWeightReferenceBands(
    timeline: List<PetHistoryBreedReferenceTimelinePoint>,
): List<BreedWeightReferenceBand> {
    val maximumValueCount = timeline.maxOfOrNull { it.values?.size ?: 0 } ?: return emptyList()
    return buildList {
        repeat(maximumValueCount) { valueIndex ->
            var segment = mutableListOf<BreedWeightReferenceBandPoint>()
            fun finishSegment() {
                if (segment.size >= 2) add(BreedWeightReferenceBand(segment))
                segment = mutableListOf()
            }
            timeline.forEach { timelinePoint ->
                val interval = timelinePoint.values?.getOrNull(valueIndex) as? PetHistoryBreedChartValue.Interval
                if (interval == null) {
                    finishSegment()
                } else {
                    val point = BreedWeightReferenceBandPoint(
                        xEpochMillis = timelinePoint.xEpochMillis,
                        lowerKg = interval.lowerKg,
                        upperKg = interval.upperKg,
                    )
                    if (segment.lastOrNull()?.xEpochMillis?.let { it >= point.xEpochMillis } == true) finishSegment()
                    segment.add(point)
                }
            }
            finishSegment()
        }
    }
}

internal data class PetWeightChartModelSeries(
    val x: List<Long>,
    val y: List<Double>,
)

internal enum class PetWeightDisplayedSeriesKind {
    FACTUAL,
    CATEGORY_LOWER,
    CATEGORY_MEDIAN_LOWER,
    CATEGORY_MEDIAN_UPPER,
    CATEGORY_UPPER,
    BREED_LOWER,
    BREED_UPPER,
    BREED_CENTER,
}

internal enum class PetWeightDisplayedSeriesStyle { FACTUAL, CATEGORY, BREED_BOUNDARY, BREED_CENTER }

/** The single source of truth for everything Vico displays. */
internal data class PetWeightDisplayedSeries(
    val id: String,
    val kind: PetWeightDisplayedSeriesKind,
    val label: String,
    val x: List<Long>,
    val y: List<Double>,
    val style: PetWeightDisplayedSeriesStyle,
) {
    init {
        require(x.size == y.size)
        require(x.isNotEmpty())
        require(y.all(Double::isFinite))
    }
}

internal fun petWeightDisplayedSeries(
    factual: List<ChartPoint>,
    reference: PetHistoryWeightReference,
    breedReferenceTimeline: List<PetHistoryBreedReferenceTimelinePoint>,
    zoneId: ZoneId,
): List<PetWeightDisplayedSeries> = buildList {
    val factualPoints = factual.filter { it.xEpochMillis != null && it.value.isFinite() }
    if (factualPoints.isNotEmpty()) {
        add(
            PetWeightDisplayedSeries(
                id = "factual",
                kind = PetWeightDisplayedSeriesKind.FACTUAL,
                label = "Фактический вес",
                x = factualPoints.map { requireNotNull(it.xEpochMillis) },
                y = factualPoints.map(ChartPoint::value),
                style = PetWeightDisplayedSeriesStyle.FACTUAL,
            ),
        )
    }
    val breedSeries = breedWeightReferenceChartSeries(breedReferenceTimeline)
    if (breedSeries.isNotEmpty()) {
        val counters = mutableMapOf<BreedWeightReferenceSeriesKind, Int>()
        breedSeries.forEach { series ->
            val occurrence = counters.getOrDefault(series.kind, 0)
            counters[series.kind] = occurrence + 1
            val (kind, label, style) = when (series.kind) {
                BreedWeightReferenceSeriesKind.LOWER_BOUNDARY -> Triple(
                    PetWeightDisplayedSeriesKind.BREED_LOWER,
                    "Нижняя граница",
                    PetWeightDisplayedSeriesStyle.BREED_BOUNDARY,
                )
                BreedWeightReferenceSeriesKind.UPPER_BOUNDARY -> Triple(
                    PetWeightDisplayedSeriesKind.BREED_UPPER,
                    "Верхняя граница",
                    PetWeightDisplayedSeriesStyle.BREED_BOUNDARY,
                )
                BreedWeightReferenceSeriesKind.CENTER -> Triple(
                    PetWeightDisplayedSeriesKind.BREED_CENTER,
                    "Медиана или среднее",
                    PetWeightDisplayedSeriesStyle.BREED_CENTER,
                )
            }
            add(
                PetWeightDisplayedSeries(
                    id = "breed-${series.kind.name.lowercase()}-$occurrence",
                    kind = kind,
                    label = label,
                    x = series.xEpochMillis,
                    y = series.points.map { it.second },
                    style = style,
                ),
            )
        }
    } else {
        petWeightReferenceChartSeries(reference).forEachIndexed { index, series ->
            val (kind, label) = when (series.kind) {
                PetWeightReferenceSeriesKind.LOWER -> PetWeightDisplayedSeriesKind.CATEGORY_LOWER to "Нижняя граница эталона"
                PetWeightReferenceSeriesKind.MEDIAN_LOWER -> PetWeightDisplayedSeriesKind.CATEGORY_MEDIAN_LOWER to "Нижняя медианная граница"
                PetWeightReferenceSeriesKind.MEDIAN_UPPER -> PetWeightDisplayedSeriesKind.CATEGORY_MEDIAN_UPPER to "Верхняя медианная граница"
                PetWeightReferenceSeriesKind.UPPER -> PetWeightDisplayedSeriesKind.CATEGORY_UPPER to "Верхняя граница эталона"
            }
            add(
                PetWeightDisplayedSeries(
                    id = "category-${series.kind.name.lowercase()}-$index",
                    kind = kind,
                    label = label,
                    x = series.points.map { (date, _) -> date.atStartOfDay(zoneId).toInstant().toEpochMilli() },
                    y = series.points.map { it.second },
                    style = PetWeightDisplayedSeriesStyle.CATEGORY,
                ),
            )
        }
    }
}

internal fun formatPetWeightDisplayedMarker(
    targetXEpochMillis: Long,
    displayedSeries: List<PetWeightDisplayedSeries>,
    locale: Locale = Locale.getDefault(),
): String {
    val number = NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = 2
        maximumFractionDigits = 2
        isGroupingUsed = false
    }
    return displayedSeries.mapNotNull { series ->
        series.x.indexOf(targetXEpochMillis).takeIf { it >= 0 }?.let { index ->
            "${series.label}: ${number.format(series.y[index])} кг"
        }
    }.joinToString("\n")
}

internal fun petWeightDisplayedMarkerXs(
    displayedSeries: List<PetWeightDisplayedSeries>,
): List<Long> = displayedSeries.flatMap(PetWeightDisplayedSeries::x).distinct().sorted()

internal fun breedWeightReferenceChartSeries(
    timeline: List<PetHistoryBreedReferenceTimelinePoint>,
): List<BreedWeightReferenceChartSeries> {
    val maximumValueCount = timeline.maxOfOrNull { it.values?.size ?: 0 } ?: return emptyList()
    return buildList {
        repeat(maximumValueCount) { valueIndex ->
            fun points(selector: (PetHistoryBreedChartValue) -> Double?): List<Pair<List<Pair<LocalDate, Double>>, List<Long>>> {
                val segments = mutableListOf<MutableList<Pair<LocalDate, Double>>>()
                val timestamps = mutableListOf<MutableList<Long>>()
                timeline.forEach { point ->
                    val value = point.values?.getOrNull(valueIndex)?.let(selector)
                    if (value == null) {
                        if (segments.lastOrNull()?.isNotEmpty() == true) {
                            segments.add(mutableListOf())
                            timestamps.add(mutableListOf())
                        }
                    } else {
                        if (segments.isEmpty()) {
                            segments.add(mutableListOf())
                            timestamps.add(mutableListOf())
                        }
                        segments.last().add(point.date to value)
                        timestamps.last().add(point.xEpochMillis)
                    }
                }
                return segments.zip(timestamps).filter { it.first.isNotEmpty() }
            }

            points { (it as? PetHistoryBreedChartValue.Interval)?.lowerKg }
                .forEach { (values, x) -> add(BreedWeightReferenceChartSeries(BreedWeightReferenceSeriesKind.LOWER_BOUNDARY, values, xEpochMillis = x)) }
            points { (it as? PetHistoryBreedChartValue.Interval)?.upperKg }
                .forEach { (values, x) -> add(BreedWeightReferenceChartSeries(BreedWeightReferenceSeriesKind.UPPER_BOUNDARY, values, xEpochMillis = x)) }
            points {
                when (it) {
                    is PetHistoryBreedChartValue.Interval -> it.centerKg
                    is PetHistoryBreedChartValue.Single -> it.valueKg
                }
            }.forEach { (values, x) -> add(BreedWeightReferenceChartSeries(BreedWeightReferenceSeriesKind.CENTER, values, xEpochMillis = x)) }
        }
    }
}

internal fun petWeightChartModelSeries(
    factual: List<ChartPoint>,
    referenceSeries: List<PetWeightReferenceChartSeries>,
    breedSeries: List<BreedWeightReferenceChartSeries>,
    zoneId: ZoneId,
): List<PetWeightChartModelSeries> = buildList {
    if (factual.isNotEmpty()) {
        add(
            PetWeightChartModelSeries(
                x = factual.map { requireNotNull(it.xEpochMillis) },
                y = factual.map(ChartPoint::value),
            ),
        )
    }
    referenceSeries.takeIf { breedSeries.isEmpty() }.orEmpty().forEach { series ->
        add(
            PetWeightChartModelSeries(
                x = series.points.map { (date, _) ->
                    date.atStartOfDay(zoneId).toInstant().toEpochMilli()
                },
                y = series.points.map { it.second },
            ),
        )
    }
    breedSeries.forEach { series ->
        add(
            PetWeightChartModelSeries(
                x = series.xEpochMillis,
                y = series.points.map { it.second },
            ),
        )
    }
}

internal fun petWeightChartRange(
    factual: List<ChartPoint>,
    reference: PetHistoryWeightReference,
    breedReference: PetHistoryBreedReference = PetHistoryBreedReference.Hidden,
    breedReferenceTimeline: List<PetHistoryBreedReferenceTimelinePoint> = emptyList(),
): PetWeightChartRange? {
    val hasBreedTimeline = breedReferenceTimeline.any { !it.values.isNullOrEmpty() }
    val values = buildList {
        addAll(factual.map(ChartPoint::value).filter(Double::isFinite))
        if (!hasBreedTimeline && reference is PetHistoryWeightReference.Available) {
            reference.segments.flatten().forEach { point ->
                add(point.lowerKg)
                add(point.upperKg)
            }
        }
        if (breedReference is PetHistoryBreedReference.Available) {
            breedReference.chartValues.forEach { value ->
                when (value) {
                    is PetHistoryBreedChartValue.Interval -> {
                        add(value.lowerKg)
                        add(value.upperKg)
                    }
                    is PetHistoryBreedChartValue.Single -> add(value.valueKg)
                }
            }
        }
        breedReferenceTimeline.forEach { point ->
            point.values.orEmpty().forEach { value ->
                when (value) {
                    is PetHistoryBreedChartValue.Interval -> {
                        add(value.lowerKg)
                        add(value.upperKg)
                        value.centerKg?.let(::add)
                    }
                    is PetHistoryBreedChartValue.Single -> add(value.valueKg)
                }
            }
        }
    }
    if (values.isEmpty()) return null
    val min = values.min()
    val max = values.max()
    val padding = ((max - min) * 0.08).coerceAtLeast(0.1)
    return PetWeightChartRange((min - padding).coerceAtLeast(0.0), max + padding)
}

internal fun petWeightReferenceChartSeries(
    reference: PetHistoryWeightReference,
): List<PetWeightReferenceChartSeries> {
    val available = reference as? PetHistoryWeightReference.Available ?: return emptyList()
    return available.segments.flatMap { segment ->
        listOf(
            PetWeightReferenceChartSeries(
                PetWeightReferenceSeriesKind.LOWER,
                segment.map { it.date to it.lowerKg },
            ),
            PetWeightReferenceChartSeries(
                PetWeightReferenceSeriesKind.MEDIAN_LOWER,
                segment.map { it.date to it.medianLowerKg },
            ),
            PetWeightReferenceChartSeries(
                PetWeightReferenceSeriesKind.MEDIAN_UPPER,
                segment.map { it.date to it.medianUpperKg },
            ),
            PetWeightReferenceChartSeries(
                PetWeightReferenceSeriesKind.UPPER,
                segment.map { it.date to it.upperKg },
            ),
        )
    }
}

internal fun shouldShowWeightReferenceExplanation(
    reference: PetHistoryWeightReference,
    breedReference: PetHistoryBreedReference,
): Boolean = reference is PetHistoryWeightReference.Available ||
    breedReference !is PetHistoryBreedReference.Available

@Composable
internal fun PetWeightReferenceChartCard(
    series: ChartSeries,
    reference: PetHistoryWeightReference,
    breedReference: PetHistoryBreedReference = PetHistoryBreedReference.Hidden,
    breedReferenceTimeline: List<PetHistoryBreedReferenceTimelinePoint> = emptyList(),
    startDate: LocalDate,
    endDateInclusive: LocalDate,
    zoneId: ZoneId,
) {
    val factual = remember(series.points) {
        series.points.filter { it.xEpochMillis != null && it.value.isFinite() }
            .sortedBy(ChartPoint::measuredAtEpochSecond)
    }
    val displayedSeries = remember(factual, reference, breedReferenceTimeline, zoneId) {
        petWeightDisplayedSeries(factual, reference, breedReferenceTimeline, zoneId)
    }
    val yRange = remember(displayedSeries) {
        displayedSeries.flatMap(PetWeightDisplayedSeries::y).takeIf(List<Double>::isNotEmpty)?.let { values ->
            val min = values.min()
            val max = values.max()
            val padding = ((max - min) * 0.08).coerceAtLeast(0.1)
            PetWeightChartRange((min - padding).coerceAtLeast(0.0), max + padding)
        }
    }
    val factualColor = MaterialTheme.colorScheme.primary
    val referenceColor = MaterialTheme.colorScheme.tertiary
    val available = reference as? PetHistoryWeightReference.Available
    val hasBreedTimeline = breedReferenceTimeline.any { !it.values.isNullOrEmpty() }
    val showReferenceExplanation = shouldShowWeightReferenceExplanation(reference, breedReference)
    val description = buildString {
        append("График веса питомца. ")
        append(if (factual.isEmpty()) "Измерений нет. " else "Измерений: ${factual.size}. ")
        if (showReferenceExplanation && !hasBreedTimeline) {
            append(available?.accessibilityLabel ?: (reference as PetHistoryWeightReference.Unavailable).explanation)
            if (available != null) append(" Фактический вес отмечен кругами; эталон — четырьмя линиями границ.")
        }
        if (breedReference is PetHistoryBreedReference.Available) {
            append(" ${breedReference.accessibilityLabel} Породные ориентиры построены по датам измерений; одиночное значение отмечено ромбом.")
        }
    }

    HuaweiSurface(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(HuaweiDimensions.CompactItemSpacing)) {
            Text(
                "Вес питомца",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            if (yRange != null) {
                PetWeightVicoChart(
                    displayedSeries = displayedSeries,
                    breedBands = breedWeightReferenceBands(breedReferenceTimeline),
                    startDate = startDate,
                    endDateInclusive = endDateInclusive,
                    zoneId = zoneId,
                    yRange = yRange,
                    factualColor = factualColor,
                    referenceColor = referenceColor,
                    contentDescription = description,
                )
                if (displayedSeries.isNotEmpty()) {
                    DisplayedSeriesLegend(displayedSeries, factualColor, referenceColor)
                } else if (factual.size < 2) {
                    Text("Для линии нужно минимум два измерения; отдельное измерение показано точкой.")
                }
            } else {
                Text("Нет данных за выбранный период", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (showReferenceExplanation && !hasBreedTimeline) ReferenceExplanation(reference)
        }
    }
}

@Composable
private fun PetWeightVicoChart(
    displayedSeries: List<PetWeightDisplayedSeries>,
    breedBands: List<BreedWeightReferenceBand>,
    startDate: LocalDate,
    endDateInclusive: LocalDate,
    zoneId: ZoneId,
    yRange: PetWeightChartRange,
    factualColor: Color,
    referenceColor: Color,
    contentDescription: String,
) {
    val xRange = remember(startDate, endDateInclusive, zoneId) {
        chartXRange(startDate, endDateInclusive, zoneId)
    }
    val rangeProvider = remember(xRange, yRange) {
        object : CartesianLayerRangeProvider {
            override fun getMinX(minX: Double, maxX: Double, extraStore: ExtraStore) = xRange.minX
            override fun getMaxX(minX: Double, maxX: Double, extraStore: ExtraStore) = xRange.maxX
            override fun getMinY(minY: Double, maxY: Double, extraStore: ExtraStore) = yRange.min
            override fun getMaxY(minY: Double, maxY: Double, extraStore: ExtraStore) = yRange.max
        }
    }
    val lines = displayedSeries.map { series ->
        when (series.style) {
            PetWeightDisplayedSeriesStyle.FACTUAL -> rememberSmoothChartLine(factualColor, series.x.size)
            PetWeightDisplayedSeriesStyle.CATEGORY -> rememberSmoothChartLine(referenceColor, series.x.size)
            PetWeightDisplayedSeriesStyle.BREED_BOUNDARY,
            PetWeightDisplayedSeriesStyle.BREED_CENTER,
            -> rememberBreedChartLine(
                    if (series.style == PetWeightDisplayedSeriesStyle.BREED_CENTER) {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.22f)
                    } else {
                        Color(0xFF43A047).copy(alpha = 0.36f)
                    },
                    if (series.style == PetWeightDisplayedSeriesStyle.BREED_CENTER) BreedWeightReferenceSeriesKind.CENTER
                    else if (series.kind == PetWeightDisplayedSeriesKind.BREED_LOWER) BreedWeightReferenceSeriesKind.LOWER_BOUNDARY
                    else BreedWeightReferenceSeriesKind.UPPER_BOUNDARY,
                )
        }
    }
    val bandDecoration = remember(breedBands) {
        BreedWeightReferenceBandDecoration(breedBands, Color(0xFF66BB6A).copy(alpha = 0.14f))
    }
    val modelProducer = remember { CartesianChartModelProducer() }
    val bottomFormatter = remember(zoneId) {
        CartesianValueFormatter { _, value, _ ->
            PetAxisDateFormatter.format(Instant.ofEpochMilli(value.toLong()).atZone(zoneId))
        }
    }
    val markerFormatter = remember(displayedSeries) {
        DefaultCartesianMarker.ValueFormatter { _, targets ->
            val target = targets.firstOrNull() as? LineCartesianLayerMarkerTarget
                ?: return@ValueFormatter ""
            formatPetWeightDisplayedMarker(target.x.toLong(), displayedSeries)
        }
    }
    val zoomState = key(xRange.minX, xRange.maxX, zoneId) {
        rememberVicoZoomState(zoomEnabled = true, initialZoom = Zoom.Content)
    }
    val selectableXs = remember(displayedSeries) {
        petWeightDisplayedMarkerXs(displayedSeries)
    }
    var selectedXIndex by remember(selectableXs) { mutableIntStateOf(0) }
    fun selectRelative(offset: Int): Boolean {
        if (selectableXs.isEmpty()) return false
        selectedXIndex = (selectedXIndex + offset).mod(selectableXs.size)
        return true
    }
    val accessibleMarker = selectableXs.getOrNull(selectedXIndex)
        ?.let { formatPetWeightDisplayedMarker(it, displayedSeries) }
        ?.takeIf(String::isNotEmpty)

    LaunchedEffect(displayedSeries) {
        modelProducer.runTransaction {
            lineModel {
                displayedSeries.forEach { chartSeries ->
                    series(x = chartSeries.x, y = chartSeries.y)
                }
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(250.dp)) {
        CartesianChartHost(
            chart = rememberCartesianChart(
                rememberSmoothLineLayer(lines, rangeProvider),
                startAxis = rememberChartStartAxis(
                    CartesianValueFormatter.decimal(decimalCount = 2, suffix = " кг"),
                ),
                bottomAxis = rememberChartBottomAxis(bottomFormatter),
                marker = rememberChartMarker(
                    markerFormatter,
                    lineCount = displayedSeries.size,
                ),
                decorations = listOf(bandDecoration),
            ),
            modelProducer = modelProducer,
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxSize()
                .testTag(PetWeightChartTestTags.Chart)
                .semantics {
                    this.contentDescription = contentDescription
                    role = Role.Button
                    stateDescription = accessibleMarker ?: "Нет доступных точек для выбора"
                    onClick("Выбрать следующую точку") { selectRelative(1) }
                    customActions = listOf(
                        CustomAccessibilityAction("Выбрать предыдущую точку") { selectRelative(-1) },
                        CustomAccessibilityAction("Выбрать следующую точку") { selectRelative(1) },
                    )
                },
            scrollState = rememberVicoScrollState(scrollEnabled = true),
            zoomState = zoomState,
        )
    }
}

@Composable
private fun rememberBreedChartLine(
    color: Color,
    kind: BreedWeightReferenceSeriesKind,
): LineCartesianLayer.Line {
    val presentation = breedWeightReferenceSeriesPresentation(kind)
    return remember(color, kind) {
        LineCartesianLayer.Line(
            fill = LineCartesianLayer.LineFill.single(Fill(color)),
            stroke = LineCartesianLayer.LineStroke.Continuous(presentation.strokeWidthDp.dp),
            areaFill = null,
            pointProvider = null,
            interpolator = LineCartesianLayer.Interpolator.Sharp,
        )
    }
}

private class BreedWeightReferenceBandDecoration(
    private val bands: List<BreedWeightReferenceBand>,
    color: Color,
) : Decoration {
    private val paint = Paint().apply { this.color = color }

    override fun drawUnderLayers(context: CartesianDrawingContext) = with(context) {
        val yRange = ranges.getYRange(Axis.Position.Vertical.Start)
        if (ranges.xStep == 0.0 || yRange.length == 0.0) return@with
        val start = if (isLtr) layerBounds.left else layerBounds.right
        val baseX = start + layoutDirectionMultiplier * layerDimensions.startPadding - scroll
        fun x(value: Long): Float = baseX + layoutDirectionMultiplier * layerDimensions.xSpacing *
            ((value - ranges.minX) / ranges.xStep).toFloat()
        fun y(value: Double): Float = layerBounds.bottom -
            ((value - yRange.minY) / yRange.length).toFloat() * layerBounds.height

        bands.forEach { band ->
            val path = Path()
            band.points.forEachIndexed { index, point ->
                if (index == 0) path.moveTo(x(point.xEpochMillis), y(point.lowerKg))
                else path.lineTo(x(point.xEpochMillis), y(point.lowerKg))
            }
            band.points.asReversed().forEach { point ->
                path.lineTo(x(point.xEpochMillis), y(point.upperKg))
            }
            path.close()
            canvas.drawPath(path, paint)
        }
    }
}

@Composable
private fun BreedChartLegend(reference: PetHistoryBreedReference.Available) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (reference.chartValues.any { it is PetHistoryBreedChartValue.Interval }) {
            Text("— Границы породного диапазона · возраст на дату измерения", style = MaterialTheme.typography.bodySmall)
        }
        if (reference.chartValues.any {
                it is PetHistoryBreedChartValue.Single || it is PetHistoryBreedChartValue.Interval && it.centerKg != null
            }) {
            Text("◆ Породное среднее или медиана · ${reference.ageLabel}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun DisplayedSeriesLegend(
    displayedSeries: List<PetWeightDisplayedSeries>,
    factualColor: Color,
    referenceColor: Color,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        displayedSeries.distinctBy(PetWeightDisplayedSeries::kind).forEach { series ->
            val color = when (series.style) {
                PetWeightDisplayedSeriesStyle.FACTUAL -> factualColor
                PetWeightDisplayedSeriesStyle.CATEGORY -> referenceColor
                PetWeightDisplayedSeriesStyle.BREED_BOUNDARY -> MaterialTheme.colorScheme.secondary
                PetWeightDisplayedSeriesStyle.BREED_CENTER -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
            }
            val symbol = when (series.style) {
                PetWeightDisplayedSeriesStyle.FACTUAL -> "●"
                PetWeightDisplayedSeriesStyle.BREED_CENTER -> "◆"
                else -> "—"
            }
            ChartLegend("$symbol ${series.label}", color)
        }
    }
}

@Composable
private fun ChartLegend(text: String, color: Color) {
    Text(text, color = color, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun ReferenceExplanation(reference: PetHistoryWeightReference) {
    when (reference) {
        is PetHistoryWeightReference.Unavailable -> Text(
            reference.explanation,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag(PetWeightChartTestTags.Unavailable),
        )
        is PetHistoryWeightReference.Available -> Column(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(PetWeightChartTestTags.ReferenceDetails)
                .semantics(mergeDescendants = true) {
                    contentDescription = buildString {
                        append(reference.accessibilityLabel)
                        reference.constraints.forEach { append(" Ограничение: $it.") }
                        append(" Эталон не ставит диагноз.")
                    }
                },
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Как читать эталон", style = MaterialTheme.typography.titleSmall)
            Text("${reference.basisLabel} · ${reference.ageLabel}")
            Text("Внешние линии показывают общий диапазон, две внутренние — медианный диапазон.")
            Text(reference.sourceLabel, style = MaterialTheme.typography.bodySmall)
            Text("Лицензия: ${reference.license}", style = MaterialTheme.typography.bodySmall)
            reference.constraints.forEach { Text("Ограничение: $it", style = MaterialTheme.typography.bodySmall) }
            Text(
                "Эталон помогает следить за динамикой, но не ставит диагноз. Обсудите заметные отклонения или изменения веса с ветеринаром.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private val PetAxisDateFormatter = DateTimeFormatter.ofPattern("dd.MM")
