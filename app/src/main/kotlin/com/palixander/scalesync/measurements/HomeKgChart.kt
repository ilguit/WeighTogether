package com.palixander.scalesync.measurements

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.palixander.scalesync.charts.ChartPoint
import com.palixander.scalesync.charts.chartViewport
import com.palixander.scalesync.charts.chartScrollOffset
import com.palixander.scalesync.charts.initialVisibleWidth
import com.palixander.scalesync.charts.chartYRange
import com.palixander.scalesync.charts.rememberChartBottomAxis
import com.palixander.scalesync.charts.rememberChartMarker
import com.palixander.scalesync.charts.rememberChartStartAxis
import com.palixander.scalesync.charts.rememberChartLine
import com.palixander.scalesync.charts.rememberChartLineLayer
import com.palixander.scalesync.ui.icons.HuaweiIcons
import com.palixander.scalesync.ui.components.HuaweiSurface
import com.palixander.scalesync.ui.currentAppLocale
import com.palixander.scalesync.ui.theme.HuaweiDimensions
import com.palixander.scalesync.ui.text.UiText
import com.palixander.scalesync.ui.text.resolve
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.Scroll
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
    val label: UiText,
    val unit: UiText,
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
                unit = requireNotNull(series.unit),
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
    resolveText: (UiText) -> String,
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
        append(resolveText(entry.label))
        append(": ")
        append(if (entry.key == HomeKgChartMetric.WEIGHT.key) formatWeight(entry.valueKg, locale) else number.format(entry.valueKg))
        append(' ')
        append(resolveText(entry.unit))
    }
}

