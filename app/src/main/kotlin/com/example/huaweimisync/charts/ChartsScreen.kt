package com.example.huaweimisync.charts

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.huaweimisync.ui.components.HuaweiFilterButton
import com.example.huaweimisync.ui.accounts.AccountSelector
import com.example.huaweimisync.ui.components.HuaweiIconButton
import com.example.huaweimisync.ui.components.HuaweiSurface
import com.example.huaweimisync.ui.icons.HuaweiIcons
import com.example.huaweimisync.ui.theme.HuaweiDimensions
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.Zoom
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.compose.cartesian.data.lineModel
import com.patrykandpatrick.vico.compose.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val DateFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")
private val AxisDateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM HH:mm")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChartsScreen(
    state: ChartsUiState,
    callbacks: ChartsCallbacks,
    modifier: Modifier = Modifier,
    zoneId: ZoneId = ZoneId.systemDefault(),
) {
    if (state.isCustomDatePickerOpen) {
        InclusiveDateRangeDialog(
            startDate = state.startDate,
            endDateInclusive = state.endDateInclusive,
            onConfirm = callbacks.setDateRange,
            onDismiss = callbacks.dismissCustomDatePicker,
        )
    }

    when (state.activeFilterSheet) {
        ChartFilterSheet.RANGE -> RangeFilterSheet(
            selectedPreset = state.rangePreset,
            onPresetSelected = callbacks.selectRangePreset,
            onDismiss = callbacks.dismissFilterSheet,
        )

        ChartFilterSheet.METRICS -> MetricSelectionSheet(
            options = state.metricOptions,
            selectedMetricKeys = state.selectedMetricKeys,
            onMetricSelectionChange = callbacks.setMetricSelected,
            onSelectAll = callbacks.selectAll,
            onClearSelection = callbacks.clearSelection,
            onDone = callbacks.doneSelectingMetrics,
            onDismiss = callbacks.dismissFilterSheet,
        )

        null -> Unit
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = HuaweiDimensions.ContentPadding),
        verticalArrangement = Arrangement.spacedBy(HuaweiDimensions.ItemSpacing),
    ) {
        item { Spacer(Modifier.height(1.dp)) }
        item {
            AccountSelector(
                state = state.accountSelector,
                onAccountSelected = callbacks.onAccountSelected,
            )
        }
        item {
            ChartFilterRow(
                rangeText = rangeLabel(state.rangePreset, state.startDate, state.endDateInclusive),
                selectedCount = state.selectedMetricKeys.size,
                metricCount = state.metricOptions.size,
                onOpenRangeFilter = callbacks.openRangeFilter,
                onOpenMetricFilter = callbacks.openMetricFilter,
            )
        }
        state.errorMessage?.let { message ->
            item {
                HuaweiSurface(containerColor = MaterialTheme.colorScheme.errorContainer) {
                    Text(
                        text = message,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        when {
            state.isLoading -> item {
                Box(
                    Modifier.fillMaxWidth().padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.semantics { contentDescription = "Загрузка графиков" },
                    )
                }
            }

            state.selectedMetricKeys.isEmpty() -> item {
                ChartsEmptyState(onChooseMetrics = callbacks.openMetricFilter)
            }

            else -> {
                val seriesByKey = state.series.associateBy { it.metric.key }
                items(state.selectedMetrics, key = ChartMetricOption::key) { metric ->
                    MetricChartCard(
                        series = seriesByKey[metric.key] ?: ChartSeries(metric, emptyList()),
                        startDate = state.startDate,
                        endDateInclusive = state.endDateInclusive,
                        zoneId = zoneId,
                    )
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun ChartFilterRow(
    rangeText: String,
    selectedCount: Int,
    metricCount: Int,
    onOpenRangeFilter: () -> Unit,
    onOpenMetricFilter: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(HuaweiDimensions.CompactItemSpacing),
    ) {
        HuaweiFilterButton(
            text = rangeText,
            onClick = onOpenRangeFilter,
            icon = HuaweiIcons.Calendar,
            modifier = Modifier.semantics {
                contentDescription = "Период: $rangeText"
            },
        )
        HuaweiFilterButton(
            text = "$selectedCount из $metricCount",
            onClick = onOpenMetricFilter,
            icon = HuaweiIcons.Tune,
            selected = selectedCount != 0,
            modifier = Modifier.semantics {
                contentDescription = "Показатели: $selectedCount из $metricCount"
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RangeFilterSheet(
    selectedPreset: ChartRangePreset,
    onPresetSelected: (ChartRangePreset) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = HuaweiDimensions.ContentPadding)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(HuaweiDimensions.CompactItemSpacing),
        ) {
            SheetHeader(title = "Период", onDismiss = onDismiss)
            RangePresetRow(
                first = ChartRangePreset.LAST_7_DAYS,
                second = ChartRangePreset.LAST_30_DAYS,
                selectedPreset = selectedPreset,
                onPresetSelected = onPresetSelected,
            )
            RangePresetRow(
                first = ChartRangePreset.LAST_3_MONTHS,
                second = ChartRangePreset.YEAR_TO_DATE,
                selectedPreset = selectedPreset,
                onPresetSelected = onPresetSelected,
            )
            PresetChoice(
                preset = ChartRangePreset.CUSTOM,
                selected = selectedPreset == ChartRangePreset.CUSTOM,
                onClick = { onPresetSelected(ChartRangePreset.CUSTOM) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun RangePresetRow(
    first: ChartRangePreset,
    second: ChartRangePreset,
    selectedPreset: ChartRangePreset,
    onPresetSelected: (ChartRangePreset) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(HuaweiDimensions.CompactItemSpacing),
    ) {
        PresetChoice(
            preset = first,
            selected = selectedPreset == first,
            onClick = { onPresetSelected(first) },
            modifier = Modifier.weight(1f),
        )
        PresetChoice(
            preset = second,
            selected = selectedPreset == second,
            onClick = { onPresetSelected(second) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun PresetChoice(
    preset: ChartRangePreset,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.heightIn(min = HuaweiDimensions.TouchTarget),
        shape = MaterialTheme.shapes.medium,
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        border = BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = null)
            Text(
                text = preset.title(),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MetricSelectionSheet(
    options: List<ChartMetricOption>,
    selectedMetricKeys: Set<String>,
    onMetricSelectionChange: (String, Boolean) -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onDone: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .padding(horizontal = HuaweiDimensions.ContentPadding),
        ) {
            SheetHeader(title = "Показатели", onDismiss = onDismiss)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(HuaweiDimensions.CompactItemSpacing),
            ) {
                TextButton(onClick = onSelectAll) { Text("Выбрать все") }
                TextButton(onClick = onClearSelection) { Text("Очистить") }
                Text(
                    text = "Выбрано: ${selectedMetricKeys.size}",
                    modifier = Modifier.align(Alignment.CenterVertically),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) {
                items(options, key = ChartMetricOption::key) { metric ->
                    MetricChoiceRow(
                        metric = metric,
                        selected = metric.key in selectedMetricKeys,
                        onSelectionChange = { selected ->
                            onMetricSelectionChange(metric.key, selected)
                        },
                    )
                }
            }
            Button(
                onClick = onDone,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = HuaweiDimensions.TouchTarget),
                shape = MaterialTheme.shapes.medium,
            ) {
                Text("Готово")
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun MetricChoiceRow(
    metric: ChartMetricOption,
    selected: Boolean,
    onSelectionChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = HuaweiDimensions.TouchTarget)
            .toggleable(
                value = selected,
                role = Role.Checkbox,
                onValueChange = onSelectionChange,
            )
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = selected, onCheckedChange = null)
        Text(
            text = metric.labelWithUnit(),
            modifier = Modifier.weight(1f).padding(start = 8.dp),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun SheetHeader(title: String, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f).semantics { heading() },
            style = MaterialTheme.typography.titleLarge,
        )
        HuaweiIconButton(
            icon = HuaweiIcons.Close,
            contentDescription = "Закрыть",
            onClick = onDismiss,
        )
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
private fun ChartsEmptyState(onChooseMetrics: () -> Unit) {
    HuaweiSurface(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Показатели не выбраны" },
        contentPadding = androidx.compose.foundation.layout.PaddingValues(24.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(HuaweiDimensions.CompactItemSpacing),
        ) {
            Text(
                text = "Показатели не выбраны",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            Text(
                text = "Выберите один или несколько показателей, чтобы построить графики.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            HuaweiFilterButton(
                text = "Выбрать показатели",
                onClick = onChooseMetrics,
                icon = HuaweiIcons.Tune,
            )
        }
    }
}

@Composable
private fun MetricChartCard(
    series: ChartSeries,
    startDate: LocalDate,
    endDateInclusive: LocalDate,
    zoneId: ZoneId,
) {
    val points = remember(series.points) { orderedChartPoints(series.points) }
    val summary = remember(points) { chartValueSummary(points) }
    val currentValue = remember(summary.current, series.metric) {
        formatChartCurrentValue(summary.current?.value, series.metric)
    }
    val delta = remember(summary.delta, series.metric) {
        formatChartDelta(summary.delta, series.metric)
    }
    HuaweiSurface(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(HuaweiDimensions.CompactItemSpacing)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = series.metric.displayName,
                    modifier = Modifier.weight(1f).semantics { heading() },
                    style = MaterialTheme.typography.titleMedium,
                )
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = currentValue,
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.End,
                    )
                    Text(
                        text = "К предыдущему: $delta",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.End,
                    )
                }
            }
            when {
                points.isEmpty() -> Text(
                    text = "Нет данных за выбранный период",
                    modifier = Modifier.padding(vertical = 28.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )

                else -> {
                    if (points.size == 1) {
                        Text(
                            text = "Одно измерение",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    MetricLineChart(
                        metric = series.metric,
                        points = points,
                        startDate = startDate,
                        endDateInclusive = endDateInclusive,
                        zoneId = zoneId,
                        contentDescription = buildString {
                            append("График: ${series.metric.displayName}. ")
                            append("Последнее значение: $currentValue. ")
                            append("Изменение к предыдущему: $delta")
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun MetricLineChart(
    metric: ChartMetricOption,
    points: List<ChartPoint>,
    startDate: LocalDate,
    endDateInclusive: LocalDate,
    zoneId: ZoneId,
    contentDescription: String,
) {
    val modelProducer = remember { CartesianChartModelProducer() }
    val xRange = remember(startDate, endDateInclusive, zoneId) {
        chartXRange(startDate, endDateInclusive, zoneId)
    }
    val yRange = remember(points, metric.decimalPlaces) { chartYRange(points, metric.decimalPlaces) }
    val rangeProvider = remember(xRange, yRange) {
        object : CartesianLayerRangeProvider {
            override fun getMinX(minX: Double, maxX: Double, extraStore: ExtraStore) =
                xRange.minX

            override fun getMaxX(minX: Double, maxX: Double, extraStore: ExtraStore) =
                xRange.maxX

            override fun getMinY(minY: Double, maxY: Double, extraStore: ExtraStore) =
                yRange?.min ?: minY

            override fun getMaxY(minY: Double, maxY: Double, extraStore: ExtraStore) =
                yRange?.max ?: maxY
        }
    }
    val primaryColor = MaterialTheme.colorScheme.primary
    val pointComponent = rememberShapeComponent(
        fill = Fill(MaterialTheme.colorScheme.surface),
        shape = CircleShape,
        strokeFill = Fill(primaryColor),
        strokeThickness = 2.dp,
    )
    val line = LineCartesianLayer.rememberLine(
        fill = LineCartesianLayer.LineFill.single(Fill(primaryColor)),
        areaFill = LineCartesianLayer.AreaFill.single(
            Fill(primaryColor.copy(alpha = ChartAreaAlpha)),
        ),
        pointProvider = LineCartesianLayer.PointProvider.single(
            LineCartesianLayer.Point(pointComponent),
        ),
    )
    val bottomFormatter = remember(zoneId) {
        CartesianValueFormatter { _, value, _ ->
            AxisDateTimeFormatter.format(Instant.ofEpochMilli(value.toLong()).atZone(zoneId))
        }
    }
    val markerValueFormatter = remember(metric, zoneId) {
        DefaultCartesianMarker.ValueFormatter { _, targets ->
            val target = targets.firstOrNull() as? LineCartesianLayerMarkerTarget
                ?: return@ValueFormatter ""
            val value = target.points.firstOrNull()?.entry?.y ?: return@ValueFormatter ""
            formatChartMarkerText(
                measuredAtEpochMillis = target.x.toLong(),
                value = value,
                metric = metric,
                zoneId = zoneId,
            )
        }
    }
    val zoomState = key(xRange.minX, xRange.maxX, zoneId) {
        rememberVicoZoomState(
            zoomEnabled = true,
            initialZoom = Zoom.Content,
        )
    }

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
        modifier = Modifier
            .fillMaxWidth()
            .height(250.dp)
            .semantics { this.contentDescription = contentDescription },
        scrollState = rememberVicoScrollState(scrollEnabled = true),
        zoomState = zoomState,
    )
}

@Composable
private fun rememberChartMarker(
    valueFormatter: DefaultCartesianMarker.ValueFormatter,
): DefaultCartesianMarker {
    val background = rememberShapeComponent(
        fill = Fill(MaterialTheme.colorScheme.inverseSurface),
        shape = MarkerCornerBasedShape(RoundedCornerShape(12.dp)),
    )
    val label = rememberTextComponent(
        style = TextStyle(
            color = MaterialTheme.colorScheme.inverseOnSurface,
            textAlign = TextAlign.Center,
        ),
        lineCount = 2,
        padding = Insets(10.dp, 7.dp),
        background = background,
        minWidth = TextComponent.MinWidth.text("00.00.0000 00:00"),
    )
    return rememberDefaultCartesianMarker(
        label = label,
        valueFormatter = valueFormatter,
        indicator = { color ->
            ShapeComponent(
                fill = Fill(Color.White),
                shape = CircleShape,
                strokeFill = Fill(color),
                strokeThickness = 2.dp,
            )
        },
        indicatorSize = 14.dp,
    )
}

private fun rangeLabel(
    preset: ChartRangePreset,
    startDate: LocalDate,
    endDateInclusive: LocalDate,
): String = when (preset) {
    ChartRangePreset.LAST_7_DAYS -> "7 дней"
    ChartRangePreset.LAST_30_DAYS -> "30 дней"
    ChartRangePreset.LAST_3_MONTHS -> "3 месяца"
    ChartRangePreset.YEAR_TO_DATE -> "С начала года"
    ChartRangePreset.CUSTOM ->
        "${DateFormatter.format(startDate)} — ${DateFormatter.format(endDateInclusive)}"
}

private fun ChartRangePreset.title(): String = when (this) {
    ChartRangePreset.LAST_7_DAYS -> "7 дней"
    ChartRangePreset.LAST_30_DAYS -> "30 дней"
    ChartRangePreset.LAST_3_MONTHS -> "3 месяца"
    ChartRangePreset.YEAR_TO_DATE -> "С начала года"
    ChartRangePreset.CUSTOM -> "Свои даты"
}

private fun ChartMetricOption.labelWithUnit(): String =
    if (unit.isBlank()) displayName else "$displayName, $unit"

private const val ChartAreaAlpha = 0.20f
