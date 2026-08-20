package com.example.huaweimisync.measurements

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.testTag
import com.example.huaweimisync.ui.components.HuaweiIconButton
import com.example.huaweimisync.ui.components.HuaweiRowIcon
import com.example.huaweimisync.ui.components.HuaweiSectionTitle
import com.example.huaweimisync.ui.components.HuaweiStatusAction
import com.example.huaweimisync.ui.components.HuaweiStatusTone
import com.example.huaweimisync.ui.components.HuaweiSurface
import com.example.huaweimisync.ui.icons.HuaweiIcons
import com.example.huaweimisync.ui.accounts.AccountSelector
import com.example.huaweimisync.ui.theme.HuaweiColors
import com.example.huaweimisync.ui.theme.HuaweiDimensions
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val EditorWarning =
    "Изменение сохранится в истории. Доступные направления синхронизации будут поставлены в очередь."
private const val MissingMeasurementValue = "—"

private val historyAdditionalFields = MeasurementField.entries.filterNot { field ->
    field in setOf(
        MeasurementField.WEIGHT_KG,
        MeasurementField.BODY_FAT_PERCENT,
        MeasurementField.MUSCLE_MASS_KG,
        MeasurementField.BMI,
    )
}

@Composable
fun MeasurementsScreen(
    state: MeasurementsUiState,
    callbacks: MeasurementsCallbacks,
    modifier: Modifier = Modifier,
) {
    var summaryMetricsExpanded by rememberSaveable { mutableStateOf(false) }
    var expandedHistoryIds by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var syncMeasurementId by rememberSaveable { mutableStateOf<String?>(null) }
    val syncItem = state.measurements.firstOrNull { it.id == syncMeasurementId }

    Column(modifier = modifier.fillMaxSize()) {
        if (state.destination != MeasurementsDestination.EDITOR) {
            AccountSelector(
                state = state.accountSelector,
                onAccountSelected = callbacks.onAccountSelected,
                modifier = Modifier.padding(
                    horizontal = HuaweiDimensions.ContentPadding,
                    vertical = HuaweiDimensions.CompactContentPadding,
                ),
            )
        }
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            when (state.destination) {
                MeasurementsDestination.SUMMARY -> MeasurementSummaryScreen(
                    state = state,
                    metricsExpanded = summaryMetricsExpanded,
                    onMetricsExpandedChange = { summaryMetricsExpanded = it },
                    onSyncRequested = { syncMeasurementId = it.id },
                    callbacks = callbacks,
                )

                MeasurementsDestination.PENDING_QUEUE -> PendingQueueDestination(
                    onBack = callbacks.onBackRequested,
                )

                MeasurementsDestination.HISTORY -> MeasurementHistoryScreen(
                    state = state,
                    expandedIds = expandedHistoryIds,
                    onExpandedChange = { id, expanded ->
                        expandedHistoryIds = if (expanded) {
                            (expandedHistoryIds + id).distinct()
                        } else {
                            expandedHistoryIds - id
                        }
                    },
                    onSyncRequested = { syncMeasurementId = it.id },
                    callbacks = callbacks,
                )

                MeasurementsDestination.EDITOR -> state.editor?.let { editor ->
                    MeasurementEditorScreen(editor = editor, callbacks = callbacks)
                } ?: MissingEditorState(onBack = callbacks.onBackRequested)
            }

            state.deleteConfirmation?.let { confirmation ->
                DeleteMeasurementDialog(
                    confirmation = confirmation,
                    onConfirm = { callbacks.onDeleteConfirmed(confirmation.measurementId) },
                    onDismiss = callbacks.onDeleteDismissed,
                )
            }
        }
    }

    syncItem?.let { item ->
        MeasurementSyncSheet(
            item = item,
            onRetry = {
                callbacks.onRetryRequested(item.id)
                syncMeasurementId = null
            },
            onDismiss = { syncMeasurementId = null },
        )
    }
}

