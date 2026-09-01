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
    val points: List<Pair<LocalDate, Double>>,
    val drawsInterval: Boolean,
)

internal fun breedWeightReferenceChartSeries(
    reference: PetHistoryBreedReference,
    date: LocalDate,
): List<BreedWeightReferenceChartSeries> {
    val available = reference as? PetHistoryBreedReference.Available ?: return emptyList()
    return available.chartValues.flatMap { value ->
        when (value) {
            is PetHistoryBreedChartValue.Interval -> buildList {
                add(BreedWeightReferenceChartSeries(listOf(date to value.lowerKg, date to value.upperKg), true))
                value.centerKg?.let { add(BreedWeightReferenceChartSeries(listOf(date to it), false)) }
            }
            is PetHistoryBreedChartValue.Single ->
                listOf(BreedWeightReferenceChartSeries(listOf(date to value.valueKg), false))
        }
    }
}

internal data class PetWeightMarkerSelection(
    val date: LocalDate,
    val measurementKg: Double?,
    val reference: PetHistoryReferencePoint?,
)

internal fun petWeightMarkerSelection(
    targetXEpochMillis: Long,
    factual: List<ChartPoint>,
    reference: PetHistoryWeightReference,
    zoneId: ZoneId,
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
    return PetWeightMarkerSelection(targetDate, measurement?.value, referencePoint)
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
    }
}

internal fun petWeightChartRange(
    factual: List<ChartPoint>,
    reference: PetHistoryWeightReference,
    breedReference: PetHistoryBreedReference = PetHistoryBreedReference.Hidden,
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

@Composable
internal fun PetWeightReferenceChartCard(
    series: ChartSeries,
    reference: PetHistoryWeightReference,
    breedReference: PetHistoryBreedReference = PetHistoryBreedReference.Hidden,
    startDate: LocalDate,
    endDateInclusive: LocalDate,
    zoneId: ZoneId,
) {
    val factual = remember(series.points) {
        series.points.filter { it.xEpochMillis != null && it.value.isFinite() }
            .sortedBy(ChartPoint::measuredAtEpochSecond)
    }
    val yRange = remember(factual, reference, breedReference) { petWeightChartRange(factual, reference, breedReference) }
    val factualColor = MaterialTheme.colorScheme.primary
    val referenceColor = MaterialTheme.colorScheme.tertiary
    val available = reference as? PetHistoryWeightReference.Available
    val description = buildString {
        append("График веса питомца. ")
        append(if (factual.isEmpty()) "Измерений нет. " else "Измерений: ${factual.size}. ")
        append(available?.accessibilityLabel ?: (reference as PetHistoryWeightReference.Unavailable).explanation)
        if (available != null) append(" Фактический вес отмечен кругами; эталон — четырьмя линиями границ.")
        if (breedReference is PetHistoryBreedReference.Available) {
            append(" ${breedReference.accessibilityLabel} Породный диапазон отмечен вертикальным отрезком, одиночное значение — ромбом.")
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
            ReferenceExplanation(reference)
        }
    }
}

@Composable
private fun PetWeightVicoChart(
    factual: List<ChartPoint>,
    reference: PetHistoryWeightReference,
    breedReference: PetHistoryBreedReference,
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
    val breedDate = LocalDate.now().coerceIn(startDate, endDateInclusive)
    val breedSeries = remember(breedReference, breedDate) {
        breedWeightReferenceChartSeries(breedReference, breedDate)
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
            add(rememberBreedChartLine(MaterialTheme.colorScheme.secondary, series.drawsInterval))
        }
    }
    val modelProducer = remember { CartesianChartModelProducer() }
    val bottomFormatter = remember(zoneId) {
        CartesianValueFormatter { _, value, _ ->
            PetAxisDateFormatter.format(Instant.ofEpochMilli(value.toLong()).atZone(zoneId))
        }
    }
    val markerFormatter = remember(factual, reference, zoneId) {
        DefaultCartesianMarker.ValueFormatter { _, targets ->
            val target = targets.firstOrNull() as? LineCartesianLayerMarkerTarget
                ?: return@ValueFormatter ""
            formatPetWeightMarker(petWeightMarkerSelection(target.x.toLong(), factual, reference, zoneId))
        }
    }
    val zoomState = key(xRange.minX, xRange.maxX, zoneId) {
        rememberVicoZoomState(zoomEnabled = true, initialZoom = Zoom.Content)
    }
    val selectableDates = remember(factual, available, zoneId) {
        (factual.map { it.measuredAt.atZone(zoneId).toLocalDate() } +
            available?.segments.orEmpty().flatten().map(PetHistoryReferencePoint::date))
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
        )
    }

    LaunchedEffect(factual, referenceSeries, zoneId) {
        modelProducer.runTransaction {
            lineModel {
                if (factual.isNotEmpty()) {
                    series(
                        x = factual.map { requireNotNull(it.xEpochMillis) },
                        y = factual.map(ChartPoint::value),
                    )
                }
                referenceSeries.forEach { referenceChartSeries ->
                    val points = referenceChartSeries.points
                    series(
                        x = points.map { (date, _) -> date.atStartOfDay(zoneId).toInstant().toEpochMilli() },
                        y = points.map { it.second },
                    )
                }
                breedSeries.forEach { breedChartSeries ->
                    series(
                        x = breedChartSeries.points.map { (date, _) ->
                            date.atStartOfDay(zoneId).toInstant().toEpochMilli()
                        },
                        y = breedChartSeries.points.map { it.second },
                    )
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
                marker = rememberChartMarker(markerFormatter, lineCount = 5),
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
        if (breedReference is PetHistoryBreedReference.Available) BreedReferenceSemantics(breedReference, breedDate)
    }
}

@Composable
private fun rememberBreedChartLine(color: Color, drawsInterval: Boolean): LineCartesianLayer.Line {
    val diamond = remember {
        GenericShape { size, _ ->
            moveTo(size.width / 2f, 0f)
            lineTo(size.width, size.height / 2f)
            lineTo(size.width / 2f, size.height)
            lineTo(0f, size.height / 2f)
            close()
        }
    }
    return remember(color, drawsInterval, diamond) {
        LineCartesianLayer.Line(
            fill = LineCartesianLayer.LineFill.single(Fill(color)),
            stroke = LineCartesianLayer.LineStroke.Continuous(if (drawsInterval) 4.dp else 0.dp),
            areaFill = null,
            pointProvider = if (drawsInterval) null else {
                LineCartesianLayer.PointProvider.single(
                    LineCartesianLayer.Point(
                        component = ShapeComponent(fill = Fill(color), shape = diamond),
                        size = 14.dp,
                    ),
                )
            },
            interpolator = LineCartesianLayer.Interpolator.Sharp,
        )
    }
}

@Composable
private fun BreedReferenceSemantics(
    reference: PetHistoryBreedReference.Available,
    date: LocalDate,
) {
    Box(
        Modifier
            .fillMaxSize()
            .testTag(PetWeightChartTestTags.BreedLayer)
            .semantics(mergeDescendants = true) {
                contentDescription = reference.chartValues.joinToString(" ") { it.accessibilityLabel }
                stateDescription = "Породный ориентир показан на текущую дату $date"
            },
    )
}

@Composable
private fun BreedChartLegend(reference: PetHistoryBreedReference.Available) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (reference.chartValues.any { it is PetHistoryBreedChartValue.Interval }) {
            Text("│ Породный диапазон · ${reference.ageLabel}", style = MaterialTheme.typography.bodySmall)
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
