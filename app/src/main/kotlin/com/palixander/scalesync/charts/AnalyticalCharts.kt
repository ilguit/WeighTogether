package com.palixander.scalesync.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.palixander.scalesync.R
import com.palixander.scalesync.measurements.HomeKgChart
import com.palixander.scalesync.measurements.HomeKgChartSeriesCatalog
import com.palixander.scalesync.measurements.formatMeasurementDateTime
import com.palixander.scalesync.measurements.formatWeight
import com.palixander.scalesync.ui.components.ScaleSyncSurface
import com.palixander.scalesync.ui.currentAppLocale
import com.palixander.scalesync.ui.text.resolve
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.hypot

internal fun analyticalTitle(type: AnalyticalChartType): Int = when (type) {
    AnalyticalChartType.MORNING -> R.string.analytical_morning
    AnalyticalChartType.DAILY_MINIMUM -> R.string.analytical_minimum
    AnalyticalChartType.HOURLY -> R.string.analytical_hourly
}

internal fun minuteText(minute: Int): String = String.format(Locale.ROOT, "%02d:%02d", minute / 60, minute % 60)
internal fun parseMinute(text: String): Int {
    if (!text.matches(Regex("[0-9]{1,2}:[0-9]{2}"))) return -1
    val parts = text.split(':').map(String::toInt)
    return if (parts[0] in 0..23 && parts[1] in 0..59 || parts[0] == 24 && parts[1] == 0) parts[0] * 60 + parts[1] else -1
}

@Composable
internal fun AnalyticalUndoHost(state: AnalyticalChartState, callbacks: AnalyticalChartCallbacks?, modifier: Modifier) {
    val host = remember { SnackbarHostState() }
    val removed = stringResource(R.string.analytical_removed)
    val undo = stringResource(R.string.action_undo)
    LaunchedEffect(state.accountId, state.removed) {
        host.currentSnackbarData?.dismiss()
        if (state.removed != null && callbacks != null) {
            if (host.showSnackbar(removed, undo, withDismissAction = true) == SnackbarResult.ActionPerformed) {
                callbacks.controller.undoRemove()
            } else callbacks.controller.dismissUndo()
        }
    }
    SnackbarHost(host, modifier.testTag("analytical-undo"))
}

@Composable
internal fun AnalyticalChartDialogs(state: ChartsUiState, callbacks: AnalyticalChartCallbacks?) {
    if (callbacks == null) return
    val owner = LocalLifecycleOwner.current
    val resume by rememberUpdatedState(callbacks.onResume)
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) resume() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    if (state.analytical.showAddMenu) {
        AnalyticalDialog(stringResource(R.string.analytical_add), { callbacks.controller.showAddMenu(false) }) {
            AnalyticalChartType.entries.forEach { type ->
                TextButton(
                    onClick = { callbacks.controller.edit(type) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("analytical-add-$type"),
                ) {
                    Text(stringResource(analyticalTitle(type)) + if (state.analytical.settings.any { it.type == type })
                        " · " + stringResource(R.string.analytical_added) else "")
                }
            }
        }
    }
    state.analytical.draft?.let { draft ->
        key(state.analytical.accountId, draft.settings.type) { AnalyticalEditor(draft, callbacks, state.isLoading, state.analyticalError) }
    }
}