@Composable
private fun PendingQueueDestination(onBack: () -> Unit) {
    LazyColumn(
        modifier = Modifier
            .widthIn(max = 680.dp)
            .fillMaxHeight()
            .fillMaxWidth()
            .testTag("pending-queue"),
        contentPadding = PaddingValues(
            horizontal = HuaweiDimensions.ContentPadding,
            vertical = HuaweiDimensions.CompactContentPadding,
        ),
    ) {
        item {
            NestedScreenHeader(
                title = "Не назначено",
                backContentDescription = "Назад к последнему измерению",
                onBack = onBack,
            )
        }
    }
}

@Composable
private fun MeasurementSummaryScreen(
    state: MeasurementsUiState,
    metricsExpanded: Boolean,
    onMetricsExpandedChange: (Boolean) -> Unit,
    onSyncRequested: (MeasurementUiItem) -> Unit,
    callbacks: MeasurementsCallbacks,
) {
    Box(Modifier.fillMaxSize()) {
        when {
            state.isLoading && state.summary == null -> LoadingState("Загрузка последнего измерения")
            state.hasNoLatestMeasurement -> NoLatestMeasurementState(
                onHistoryRequested = callbacks.onHistoryRequested,
            )

            state.summary != null -> {
                LazyColumn(
                    modifier = Modifier
                        .widthIn(max = 680.dp)
                        .fillMaxHeight()
                        .fillMaxWidth()
                        .align(Alignment.TopCenter),
                    contentPadding = PaddingValues(
                        horizontal = HuaweiDimensions.ContentPadding,
                        vertical = HuaweiDimensions.CompactContentPadding,
                    ),
                    verticalArrangement = Arrangement.spacedBy(HuaweiDimensions.ItemSpacing),
                ) {
                    if (state.isLoading) {
                        item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    }
                    item {
                        MeasurementSummaryCard(
                            summary = state.summary,
                            expanded = metricsExpanded,
                            onExpandedChange = onMetricsExpandedChange,
                            onSyncRequested = { onSyncRequested(state.summary.latest) },
                            onEditRequested = {
                                callbacks.onEditRequested(
                                    state.summary.latest.id,
                                    MeasurementEditorOrigin.SUMMARY,
                                )
                            },
                            onDeleteRequested = {
                                callbacks.onDeleteRequested(state.summary.latest.id)
                            },
                        )
                    }
                    item {
                        OutlinedButton(
                            onClick = callbacks.onHistoryRequested,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = HuaweiDimensions.TouchTarget)
                                .testTag("measurements-history-cta"),
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            Text("История измерений")
                        }
                    }
                    item { Spacer(Modifier.height(12.dp)) }
                }
            }
        }
    }
}

