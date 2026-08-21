package com.example.huaweimisync.measurements

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.huaweimisync.charts.ChartPoint
import com.example.huaweimisync.charts.chartXRange
import com.example.huaweimisync.charts.chartYRange
import com.example.huaweimisync.charts.rememberChartBottomAxis
import com.example.huaweimisync.charts.rememberChartMarker
import com.example.huaweimisync.charts.rememberChartStartAxis
import com.example.huaweimisync.charts.rememberSmoothChartLine
import com.example.huaweimisync.charts.rememberSmoothLineLayer
import com.example.huaweimisync.ui.components.HuaweiSurface
import com.example.huaweimisync.ui.theme.HuaweiDimensions
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.Zoom
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.compose.cartesian.data.lineModel
import com.patrykandpatrick.vico.compose.cartesian.marker.DefaultCartesianMarker
import com.patrykandpatrick.vico.compose.cartesian.marker.LineCartesianLayerMarkerTarget
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import com.patrykandpatrick.vico.compose.common.data.ExtraStore
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

internal data class HomeKgChartMarkerEntry(
    val key: String,
    val label: String,
    val valueKg: Double,
    val decimalPlaces: Int,
    val colorArgb: Int,
)

internal data class HomeKgChartMarkerSelection(
    val measuredAtEpochSecond: Long,
    val entries: List<HomeKgChartMarkerEntry>,
)

/** Finds one real measurement nearest to Vico's target and keeps catalog/line order. */
internal fun homeKgChartMarkerSelection(
    state: HomeKgChartUiState,
    targetXEpochMillis: Long,
): HomeKgChartMarkerSelection? {
    val activeSeries = state.series.filter { it.key in state.activeSeriesKeys }
    val targetEpochSecond = Math.floorDiv(targetXEpochMillis, 1_000L)
    val selectedPoint = activeSeries
        .asSequence()
        .flatMap { it.points.asSequence() }
        .distinctBy(HomeKgChartPoint::measurementId)
        .minWithOrNull(
            compareBy<HomeKgChartPoint> { candidate ->
                abs(candidate.measuredAtEpochSecond - targetEpochSecond)
            }.thenBy(HomeKgChartPoint::measuredAtEpochSecond),
        ) ?: return null
    val entries = activeSeries.mapNotNull { series ->
        series.points.firstOrNull { point ->
            point.measurementId == selectedPoint.measurementId &&
                point.measuredAtEpochSecond == selectedPoint.measuredAtEpochSecond
        }?.let { point ->
            HomeKgChartMarkerEntry(
                key = series.key,
                label = series.label,
                valueKg = point.valueKg,
                decimalPlaces = series.decimalPlaces,
                colorArgb = series.color.argb,
            )
        }
    }
    return HomeKgChartMarkerSelection(
        measuredAtEpochSecond = selectedPoint.measuredAtEpochSecond,
        entries = entries,
    )
}

internal fun formatHomeKgChartMarker(
    selection: HomeKgChartMarkerSelection,
    zoneId: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): String = buildString {
    append(
        HomeMarkerDateTimeFormatter.format(
            Instant.ofEpochSecond(selection.measuredAtEpochSecond).atZone(zoneId),
        ),
    )
    selection.entries.forEach { entry ->
        val number = NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = entry.decimalPlaces
            maximumFractionDigits = entry.decimalPlaces
            isGroupingUsed = false
        }
        append('\n')
        append(entry.label)
        append(": ")
        append(number.format(entry.valueKg))
        append(" кг")
    }
}

