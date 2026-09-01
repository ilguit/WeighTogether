package com.palixander.scalesync.ui.profiles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.GenericShape
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
import com.patrykandpatrick.vico.compose.cartesian.Zoom
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.compose.cartesian.data.lineModel
import com.patrykandpatrick.vico.compose.cartesian.marker.DefaultCartesianMarker
import com.patrykandpatrick.vico.compose.cartesian.marker.LineCartesianLayerMarkerTarget
import com.patrykandpatrick.vico.compose.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import com.patrykandpatrick.vico.compose.common.data.ExtraStore
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.common.component.ShapeComponent
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

internal object PetWeightChartTestTags {
    const val Chart = "pet-weight-reference-chart"
    const val ReferenceDetails = "pet-weight-reference-details"
    const val Unavailable = "pet-weight-reference-unavailable"
    const val BreedLayer = "pet-breed-reference-chart-layer"
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
    val showsPointMarkers: Boolean = true,
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
    -> BreedWeightReferenceSeriesPresentation(strokeWidthDp = 4, pointSizeDp = 8, lowEmphasis = false)
    BreedWeightReferenceSeriesKind.CENTER ->
        BreedWeightReferenceSeriesPresentation(strokeWidthDp = 2, pointSizeDp = 8, lowEmphasis = true)
}

internal data class PetWeightChartModelSeries(
    val x: List<Long>,
    val y: List<Double>,
)

internal fun breedWeightReferenceChartSeries(
    timeline: List<PetHistoryBreedReferenceTimelinePoint>,
): List<BreedWeightReferenceChartSeries> {
    val maximumValueCount = timeline.maxOfOrNull { it.values?.size ?: 0 } ?: return emptyList()
    return buildList {
        repeat(maximumValueCount) { valueIndex ->
            fun points(selector: (PetHistoryBreedChartValue) -> Double?): List<List<Pair<LocalDate, Double>>> {
                val segments = mutableListOf<MutableList<Pair<LocalDate, Double>>>()
                timeline.forEach { point ->
                    val value = point.values?.getOrNull(valueIndex)?.let(selector)
                    if (value == null) {
                        if (segments.lastOrNull()?.isNotEmpty() == true) segments.add(mutableListOf())
                    } else {
                        if (segments.isEmpty()) segments.add(mutableListOf())
                        segments.last().add(point.date to value)
                    }
                }
                return segments.filter(List<Pair<LocalDate, Double>>::isNotEmpty)
            }

            points { (it as? PetHistoryBreedChartValue.Interval)?.lowerKg }
                .forEach { add(BreedWeightReferenceChartSeries(BreedWeightReferenceSeriesKind.LOWER_BOUNDARY, it)) }
            points { (it as? PetHistoryBreedChartValue.Interval)?.upperKg }
                .forEach { add(BreedWeightReferenceChartSeries(BreedWeightReferenceSeriesKind.UPPER_BOUNDARY, it)) }
            points {
                when (it) {
                    is PetHistoryBreedChartValue.Interval -> it.centerKg
                    is PetHistoryBreedChartValue.Single -> it.valueKg
                }
            }.forEach { add(BreedWeightReferenceChartSeries(BreedWeightReferenceSeriesKind.CENTER, it)) }
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
    referenceSeries.forEach { series ->
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
                x = series.points.map { (date, _) ->
                    date.atStartOfDay(zoneId).toInstant().toEpochMilli()
                },
                y = series.points.map { it.second },
            ),
        )
    }
}

internal data class PetWeightMarkerSelection(
    val date: LocalDate,
    val measurementKg: Double?,
    val reference: PetHistoryReferencePoint?,
    val breedValues: List<PetHistoryBreedChartValue>? = null,
)

internal fun petWeightMarkerSelection(
    targetXEpochMillis: Long,
    factual: List<ChartPoint>,
    reference: PetHistoryWeightReference,
    zoneId: ZoneId,
    breedReferenceTimeline: List<PetHistoryBreedReferenceTimelinePoint> = emptyList(),
): PetWeightMarkerSelection {
    val targetDate = Instant.ofEpochMilli(targetXEpochMillis).atZone(zoneId).toLocalDate()
    val measurement = factual
        .filter { it.measuredAt.atZone(zoneId).toLocalDate() == targetDate }
        .minByOrNull { abs(it.xEpochMillis!! - targetXEpochMillis) }
    val referencePoint = (reference as? PetHistoryWeightReference.Available)
        ?.segments
        ?.asSequence()
        ?.flatten()
        ?.firstOrNull { it.date == targetDate }
    val breedValues = breedReferenceTimeline.firstOrNull { it.date == targetDate }?.values
    return PetWeightMarkerSelection(targetDate, measurement?.value, referencePoint, breedValues)
}

