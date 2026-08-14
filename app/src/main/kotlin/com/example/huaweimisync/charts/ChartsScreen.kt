package com.example.huaweimisync.charts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.compose.cartesian.data.lineModel
import com.patrykandpatrick.vico.compose.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.marker.DefaultCartesianMarker
import com.patrykandpatrick.vico.compose.cartesian.marker.LineCartesianLayerMarkerTarget
import com.patrykandpatrick.vico.compose.cartesian.marker.rememberDefaultCartesianMarker
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.common.Insets
import com.patrykandpatrick.vico.compose.common.MarkerCornerBasedShape
import com.patrykandpatrick.vico.compose.common.component.ShapeComponent
import com.patrykandpatrick.vico.compose.common.component.TextComponent
import com.patrykandpatrick.vico.compose.common.component.rememberShapeComponent
import com.patrykandpatrick.vico.compose.common.component.rememberTextComponent
import com.patrykandpatrick.vico.compose.common.data.ExtraStore
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DateFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")
private val AxisDateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM HH:mm")
private val MarkerDateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChartsScreen(
    state: ChartsUiState,
    onDateRangeChange: (LocalDate, LocalDate) -> Unit,
    onMetricSelectionChange: (String, Boolean) -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    modifier: Modifier = Modifier,
    zoneId: ZoneId = ZoneId.systemDefault(),
) {
    var showDatePicker by remember { mutableStateOf(false) }
    var showMetricPicker by remember { mutableStateOf(false) }

    if (showDatePicker) {
        InclusiveDateRangeDialog(
            startDate = state.startDate,
            endDateInclusive = state.endDateInclusive,
            onConfirm = { startDate, endDate ->
                showDatePicker = false
                onDateRangeChange(startDate, endDate)
            },
            onDismiss = { showDatePicker = false },
        )
    }
    if (showMetricPicker) {
        MetricSelectionDialog(
            options = state.metricOptions,
            selectedMetricKeys = state.selectedMetricKeys,
            onMetricSelectionChange = onMetricSelectionChange,
            onSelectAll = onSelectAll,
            onClearSelection = onClearSelection,
            onDismiss = { showMetricPicker = false },
        )
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Spacer(Modifier.height(1.dp)) }
        item {
            FiltersCard(
                startDate = state.startDate,
                endDateInclusive = state.endDateInclusive,
                selectedCount = state.selectedMetricKeys.size,
                metricCount = state.metricOptions.size,
                onOpenDatePicker = { showDatePicker = true },
                onOpenMetricPicker = { showMetricPicker = true },
            )
        }
        state.errorMessage?.let { message ->
            item { Text(message, color = MaterialTheme.colorScheme.error) }
        }
        when {
            state.isLoading -> item {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            state.selectedMetricKeys.isEmpty() -> item {
                MessageCard("Выберите хотя бы один показатель")
            }

            else -> {
                val seriesByKey = state.series.associateBy { it.metric.key }
                items(state.selectedMetrics, key = ChartMetricOption::key) { metric ->
                    MetricChartCard(
                        series = seriesByKey[metric.key] ?: ChartSeries(metric, emptyList()),
                        zoneId = zoneId,
                    )
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun FiltersCard(
    startDate: LocalDate,
    endDateInclusive: LocalDate,
    selectedCount: Int,
    metricCount: Int,
    onOpenDatePicker: () -> Unit,
    onOpenMetricPicker: () -> Unit,
) = Card(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Фильтры", style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = onOpenDatePicker, modifier = Modifier.fillMaxWidth()) {
            Text("${DateFormatter.format(startDate)} — ${DateFormatter.format(endDateInclusive)}")
        }
        OutlinedButton(onClick = onOpenMetricPicker, modifier = Modifier.fillMaxWidth()) {
            Text("Показатели: $selectedCount из $metricCount")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InclusiveDateRangeDialog(
    startDate: LocalDate,
    endDateInclusive: LocalDate,
    onConfirm: (LocalDate, LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    val state = rememberDateRangePickerState(
        initialSelectedStartDateMillis = startDate.toDatePickerUtcMillis(),
        initialSelectedEndDateMillis = endDateInclusive.toDatePickerUtcMillis(),
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = state.selectedStartDateMillis != null && state.selectedEndDateMillis != null,
                onClick = {
                    val startMillis = state.selectedStartDateMillis ?: return@TextButton
                    val endMillis = state.selectedEndDateMillis ?: return@TextButton
                    onConfirm(
                        datePickerUtcMillisToLocalDate(startMillis),
                        datePickerUtcMillisToLocalDate(endMillis),
                    )
                },
            ) { Text("Применить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    ) {
        DateRangePicker(
            state = state,
            modifier = Modifier.fillMaxWidth().height(520.dp),
            title = { Text("Диапазон дат", Modifier.padding(16.dp)) },
            headline = null,
            showModeToggle = false,
        )
    }
}

@Composable
private fun MetricSelectionDialog(
    options: List<ChartMetricOption>,
    selectedMetricKeys: Set<String>,
    onMetricSelectionChange: (String, Boolean) -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Показатели") },
        text = {
            LazyColumn(Modifier.fillMaxWidth().height(420.dp)) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onSelectAll) { Text("Выбрать все") }
                        TextButton(onClick = onClearSelection) { Text("Очистить") }
                    }
                }
                items(options, key = ChartMetricOption::key) { metric ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = metric.key in selectedMetricKeys,
                            onCheckedChange = { onMetricSelectionChange(metric.key, it) },
                        )
                        Text(metric.labelWithUnit(), Modifier.weight(1f))
                    }
                }
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text("Готово") } },
    )
}

@Composable
private fun MessageCard(message: String) = Card(Modifier.fillMaxWidth()) {
    Text(message, Modifier.padding(20.dp), style = MaterialTheme.typography.bodyLarge)
}

@Composable
private fun MetricChartCard(series: ChartSeries, zoneId: ZoneId) = Card(Modifier.fillMaxWidth()) {
    val points = remember(series.points) { orderedChartPoints(series.points) }
    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(series.metric.labelWithUnit(), style = MaterialTheme.typography.titleMedium)
        when {
            points.isEmpty() -> Text("Нет данных за выбранный период")
            points.size == 1 -> {
                Text("Одно измерение", style = MaterialTheme.typography.labelMedium)
                MetricLineChart(series.metric, points, zoneId)
            }
            else -> MetricLineChart(series.metric, points, zoneId)
        }
    }
}