@Composable
internal fun AnalyticalChartCard(card: AnalyticalCardUiState, loading: Boolean, error: Boolean,
    callbacks: AnalyticalChartCallbacks, zoneId: ZoneId,
) {
    val type = card.settings.type
    var help by rememberSaveable { mutableStateOf(false) }
    var excluded by rememberSaveable { mutableStateOf(false) }
    var menuExpanded by rememberSaveable { mutableStateOf(false) }
    val title = stringResource(analyticalTitle(type))
    val locale = currentAppLocale()
    val subtitle = when (type) {
        AnalyticalChartType.MORNING -> stringResource(R.string.analytical_window,
            minuteText(card.settings.morningWindow.startMinute), minuteText(card.settings.morningWindow.endMinute))
        AnalyticalChartType.DAILY_MINIMUM -> stringResource(R.string.analytical_minimum_hint)
        AnalyticalChartType.HOURLY -> stringResource(R.string.analytical_all_time, card.sourceCount)
    }
    val menuDescription = stringResource(R.string.analytical_menu)
    ScaleSyncSurface(Modifier.fillMaxWidth().testTag("analytical-card-$type").pointerInput(type) {
        detectTapGestures(onLongPress = { menuExpanded = true })
    }.semantics { onLongClick(menuDescription) { menuExpanded = true; true } }) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).semantics { heading() })
                Box {
                    IconButton({ menuExpanded = true }, Modifier.size(48.dp).testTag("analytical-menu-$type")) {
                        Text("⋮", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { contentDescription = menuDescription })
                    }
                    DropdownMenu(menuExpanded, { menuExpanded = false }) {
                        DropdownMenuItem({ Text(stringResource(R.string.analytical_excluded, card.excluded.size)) }, onClick = { menuExpanded = false; excluded = true }, modifier = Modifier.testTag("analytical-excluded-$type"))
                        DropdownMenuItem({ Text(stringResource(R.string.analytical_help)) }, onClick = { menuExpanded = false; help = true }, modifier = Modifier.testTag("analytical-help-$type"))
                        DropdownMenuItem({ Text(stringResource(R.string.analytical_configure)) }, onClick = { menuExpanded = false; callbacks.controller.edit(type) }, modifier = Modifier.testTag("analytical-edit-$type"))
                        DropdownMenuItem({ Text(stringResource(R.string.analytical_remove)) }, onClick = { menuExpanded = false; callbacks.controller.remove(type) }, modifier = Modifier.testTag("analytical-remove-$type"))
                    }
                }
            }
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
            when {
                loading -> CircularProgressIndicator(Modifier.testTag("analytical-loading-$type"))
                error -> {
                    Text(stringResource(R.string.analytical_error))
                    TextButton(callbacks.retry, Modifier.testTag("analytical-retry-$type")) { Text(stringResource(R.string.action_retry)) }
                }
                type == AnalyticalChartType.HOURLY -> HourlyChart(card.hourlyCounts)
                else -> {
                    val chart = card.chart
                    when {
                        card.sourceCount == 0 -> Text(stringResource(R.string.analytical_no_source))
                        card.retainedCount == 0 -> Text(stringResource(R.string.analytical_no_filtered))
                        chart != null -> {
                            val points = chart.series.filter { it.key in chart.activeSeriesKeys }.flatMap { it.points }
                            if (points.isNotEmpty() && points.none {
                                it.measuredAtEpochSecond >= chart.period.startInclusiveEpochSecond && it.measuredAtEpochSecond < chart.period.endExclusiveEpochSecond
                            }) Text(stringResource(R.string.analytical_no_period))
                            HomeKgChart(chart, { callbacks.controller.toggleSavedSeries(type, it) }, zoneId = zoneId,
                                title = title, subtitle = subtitle, embedded = true)
                            analyticalSingleMeasurement(chart)?.let { measurement ->
                                // A single real measurement remains readable without inventing a second point.
                                Text(formatMeasurementDateTime(Instant.ofEpochSecond(measurement.measuredAtEpochSecond), zoneId, locale))
                                val resources = LocalContext.current.resources
                                chart.series.filter { it.key in chart.activeSeriesKeys }.forEach { series ->
                                    Text("${series.label.resolve(resources)}: ${measurement.valuesKg[series.key]?.let { formatWeight(it, locale) } ?: "—"} ${stringResource(R.string.unit_kg)}")
                                }
                            }
                        }
                    }
                    if (card.settings.activeSeriesKeys.isEmpty()) {
                        TextButton({ callbacks.controller.edit(type) }) { Text(stringResource(R.string.analytical_choose_series)) }
                    }
                    if (!card.sufficientHistory && card.sourceCount > 0) Text(stringResource(R.string.analytical_small_history), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    if (help) AnalyticalDialog(stringResource(R.string.analytical_help), { help = false }) {
        Text(stringResource(if (type == AnalyticalChartType.HOURLY) R.string.analytical_hourly_hint else R.string.analytical_rule))
    }
    if (excluded) Dialog(onDismissRequest = { excluded = false }) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(16.dp).heightIn(max = 600.dp)) {
                Text(stringResource(R.string.analytical_excluded, card.excluded.size), style = MaterialTheme.typography.titleMedium)
                LazyColumn(Modifier.weight(1f, fill = false)) {
                    if (card.excluded.isEmpty()) item { Text(stringResource(R.string.analytical_none_excluded)) }
                    items(card.excluded, key = { it.id }) { row ->
                        Text("${formatMeasurementDateTime(Instant.ofEpochSecond(row.measuredAtEpochSecond), zoneId, locale)} — ${formatWeight(row.weightKg, locale)} ${stringResource(R.string.unit_kg)}", Modifier.padding(vertical = 8.dp))
                    }
                }
                TextButton({ excluded = false }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_close)) }
            }
        }
    }
}