internal fun formatPetWeightMarker(
    selection: PetWeightMarkerSelection,
    locale: Locale = Locale.getDefault(),
): String {
    val number = NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = 2
        maximumFractionDigits = 2
    }
    fun kg(value: Double?) = value?.let { "${number.format(it)} кг" } ?: "—"
    return buildString {
        append(PetMarkerDateFormatter.format(selection.date))
        append("\nИзмерение: ${kg(selection.measurementKg)}")
        append("\nНижняя граница: ${kg(selection.reference?.lowerKg)}")
        append("\nМедиана: ${selection.reference?.let { "${kg(it.medianLowerKg)}–${kg(it.medianUpperKg)}" } ?: "—"}")
        append("\nВерхняя граница: ${kg(selection.reference?.upperKg)}")
        selection.breedValues?.takeIf(List<PetHistoryBreedChartValue>::isNotEmpty)?.let { values ->
            append("\nПородный ориентир: ")
            append(values.joinToString("; ") { it.accessibilityLabel })
        }
    }
}

internal fun petWeightChartRange(
    factual: List<ChartPoint>,
    reference: PetHistoryWeightReference,
    breedReference: PetHistoryBreedReference = PetHistoryBreedReference.Hidden,
    breedReferenceTimeline: List<PetHistoryBreedReferenceTimelinePoint> = emptyList(),
): PetWeightChartRange? {
    val values = buildList {
        addAll(factual.map(ChartPoint::value).filter(Double::isFinite))
        if (reference is PetHistoryWeightReference.Available) {
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
    val yRange = remember(factual, reference, breedReference, breedReferenceTimeline) {
        petWeightChartRange(factual, reference, breedReference, breedReferenceTimeline)
    }
    val factualColor = MaterialTheme.colorScheme.primary
    val referenceColor = MaterialTheme.colorScheme.tertiary
    val available = reference as? PetHistoryWeightReference.Available
    val showReferenceExplanation = shouldShowWeightReferenceExplanation(reference, breedReference)
    val description = buildString {
        append("График веса питомца. ")
        append(if (factual.isEmpty()) "Измерений нет. " else "Измерений: ${factual.size}. ")
        if (showReferenceExplanation) {
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
                    factual = factual,
                    reference = reference,
                    breedReference = breedReference,
                    breedReferenceTimeline = breedReferenceTimeline,
                    startDate = startDate,
                    endDateInclusive = endDateInclusive,
                    zoneId = zoneId,
                    yRange = yRange,
                    factualColor = factualColor,
                    referenceColor = referenceColor,
                    contentDescription = description,
                )
                if (available != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        ChartLegend("● Фактический вес", factualColor)
                        ChartLegend("— Границы эталона", referenceColor)
                        if (breedReference is PetHistoryBreedReference.Available) {
                            BreedChartLegend(breedReference)
                        }
                    }
                } else if (breedReference is PetHistoryBreedReference.Available) {
                    BreedChartLegend(breedReference)
                } else if (factual.size < 2) {
                    Text("Для линии нужно минимум два измерения; отдельное измерение показано точкой.")
                }
            } else {
                Text("Нет данных за выбранный период", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (showReferenceExplanation) ReferenceExplanation(reference)
        }
    }
}

@Composable
private fun PetWeightVicoChart(
    factual: List<ChartPoint>,
    reference: PetHistoryWeightReference,
    breedReference: PetHistoryBreedReference,
    breedReferenceTimeline: List<PetHistoryBreedReferenceTimelinePoint>,
    startDate: LocalDate,
    endDateInclusive: LocalDate,
    zoneId: ZoneId,
    yRange: PetWeightChartRange,
    factualColor: Color,
    referenceColor: Color,
    contentDescription: String,
) {
    val available = reference as? PetHistoryWeightReference.Available
    val referenceSeries = remember(reference) { petWeightReferenceChartSeries(reference) }
    val breedSeries = remember(breedReferenceTimeline) {
        breedWeightReferenceChartSeries(breedReferenceTimeline)
    }
    val modelSeries = remember(factual, referenceSeries, breedSeries, zoneId) {
        petWeightChartModelSeries(factual, referenceSeries, breedSeries, zoneId)
    }
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
    val lines = buildList {
        if (factual.isNotEmpty()) add(rememberSmoothChartLine(factualColor, factual.size))
        referenceSeries.forEach { series ->
            add(rememberSmoothChartLine(referenceColor, series.points.size))
        }
        breedSeries.forEach { series ->
            val presentation = breedWeightReferenceSeriesPresentation(series.kind)
            add(
                rememberBreedChartLine(
                    if (presentation.lowEmphasis) {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                    } else {
                        MaterialTheme.colorScheme.secondary
                    },
                    series.kind,
                    series.showsPointMarkers,
                ),
            )
        }
    }
    val modelProducer = remember { CartesianChartModelProducer() }
    val bottomFormatter = remember(zoneId) {
        CartesianValueFormatter { _, value, _ ->
            PetAxisDateFormatter.format(Instant.ofEpochMilli(value.toLong()).atZone(zoneId))
        }
    }
    val markerFormatter = remember(factual, reference, breedReferenceTimeline, zoneId) {
        DefaultCartesianMarker.ValueFormatter { _, targets ->
            val target = targets.firstOrNull() as? LineCartesianLayerMarkerTarget
                ?: return@ValueFormatter ""
            formatPetWeightMarker(
                petWeightMarkerSelection(
                    target.x.toLong(),
                    factual,
                    reference,
                    zoneId,
                    breedReferenceTimeline,
                ),
            )
        }
    }
    val zoomState = key(xRange.minX, xRange.maxX, zoneId) {
        rememberVicoZoomState(zoomEnabled = true, initialZoom = Zoom.Content)
    }
    val selectableDates = remember(factual, available, breedReferenceTimeline, zoneId) {
        (factual.map { it.measuredAt.atZone(zoneId).toLocalDate() } +
            available?.segments.orEmpty().flatten().map(PetHistoryReferencePoint::date) +
            breedReferenceTimeline.filter { !it.values.isNullOrEmpty() }.map(PetHistoryBreedReferenceTimelinePoint::date))
            .distinct()
            .sorted()
    }
    var selectedDateIndex by remember(selectableDates) { mutableIntStateOf(0) }
    fun selectRelative(offset: Int): Boolean {
        if (selectableDates.isEmpty()) return false
        selectedDateIndex = (selectedDateIndex + offset).mod(selectableDates.size)
        return true
    }
    val accessibleSelection = selectableDates.getOrNull(selectedDateIndex)?.let { date ->
        petWeightMarkerSelection(
            date.atStartOfDay(zoneId).toInstant().toEpochMilli(),
            factual,
            reference,
            zoneId,
            breedReferenceTimeline,
        )
    }

    LaunchedEffect(modelSeries) {
        modelProducer.runTransaction {
            lineModel {
                modelSeries.forEach { chartSeries ->
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
                    lineCount = if (breedReferenceTimeline.any { !it.values.isNullOrEmpty() }) 6 else 5,
                ),
            ),
            modelProducer = modelProducer,
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxSize()
                .testTag(PetWeightChartTestTags.Chart)
                .semantics {
                    this.contentDescription = contentDescription
                    role = Role.Button
                    stateDescription = accessibleSelection?.let(::formatPetWeightMarker)
                        ?: "Нет доступных дат для выбора"
                    onClick("Выбрать следующую дату") { selectRelative(1) }
                    customActions = listOf(
                        CustomAccessibilityAction("Выбрать предыдущую дату") { selectRelative(-1) },
                        CustomAccessibilityAction("Выбрать следующую дату") { selectRelative(1) },
                    )
                },
            scrollState = rememberVicoScrollState(scrollEnabled = true),
            zoomState = zoomState,
        )
        if (breedReference is PetHistoryBreedReference.Available) {
            BreedReferenceSemantics(
                accessibleSelection?.date ?: endDateInclusive,
                accessibleSelection?.breedValues,
            )
        }
    }
}

@Composable
private fun rememberBreedChartLine(
    color: Color,
    kind: BreedWeightReferenceSeriesKind,
    showsPointMarkers: Boolean,
): LineCartesianLayer.Line {
    val diamond = remember {
        GenericShape { size, _ ->
            moveTo(size.width / 2f, 0f)
            lineTo(size.width, size.height / 2f)
            lineTo(size.width / 2f, size.height)
            lineTo(0f, size.height / 2f)
            close()
        }
    }
    val presentation = breedWeightReferenceSeriesPresentation(kind)
    return remember(color, kind, showsPointMarkers, diamond) {
        val pointShape = if (kind == BreedWeightReferenceSeriesKind.CENTER) diamond else CircleShape
        LineCartesianLayer.Line(
            fill = LineCartesianLayer.LineFill.single(Fill(color)),
            stroke = LineCartesianLayer.LineStroke.Continuous(presentation.strokeWidthDp.dp),
            areaFill = null,
            pointProvider = if (showsPointMarkers) {
                LineCartesianLayer.PointProvider.single(
                    LineCartesianLayer.Point(
                        component = ShapeComponent(fill = Fill(color), shape = pointShape),
                        size = presentation.pointSizeDp.dp,
                    ),
                )
            } else {
                null
            },
            interpolator = LineCartesianLayer.Interpolator.Sharp,
        )
    }
}

@Composable
private fun BreedReferenceSemantics(
    date: LocalDate,
    values: List<PetHistoryBreedChartValue>?,
) {
    Box(
        Modifier
            .fillMaxSize()
            .testTag(PetWeightChartTestTags.BreedLayer)
            .semantics(mergeDescendants = true) {
                values?.takeIf(List<PetHistoryBreedChartValue>::isNotEmpty)?.let {
                    contentDescription = it.joinToString(" ") { value -> value.accessibilityLabel }
                    stateDescription = "Породный ориентир для $date"
                }
            },
    )
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
private val PetMarkerDateFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")