@Composable
private fun MetricLineChart(
    metric: ChartMetricOption,
    points: List<ChartPoint>,
    zoneId: ZoneId,
) {
    val modelProducer = remember { CartesianChartModelProducer() }
    val yRange = remember(points, metric.decimalPlaces) { chartYRange(points, metric.decimalPlaces) }
    val rangeProvider = remember(yRange) {
        object : CartesianLayerRangeProvider {
            override fun getMinY(minY: Double, maxY: Double, extraStore: ExtraStore) =
                yRange?.min ?: minY

            override fun getMaxY(minY: Double, maxY: Double, extraStore: ExtraStore) =
                yRange?.max ?: maxY
        }
    }
    val primaryColor = MaterialTheme.colorScheme.primary
    val pointComponent = rememberShapeComponent(Fill(primaryColor), CircleShape)
    val line = LineCartesianLayer.rememberLine(
        fill = LineCartesianLayer.LineFill.single(Fill(primaryColor)),
        areaFill = null,
        pointProvider = LineCartesianLayer.PointProvider.single(LineCartesianLayer.Point(pointComponent)),
    )
    val bottomFormatter = remember(zoneId) {
        CartesianValueFormatter { _, value, _ ->
            AxisDateTimeFormatter.format(Instant.ofEpochMilli(value.toLong()).atZone(zoneId))
        }
    }
    val markerValueFormatter = remember(metric, zoneId) { markerValueFormatter(metric, zoneId) }

    LaunchedEffect(points) {
        modelProducer.runTransaction {
            lineModel {
                series(
                    x = points.map(ChartPoint::measuredAtEpochMillis),
                    y = points.map(ChartPoint::value),
                )
            }
        }
    }
    CartesianChartHost(
        chart = rememberCartesianChart(
            rememberLineCartesianLayer(
                lineProvider = LineCartesianLayer.LineProvider.series(listOf(line)),
                rangeProvider = rangeProvider,
            ),
            startAxis = VerticalAxis.rememberStart(
                valueFormatter = CartesianValueFormatter.decimal(
                    decimalCount = metric.decimalPlaces,
                    suffix = metric.unit.takeIf(String::isNotBlank)?.let { " $it" }.orEmpty(),
                ),
            ),
            bottomAxis = HorizontalAxis.rememberBottom(
                guideline = null,
                labelRotationDegrees = 35f,
                valueFormatter = bottomFormatter,
            ),
            marker = rememberChartMarker(markerValueFormatter),
        ),
        modelProducer = modelProducer,
        modifier = Modifier.fillMaxWidth().height(260.dp),
        scrollState = rememberVicoScrollState(scrollEnabled = true),
        zoomState = rememberVicoZoomState(zoomEnabled = true),
    )
}

private fun markerValueFormatter(
    metric: ChartMetricOption,
    zoneId: ZoneId,
): DefaultCartesianMarker.ValueFormatter =
    DefaultCartesianMarker.ValueFormatter { _, targets ->
        val target = targets.firstOrNull() as? LineCartesianLayerMarkerTarget
            ?: return@ValueFormatter ""
        val value = target.points.firstOrNull()?.entry?.y ?: return@ValueFormatter ""
        val dateTime = MarkerDateTimeFormatter.format(
            Instant.ofEpochMilli(target.x.toLong()).atZone(zoneId),
        )
        buildString {
            append(dateTime)
            append('\n')
            append(decimalFormat(metric.decimalPlaces).format(value))
            if (metric.unit.isNotBlank()) append(" ${metric.unit}")
        }
    }

@Composable
private fun rememberChartMarker(
    valueFormatter: DefaultCartesianMarker.ValueFormatter,
): DefaultCartesianMarker {
    val background = rememberShapeComponent(
        fill = Fill(MaterialTheme.colorScheme.surface),
        shape = MarkerCornerBasedShape(RoundedCornerShape(8.dp)),
        strokeFill = Fill(MaterialTheme.colorScheme.outline),
        strokeThickness = 1.dp,
    )
    val label = rememberTextComponent(
        style = TextStyle(
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        ),
        padding = Insets(8.dp, 5.dp),
        background = background,
        minWidth = TextComponent.MinWidth.fixed(64.dp),
    )
    return rememberDefaultCartesianMarker(
        label = label,
        valueFormatter = valueFormatter,
        indicator = { color -> ShapeComponent(Fill(color), CircleShape) },
        indicatorSize = 14.dp,
    )
}

private fun ChartMetricOption.labelWithUnit(): String =
    if (unit.isBlank()) displayName else "$displayName, $unit"

private fun decimalFormat(decimalPlaces: Int): DecimalFormat {
    val pattern = buildString {
        append('0')
        if (decimalPlaces > 0) {
            append('.')
            repeat(decimalPlaces) { append('0') }
        }
    }
    return DecimalFormat(pattern, DecimalFormatSymbols(Locale.getDefault()))
}