@Composable
internal fun HomeKgChart(
    state: HomeKgChartUiState,
    onSeriesToggled: (String) -> Unit,
    modifier: Modifier = Modifier,
    zoneId: ZoneId = ZoneId.systemDefault(),
) {
    val hasPeriodData = state.series.any { it.points.isNotEmpty() }
    val plottedSeries = state.series.filter { series ->
        series.key in state.activeSeriesKeys && series.points.isNotEmpty()
    }

    HuaweiSurface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("home-kg-chart"),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Динамика состава тела", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Последние 30 дней · кг",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            when {
                !hasPeriodData -> HomeChartMessage(
                    text = "За последние 30 дней нет данных для графика.",
                    tag = "home-kg-chart-no-data",
                )

                state.activeSeriesKeys.isEmpty() -> HomeChartMessage(
                    text = "Выберите показатели в легенде, чтобы показать график.",
                    tag = "home-kg-chart-no-active",
                )

                plottedSeries.isEmpty() -> HomeChartMessage(
                    text = "Для выбранных показателей пока нет данных.",
                    tag = "home-kg-chart-selected-no-data",
                )

                else -> HomeKgVicoChart(state, plottedSeries, zoneId)
            }

            Text(
                "Показатели",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.series.forEach { series ->
                    HomeKgLegendItem(
                        series = series,
                        selected = series.key in state.activeSeriesKeys,
                        onClick = { onSeriesToggled(series.key) },
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeChartMessage(text: String, tag: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun HomeKgLegendItem(
    series: HomeKgChartSeries,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val color = Color(series.color.argb)
    Surface(
        modifier = Modifier
            .heightIn(min = HuaweiDimensions.TouchTarget)
            .toggleable(value = selected, role = Role.Checkbox, onValueChange = { onClick() })
            .semantics {
                this.selected = selected
                contentDescription = "${series.label}, цвет ${series.color.argb.toUInt().toString(16).uppercase()}"
            }
            .testTag("home-kg-legend-${series.key}"),
        shape = MaterialTheme.shapes.medium,
        color = if (selected) color.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, color),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Box(
                Modifier
                    .size(10.dp)
                    .background(color, CircleShape),
            )
            Text(series.label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun HomeKgVicoChart(
    state: HomeKgChartUiState,
    plottedSeries: List<HomeKgChartSeries>,
    zoneId: ZoneId,
) {
    val modelProducer = remember { CartesianChartModelProducer() }
    val xRange = remember(state.period, zoneId) {
        chartXRange(state.period.startDate, state.period.endDateInclusive, zoneId)
    }
    val points = remember(plottedSeries) {
        plottedSeries.flatMap { series ->
            series.points.map { ChartPoint(it.measuredAtEpochSecond, it.valueKg) }
        }
    }
    val yRange = remember(points) { chartYRange(points, decimalPlaces = 2) }
    val rangeProvider = remember(xRange, yRange) {
        object : CartesianLayerRangeProvider {
            override fun getMinX(minX: Double, maxX: Double, extraStore: ExtraStore) = xRange.minX
            override fun getMaxX(minX: Double, maxX: Double, extraStore: ExtraStore) = xRange.maxX
            override fun getMinY(minY: Double, maxY: Double, extraStore: ExtraStore) = yRange?.min ?: minY
            override fun getMaxY(minY: Double, maxY: Double, extraStore: ExtraStore) = yRange?.max ?: maxY
        }
    }
    val lines = plottedSeries.map { rememberSmoothChartLine(Color(it.color.argb)) }
    val bottomFormatter = remember(zoneId) {
        CartesianValueFormatter { _, value, _ ->
            HomeAxisDateFormatter.format(Instant.ofEpochMilli(value.toLong()).atZone(zoneId))
        }
    }
    val markerFormatter = remember(state, zoneId) {
        DefaultCartesianMarker.ValueFormatter { _, targets ->
            val target = targets.firstOrNull() as? LineCartesianLayerMarkerTarget
                ?: return@ValueFormatter ""
            homeKgChartMarkerSelection(state, target.x.toLong())
                ?.let { formatHomeKgChartMarker(it, zoneId) }
                .orEmpty()
        }
    }
    val zoomState = key(xRange.minX, xRange.maxX, zoneId) {
        rememberVicoZoomState(zoomEnabled = true, initialZoom = Zoom.Content)
    }

    LaunchedEffect(plottedSeries) {
        modelProducer.runTransaction {
            lineModel {
                plottedSeries.forEach { series ->
                    series(
                        x = series.points.map { Math.multiplyExact(it.measuredAtEpochSecond, 1_000L) },
                        y = series.points.map(HomeKgChartPoint::valueKg),
                    )
                }
            }
        }
    }
    CartesianChartHost(
        chart = rememberCartesianChart(
            rememberSmoothLineLayer(lines = lines, rangeProvider = rangeProvider),
            startAxis = rememberChartStartAxis(
                CartesianValueFormatter.decimal(decimalCount = 2, suffix = " кг"),
            ),
            bottomAxis = rememberChartBottomAxis(bottomFormatter),
            marker = rememberChartMarker(markerFormatter, lineCount = plottedSeries.size + 1),
        ),
        modelProducer = modelProducer,
        modifier = Modifier
            .fillMaxWidth()
            .height(230.dp)
            .semantics { contentDescription = "График динамики состава тела за последние 30 дней" }
            .testTag("home-kg-vico-chart"),
        scrollState = rememberVicoScrollState(scrollEnabled = true),
        zoomState = zoomState,
    )
}

private val HomeAxisDateFormatter = DateTimeFormatter.ofPattern("dd.MM")
private val HomeMarkerDateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
