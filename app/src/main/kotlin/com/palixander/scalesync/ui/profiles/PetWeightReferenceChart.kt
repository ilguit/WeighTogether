package com.palixander.scalesync.ui.profiles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.palixander.scalesync.R
import com.palixander.scalesync.charts.ChartPoint
import com.palixander.scalesync.charts.chartScrollOffset
import com.palixander.scalesync.charts.ChartSeries
import com.palixander.scalesync.charts.chartViewport
import com.palixander.scalesync.charts.initialVisibleWidth
import com.palixander.scalesync.charts.rememberChartBottomAxis
import com.palixander.scalesync.charts.rememberChartMarker
import com.palixander.scalesync.charts.rememberChartStartAxis
import com.palixander.scalesync.charts.rememberChartLine
import com.palixander.scalesync.charts.rememberChartLineLayer
import com.palixander.scalesync.core.reference.ReferenceBasis
import com.palixander.scalesync.domain.reference.WeightReferenceProvenance
import com.palixander.scalesync.domain.reference.BreedWeightValue
import com.palixander.scalesync.domain.reference.BreedWeightReferenceUnavailableReason
import com.palixander.scalesync.ui.components.HuaweiSurface
import com.palixander.scalesync.ui.reference.ReferenceSourceLauncher
import com.palixander.scalesync.ui.theme.HuaweiDimensions
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.CartesianDrawingContext
import com.patrykandpatrick.vico.compose.cartesian.Scroll
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
    const val Disclosure = "pet-weight-reference-disclosure"
    const val Unavailable = "pet-weight-reference-unavailable"
    const val Publication = "pet-weight-reference-publication"
    const val PublicationError = "pet-weight-reference-publication-error"
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
    val xEpochMillis: List<Long>? = null,
)

