package com.palixander.weightogether.charts

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
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.palixander.weightogether.R
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.palixander.weightogether.ui.components.ScaleSyncFilterButton
import com.palixander.weightogether.ui.currentAppLocale
import com.palixander.weightogether.ui.accounts.AccountSelector
import com.palixander.weightogether.ui.components.ScaleSyncIconButton
import com.palixander.weightogether.ui.components.ScaleSyncSurface
import com.palixander.weightogether.ui.icons.ScaleSyncIcons
import com.palixander.weightogether.ui.theme.ScaleSyncDimensions
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.Scroll
import com.patrykandpatrick.vico.compose.cartesian.Zoom
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.compose.cartesian.data.lineModel
import com.patrykandpatrick.vico.compose.cartesian.marker.CartesianMarkerVisibilityListener
import com.patrykandpatrick.vico.compose.cartesian.marker.DefaultCartesianMarker
import com.patrykandpatrick.vico.compose.cartesian.marker.LineCartesianLayerMarkerTarget
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import com.patrykandpatrick.vico.compose.common.data.ExtraStore
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val DateFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")
private val AxisDateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChartsScreen(
    state: ChartsUiState,
    callbacks: ChartsCallbacks,
    modifier: Modifier = Modifier,
    zoneId: ZoneId = ZoneId.systemDefault(),
    showAccountSelector: Boolean = true,
) {
    val loadingDescription = stringResource(R.string.chart_loading)
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

    Box(modifier.fillMaxSize()) {
        AnalyticalChartDialogs(state, callbacks.analytical)
        LazyColumn(
            modifier = Modifier
                .testTag("charts-list")
                .fillMaxSize()
                .padding(horizontal = ScaleSyncDimensions.ContentPadding),
            verticalArrangement = Arrangement.spacedBy(ScaleSyncDimensions.ItemSpacing),
        ) {
            item { Spacer(Modifier.height(1.dp)) }
            if (showAccountSelector) {
                item {
                    AccountSelector(
                        state = state.accountSelector,
                        onAccountSelected = callbacks.onAccountSelected,
                    )
                }
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
            callbacks.analytical?.let { analyticalCallbacks ->
                item {
                    Button(
                        onClick = { analyticalCallbacks.controller.showAddMenu(true) },
                        enabled = state.analytical.accountId != null,
                        modifier = Modifier.fillMaxWidth().testTag("analytical-add"),
                    ) { Text(stringResource(R.string.analytical_add)) }
                }
                items(state.analytical.settings, key = { "analytical-${state.analytical.accountId}-${it.type}" }) { settings ->
                    AnalyticalChartCard(
                        card = state.analyticalCards.firstOrNull { it.settings.type == settings.type }
                            ?: AnalyticalCardUiState(settings),
                        loading = state.isLoading,
                        error = state.analyticalError,
                        callbacks = analyticalCallbacks,
                        zoneId = state.zoneId,
                    )
                }
            }
            state.errorMessage?.let { message ->
                item {
                    ScaleSyncSurface(containerColor = MaterialTheme.colorScheme.errorContainer) {
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
                            modifier = Modifier.semantics { contentDescription = loadingDescription },
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
        AnalyticalUndoHost(state.analytical, callbacks.analytical, Modifier.align(Alignment.BottomCenter))
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
    val periodDescription = stringResource(R.string.chart_period_description, rangeText)
    val metricsDescription = stringResource(R.string.chart_metrics_description, selectedCount, metricCount)
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(ScaleSyncDimensions.CompactItemSpacing),
    ) {
        ScaleSyncFilterButton(
            text = rangeText,
            onClick = onOpenRangeFilter,
            icon = ScaleSyncIcons.Calendar,
            modifier = Modifier.semantics {
                contentDescription = periodDescription
            },
        )
        ScaleSyncFilterButton(
            text = "$selectedCount / $metricCount",
            onClick = onOpenMetricFilter,
            icon = ScaleSyncIcons.Tune,
            selected = selectedCount != 0,
            modifier = Modifier.semantics {
                contentDescription = metricsDescription
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
                .padding(horizontal = ScaleSyncDimensions.ContentPadding)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(ScaleSyncDimensions.CompactItemSpacing),
        ) {
            SheetHeader(title = stringResource(R.string.chart_period), onDismiss = onDismiss)
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
        horizontalArrangement = Arrangement.spacedBy(ScaleSyncDimensions.CompactItemSpacing),
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
        modifier = modifier.heightIn(min = ScaleSyncDimensions.TouchTarget),
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
                .padding(horizontal = ScaleSyncDimensions.ContentPadding),
        ) {
            SheetHeader(title = stringResource(R.string.chart_metrics), onDismiss = onDismiss)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(ScaleSyncDimensions.CompactItemSpacing),
            ) {
                TextButton(onClick = onSelectAll) { Text(stringResource(R.string.action_select_all)) }
                TextButton(onClick = onClearSelection) { Text(stringResource(R.string.action_clear)) }
                Text(
                    text = stringResource(R.string.chart_selected_count, selectedMetricKeys.size),
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
                    .heightIn(min = ScaleSyncDimensions.TouchTarget),
                shape = MaterialTheme.shapes.medium,
            ) {
                Text(stringResource(R.string.action_done))
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
            .heightIn(min = ScaleSyncDimensions.TouchTarget)
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
        ScaleSyncIconButton(
            icon = ScaleSyncIcons.Close,
            contentDescription = stringResource(R.string.action_close),
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
            ) { Text(stringResource(R.string.action_apply)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    ) {
        DateRangePicker(
            state = state,
            modifier = Modifier.fillMaxWidth().height(520.dp),
            title = { Text(stringResource(R.string.chart_date_range), Modifier.padding(16.dp)) },
            headline = null,
            showModeToggle = false,
        )
    }
}

@Composable
private fun ChartsEmptyState(onChooseMetrics: () -> Unit) {
    val noMetricsDescription = stringResource(R.string.chart_no_metrics)
    ScaleSyncSurface(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = noMetricsDescription },
        contentPadding = androidx.compose.foundation.layout.PaddingValues(24.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(ScaleSyncDimensions.CompactItemSpacing),
        ) {
            Text(
                text = stringResource(R.string.chart_no_metrics),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.chart_no_metrics_help),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            ScaleSyncFilterButton(
                text = stringResource(R.string.action_select_metrics),
                onClick = onChooseMetrics,
                icon = ScaleSyncIcons.Tune,
            )
        }
    }
}

@Composable
internal fun MetricChartCard(
    series: ChartSeries,
    startDate: LocalDate,
    endDateInclusive: LocalDate,
    zoneId: ZoneId,
) {
    val locale = currentAppLocale()
    val metric = series.metric.resolveStrings()
    val points = remember(series.points) { orderedChartPoints(series.points) }
    val selectedRange = remember(startDate, endDateInclusive, zoneId) { chartXRange(startDate, endDateInclusive, zoneId) }
    val selectedPoints = remember(points, selectedRange) {
        points.filter { it.xEpochMillis?.toDouble()?.let { x -> x >= selectedRange.minX && x < selectedRange.maxX } == true }
    }
    val summary = remember(selectedPoints) { chartValueSummary(selectedPoints) }
    val currentValue = remember(summary.current, metric, locale) {
        formatChartCurrentValue(summary.current?.value, metric, locale)
    }
    val delta = remember(summary.delta, metric, locale) {
        formatChartDelta(summary.delta, metric, locale)
    }
    val statistics = remember(selectedPoints) { chartStatistics(selectedPoints) }
    val minimum = remember(statistics?.minimum, metric, locale) {
        formatChartStatistic(statistics?.minimum, metric, locale)
    }
    val maximum = remember(statistics?.maximum, metric, locale) {
        formatChartStatistic(statistics?.maximum, metric, locale)
    }
    val average = remember(statistics?.average, metric, locale) {
        formatChartStatistic(statistics?.average, metric, locale)
    }
    val chartDescription = stringResource(
        R.string.chart_accessibility_summary,
        metric.displayName,
        currentValue,
        delta,
    )
    ScaleSyncSurface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(MetricChartTestTags.Card),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(ScaleSyncDimensions.CompactItemSpacing)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = metric.displayName,
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
                        text = stringResource(R.string.chart_previous_delta, delta),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.End,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ChartStatistic(
                    label = stringResource(R.string.chart_minimum),
                    value = minimum,
                    modifier = Modifier.weight(1f),
                )
                ChartStatistic(
                    label = stringResource(R.string.chart_maximum),
                    value = maximum,
                    modifier = Modifier.weight(1f),
                )
                ChartStatistic(
                    label = stringResource(R.string.chart_average),
                    value = average,
                    modifier = Modifier.weight(1f),
                )
            }
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    when {
                        points.isEmpty() -> Text(
                            text = stringResource(R.string.chart_no_data),
                            modifier = Modifier.padding(vertical = 28.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                        )

                        !isChartRenderable(points) -> Text(
                            text = stringResource(R.string.chart_insufficient_data),
                            modifier = Modifier.testTag(MetricChartTestTags.InsufficientInterval),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                        )

                        else -> MetricLineChart(
                            metric = metric,
                            points = points,
                            startDate = startDate,
                            endDateInclusive = endDateInclusive,
                            zoneId = zoneId,
                            contentDescription = chartDescription,
                            modifier = Modifier.testTag(MetricChartTestTags.ChartHost),
                        )
                    }
            }
        }
    }
}

@Composable
private fun ChartMetricOption.resolveStrings(): ChartMetricOption = copy(
    displayName = displayNameRes?.let { stringResource(it) } ?: displayName,
    unit = unitRes?.let { stringResource(it) } ?: unit,
    deltaUnit = deltaUnitRes?.let { stringResource(it) } ?: deltaUnit,
)

object MetricChartTestTags {
    const val Card = "metric-chart-card"
    const val ChartHost = "metric-chart-host"
    const val InsufficientInterval = "metric-chart-insufficient-interval"
}

@Composable
private fun ChartStatistic(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    val description = stringResource(R.string.chart_stat_description, label, value)
    Column(
        modifier = modifier.semantics { contentDescription = description },
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
internal fun MetricLineChart(
    metric: ChartMetricOption,
    points: List<ChartPoint>,
    startDate: LocalDate,
    endDateInclusive: LocalDate,
    zoneId: ZoneId,
    contentDescription: String,
    modifier: Modifier = Modifier,
    markerVisibilityListener: CartesianMarkerVisibilityListener? = null,
) {
    val locale = currentAppLocale()
    val chartPoints = remember(points) {
        points.mapNotNull { point -> point.xEpochMillis?.let { x -> x to point } }
    }
    val modelProducer = remember { CartesianChartModelProducer() }
    val viewport = remember(chartPoints, startDate, endDateInclusive, zoneId) {
        chartViewport(chartPoints.map { it.first }, startDate, endDateInclusive, zoneId)
    }
    val yRange = remember(points, metric.decimalPlaces) { chartYRange(points, metric.decimalPlaces) }
    val rangeProvider = remember(viewport, yRange) {
        object : CartesianLayerRangeProvider {
            override fun getMinX(minX: Double, maxX: Double, extraStore: ExtraStore) =
                viewport.modelRange.minX

            override fun getMaxX(minX: Double, maxX: Double, extraStore: ExtraStore) =
                viewport.modelRange.maxX

            override fun getMinY(minY: Double, maxY: Double, extraStore: ExtraStore) =
                yRange?.min ?: minY

            override fun getMaxY(minY: Double, maxY: Double, extraStore: ExtraStore) =
                yRange?.max ?: maxY
        }
    }
    val primaryColor = MaterialTheme.colorScheme.primary
    val line = rememberChartLine(primaryColor, points.size)
    val bottomFormatter = remember(zoneId) {
        CartesianValueFormatter { _, value, _ ->
            AxisDateTimeFormatter.format(Instant.ofEpochMilli(value.toLong()).atZone(zoneId))
        }
    }
    val markerValueFormatter = remember(metric, zoneId, points, locale) {
        DefaultCartesianMarker.ValueFormatter { _, targets ->
            val target = targets.firstOrNull() as? LineCartesianLayerMarkerTarget
                ?: return@ValueFormatter ""
            val value = target.points.firstOrNull()?.entry?.y ?: return@ValueFormatter ""
            chartPoints.firstOrNull { (x, point) ->
                x == target.x.toLong() && point.value == value
            }?.let { (_, point) ->
                formatChartMarkerText(point = point, metric = metric, zoneId = zoneId, locale = locale)
            } ?: formatChartMarkerText(
                measuredAtEpochSecond = Math.floorDiv(target.x.toLong(), 1_000L),
                value = value,
                metric = metric,
                zoneId = zoneId,
                locale = locale,
            )
        }
    }
    val zoomState = key(viewport.initialVisibleRange, zoneId) {
        rememberVicoZoomState(
            zoomEnabled = true,
            initialZoom = Zoom.x(viewport.initialVisibleWidth()),
        )
    }
    val scrollState = key(viewport.initialVisibleRange, zoneId) {
        rememberVicoScrollState(scrollEnabled = true, initialScroll = Scroll.Absolute.x(viewport.initialVisibleRange.minX))
    }

    LaunchedEffect(points) {
        modelProducer.runTransaction {
            lineModel {
                series(
                    x = chartPoints.map { it.first },
                    y = chartPoints.map { it.second.value },
                )
            }
        }
    }
    CartesianChartHost(
        chart = rememberCartesianChart(
            rememberChartLineLayer(
                lines = listOf(line),
                rangeProvider = rangeProvider,
            ),
            startAxis = rememberChartStartAxis(
                valueFormatter = CartesianValueFormatter.decimal(
                    decimalCount = metric.decimalPlaces,
                    suffix = metric.unit.takeIf(String::isNotBlank)?.let { " $it" }.orEmpty(),
                ),
            ),
            bottomAxis = rememberChartBottomAxis(bottomFormatter, zoneId),
            marker = rememberChartMarker(markerValueFormatter),
            markerVisibilityListener = markerVisibilityListener,
        ),
        modelProducer = modelProducer,
        modifier = modifier
            .fillMaxWidth()
            .height(250.dp)
            .semantics {
                this.contentDescription = contentDescription
                chartScrollOffset = scrollState.value
            },
        scrollState = scrollState,
        zoomState = zoomState,
    )
}

@Composable
private fun rangeLabel(
    preset: ChartRangePreset,
    startDate: LocalDate,
    endDateInclusive: LocalDate,
): String = when (preset) {
    ChartRangePreset.ALL -> stringResource(R.string.chart_range_all)
    ChartRangePreset.LAST_7_DAYS -> stringResource(R.string.chart_range_7_days)
    ChartRangePreset.LAST_30_DAYS -> stringResource(R.string.chart_range_30_days)
    ChartRangePreset.LAST_3_MONTHS -> stringResource(R.string.chart_range_3_months)
    ChartRangePreset.YEAR_TO_DATE -> stringResource(R.string.chart_range_year_to_date)
    ChartRangePreset.CUSTOM ->
        "${DateFormatter.format(startDate)} — ${DateFormatter.format(endDateInclusive)}"
}

@Composable
private fun ChartRangePreset.title(): String = when (this) {
    ChartRangePreset.ALL -> stringResource(R.string.chart_range_all)
    ChartRangePreset.LAST_7_DAYS -> stringResource(R.string.chart_range_7_days)
    ChartRangePreset.LAST_30_DAYS -> stringResource(R.string.chart_range_30_days)
    ChartRangePreset.LAST_3_MONTHS -> stringResource(R.string.chart_range_3_months)
    ChartRangePreset.YEAR_TO_DATE -> stringResource(R.string.chart_range_year_to_date)
    ChartRangePreset.CUSTOM -> stringResource(R.string.chart_range_custom)
}

private fun ChartMetricOption.labelWithUnit(): String =
    if (unit.isBlank()) displayName else "$displayName, $unit"