@Composable
internal fun HomeKgChart(
    state: HomeKgChartUiState,
    onSeriesToggled: (String) -> Unit,
    modifier: Modifier = Modifier,
    zoneId: ZoneId = ZoneId.systemDefault(),
    title: String = stringResource(com.palixander.scalesync.R.string.chart_body_composition_title),
    subtitle: String = stringResource(com.palixander.scalesync.R.string.chart_last_14_days_kg),
    embedded: Boolean = false,
) {
    val locale = currentAppLocale()
    val hasHistoryData = state.series.any { it.points.isNotEmpty() }
    val plottedSeries = state.series.filter { series ->
        series.key in state.activeSeriesKeys && series.points.isNotEmpty()
    }

    val content: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!embedded) Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    subtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            when {
                !hasHistoryData -> HomeChartMessage(
                    text = stringResource(com.palixander.scalesync.R.string.chart_no_data),
                    tag = "home-kg-chart-no-data",
                )

                state.activeSeriesKeys.isEmpty() -> HomeChartMessage(
                    text = stringResource(com.palixander.scalesync.R.string.chart_select_metrics_hint),
                    tag = "home-kg-chart-no-active",
                )

                plottedSeries.isEmpty() -> HomeChartMessage(
                    text = stringResource(com.palixander.scalesync.R.string.chart_selected_no_data),
                    tag = "home-kg-chart-selected-no-data",
                )

                else -> HomeKgVicoChart(state, plottedSeries, zoneId)
            }

            var expanded by rememberSaveable { mutableStateOf(false) }
            val expansionStateDescription = stringResource(
                if (expanded) com.palixander.scalesync.R.string.state_expanded
                else com.palixander.scalesync.R.string.state_collapsed,
            )
            TextButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.fillMaxWidth()
                    .heightIn(min = HuaweiDimensions.TouchTarget)
                    .semantics { stateDescription = expansionStateDescription }
                    .testTag("home-kg-series-toggle"),
            ) {
                Text(
                    stringResource(
                        com.palixander.scalesync.R.string.chart_metrics_count,
                        state.series.count { it.key in state.activeSeriesKeys },
                        state.series.size,
                    ),
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    HuaweiIcons.ChevronDown,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp).rotate(if (expanded) 180f else 0f),
                )
            }
            if (expanded) {
                Column {
                    state.series.forEach { series ->
                        HomeKgSeriesItem(
                            series = series,
                            selected = series.key in state.activeSeriesKeys,
                            onClick = { onSeriesToggled(series.key) },
                        )
                    }
                }
            }
        }
    }
    if (embedded) {
        Box(modifier.fillMaxWidth().testTag("home-kg-chart")) { content() }
    } else {
        HuaweiSurface(modifier = modifier.fillMaxWidth().testTag("home-kg-chart"), content = content)
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
private fun HomeKgSeriesItem(
    series: HomeKgChartSeries,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .heightIn(min = HuaweiDimensions.TouchTarget)
            .toggleable(value = selected, role = Role.Checkbox, onValueChange = { onClick() })
            .testTag("home-kg-legend-${series.key}")
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Checkbox(checked = selected, onCheckedChange = null)
        Box(Modifier.size(10.dp).background(Color(series.color.argb), CircleShape))
        Text(series.label.resolve(LocalContext.current.resources), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun HomeKgVicoChart(
    state: HomeKgChartUiState,
    plottedSeries: List<HomeKgChartSeries>,
    zoneId: ZoneId,
) {
    val locale = currentAppLocale()
    val chartContentDescription = stringResource(
        com.palixander.scalesync.R.string.chart_home_content_description,
    )
    val resources = LocalContext.current.resources
    val modelProducer = remember { CartesianChartModelProducer() }
    val points = remember(plottedSeries) {
        plottedSeries.flatMap { series ->
            series.points.map { ChartPoint(it.measuredAtEpochSecond, it.valueKg) }
        }
    }
    val viewport = remember(points, state.period, zoneId) {
        chartViewport(points.mapNotNull(ChartPoint::xEpochMillis), state.period.startDate, state.period.endDateInclusive, zoneId)
    }
    val yRange = remember(points) { chartYRange(points, decimalPlaces = 2) }
    val rangeProvider = remember(viewport, yRange) {
        object : CartesianLayerRangeProvider {
            override fun getMinX(minX: Double, maxX: Double, extraStore: ExtraStore) = viewport.modelRange.minX
            override fun getMaxX(minX: Double, maxX: Double, extraStore: ExtraStore) = viewport.modelRange.maxX
            override fun getMinY(minY: Double, maxY: Double, extraStore: ExtraStore) = yRange?.min ?: minY
            override fun getMaxY(minY: Double, maxY: Double, extraStore: ExtraStore) = yRange?.max ?: maxY
        }
    }
    val lines = plottedSeries.map { series ->
        rememberChartLine(
            color = Color(series.color.argb),
            pointCount = series.points.size,
        )
    }
    val bottomFormatter = remember(zoneId) {
        CartesianValueFormatter { _, value, _ ->
            HomeAxisDateFormatter.format(Instant.ofEpochMilli(value.toLong()).atZone(zoneId))
        }
    }
    val markerFormatter = remember(state, zoneId, locale) {
        DefaultCartesianMarker.ValueFormatter { _, targets ->
            val target = targets.firstOrNull() as? LineCartesianLayerMarkerTarget
                ?: return@ValueFormatter ""
            homeKgChartMarkerSelection(state, target.x.toLong())
                ?.let { formatHomeKgChartMarker(it, zoneId, locale, resolveText = { text -> text.resolve(resources) }) }
                .orEmpty()
        }
    }
    val zoomState = key(viewport.initialVisibleRange, zoneId) {
        rememberVicoZoomState(zoomEnabled = true, initialZoom = Zoom.x(viewport.initialVisibleWidth()))
    }
    val scrollState = key(viewport.initialVisibleRange, zoneId) {
        rememberVicoScrollState(scrollEnabled = true, initialScroll = Scroll.Absolute.x(viewport.initialVisibleRange.minX))
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
            rememberChartLineLayer(lines = lines, rangeProvider = rangeProvider),
            startAxis = rememberChartStartAxis(
                CartesianValueFormatter.decimal(
                    decimalCount = 2,
                    suffix = " ${com.palixander.scalesync.ui.text.uiText(com.palixander.scalesync.R.string.unit_kg).resolve(resources)}",
                ),
            ),
            bottomAxis = rememberChartBottomAxis(bottomFormatter, zoneId),
            marker = rememberChartMarker(markerFormatter, lineCount = plottedSeries.size + 1),
        ),
        modelProducer = modelProducer,
        modifier = Modifier
            .fillMaxWidth()
            .height(230.dp)
            .semantics {
                contentDescription = chartContentDescription
                chartScrollOffset = scrollState.value
            }
            .testTag("home-kg-vico-chart"),
        scrollState = scrollState,
        zoomState = zoomState,
    )
}

private val HomeAxisDateFormatter = DateTimeFormatter.ofPattern("dd.MM")
private val HomeMarkerDateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