internal data class BreedWeightReferenceChartSeries(
    val kind: BreedWeightReferenceSeriesKind,
    val points: List<Pair<LocalDate, Double>>,
    val showsPointMarkers: Boolean = false,
    val xEpochMillis: List<Long>,
    val statisticLabel: String? = null,
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

internal data class BreedWeightReferenceBand(
    val points: List<BreedWeightReferenceBandPoint>,
    /** Visual-only fill for a published upper boundary; this does not add a lower reference value. */
    val lowerEdge: LowerEdge = LowerEdge.PUBLISHED,
) {
    internal enum class LowerEdge {
        PUBLISHED,
        CHART_DOMAIN_MINIMUM,
    }

    init {
        require(points.size >= 2)
        require(points.zipWithNext().all { (first, second) -> first.xEpochMillis < second.xEpochMillis })
    }
}

internal fun breedWeightReferenceBandLowerKg(
    band: BreedWeightReferenceBand,
    point: BreedWeightReferenceBandPoint,
    chartDomainMinimumKg: Double,
): Double = when (band.lowerEdge) {
    BreedWeightReferenceBand.LowerEdge.PUBLISHED -> point.lowerKg
    BreedWeightReferenceBand.LowerEdge.CHART_DOMAIN_MINIMUM -> chartDomainMinimumKg
}

/** Produces one polygon per stable source/statistic identity and continuous interval segment. */
internal fun breedWeightReferenceBands(
    timeline: List<PetHistoryBreedReferenceTimelinePoint>,
): List<BreedWeightReferenceBand> {
    val seriesIds = timeline.flatMap { point -> point.values.orEmpty().map(PetHistoryBreedChartValue::seriesId) }.distinct()
    return buildList {
        seriesIds.forEach { seriesId ->
            val lowerEdge = timeline.asSequence()
                .flatMap { it.values.orEmpty().asSequence() }
                .firstOrNull { it.seriesId == seriesId }
                .let { value ->
                    if (value is PetHistoryBreedChartValue.Boundary &&
                        value.direction == BreedWeightValue.Boundary.Direction.UPPER
                    ) {
                        BreedWeightReferenceBand.LowerEdge.CHART_DOMAIN_MINIMUM
                    } else {
                        BreedWeightReferenceBand.LowerEdge.PUBLISHED
                    }
                }
            var segment = mutableListOf<BreedWeightReferenceBandPoint>()
            fun finishSegment() {
                if (segment.size >= 2) add(BreedWeightReferenceBand(segment, lowerEdge))
                segment = mutableListOf()
            }
            timeline.forEach { timelinePoint ->
                val value = timelinePoint.values?.firstOrNull { it.seriesId == seriesId }
                val bounds = when (value) {
                    is PetHistoryBreedChartValue.Interval -> value.lowerKg to value.upperKg
                    is PetHistoryBreedChartValue.Boundary -> value
                        .takeIf { it.direction == BreedWeightValue.Boundary.Direction.UPPER }
                        ?.let { it.valueKg to it.valueKg }
                    else -> null
                }
                if (bounds == null) {
                    finishSegment()
                } else {
                    val point = BreedWeightReferenceBandPoint(
                        xEpochMillis = timelinePoint.xEpochMillis,
                        lowerKg = bounds.first,
                        upperKg = bounds.second,
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

internal data class PetWeightExactObservationGlyph(
    val xEpochMillis: Long,
    val lowerKg: Double,
    val meanKg: Double,
    val upperKg: Double,
) {
    init { require(lowerKg <= meanKg && meanKg <= upperKg) }
}

internal fun exactObservationGlyphs(
    reference: PetHistoryWeightReference.Available?,
    zoneId: ZoneId,
): List<PetWeightExactObservationGlyph> = reference
    ?.segments.orEmpty()
    .filter { it.provenance == WeightReferenceProvenance.BREED_EXACT_OBSERVATION }
    .flatMap { it.points }.map { point ->
        PetWeightExactObservationGlyph(
            point.date.atStartOfDay(zoneId).toInstant().toEpochMilli(),
            point.lowerKg,
            (point.medianLowerKg + point.medianUpperKg) / 2.0,
            point.upperKg,
        )
    }

internal data class PetWeightChartLegendEntry(
    val label: String,
    val style: PetWeightDisplayedSeriesStyle,
)

internal fun petWeightChartLegendEntries(
    displayedSeries: List<PetWeightDisplayedSeries>,
): List<PetWeightChartLegendEntry> = buildList {
    if (displayedSeries.any { it.style == PetWeightDisplayedSeriesStyle.FACTUAL }) {
        add(PetWeightChartLegendEntry("● Фактический вес", PetWeightDisplayedSeriesStyle.FACTUAL))
    }
    displayedSeries
        .filter { it.style == PetWeightDisplayedSeriesStyle.CATEGORY }
        .distinctBy(PetWeightDisplayedSeries::kind)
        .forEach { add(PetWeightChartLegendEntry("— ${it.label}", PetWeightDisplayedSeriesStyle.CATEGORY)) }
    if (displayedSeries.any { it.style == PetWeightDisplayedSeriesStyle.BREED_BOUNDARY }) {
        add(
            PetWeightChartLegendEntry(
                "▰ Светло-зелёная зона — породный диапазон",
                PetWeightDisplayedSeriesStyle.BREED_BOUNDARY,
            ),
        )
    }
    displayedSeries
        .filter { it.style == PetWeightDisplayedSeriesStyle.BREED_CENTER }
        .distinctBy(PetWeightDisplayedSeries::label)
        .forEach { add(PetWeightChartLegendEntry("— ${it.label}", PetWeightDisplayedSeriesStyle.BREED_CENTER)) }
}

internal fun populationWeightChartLegendEntries(): List<PetWeightChartLegendEntry> = listOf(
    PetWeightChartLegendEntry("▰ Типичный диапазон веса", PetWeightDisplayedSeriesStyle.BREED_BOUNDARY),
    PetWeightChartLegendEntry("— P50", PetWeightDisplayedSeriesStyle.BREED_CENTER),
)

internal fun referenceWeightChartLegendEntries(provenance: WeightReferenceProvenance): List<PetWeightChartLegendEntry> = when (provenance) {
    WeightReferenceProvenance.BREED_CURVE -> listOf(
        PetWeightChartLegendEntry("▰ Светло-зелёная зона — модельный породный диапазон", PetWeightDisplayedSeriesStyle.BREED_BOUNDARY),
        PetWeightChartLegendEntry("— Центр модельного диапазона", PetWeightDisplayedSeriesStyle.BREED_CENTER),
    )
    WeightReferenceProvenance.BREED_EXACT_OBSERVATION -> listOf(
        PetWeightChartLegendEntry("↕ Диапазон наблюдения породы в дату рождения", PetWeightDisplayedSeriesStyle.BREED_BOUNDARY),
        PetWeightChartLegendEntry("● Средний вес породы в дату рождения", PetWeightDisplayedSeriesStyle.BREED_CENTER),
    )
    WeightReferenceProvenance.POPULATION_FALLBACK_FOR_SELECTED_BREED -> listOf(
        PetWeightChartLegendEntry("▰ Общий диапазон P9–P91 (не по породе)", PetWeightDisplayedSeriesStyle.BREED_BOUNDARY),
        PetWeightChartLegendEntry("— Общая P50 (не по породе)", PetWeightDisplayedSeriesStyle.BREED_CENTER),
    )
    WeightReferenceProvenance.POPULATION,
    WeightReferenceProvenance.WEIGHT_CATEGORY,
    -> populationWeightChartLegendEntries()
}

internal fun petWeightChartDescription(
    factualCount: Int,
    reference: PetHistoryWeightReference,
    showReferenceExplanation: Boolean,
    hasBreedTimeline: Boolean,
    isPopulationReference: Boolean,
    legendEntries: List<PetWeightChartLegendEntry>,
): String = buildString {
    append("График веса питомца. ")
    append(if (factualCount == 0) "Измерений нет. " else "Измерений: $factualCount. ")
    val available = reference as? PetHistoryWeightReference.Available
    if (showReferenceExplanation && !hasBreedTimeline) {
        append(available?.accessibilityLabel ?: (reference as PetHistoryWeightReference.Unavailable).explanation)
        if (available != null) append(
            if (available.provenance == WeightReferenceProvenance.BREED_CURVE) " Фактический вес отмечен кругами; модельный породный диапазон — светло-зелёной зоной, его центр — линией."
            else if (available.provenance == WeightReferenceProvenance.POPULATION_FALLBACK_FOR_SELECTED_BREED) " Фактический вес отмечен кругами; общий, не породный диапазон — зоной P9–P91 и линией P50."
            else if (available.provenance == WeightReferenceProvenance.BREED_EXACT_OBSERVATION) " Породное наблюдение в дату рождения показано вертикальным интервалом и точкой среднего веса."
            else if (isPopulationReference) " Фактический вес отмечен кругами; типичный диапазон веса — зоной P9–P91 и линией P50."
            else " Фактический вес отмечен кругами; эталон — четырьмя линиями границ.",
        )
    }
    if (isPopulationReference) append(" Сведения справочные и не оценивают здоровье питомца.")
    if (legendEntries.isNotEmpty()) {
        append(" Отображаются: ")
        append(legendEntries.joinToString("; ") { it.label })
        append('.')
    }
}

/** Smooth rendering samples; input knots remain the authoritative values. */
internal fun monotoneSmoothedChartPoints(
    x: List<Long>,
    y: List<Double>,
    samplesPerInterval: Int = 8,
): PetWeightChartModelSeries {
    require(x.size == y.size && x.isNotEmpty())
    require(x.zipWithNext().all { (first, second) -> first < second })
    require(samplesPerInterval > 0)
    if (x.size < 3) return PetWeightChartModelSeries(x, y)
    val slopes = DoubleArray(x.lastIndex) { index ->
        (y[index + 1] - y[index]) / (x[index + 1] - x[index]).toDouble()
    }
    val tangents = DoubleArray(x.size)
    tangents[0] = slopes.first()
    tangents[tangents.lastIndex] = slopes.last()
    for (index in 1 until tangents.lastIndex) {
        tangents[index] = if (slopes[index - 1] * slopes[index] <= 0.0) 0.0
        else 2.0 / (1.0 / slopes[index - 1] + 1.0 / slopes[index])
    }
    val renderedX = mutableListOf<Long>()
    val renderedY = mutableListOf<Double>()
    for (index in slopes.indices) {
        val width = (x[index + 1] - x[index]).toDouble()
        for (sample in 0 until samplesPerInterval) {
            val t = sample.toDouble() / samplesPerInterval
            val t2 = t * t
            val t3 = t2 * t
            val value = (2 * t3 - 3 * t2 + 1) * y[index] +
                (t3 - 2 * t2 + t) * width * tangents[index] +
                (-2 * t3 + 3 * t2) * y[index + 1] +
                (t3 - t2) * width * tangents[index + 1]
            renderedX += x[index] + (x[index + 1] - x[index]) * sample / samplesPerInterval
            renderedY += value.coerceIn(minOf(y[index], y[index + 1]), maxOf(y[index], y[index + 1]))
        }
    }
    renderedX += x.last()
    renderedY += y.last()
    return PetWeightChartModelSeries(renderedX, renderedY)
}

/** Geometry rendered by Vico and sampled by the marker formatter. */
internal fun petWeightRenderedModelSeries(
    series: PetWeightDisplayedSeries,
): PetWeightChartModelSeries = if (series.style == PetWeightDisplayedSeriesStyle.CATEGORY) {
    monotoneSmoothedChartPoints(series.x, series.y)
} else {
    PetWeightChartModelSeries(series.x, series.y)
}

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

/** Canonical decision used by every chart consumer: an all-null legacy timeline is absent. */
internal fun hasDisplayableBreedReference(
    timeline: List<PetHistoryBreedReferenceTimelinePoint>,
): Boolean = timeline.any { !it.values.isNullOrEmpty() }

internal enum class PetWeightPrimaryReference { LEGACY_BREED, RESOLVED_REFERENCE, NONE }

/** One decision shared by line, band, range and legend presentation. */
internal fun petWeightPrimaryReference(
    reference: PetHistoryWeightReference,
    timeline: List<PetHistoryBreedReferenceTimelinePoint>,
): PetWeightPrimaryReference {
    val available = reference as? PetHistoryWeightReference.Available
    val legacyEligible = available?.provenance in setOf(
        null,
        WeightReferenceProvenance.POPULATION,
        WeightReferenceProvenance.WEIGHT_CATEGORY,
    )
    return when {
        legacyEligible && hasDisplayableBreedReference(timeline) -> PetWeightPrimaryReference.LEGACY_BREED
        available != null -> PetWeightPrimaryReference.RESOLVED_REFERENCE
        else -> PetWeightPrimaryReference.NONE
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
    val primaryReference = petWeightPrimaryReference(reference, breedReferenceTimeline)
    val breedSeries = if (primaryReference == PetWeightPrimaryReference.LEGACY_BREED) {
        breedWeightReferenceChartSeries(breedReferenceTimeline)
    } else emptyList()
    if (breedSeries.isNotEmpty()) {
        val counters = mutableMapOf<BreedWeightReferenceSeriesKind, Int>()
        breedSeries.distinctBy { Triple(it.kind, it.xEpochMillis, it.points.map { point -> point.second }) }.forEach { series ->
            val occurrence = counters.getOrDefault(series.kind, 0)
            counters[series.kind] = occurrence + 1
            val (kind, label, style) = when (series.kind) {
                BreedWeightReferenceSeriesKind.LOWER_BOUNDARY -> Triple(
                    PetWeightDisplayedSeriesKind.BREED_LOWER,
                    series.statisticLabel ?: "Нижняя граница",
                    PetWeightDisplayedSeriesStyle.BREED_BOUNDARY,
                )
                BreedWeightReferenceSeriesKind.UPPER_BOUNDARY -> Triple(
                    PetWeightDisplayedSeriesKind.BREED_UPPER,
                    series.statisticLabel ?: "Верхняя граница",
                    PetWeightDisplayedSeriesStyle.BREED_BOUNDARY,
                )
                BreedWeightReferenceSeriesKind.CENTER -> Triple(
                    PetWeightDisplayedSeriesKind.BREED_CENTER,
                    series.statisticLabel ?: "Медиана или среднее",
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
    } else if (
        primaryReference == PetWeightPrimaryReference.RESOLVED_REFERENCE &&
        reference is PetHistoryWeightReference.Available &&
        reference.provenance != WeightReferenceProvenance.BREED_EXACT_OBSERVATION
    ) {
        val availableReference = reference
        val isBreedModel = availableReference.provenance == WeightReferenceProvenance.BREED_CURVE
        val isFittedPopulation = availableReference.isFittedPopulationPercentiles
        val useBreedPresentation = isBreedModel || isFittedPopulation
        petWeightReferenceChartSeries(reference, factual, zoneId).forEachIndexed { index, series ->
            if (useBreedPresentation && series.kind == PetWeightReferenceSeriesKind.MEDIAN_UPPER) return@forEachIndexed
            val (kind, label) = when (series.kind) {
                PetWeightReferenceSeriesKind.LOWER -> (if (useBreedPresentation) PetWeightDisplayedSeriesKind.BREED_LOWER else PetWeightDisplayedSeriesKind.CATEGORY_LOWER) to when {
                    isBreedModel -> "Нижняя граница модели"
                    isFittedPopulation -> "Нижняя граница P9"
                    else -> "Нижняя граница эталона"
                }
                PetWeightReferenceSeriesKind.MEDIAN_LOWER -> (if (useBreedPresentation) PetWeightDisplayedSeriesKind.BREED_CENTER else PetWeightDisplayedSeriesKind.CATEGORY_MEDIAN_LOWER) to when {
                    isBreedModel -> "Центр породной модели"
                    isFittedPopulation -> "Медиана P50"
                    else -> "Нижняя медианная граница"
                }
                PetWeightReferenceSeriesKind.MEDIAN_UPPER -> PetWeightDisplayedSeriesKind.CATEGORY_MEDIAN_UPPER to "Верхняя медианная граница"
                PetWeightReferenceSeriesKind.UPPER -> (if (useBreedPresentation) PetWeightDisplayedSeriesKind.BREED_UPPER else PetWeightDisplayedSeriesKind.CATEGORY_UPPER) to when {
                    isBreedModel -> "Верхняя граница модели"
                    isFittedPopulation -> "Верхняя граница P91"
                    else -> "Верхняя граница эталона"
                }
            }
            add(
                PetWeightDisplayedSeries(
                    id = "category-${series.kind.name.lowercase()}-$index",
                    kind = kind,
                    label = label,
                    x = series.xEpochMillis
                        ?: series.points.map { (date, _) -> date.atStartOfDay(zoneId).toInstant().toEpochMilli() },
                    y = series.points.map { it.second },
                    style = if (!useBreedPresentation) PetWeightDisplayedSeriesStyle.CATEGORY
                    else if (kind == PetWeightDisplayedSeriesKind.BREED_CENTER) PetWeightDisplayedSeriesStyle.BREED_CENTER
                    else PetWeightDisplayedSeriesStyle.BREED_BOUNDARY,
                ),
            )
        }
    }
}

internal fun formatPetWeightDisplayedMarker(
    targetXEpochMillis: Long,
    displayedSeries: List<PetWeightDisplayedSeries>,
    zoneId: ZoneId,
    locale: Locale = Locale.getDefault(),
): String {
    val number = NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = 2
        maximumFractionDigits = 2
        isGroupingUsed = false
    }
    val targetDate = Instant.ofEpochMilli(targetXEpochMillis).atZone(zoneId).toLocalDate()
    val isFactualSelection = displayedSeries.any { series ->
        series.kind == PetWeightDisplayedSeriesKind.FACTUAL && targetXEpochMillis in series.x
    }
    val values = displayedSeries.mapNotNull { series ->
        val value = if (series.kind == PetWeightDisplayedSeriesKind.FACTUAL) {
            series.x.indexOf(targetXEpochMillis).takeIf { it >= 0 }?.let(series.y::get)
        } else {
            // Reference samples represent a local calendar date. A factual point later on that
            // same date must receive those bounds even when it is the final visible day (where
            // epoch interpolation has no following anchor).
            val referenceDayStart = targetDate.atStartOfDay(zoneId).toInstant().toEpochMilli()
            val sameDateIndex = if (isFactualSelection) series.x.indexOf(referenceDayStart) else -1
            if (sameDateIndex >= 0) series.y[sameDateIndex]
            else petWeightRenderedModelSeries(series).valueAt(targetXEpochMillis)
        }
        value?.let {
            "${series.label}: ${number.format(it)} кг"
        }
    }
    if (values.isEmpty()) return ""
    val date = DateTimeFormatter.ofPattern("dd.MM.yyyy", locale).format(targetDate)
    return (listOf("Дата: $date") + values).joinToString("\n")
}

private fun PetWeightChartModelSeries.valueAt(targetXEpochMillis: Long): Double? {
    val values = x.indices.map { index -> x[index] to y[index] }.sortedBy { it.first }
    values.firstOrNull { (x, _) -> x == targetXEpochMillis }?.let { return it.second }

    val insertionIndex = values.indexOfFirst { (x, _) -> x > targetXEpochMillis }
    if (insertionIndex <= 0) return null
    val (startX, startValue) = values[insertionIndex - 1]
    val (endX, endValue) = values[insertionIndex]
    val intervalMillis = endX.toDouble() - startX.toDouble()
    if (intervalMillis <= 0) return null
    val elapsedMillis = targetXEpochMillis.toDouble() - startX.toDouble()
    return startValue + (endValue - startValue) * elapsedMillis / intervalMillis
}

internal fun petWeightDisplayedMarkerXs(
    displayedSeries: List<PetWeightDisplayedSeries>,
): List<Long> = displayedSeries.flatMap(PetWeightDisplayedSeries::x).distinct().sorted()

internal fun breedWeightReferenceChartSeries(
    timeline: List<PetHistoryBreedReferenceTimelinePoint>,
): List<BreedWeightReferenceChartSeries> {
    val seriesIds = timeline.flatMap { point -> point.values.orEmpty().map(PetHistoryBreedChartValue::seriesId) }.distinct()
    return buildList {
        seriesIds.forEach { seriesId ->
            val statisticLabel = timeline.asSequence()
                .flatMap { it.values.orEmpty().asSequence() }
                .firstOrNull { it.seriesId == seriesId }
                ?.statisticLabel
            fun points(selector: (PetHistoryBreedChartValue) -> Double?): List<Pair<List<Pair<LocalDate, Double>>, List<Long>>> {
                val segments = mutableListOf<MutableList<Pair<LocalDate, Double>>>()
                val timestamps = mutableListOf<MutableList<Long>>()
                timeline.forEach { point ->
                    val value = point.values?.firstOrNull { it.seriesId == seriesId }?.let(selector)
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
                (it as? PetHistoryBreedChartValue.Boundary)
                    ?.takeIf { boundary -> boundary.direction == BreedWeightValue.Boundary.Direction.LOWER }
                    ?.valueKg
            }.forEach { (values, x) -> add(BreedWeightReferenceChartSeries(BreedWeightReferenceSeriesKind.LOWER_BOUNDARY, values, xEpochMillis = x, statisticLabel = statisticLabel)) }
            points {
                (it as? PetHistoryBreedChartValue.Boundary)
                    ?.takeIf { boundary -> boundary.direction == BreedWeightValue.Boundary.Direction.UPPER }
                    ?.valueKg
            }.forEach { (values, x) -> add(BreedWeightReferenceChartSeries(BreedWeightReferenceSeriesKind.UPPER_BOUNDARY, values, xEpochMillis = x, statisticLabel = statisticLabel)) }
            points {
                when (it) {
                    is PetHistoryBreedChartValue.Interval -> it.centerKg
                    is PetHistoryBreedChartValue.Single -> it.valueKg
                    is PetHistoryBreedChartValue.Boundary -> null
                }
            }.forEach { (values, x) ->
                add(
                    BreedWeightReferenceChartSeries(
                        BreedWeightReferenceSeriesKind.CENTER,
                        values,
                        xEpochMillis = x,
                        statisticLabel = statisticLabel,
                    ),
                )
            }
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
    val hasBreedTimeline = hasDisplayableBreedReference(breedReferenceTimeline)
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
                    is PetHistoryBreedChartValue.Boundary -> add(value.valueKg)
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
                    is PetHistoryBreedChartValue.Boundary -> add(value.valueKg)
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
    factual: List<ChartPoint> = emptyList(),
    zoneId: ZoneId = ZoneId.systemDefault(),
): List<PetWeightReferenceChartSeries> {
    val available = reference as? PetHistoryWeightReference.Available ?: return emptyList()
    return available.segments.flatMap { segment ->
        listOf(
            PetWeightReferenceChartSeries(
                PetWeightReferenceSeriesKind.LOWER,
                segment.map { it.date to it.lowerKg },
                referenceSegmentX(segment.map { it.date }, factual, zoneId),
            ),
            PetWeightReferenceChartSeries(
                PetWeightReferenceSeriesKind.MEDIAN_LOWER,
                segment.map { it.date to it.medianLowerKg },
                referenceSegmentX(segment.map { it.date }, factual, zoneId),
            ),
            PetWeightReferenceChartSeries(
                PetWeightReferenceSeriesKind.MEDIAN_UPPER,
                segment.map { it.date to it.medianUpperKg },
                referenceSegmentX(segment.map { it.date }, factual, zoneId),
            ),
            PetWeightReferenceChartSeries(
                PetWeightReferenceSeriesKind.UPPER,
                segment.map { it.date to it.upperKg },
                referenceSegmentX(segment.map { it.date }, factual, zoneId),
            ),
        )
    }
}

private fun referenceSegmentX(
    dates: List<LocalDate>,
    factual: List<ChartPoint>,
    zoneId: ZoneId,
): List<Long>? {
    if (factual.isEmpty()) return null
    val factualXsByDate = factual.mapNotNull { point ->
        point.xEpochMillis?.let { x ->
            runCatching { point.measuredAt.atZone(zoneId).toLocalDate() to x }.getOrNull()
        }
    }.groupBy({ it.first }, { it.second })
    return dates.mapIndexed { index, date ->
        val sameDayXs = factualXsByDate[date]
        when {
            index == 0 && sameDayXs != null -> sameDayXs.min()
            index == dates.lastIndex && sameDayXs != null -> sameDayXs.max()
            else -> date.atStartOfDay(zoneId).toInstant().toEpochMilli()
        }
    }
}

internal fun shouldShowWeightReferenceExplanation(
    reference: PetHistoryWeightReference,
    breedReference: PetHistoryBreedReference,
): Boolean = reference is PetHistoryWeightReference.Available ||
    breedReference is PetHistoryBreedReference.Hidden ||
    (breedReference is PetHistoryBreedReference.Unavailable &&
        breedReference.reason == BreedWeightReferenceUnavailableReason.OtherBreed)

@Composable
internal fun PetWeightReferenceChartCard(
    series: ChartSeries,
    reference: PetHistoryWeightReference,
    breedReference: PetHistoryBreedReference = PetHistoryBreedReference.Hidden,
    breedReferenceTimeline: List<PetHistoryBreedReferenceTimelinePoint> = emptyList(),
    startDate: LocalDate,
    endDateInclusive: LocalDate,
    zoneId: ZoneId,
    sourceLauncher: ReferenceSourceLauncher,
) {
    val factual = remember(series.points) {
        series.points.filter { it.xEpochMillis != null && it.value.isFinite() }
            .sortedBy(ChartPoint::measuredAtEpochSecond)
    }
    val displayedSeries = remember(factual, reference, breedReferenceTimeline, zoneId) {
        petWeightDisplayedSeries(factual, reference, breedReferenceTimeline, zoneId)
    }
    val primaryReference = remember(reference, breedReferenceTimeline) {
        petWeightPrimaryReference(reference, breedReferenceTimeline)
    }
    val available = reference as? PetHistoryWeightReference.Available
    val isPopulationReference = available?.isFittedPopulationPercentiles == true
    val exactObservationGlyphs = remember(available, zoneId) { exactObservationGlyphs(available, zoneId) }
    val legendEntries = remember(displayedSeries, isPopulationReference, available?.provenance) {
        if (isPopulationReference) buildList {
            if (displayedSeries.any { it.style == PetWeightDisplayedSeriesStyle.FACTUAL }) {
                add(PetWeightChartLegendEntry("● Фактический вес", PetWeightDisplayedSeriesStyle.FACTUAL))
            }
            addAll(referenceWeightChartLegendEntries(requireNotNull(available).provenance))
        } else if (available?.provenance in setOf(
                WeightReferenceProvenance.BREED_CURVE,
                WeightReferenceProvenance.BREED_EXACT_OBSERVATION,
            )
        ) buildList {
            if (displayedSeries.any { it.style == PetWeightDisplayedSeriesStyle.FACTUAL }) {
                add(PetWeightChartLegendEntry("● Фактический вес", PetWeightDisplayedSeriesStyle.FACTUAL))
            }
            addAll(referenceWeightChartLegendEntries(requireNotNull(available).provenance))
        } else petWeightChartLegendEntries(displayedSeries)
    }
    val yRange = remember(displayedSeries) {
        (displayedSeries.flatMap(PetWeightDisplayedSeries::y) + exactObservationGlyphs.flatMap { listOf(it.lowerKg, it.upperKg) })
            .takeIf(List<Double>::isNotEmpty)?.let { values ->
            val min = values.min()
            val max = values.max()
            val padding = ((max - min) * 0.08).coerceAtLeast(0.1)
            PetWeightChartRange((min - padding).coerceAtLeast(0.0), max + padding)
        }
    }
    val factualColor = MaterialTheme.colorScheme.primary
    val referenceColor = MaterialTheme.colorScheme.tertiary
    val hasBreedTimeline = hasDisplayableBreedReference(breedReferenceTimeline) &&
        available?.provenance !in setOf(WeightReferenceProvenance.BREED_CURVE, WeightReferenceProvenance.BREED_EXACT_OBSERVATION, WeightReferenceProvenance.POPULATION_FALLBACK_FOR_SELECTED_BREED)
    val showReferenceExplanation = shouldShowWeightReferenceExplanation(reference, breedReference)
    val description = petWeightChartDescription(
        factualCount = factual.size,
        reference = reference,
        showReferenceExplanation = showReferenceExplanation,
        hasBreedTimeline = hasBreedTimeline,
        isPopulationReference = isPopulationReference,
        legendEntries = legendEntries,
    )

    HuaweiSurface(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(HuaweiDimensions.CompactItemSpacing)) {
            Text(
                "Вес питомца",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            if (yRange != null) {
                PetWeightVicoChart(
                    displayedSeries = if (isPopulationReference) displayedSeries.filterNot {
                        it.kind == PetWeightDisplayedSeriesKind.CATEGORY_MEDIAN_UPPER
                    } else displayedSeries,
                    breedBands = when {
                        isPopulationReference -> populationWeightReferenceBands(available, zoneId, factual)
                        available?.provenance == WeightReferenceProvenance.BREED_CURVE ->
                            populationWeightReferenceBands(available, zoneId, factual)
                        available?.provenance == WeightReferenceProvenance.BREED_EXACT_OBSERVATION -> emptyList()
                        primaryReference == PetWeightPrimaryReference.LEGACY_BREED ->
                            breedWeightReferenceBands(breedReferenceTimeline)
                        primaryReference == PetWeightPrimaryReference.RESOLVED_REFERENCE && available != null ->
                            populationWeightReferenceBands(available, zoneId, factual)
                        else -> emptyList()
                    },
                    exactObservationGlyphs = exactObservationGlyphs,
                    startDate = startDate,
                    endDateInclusive = endDateInclusive,
                    zoneId = zoneId,
                    yRange = yRange,
                    factualColor = factualColor,
                    referenceColor = referenceColor,
                    contentDescription = description,
                )
                if (displayedSeries.isNotEmpty() || exactObservationGlyphs.isNotEmpty()) {
                    DisplayedSeriesLegend(legendEntries, factualColor, referenceColor)
                } else if (factual.size < 2) {
                    Text(stringResource(R.string.pet_chart_single_measurement))
                }
            } else {
                Text(stringResource(R.string.pet_chart_no_data), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (showReferenceExplanation && !hasBreedTimeline) ReferenceExplanation(reference, sourceLauncher)
        }
    }
}

@Composable
private fun PetWeightVicoChart(
    displayedSeries: List<PetWeightDisplayedSeries>,
    breedBands: List<BreedWeightReferenceBand>,
    exactObservationGlyphs: List<PetWeightExactObservationGlyph>,
    startDate: LocalDate,
    endDateInclusive: LocalDate,
    zoneId: ZoneId,
    yRange: PetWeightChartRange,
    factualColor: Color,
    referenceColor: Color,
    contentDescription: String,
) {
    val viewport = remember(displayedSeries, startDate, endDateInclusive, zoneId) {
        chartViewport(
            displayedSeries.flatMap(PetWeightDisplayedSeries::x),
            startDate,
            endDateInclusive,
            zoneId,
        )
    }
    val rangeProvider = remember(viewport, yRange) {
        object : CartesianLayerRangeProvider {
            override fun getMinX(minX: Double, maxX: Double, extraStore: ExtraStore) = viewport.modelRange.minX
            override fun getMaxX(minX: Double, maxX: Double, extraStore: ExtraStore) = viewport.modelRange.maxX
            override fun getMinY(minY: Double, maxY: Double, extraStore: ExtraStore) = yRange.min
            override fun getMaxY(minY: Double, maxY: Double, extraStore: ExtraStore) = yRange.max
        }
    }
    val lines = displayedSeries.map { series ->
        when (series.style) {
            PetWeightDisplayedSeriesStyle.FACTUAL -> rememberChartLine(factualColor, series.x.size)
            PetWeightDisplayedSeriesStyle.CATEGORY -> rememberBreedChartLine(
                referenceColor,
                if (series.kind == PetWeightDisplayedSeriesKind.CATEGORY_MEDIAN_LOWER) BreedWeightReferenceSeriesKind.CENTER
                else BreedWeightReferenceSeriesKind.LOWER_BOUNDARY,
            )
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
    val exactObservationDecoration = remember(exactObservationGlyphs) {
        ExactObservationDecoration(exactObservationGlyphs, Color(0xFF43A047))
    }
    val modelProducer = remember { CartesianChartModelProducer() }
    val bottomFormatter = remember(zoneId) {
        CartesianValueFormatter { _, value, _ ->
            PetAxisDateFormatter.format(Instant.ofEpochMilli(value.toLong()).atZone(zoneId))
        }
    }
    val markerFormatter = remember(displayedSeries, zoneId) {
        DefaultCartesianMarker.ValueFormatter { _, targets ->
            val target = targets.firstOrNull() as? LineCartesianLayerMarkerTarget
                ?: return@ValueFormatter ""
            formatPetWeightDisplayedMarker(target.x.toLong(), displayedSeries, zoneId)
        }
    }
    val zoomState = key(viewport.initialVisibleRange, zoneId) {
        rememberVicoZoomState(zoomEnabled = true, initialZoom = Zoom.x(viewport.initialVisibleWidth()))
    }
    val scrollState = key(viewport.initialVisibleRange, zoneId) {
        rememberVicoScrollState(scrollEnabled = true, initialScroll = Scroll.Absolute.x(viewport.initialVisibleRange.minX))
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
        ?.let { formatPetWeightDisplayedMarker(it, displayedSeries, zoneId) }
        ?.takeIf(String::isNotEmpty)

    LaunchedEffect(displayedSeries) {
        modelProducer.runTransaction {
            lineModel {
                displayedSeries.forEach { chartSeries ->
                    val rendered = petWeightRenderedModelSeries(chartSeries)
                    series(x = rendered.x, y = rendered.y)
                }
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(250.dp)) {
        CartesianChartHost(
            chart = rememberCartesianChart(
                rememberChartLineLayer(lines, rangeProvider),
                startAxis = rememberChartStartAxis(
                    CartesianValueFormatter.decimal(decimalCount = 2, suffix = " кг"),
                ),
                bottomAxis = rememberChartBottomAxis(bottomFormatter),
                marker = rememberChartMarker(
                    markerFormatter,
                    lineCount = null,
                ),
                decorations = listOf(bandDecoration, exactObservationDecoration),
            ),
            modelProducer = modelProducer,
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxSize()
                .testTag(PetWeightChartTestTags.Chart)
                .semantics {
                    this.contentDescription = contentDescription
                    chartScrollOffset = scrollState.value
                    role = Role.Button
                    stateDescription = accessibleMarker ?: "Нет доступных точек для выбора"
                    onClick("Выбрать следующую точку") { selectRelative(1) }
                    customActions = listOf(
                        CustomAccessibilityAction("Выбрать предыдущую точку") { selectRelative(-1) },
                        CustomAccessibilityAction("Выбрать следующую точку") { selectRelative(1) },
                    )
                },
            scrollState = scrollState,
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

        canvas.save()
        canvas.clipRect(layerBounds.left, layerBounds.top, layerBounds.right, layerBounds.bottom)
        try {
            bands.forEach { band ->
                val path = Path()
                band.points.forEachIndexed { index, point ->
                    val lowerKg = breedWeightReferenceBandLowerKg(band, point, yRange.minY)
                    if (index == 0) path.moveTo(x(point.xEpochMillis), y(lowerKg))
                    else path.lineTo(x(point.xEpochMillis), y(lowerKg))
                }
                band.points.asReversed().forEach { point ->
                    path.lineTo(x(point.xEpochMillis), y(point.upperKg))
                }
                path.close()
                canvas.drawPath(path, paint)
            }
        } finally {
            canvas.restore()
        }
    }
}

private class ExactObservationDecoration(
    private val glyphs: List<PetWeightExactObservationGlyph>,
    color: Color,
) : Decoration {
    private val whiskerPaint = Paint().apply {
        this.color = color
        style = PaintingStyle.Stroke
        strokeWidth = 2f
    }
    private val pointPaint = Paint().apply { this.color = color }

    override fun drawOverLayers(context: CartesianDrawingContext) = with(context) {
        val yRange = ranges.getYRange(Axis.Position.Vertical.Start)
        if (ranges.xStep == 0.0 || yRange.length == 0.0) return@with
        val start = if (isLtr) layerBounds.left else layerBounds.right
        val baseX = start + layoutDirectionMultiplier * layerDimensions.startPadding - scroll
        fun x(value: Long): Float = baseX + layoutDirectionMultiplier * layerDimensions.xSpacing *
            ((value - ranges.minX) / ranges.xStep).toFloat()
        fun y(value: Double): Float = layerBounds.bottom -
            ((value - yRange.minY) / yRange.length).toFloat() * layerBounds.height

        canvas.save()
        canvas.clipRect(layerBounds.left, layerBounds.top, layerBounds.right, layerBounds.bottom)
        try {
            glyphs.forEach { glyph ->
                val glyphX = x(glyph.xEpochMillis)
                val cap = 6f
                canvas.drawLine(Offset(glyphX, y(glyph.lowerKg)), Offset(glyphX, y(glyph.upperKg)), whiskerPaint)
                canvas.drawLine(Offset(glyphX - cap, y(glyph.lowerKg)), Offset(glyphX + cap, y(glyph.lowerKg)), whiskerPaint)
                canvas.drawLine(Offset(glyphX - cap, y(glyph.upperKg)), Offset(glyphX + cap, y(glyph.upperKg)), whiskerPaint)
                canvas.drawCircle(Offset(glyphX, y(glyph.meanKg)), 4f, pointPaint)
            }
        } finally {
            canvas.restore()
        }
    }
}

@Composable
private fun DisplayedSeriesLegend(
    entries: List<PetWeightChartLegendEntry>,
    factualColor: Color,
    referenceColor: Color,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        entries.forEach { entry ->
            val color = when (entry.style) {
                PetWeightDisplayedSeriesStyle.FACTUAL -> factualColor
                PetWeightDisplayedSeriesStyle.CATEGORY -> referenceColor
                PetWeightDisplayedSeriesStyle.BREED_BOUNDARY -> Color(0xFF43A047).copy(alpha = 0.36f)
                PetWeightDisplayedSeriesStyle.BREED_CENTER -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
            }
            ChartLegend(entry.label, color)
        }
    }
}

@Composable
private fun ChartLegend(text: String, color: Color) {
    Text(text, color = color, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun ReferenceExplanation(reference: PetHistoryWeightReference, sourceLauncher: ReferenceSourceLauncher) {
    when (reference) {
        is PetHistoryWeightReference.Unavailable -> Text(
            reference.explanation,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .testTag(PetWeightChartTestTags.Unavailable)
                .semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite },
        )
        is PetHistoryWeightReference.Available -> {
            var sourceExpanded by remember(reference) { mutableStateOf(false) }
            var sourceError by remember(reference.publicationUrl) { mutableStateOf(false) }
            val expandedState = stringResource(R.string.state_expanded)
            val collapsedState = stringResource(R.string.state_collapsed)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(PetWeightChartTestTags.ReferenceDetails)
                    .semantics(mergeDescendants = true) {
                        contentDescription = buildString {
                            append("${reference.basisLabel}. ${reference.ageLabel}.")
                            if (sourceExpanded) {
                                append(" ${reference.accessibilityLabel}")
                                reference.constraints.forEach { append(" Ограничение: $it.") }
                            }
                            if (reference.isFittedPopulationPercentiles) {
                                append(" Сведения справочные и не оценивают здоровье питомца.")
                            } else append(" Эталон не ставит диагноз.")
                        }
                    },
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
            Text(stringResource(if (reference.isFittedPopulationPercentiles) R.string.reference_how_to_read_data else R.string.reference_how_to_read), style = MaterialTheme.typography.titleSmall)
            Text("${reference.basisLabel} · ${reference.ageLabel}")
            Text(
                when {
                    reference.provenance == WeightReferenceProvenance.BREED_CURVE ->
                        stringResource(R.string.reference_breed_curve_explanation)
                    reference.isFittedPopulationPercentiles ->
                        stringResource(R.string.reference_population_explanation)
                    reference.provenance == WeightReferenceProvenance.BREED_EXACT_OBSERVATION ->
                        stringResource(R.string.reference_observation_explanation)
                    else -> stringResource(R.string.reference_bounds_explanation)
                },
            )
            TextButton(
                onClick = { sourceExpanded = !sourceExpanded },
                modifier = Modifier.testTag(PetWeightChartTestTags.Disclosure).semantics {
                    stateDescription = if (sourceExpanded) expandedState else collapsedState
                },
            ) { Text(stringResource(R.string.reference_source_and_limits, if (sourceExpanded) "▴" else "▾")) }
            if (sourceExpanded) Column(Modifier.fillMaxWidth()) {
                reference.segments.distinctBy { it.sourceId }.forEach { segment ->
                    Text(stringResource(R.string.reference_source, segment.citation), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.reference_license, segment.license), style = MaterialTheme.typography.bodySmall)
                }
                reference.sourceAuthorityLabel?.let { Text(stringResource(R.string.reference_source_type, it), style = MaterialTheme.typography.bodySmall) }
                reference.sourceAccessedDate?.let { Text(stringResource(R.string.reference_accessed, it), style = MaterialTheme.typography.bodySmall) }
                reference.sourceDisclosure?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (reference.provenance == WeightReferenceProvenance.BREED_CURVE) {
                    Text(
                        stringResource(R.string.reference_center_calculation),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                reference.constraints.forEach { Text(stringResource(R.string.reference_limitation, it), style = MaterialTheme.typography.bodySmall) }
                if (reference.publicationUrl != null) {
                    TextButton(
                        onClick = { sourceError = !sourceLauncher.open(reference.publicationUrl) },
                        modifier = Modifier.testTag(PetWeightChartTestTags.Publication),
                    ) { Text(stringResource(if (reference.isFittedPopulationPercentiles) R.string.reference_open_publication else R.string.reference_open_source)) }
                    if (sourceError) Text(
                        stringResource(R.string.reference_open_error, reference.citation),
                        modifier = Modifier
                            .testTag(PetWeightChartTestTags.PublicationError)
                            .semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Assertive },
                    )
                }
            }
            Text(
                stringResource(if (reference.isFittedPopulationPercentiles) R.string.reference_population_disclaimer else R.string.reference_disclaimer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            }
        }
    }
}

internal fun populationWeightReferenceBands(
    reference: PetHistoryWeightReference.Available?,
    zoneId: ZoneId,
    factual: List<ChartPoint> = emptyList(),
): List<BreedWeightReferenceBand> =
    reference?.segments.orEmpty().mapNotNull { segment ->
        segment.takeIf { it.size >= 2 }?.let { points ->
            val x = referenceSegmentX(points.map { it.date }, factual, zoneId)
                ?: points.map { it.date.atStartOfDay(zoneId).toInstant().toEpochMilli() }
            BreedWeightReferenceBand(points.mapIndexed { index, point ->
                BreedWeightReferenceBandPoint(
                    x[index],
                    point.lowerKg,
                    point.upperKg,
                )
            })
        }
    }

private val PetAxisDateFormatter = DateTimeFormatter.ofPattern("dd.MM")