/** A 24-sector polar histogram; selection exposes the exact value without a permanent long list. */
@Composable
internal fun HourlyChart(hourlyCounts: List<Int>) {
    val counts = List(24) { hourlyCounts.getOrElse(it) { 0 } }
    val total = counts.sum()
    var selected by rememberSaveable { mutableStateOf<Int?>(null) }
    val lengths = remember(counts) { normalizedHourlyLengths(counts) }
    val locale = currentAppLocale()
    if (total == 0) Text(stringResource(R.string.analytical_no_source)) else {
        val fill = MaterialTheme.colorScheme.primary
        val grid = MaterialTheme.colorScheme.outlineVariant
        val selectedColor = MaterialTheme.colorScheme.onSurface
        val semanticsText = counts.mapIndexed { hour, count -> "${minuteText(hour * 60)}: $count" }.joinToString(", ")
        Box(Modifier.fillMaxWidth().height(220.dp)) {
            Canvas(Modifier.fillMaxSize().testTag("analytical-radial").semantics {
                contentDescription = semanticsText
                customActions = counts.mapIndexed { hour, count ->
                    CustomAccessibilityAction("${minuteText(hour * 60)}: $count") {
                        selected = hour
                        true
                    }
                }
            }.pointerInput(counts) {
                detectTapGestures { point ->
                    val centerX = size.width / 2f
                    val centerY = size.height / 2f
                    val radius = minOf(size.width, size.height) / 2f
                    if (hypot(point.x - centerX, point.y - centerY) <= radius) {
                        hourForRadialPoint(point.x, point.y, centerX, centerY, radius * 0.12f)?.let { selected = it }
                    }
                }
            }) {
                val center = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
                val radius = minOf(size.width, size.height) * 0.42f
                drawCircle(grid, radius, center, style = Stroke(1.dp.toPx()))
                drawCircle(grid, radius / 2f, center, style = Stroke(1.dp.toPx()))
                counts.forEachIndexed { hour, count ->
                    if (count > 0) {
                        val sector = hourlySectorAngles(hour)
                        val sectorRadius = radius * lengths[hour]
                        drawArc(
                            color = if (selected == hour) selectedColor else fill,
                            startAngle = sector.startDegrees,
                            sweepAngle = sector.sweepDegrees,
                            useCenter = true,
                            topLeft = Offset(center.x - sectorRadius, center.y - sectorRadius),
                            size = Size(sectorRadius * 2f, sectorRadius * 2f),
                        )
                    }
                }
            }
            Text("00", style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.TopCenter))
            Text("06", style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.CenterEnd))
            Text("12", style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.BottomCenter))
            Text("18", style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.CenterStart))
        }
    }
    selected?.let { hour ->
        val count = counts[hour]
        val percent = String.format(locale, "%.1f", if (total == 0) 0.0 else count * 100.0 / total)
        val label = stringResource(R.string.analytical_hour_row, minuteText(hour * 60), minuteText((hour + 1) * 60), count, percent)
        Text(label, Modifier.fillMaxWidth().testTag("analytical-hour-selection").semantics { liveRegion = LiveRegionMode.Polite })
    }
}

@Composable
private fun AnalyticalEditor(draft: AnalyticalChartDraft, callbacks: AnalyticalChartCallbacks, loading: Boolean, error: Boolean) {
    Dialog(onDismissRequest = callbacks.controller::cancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        AnalyticalEditorContent(draft, callbacks, loading, error)
    }
}