@Composable
private fun MeasurementSummaryCard(
    summary: MeasurementSummaryPresentation,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSyncRequested: () -> Unit,
    onEditRequested: () -> Unit,
    onDeleteRequested: () -> Unit,
) {
    var menuExpanded by rememberSaveable(summary.latest.id) { mutableStateOf(false) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .testTag("measurement-summary"),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        border = BorderStroke(1.dp, HuaweiColors.PrimaryContainerDim),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = formatMeasurementDateTime(summary.latest.measuredAtEpochMillis),
                        modifier = Modifier.weight(1f, fill = false),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    HuaweiStatusAction(
                        icon = summary.latest.sync.state.icon,
                        contentDescription = summary.latest.sync.label,
                        onClick = onSyncRequested,
                        tone = summary.latest.sync.state.tone,
                        enabled = !summary.latest.isOperationInProgress,
                        modifier = Modifier.testTag("summary-sync-status"),
                    )
                }
                Box {
                    HuaweiIconButton(
                        icon = HuaweiIcons.More,
                        contentDescription = "Действия с последним измерением",
                        onClick = { menuExpanded = true },
                        enabled = !summary.latest.isOperationInProgress,
                        modifier = Modifier.testTag("summary-more-actions"),
                    )
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                        containerColor = MaterialTheme.colorScheme.surface,
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        DropdownMenuItem(
                            text = { Text("Изменить") },
                            leadingIcon = { Icon(HuaweiIcons.Edit, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onEditRequested()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Удалить", color = MaterialTheme.colorScheme.error) },
                            modifier = Modifier.testTag("summary-delete-measurement"),
                            leadingIcon = {
                                Icon(
                                    imageVector = HuaweiIcons.Delete,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onDeleteRequested()
                            },
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.padding(top = 12.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(
                    text = formatDisplayValue(
                        MeasurementField.WEIGHT_KG,
                        summary.latest.values.weightKg,
                    ),
                    fontSize = 40.sp,
                    lineHeight = 44.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = (-1).sp,
                )
                Text(
                    text = " кг",
                    modifier = Modifier.padding(bottom = 4.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            if (summary.latest.isWeightOnly) {
                Text(
                    text = "Только вес",
                    modifier = Modifier.testTag("summary-weight-only-label"),
                    color = MaterialTheme.colorScheme.secondary,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            Text(
                text = formatWeightDelta(summary.weightDeltaKg),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )

            MetricGrid(
                metrics = summary.keyMetrics,
                modifier = Modifier.padding(top = 18.dp),
                tileColor = HuaweiColors.SurfaceSubtle,
            )

            TextButton(
                onClick = { onExpandedChange(!expanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = HuaweiDimensions.TouchTarget)
                    .semantics {
                        stateDescription = if (expanded) "Развёрнуто" else "Свёрнуто"
                    }
                    .testTag("summary-expand-metrics"),
            ) {
                Text(if (expanded) "Скрыть показатели" else "Все показатели")
                Icon(
                    imageVector = HuaweiIcons.ChevronDown,
                    contentDescription = null,
                    modifier = Modifier.padding(start = 6.dp).size(18.dp).rotate(if (expanded) 180f else 0f),
                )
            }

            if (expanded) {
                HorizontalDivider(color = HuaweiColors.PrimaryContainerDim)
                MetricDetailsGrid(
                    metrics = summary.additionalMetrics,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun MetricGrid(
    metrics: List<MeasurementMetricPresentation>,
    modifier: Modifier = Modifier,
    tileColor: Color = MaterialTheme.colorScheme.surface,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val columnCount = if (maxWidth < 300.dp) 1 else 2
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            metrics.chunked(columnCount).forEach { rowMetrics ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    rowMetrics.forEach { metric ->
                        MetricTile(
                            metric = metric,
                            containerColor = tileColor,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    repeat(columnCount - rowMetrics.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun MetricTile(
    metric: MeasurementMetricPresentation,
    containerColor: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.heightIn(min = 72.dp),
        shape = MaterialTheme.shapes.large,
        color = containerColor,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = metric.label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = metric.displayValue(),
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

@Composable
private fun MetricDetailsGrid(
    metrics: List<MeasurementMetricPresentation>,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val columnCount = if (maxWidth < 300.dp) 1 else 2
        Column {
            metrics.chunked(columnCount).forEach { rowMetrics ->
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    rowMetrics.forEach { metric ->
                        MetricDetail(metric = metric, modifier = Modifier.weight(1f))
                    }
                    repeat(columnCount - rowMetrics.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun MetricDetail(
    metric: MeasurementMetricPresentation,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .heightIn(min = HuaweiDimensions.TouchTarget)
            .padding(vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = metric.label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        Text(text = metric.displayValue(), style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun MeasurementHistoryScreen(
    state: MeasurementsUiState,
    expandedIds: List<String>,
    onExpandedChange: (String, Boolean) -> Unit,
    onSyncRequested: (MeasurementUiItem) -> Unit,
    callbacks: MeasurementsCallbacks,
) {
    LazyColumn(
        modifier = Modifier
            .widthIn(max = 680.dp)
            .fillMaxHeight()
            .fillMaxWidth()
            .testTag("measurement-history"),
        contentPadding = PaddingValues(
            horizontal = HuaweiDimensions.ContentPadding,
            vertical = HuaweiDimensions.CompactContentPadding,
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            NestedScreenHeader(
                title = "История",
                backContentDescription = "Назад к последнему измерению",
                onBack = callbacks.onBackRequested,
            )
        }
        if (state.isLoading) {
            item {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "Загрузка истории" },
                )
            }
        }
        if (state.isHistoryEmpty) {
            item { EmptyHistoryCard() }
        } else {
            items(state.measurements, key = { it.id }) { item ->
                MeasurementHistoryCard(
                    item = item,
                    expanded = item.id in expandedIds,
                    onExpandedChange = { onExpandedChange(item.id, it) },
                    onSyncRequested = { onSyncRequested(item) },
                    callbacks = callbacks,
                )
            }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
}

@Composable
private fun MeasurementHistoryCard(
    item: MeasurementUiItem,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSyncRequested: () -> Unit,
    callbacks: MeasurementsCallbacks,
) {
    HuaweiSurface(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .testTag("history-card-${item.id}"),
        contentPadding = PaddingValues(0.dp),
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 72.dp)
                        .clickable(
                            role = Role.Button,
                            onClickLabel = if (expanded) "Свернуть измерение" else "Развернуть измерение",
                        ) { onExpandedChange(!expanded) }
                        .semantics {
                            stateDescription = if (expanded) "Развёрнуто" else "Свёрнуто"
                        }
                        .testTag("history-toggle-${item.id}")
                        .padding(start = 16.dp, top = 14.dp, bottom = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Text(
                            text = formatMeasurementDateTime(item.measuredAtEpochMillis),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            text = "${formatDisplayValue(MeasurementField.WEIGHT_KG, item.values.weightKg)} кг",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        if (item.isWeightOnly) {
                            Text(
                                text = "Только вес",
                                modifier = Modifier.testTag("history-weight-only-label-${item.id}"),
                                color = MaterialTheme.colorScheme.secondary,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                        Text(
                            text = historySubtitle(item),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Icon(
                        imageVector = HuaweiIcons.ChevronRight,
                        contentDescription = null,
                        modifier = Modifier.rotate(if (expanded) 90f else 0f),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HuaweiStatusAction(
                    icon = item.sync.state.icon,
                    contentDescription = item.sync.label,
                    onClick = onSyncRequested,
                    tone = item.sync.state.tone,
                    enabled = !item.isOperationInProgress,
                    modifier = Modifier.padding(end = 8.dp).testTag("history-sync-${item.id}"),
                )
            }

            if (expanded) {
                HorizontalDivider()
                Column(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    MetricDetailsGrid(
                        metrics = historyAdditionalFields.map { field ->
                            MeasurementMetricPresentation(field, item.values[field])
                        },
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    if (item.isLocalOnly) {
                        HuaweiSurface(
                            modifier = Modifier.fillMaxWidth(),
                            containerColor = HuaweiColors.SurfaceInfo,
                            contentPadding = PaddingValues(14.dp),
                        ) {
                            Text(
                                "Запись изменена вручную и хранится только на этом устройстве. " +
                                    "Она больше не отправляется во внешние сервисы.",
                                color = MaterialTheme.colorScheme.secondary,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            onClick = {
                                callbacks.onEditRequested(item.id, MeasurementEditorOrigin.HISTORY)
                            },
                            enabled = !item.isOperationInProgress,
                            modifier = Modifier.heightIn(min = HuaweiDimensions.TouchTarget),
                        ) {
                            Icon(HuaweiIcons.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text("Изменить", modifier = Modifier.padding(start = 5.dp))
                        }
                        TextButton(
                            onClick = { callbacks.onDeleteRequested(item.id) },
                            enabled = !item.isOperationInProgress,
                            modifier = Modifier
                                .heightIn(min = HuaweiDimensions.TouchTarget)
                                .testTag("history-delete-${item.id}"),
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        ) {
                            Icon(HuaweiIcons.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text("Удалить", modifier = Modifier.padding(start = 5.dp))
                        }
                    }
                    if (item.canRetry) {
                        OutlinedButton(
                            onClick = { callbacks.onRetryRequested(item.id) },
                            enabled = !item.isOperationInProgress,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = HuaweiDimensions.TouchTarget),
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            Icon(HuaweiIcons.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text("Повторить отправку", modifier = Modifier.padding(start = 7.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyHistoryCard() {
    HuaweiSurface(
        modifier = Modifier.fillMaxWidth().testTag("empty-history"),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 26.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            HuaweiSectionTitle("Пока нет измерений")
            Text(
                "Стабильные измерения с весов появятся здесь.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MeasurementSyncSheet(
    item: MeasurementUiItem,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.testTag("measurement-sync-sheet"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 680.dp)
                .padding(start = 18.dp, end = 18.dp, bottom = 24.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = if (item.isLocalOnly) "Локальная запись" else "Синхронизация",
                        modifier = Modifier.semantics { heading() },
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        text = item.sync.label,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                HuaweiIconButton(
                    icon = HuaweiIcons.Close,
                    contentDescription = "Закрыть",
                    onClick = onDismiss,
                )
            }

            if (item.sync.directions.isEmpty()) {
                HuaweiSurface(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    containerColor = HuaweiColors.SurfaceInfo,
                ) {
                    Text("Внешние сервисы для этой сборки отключены.")
                }
            } else {
                item.sync.directions.forEach { direction ->
                    HorizontalDivider(Modifier.padding(top = 8.dp))
                    SyncDirectionRow(direction)
                }
            }

            if (item.canRetry) {
                Button(
                    onClick = onRetry,
                    enabled = !item.isOperationInProgress,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 14.dp)
                        .heightIn(min = HuaweiDimensions.TouchTarget)
                        .testTag("sync-retry"),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Icon(HuaweiIcons.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("Повторить", modifier = Modifier.padding(start = 7.dp))
                }
            }
        }
    }
}

@Composable
private fun SyncDirectionRow(direction: MeasurementSyncDirectionPresentation) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HuaweiRowIcon(icon = direction.state.icon, contentDescription = direction.state.label)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(direction.label, style = MaterialTheme.typography.titleSmall)
            Text(
                direction.state.label,
                color = direction.state.contentColor,
                style = MaterialTheme.typography.labelMedium,
            )
            if (direction.message.isNotBlank()) {
                Text(
                    direction.message,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun MeasurementEditorScreen(
    editor: MeasurementEditorState,
    callbacks: MeasurementsCallbacks,
) {
    val parsedValues = editor.draft.parsedValuesOrNull()
    Scaffold(
        modifier = Modifier.fillMaxSize().testTag("measurement-editor"),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            Surface(
                color = MaterialTheme.colorScheme.background.copy(alpha = 0.98f),
                contentColor = MaterialTheme.colorScheme.onBackground,
                shadowElevation = 8.dp,
            ) {
                Button(
                    onClick = {
                        parsedValues?.let { values ->
                            callbacks.onEditorSaveRequested(editor.measurementId, values)
                        }
                    },
                    enabled = editor.canSave && parsedValues != null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .imePadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .heightIn(min = HuaweiDimensions.TouchTarget)
                        .testTag("editor-save"),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    if (editor.isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Text("Сохранение…", modifier = Modifier.padding(start = 8.dp))
                    } else {
                        Text("Сохранить изменения")
                    }
                }
            }
        },
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier
                .widthIn(max = 680.dp)
                .fillMaxHeight()
                .fillMaxWidth()
                .padding(contentPadding),
            contentPadding = PaddingValues(
                horizontal = HuaweiDimensions.ContentPadding,
                vertical = HuaweiDimensions.CompactContentPadding,
            ),
            verticalArrangement = Arrangement.spacedBy(HuaweiDimensions.ItemSpacing),
        ) {
            item {
                NestedScreenHeader(
                    title = "Изменить измерение",
                    backContentDescription = "Отменить редактирование",
                    onBack = callbacks.onEditorDismissed,
                    enabled = !editor.isSaving,
                )
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        formatMeasurementDateTime(editor.measuredAtEpochMillis),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    if (editor.isWeightOnly) {
                        Text(
                            text = "Только вес",
                            modifier = Modifier.testTag("editor-weight-only-label"),
                            color = MaterialTheme.colorScheme.secondary,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                    Text(
                        "Дата, устройство и исходные данные не изменяются. " +
                            "Производные значения не пересчитываются.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            item {
                HuaweiSurface(
                    modifier = Modifier.fillMaxWidth(),
                    containerColor = HuaweiColors.SurfaceInfo,
                    contentPadding = PaddingValues(14.dp),
                ) {
                    Text(
                        EditorWarning,
                        color = MaterialTheme.colorScheme.secondary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            items(editor.sections, key = { it.group.name }) { section ->
                EditorSection(
                    section = section,
                    editor = editor,
                    onFieldChanged = callbacks.onEditorFieldChanged,
                )
            }
            item { Spacer(Modifier.height(12.dp)) }
        }
    }
}

@Composable
private fun EditorSection(
    section: MeasurementEditorSection,
    editor: MeasurementEditorState,
    onFieldChanged: (MeasurementField, String) -> Unit,
) {
    HuaweiSurface(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Column {
            HuaweiSectionTitle(section.title, modifier = Modifier.padding(top = 6.dp, bottom = 4.dp))
            section.fields.forEachIndexed { index, field ->
                if (index > 0) HorizontalDivider()
                EditorFieldRow(
                    field = field,
                    value = editor.draft[field],
                    error = editor.draft.validation(field).error,
                    enabled = !editor.isSaving,
                    onValueChange = { onFieldChanged(field, it) },
                )
            }
        }
    }
}

@Composable
private fun EditorFieldRow(
    field: MeasurementField,
    value: String,
    error: String?,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
    ) {
        if (maxWidth < 300.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(field.label, style = MaterialTheme.typography.bodyMedium)
                EditorFieldInput(
                    field = field,
                    value = value,
                    error = error,
                    enabled = enabled,
                    onValueChange = onValueChange,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    field.label,
                    modifier = Modifier.weight(1f).padding(top = 14.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
                EditorFieldInput(
                    field = field,
                    value = value,
                    error = error,
                    enabled = enabled,
                    onValueChange = onValueChange,
                    modifier = Modifier.width(154.dp),
                )
            }
        }
    }
}

@Composable
private fun EditorFieldInput(
    field: MeasurementField,
    value: String,
    error: String?,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .semantics { contentDescription = field.inputLabel }
            .testTag("editor-field-${field.name}"),
        enabled = enabled,
        isError = error != null,
        supportingText = error?.let { validationError ->
            { Text(validationError) }
        },
        suffix = field.unit.takeIf(String::isNotBlank)?.let { unit ->
            { Text(unit, style = MaterialTheme.typography.bodySmall) }
        },
        singleLine = true,
        shape = MaterialTheme.shapes.small,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (field.wholeNumber) KeyboardType.Number else KeyboardType.Decimal,
        ),
    )
}

@Composable
private fun NestedScreenHeader(
    title: String,
    backContentDescription: String,
    onBack: () -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = HuaweiDimensions.TopBarHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HuaweiIconButton(
            icon = HuaweiIcons.Back,
            contentDescription = backContentDescription,
            onClick = onBack,
            enabled = enabled,
        )
        Text(
            title,
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleLarge,
        )
    }
}

@Composable
private fun NoLatestMeasurementState(onHistoryRequested: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        HuaweiSurface(
            modifier = Modifier.fillMaxWidth().widthIn(max = 480.dp).padding(16.dp),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 26.dp),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                HuaweiRowIcon(icon = HuaweiIcons.Scale, contentDescription = null)
                HuaweiSectionTitle("Пока нет измерений")
                Text(
                    "Последнее стабильное измерение с весов появится здесь.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(
                    onClick = onHistoryRequested,
                    modifier = Modifier.heightIn(min = HuaweiDimensions.TouchTarget),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Text("Открыть историю")
                }
            }
        }
    }
}

@Composable
private fun MissingEditorState(onBack: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Измерение недоступно", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onBack, modifier = Modifier.heightIn(min = HuaweiDimensions.TouchTarget)) {
                Text("Назад")
            }
        }
    }
}

@Composable
private fun LoadingState(description: String) {
    Box(
        modifier = Modifier.fillMaxSize().semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator()
            Text(
                description,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun DeleteMeasurementDialog(
    confirmation: MeasurementDeleteConfirmation,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        modifier = Modifier.testTag("delete-measurement-dialog"),
        onDismissRequest = { if (!confirmation.isDeleting) onDismiss() },
        icon = { Icon(HuaweiIcons.Delete, contentDescription = null) },
        title = { Text("Удалить измерение?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "${formatMeasurementDateTime(confirmation.measuredAtEpochMillis)} · " +
                        "${formatDisplayValue(MeasurementField.WEIGHT_KG, confirmation.weightKg)} кг",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    "Локальная запись будет удалена без возможности восстановления. " +
                        "Данные в Health Connect и Huawei Health останутся без изменений.",
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = !confirmation.isDeleting,
                modifier = Modifier
                    .heightIn(min = HuaweiDimensions.TouchTarget)
                    .testTag("delete-measurement-confirm"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) {
                Text(if (confirmation.isDeleting) "Удаление…" else "Удалить")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !confirmation.isDeleting,
                modifier = Modifier.heightIn(min = HuaweiDimensions.TouchTarget),
            ) {
                Text("Отмена")
            }
        },
    )
}

private val MeasurementSyncPresentationState.icon: ImageVector
    get() = when (this) {
        MeasurementSyncPresentationState.LOCAL_ONLY -> HuaweiIcons.LocalDevice
        MeasurementSyncPresentationState.ERROR -> HuaweiIcons.Warning
        MeasurementSyncPresentationState.PENDING -> HuaweiIcons.Pending
        MeasurementSyncPresentationState.SYNCED -> HuaweiIcons.Success
    }

private val MeasurementSyncPresentationState.tone: HuaweiStatusTone
    get() = when (this) {
        MeasurementSyncPresentationState.LOCAL_ONLY -> HuaweiStatusTone.Local
        MeasurementSyncPresentationState.ERROR -> HuaweiStatusTone.Error
        MeasurementSyncPresentationState.PENDING -> HuaweiStatusTone.Pending
        MeasurementSyncPresentationState.SYNCED -> HuaweiStatusTone.Success
    }

private val MeasurementSyncPresentationState.contentColor: Color
    @Composable get() = when (this) {
        MeasurementSyncPresentationState.LOCAL_ONLY -> HuaweiColors.Local
        MeasurementSyncPresentationState.ERROR -> MaterialTheme.colorScheme.error
        MeasurementSyncPresentationState.PENDING -> HuaweiColors.Warning
        MeasurementSyncPresentationState.SYNCED -> MaterialTheme.colorScheme.primary
    }

private fun historySubtitle(item: MeasurementUiItem): String = listOf(
    MeasurementMetricPresentation(
        MeasurementField.BODY_FAT_PERCENT,
        item.values.bodyFatPercent,
    ).displayValue().let { "Жир $it" },
    MeasurementMetricPresentation(
        MeasurementField.MUSCLE_MASS_KG,
        item.values.muscleMassKg,
    ).displayValue().let { "мышцы $it" },
    MeasurementMetricPresentation(
        MeasurementField.BMI,
        item.values.bmi,
    ).displayValue().let { "ИМТ $it" },
).joinToString(" · ")

private fun MeasurementMetricPresentation.displayValue(locale: Locale = Locale.getDefault()): String {
    if (value == null) return MissingMeasurementValue
    val formatted = formatDisplayValue(field, value, locale)
    return when (unit) {
        "" -> formatted
        "%" -> "$formatted%"
        else -> "$formatted $unit"
    }
}

private fun formatWeightDelta(delta: Double?, locale: Locale = Locale.getDefault()): String = when {
    delta == null -> "Первое измерение"
    kotlin.math.abs(delta) < 0.000_001 -> "Без изменений с прошлого измерения"
    else -> {
        val prefix = if (delta > 0) "+" else "−"
        val value = formatDisplayValue(MeasurementField.WEIGHT_KG, kotlin.math.abs(delta), locale)
        "$prefix$value кг с прошлого измерения"
    }
}

private fun formatDisplayValue(
    field: MeasurementField,
    value: Double?,
    locale: Locale = Locale.getDefault(),
): String {
    if (value == null) return MissingMeasurementValue
    return NumberFormat.getNumberInstance(locale).run {
        minimumFractionDigits = 0
        maximumFractionDigits = field.decimalPlaces
        isGroupingUsed = true
        format(value)
    }
}

fun formatMeasurementDateTime(
    epochMillis: Long,
    zoneId: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): String = DateTimeFormatter
    .ofPattern("dd.MM.yyyy HH:mm", locale)
    .format(Instant.ofEpochMilli(epochMillis).atZone(zoneId))

fun formatMeasurementValue(
    field: MeasurementField,
    value: Double,
    locale: Locale = Locale.getDefault(),
): String = String.format(locale, ".${field.decimalPlaces}f".let { "%$it" }, value)
