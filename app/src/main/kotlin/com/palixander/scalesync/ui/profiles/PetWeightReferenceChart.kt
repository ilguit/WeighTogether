package com.palixander.scalesync.ui.profiles

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.palixander.scalesync.charts.ChartPoint
import com.palixander.scalesync.charts.ChartSeries
import com.palixander.scalesync.ui.components.HuaweiSurface
import com.palixander.scalesync.ui.theme.HuaweiDimensions
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

internal object PetWeightChartTestTags {
    const val Chart = "pet-weight-reference-chart"
    const val ReferenceDetails = "pet-weight-reference-details"
    const val Unavailable = "pet-weight-reference-unavailable"
}

internal data class PetWeightChartRange(val min: Double, val max: Double)

internal fun petWeightChartRange(
    factual: List<ChartPoint>,
    reference: PetHistoryWeightReference,
): PetWeightChartRange? {
    val values = buildList {
        addAll(factual.map(ChartPoint::value).filter(Double::isFinite))
        if (reference is PetHistoryWeightReference.Available) {
            reference.segments.flatten().forEach { point ->
                add(point.lowerKg)
                add(point.upperKg)
            }
        }
    }
    if (values.isEmpty()) return null
    val min = values.min()
    val max = values.max()
    val padding = ((max - min) * 0.08).coerceAtLeast(0.1)
    return PetWeightChartRange((min - padding).coerceAtLeast(0.0), max + padding)
}

@Composable
internal fun PetWeightReferenceChartCard(
    series: ChartSeries,
    reference: PetHistoryWeightReference,
    startDate: LocalDate,
    endDateInclusive: LocalDate,
    zoneId: ZoneId,
) {
    val factual = remember(series.points) { series.points.sortedBy(ChartPoint::measuredAtEpochSecond) }
    val yRange = remember(factual, reference) { petWeightChartRange(factual, reference) }
    val factualColor = MaterialTheme.colorScheme.primary
    val referenceColor = MaterialTheme.colorScheme.tertiary
    val outlineColor = MaterialTheme.colorScheme.outline
    val available = reference as? PetHistoryWeightReference.Available
    val description = buildString {
        append("График веса питомца. ")
        append(if (factual.isEmpty()) "Измерений нет. " else "Измерений: ${factual.size}. ")
        append(available?.accessibilityLabel ?: (reference as PetHistoryWeightReference.Unavailable).explanation)
        if (available != null) append(" Фактический вес отмечен кругами; эталон — линиями и диапазоном.")
    }

    HuaweiSurface(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(HuaweiDimensions.CompactItemSpacing)) {
            Text(
                "Вес питомца",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            if (yRange != null) {
                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .height(250.dp)
                        .testTag(PetWeightChartTestTags.Chart)
                        .semantics { contentDescription = description },
                ) {
                    val left = 8.dp.toPx()
                    val right = size.width - 8.dp.toPx()
                    val top = 8.dp.toPx()
                    val bottom = size.height - 8.dp.toPx()
                    val dayCount = (endDateInclusive.toEpochDay() - startDate.toEpochDay()).coerceAtLeast(1)
                    fun x(date: LocalDate) = left + (date.toEpochDay() - startDate.toEpochDay()).toFloat() / dayCount * (right - left)
                    fun y(value: Double) = bottom - ((value - yRange.min) / (yRange.max - yRange.min)).toFloat() * (bottom - top)
                    drawLine(outlineColor.copy(alpha = .35f), Offset(left, bottom), Offset(right, bottom), 1.dp.toPx())
                    available?.segments?.forEach { segment ->
                        if (segment.isNotEmpty()) {
                            val band = Path().apply {
                                moveTo(x(segment.first().date), y(segment.first().upperKg))
                                segment.drop(1).forEach { lineTo(x(it.date), y(it.upperKg)) }
                                segment.asReversed().forEach { lineTo(x(it.date), y(it.lowerKg)) }
                                close()
                            }
                            drawPath(band, referenceColor.copy(alpha = .16f))
                            fun lineOf(value: (PetHistoryReferencePoint) -> Double, width: Float, alpha: Float) {
                                val path = Path()
                                segment.forEachIndexed { index, point ->
                                    val offset = Offset(x(point.date), y(value(point)))
                                    if (index == 0) path.moveTo(offset.x, offset.y) else path.lineTo(offset.x, offset.y)
                                }
                                drawPath(path, referenceColor.copy(alpha = alpha), style = Stroke(width))
                            }
                            lineOf(PetHistoryReferencePoint::lowerKg, 1.dp.toPx(), .7f)
                            lineOf(PetHistoryReferencePoint::upperKg, 1.dp.toPx(), .7f)
                            lineOf(PetHistoryReferencePoint::medianLowerKg, 2.dp.toPx(), 1f)
                            lineOf(PetHistoryReferencePoint::medianUpperKg, 2.dp.toPx(), 1f)
                            if (segment.size == 1) {
                                val point = segment.single()
                                drawCircle(referenceColor, 3.dp.toPx(), Offset(x(point.date), y(point.medianLowerKg)))
                                drawCircle(referenceColor, 3.dp.toPx(), Offset(x(point.date), y(point.medianUpperKg)))
                            }
                        }
                    }
                    val factualOffsets = factual.map { point ->
                        val date = Instant.ofEpochSecond(point.measuredAtEpochSecond).atZone(zoneId).toLocalDate()
                        Offset(x(date), y(point.value))
                    }
                    factualOffsets.zipWithNext().forEach { (a, b) -> drawLine(factualColor, a, b, 2.dp.toPx()) }
                    factualOffsets.forEach { point ->
                        drawCircle(Color.White, 5.dp.toPx(), point)
                        drawCircle(factualColor, 4.dp.toPx(), point)
                    }
                }
                if (available != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        ChartLegend("● Фактический вес", factualColor)
                        ChartLegend("▰ Эталонный диапазон", referenceColor)
                    }
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
                .semantics(mergeDescendants = true) { contentDescription = reference.accessibilityLabel },
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Как читать эталон", style = MaterialTheme.typography.titleSmall)
            Text("${reference.basisLabel} · ${reference.ageLabel}")
            Text("Светлая область показывает общий диапазон, две линии внутри — медианный диапазон.")
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