@Composable
internal fun AnalyticalEditorContent(draft: AnalyticalChartDraft, callbacks: AnalyticalChartCallbacks, loading: Boolean, error: Boolean) {
    val controller = callbacks.controller
    var start by remember { mutableStateOf(minuteText(draft.startMinute)) }
    var end by remember { mutableStateOf(minuteText(draft.endMinute)) }
    LaunchedEffect(draft.preview) {
        if (draft.preview != null) { start = minuteText(draft.startMinute); end = minuteText(draft.endMinute) }
    }
    Surface(Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.9f), shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(16.dp).testTag("analytical-editor")) {
            Text(stringResource(analyticalTitle(draft.settings.type)), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).testTag("analytical-editor-scroll"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (draft.settings.type == AnalyticalChartType.MORNING) {
                    Text(stringResource(if (draft.settings.morningMode == MorningFilterMode.AUTOMATIC) R.string.analytical_mode_auto else R.string.analytical_mode_manual))
                    val startLabel = stringResource(R.string.analytical_start)
                    val endLabel = stringResource(R.string.analytical_end)
                    Text(startLabel, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("analytical-start-label"))
                    OutlinedTextField(start, { start = it; controller.setWindow(parseMinute(start), parseMinute(end)) },
                        singleLine = true, isError = !draft.valid,
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = startLabel }.testTag("analytical-start"))
                    Text(endLabel, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("analytical-end-label"))
                    OutlinedTextField(end, { end = it; controller.setWindow(parseMinute(start), parseMinute(end)) },
                        singleLine = true, isError = !draft.valid,
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = endLabel }.testTag("analytical-end"))
                    if (!draft.valid) Text(stringResource(R.string.analytical_invalid_time), color = MaterialTheme.colorScheme.error)
                    TextButton(callbacks.autoSelectMorning, enabled = !loading && !error && draft.status != MorningCalculationStatus.RUNNING,
                        modifier = Modifier.fillMaxWidth().testTag("analytical-auto")) { Text(stringResource(R.string.analytical_auto)) }
                    if (loading) CircularProgressIndicator(Modifier.testTag("analytical-source-loading"))
                    if (error) {
                        Text(stringResource(R.string.analytical_error))
                        TextButton(callbacks.retry) { Text(stringResource(R.string.action_retry)) }
                    }
                    when (draft.status) {
                        MorningCalculationStatus.RUNNING -> CircularProgressIndicator(Modifier.testTag("analytical-auto-running"))
                        MorningCalculationStatus.INSUFFICIENT_DATA -> Text(stringResource(R.string.analytical_insufficient), Modifier.testTag("analytical-auto-insufficient"))
                        MorningCalculationStatus.ERROR -> Text(stringResource(R.string.analytical_error))
                        else -> Unit
                    }
                    draft.preview?.let { preview ->
                        Text(stringResource(R.string.analytical_preview, minuteText(preview.window.startMinute), minuteText(preview.window.endMinute), preview.baseCount,
                            preview.retained.size, preview.excludedByTimeCount, preview.excludedByFilter.size),
                            Modifier.testTag("analytical-preview").semantics { liveRegion = LiveRegionMode.Polite })
                    }
                }
                if (draft.settings.type == AnalyticalChartType.HOURLY) Text(stringResource(R.string.analytical_hourly_hint)) else {
                    val resources = LocalContext.current.resources
                    HomeKgChartSeriesCatalog.forEach { series ->
                        val checked = series.key in draft.settings.activeSeriesKeys
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(checked, role = Role.Checkbox, onValueChange = {
                            controller.setSeries(if (it) draft.settings.activeSeriesKeys + series.key else draft.settings.activeSeriesKeys - series.key)
                        }).testTag("analytical-series-${series.key}"), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked, onCheckedChange = null)
                            Text(series.label.resolve(resources), Modifier.weight(1f))
                        }
                    }
                }
            }
            Button(controller::save, enabled = draft.valid && draft.status != MorningCalculationStatus.RUNNING,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("analytical-save")) { Text(stringResource(R.string.action_save)) }
            TextButton(controller::cancel, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("analytical-cancel")) { Text(stringResource(R.string.action_cancel)) }
        }
    }
}

@Composable
private fun AnalyticalDialog(title: String, onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onClose) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(16.dp).heightIn(max = 600.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), content = content)
                TextButton(onClose, Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_close)) }
            }
        }
    }
}
