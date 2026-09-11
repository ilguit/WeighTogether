package com.palixander.scalesync.measurements

import com.palixander.scalesync.ui.components.ManualOriginIndicator

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
import androidx.compose.foundation.layout.sizeIn
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
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
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import com.palixander.scalesync.R
import com.palixander.scalesync.core.BodyMetric
import com.palixander.scalesync.domain.PendingMeasurementId
import com.palixander.scalesync.ui.components.HuaweiIconButton
import com.palixander.scalesync.ui.components.HuaweiRowIcon
import com.palixander.scalesync.ui.components.HuaweiSectionTitle
import com.palixander.scalesync.ui.components.HuaweiStatusAction
import com.palixander.scalesync.ui.components.HuaweiStatusTone
import com.palixander.scalesync.ui.components.HuaweiSurface
import com.palixander.scalesync.ui.icons.HuaweiIcons
import com.palixander.scalesync.ui.accounts.AccountSelector
import com.palixander.scalesync.ui.theme.HuaweiColors
import com.palixander.scalesync.ui.theme.HuaweiDimensions
import com.palixander.scalesync.ui.theme.ReferencePalette
import com.palixander.scalesync.ui.theme.ReferenceTone
import com.palixander.scalesync.ui.reference.ExpandedMetricReference
import com.palixander.scalesync.ui.reference.MetricHelpDialog
import com.palixander.scalesync.ui.reference.ReferenceGroupPresentation
import com.palixander.scalesync.ui.reference.ReferenceComponentTestTags
import com.palixander.scalesync.ui.reference.ReferenceMetricGroup
import com.palixander.scalesync.ui.reference.ReferenceMetricPresentation
import com.palixander.scalesync.ui.reference.referenceGroupColumnCount
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
    showAccountSelector: Boolean = true,
    summaryHeader: @Composable () -> Unit = {},
) {
    var summaryMetricsExpanded by rememberSaveable { mutableStateOf(false) }
    var expandedHistoryIds by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var syncMeasurementId by rememberSaveable { mutableStateOf<String?>(null) }
    var helpMeasurementKey by rememberSaveable { mutableStateOf<String?>(null) }
    var helpMetricName by rememberSaveable { mutableStateOf<String?>(null) }
    var helpAccountId by rememberSaveable { mutableStateOf<String?>(null) }
    var helpDestinationName by rememberSaveable { mutableStateOf<String?>(null) }
    var restoreFocusKey by rememberSaveable { mutableStateOf<String?>(null) }
    val helpFocusRequesters = remember { mutableMapOf<String, FocusRequester>() }
    val referenceSnackbarHostState = remember { SnackbarHostState() }
    val syncItem = state.measurements.firstOrNull {
        it.finalMeasurementId == syncMeasurementId && it.hasSyncPresentation
    }
    val helpItem = state.measurements.firstOrNull { it.presentationKey == helpMeasurementKey }
    val helpMetric = helpItem?.referenceMetrics?.firstOrNull {
        it.definition.metric.name == helpMetricName
    }
    fun helpKey(item: MeasurementUiItem, metric: ReferenceMetricPresentation): String =
        "${item.presentationKey}:${metric.definition.metric.name}"
    val onReferenceInfoClick: (MeasurementUiItem, ReferenceMetricPresentation) -> Unit = { item, metric ->
        helpMeasurementKey = item.presentationKey
        helpMetricName = metric.definition.metric.name
        helpAccountId = state.accountSelector.selectedAccountId?.value
        helpDestinationName = state.destination.name
    }

    LaunchedEffect(helpMetric, state.destination, state.accountSelector.selectedAccountId) {
        if (helpMeasurementKey != null && (
            helpMetric == null || helpDestinationName != state.destination.name ||
            helpAccountId != state.accountSelector.selectedAccountId?.value
        )) {
            helpMeasurementKey = null
            helpMetricName = null
            helpAccountId = null
            helpDestinationName = null
        }
    }
    LaunchedEffect(restoreFocusKey) {
        val key = restoreFocusKey ?: return@LaunchedEffect
        runCatching { helpFocusRequesters[key]?.requestFocus() }
        restoreFocusKey = null
    }

    Column(modifier = modifier.fillMaxSize()) {
        if (
            showAccountSelector && (state.destination == MeasurementsDestination.SUMMARY ||
            state.destination == MeasurementsDestination.HISTORY
            )
        ) {
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
                    summaryHeader = summaryHeader,
                    state = state,
                    metricsExpanded = summaryMetricsExpanded,
                    onMetricsExpandedChange = { summaryMetricsExpanded = it },
                    onSyncRequested = { syncMeasurementId = it.finalMeasurementId },
                    callbacks = callbacks,
                    onReferenceInfoClick = onReferenceInfoClick,
                    helpFocusRequesters = helpFocusRequesters,
                )

                MeasurementsDestination.PENDING_QUEUE -> PendingQueueDestination(
                    pendingMeasurements = state.pendingMeasurements,
                    onAssign = callbacks.onPendingAssignRequested,
                    onPreview = callbacks.onPendingPreviewRequested,
                    onDelete = callbacks.onPendingDeleteRequested,
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
                    onSyncRequested = { syncMeasurementId = it.finalMeasurementId },
                    callbacks = callbacks,
                    onReferenceInfoClick = onReferenceInfoClick,
                    helpFocusRequesters = helpFocusRequesters,
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
            SnackbarHost(
                hostState = referenceSnackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
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

    if (helpItem != null && helpMetric != null) {
        MetricHelpDialog(
            presentation = helpMetric,
            snackbarHostState = referenceSnackbarHostState,
            onDismissRequest = {
                restoreFocusKey = helpKey(helpItem, helpMetric)
                helpMeasurementKey = null
                helpMetricName = null
                helpAccountId = null
                helpDestinationName = null
            },
            manualWarning = helpItem.isManuallyEdited,
            legacyHeightWarning = helpItem.hasRestoredRatingHeight,
            usedDataText = referenceUsedDataText(helpItem),
        )
    }
}

@Composable
private fun PendingQueueDestination(
    pendingMeasurements: List<PendingMeasurementUiItem>,
    onAssign: (PendingMeasurementId) -> Unit,
    onPreview: (PendingMeasurementId) -> Unit,
    onDelete: (PendingMeasurementId) -> Unit,
    onBack: () -> Unit,
) {
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
        verticalArrangement = Arrangement.spacedBy(HuaweiDimensions.ItemSpacing),
    ) {
        item {
            NestedScreenHeader(
                title = "Не назначено",
                backContentDescription = "Назад к последнему измерению",
                onBack = onBack,
            )
        }
        if (pendingMeasurements.isEmpty()) {
            item { EmptyPendingQueueCard() }
        } else {
            items(
                items = pendingMeasurements,
                key = { it.id.value },
            ) { pending ->
                PendingMeasurementCard(
                    pending = pending,
                    onAssign = { onAssign(pending.id) },
                    onPreview = { onPreview(pending.id) },
                    onDelete = { onDelete(pending.id) },
                )
            }
        }
        item { Spacer(Modifier.height(12.dp)) }
    }
}

@Composable
private fun PendingMeasurementCard(
    pending: PendingMeasurementUiItem,
    onAssign: () -> Unit,
    onPreview: () -> Unit,
    onDelete: () -> Unit,
) {
    HuaweiSurface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("pending-card-${pending.id.value}"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = formatMeasurementDateTime(pending.measuredAt),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                PendingMeasurementValue(
                    label = "Вес",
                    value = "${formatDisplayValue(MeasurementField.WEIGHT_KG, pending.weightKg)} кг",
                    modifier = Modifier.weight(1f),
                )
                PendingMeasurementValue(
                    label = "Импеданс",
                    value = pending.impedanceOhm?.let { "$it Ом" } ?: MissingMeasurementValue,
                    modifier = Modifier.weight(1f),
                )
            }
            if (pending.isProcessing) {
                ProcessingStatus(
                    modifier = Modifier.testTag("pending-processing-${pending.id.value}"),
                )
            } else {
                Button(
                    onClick = onAssign,
                    enabled = pending.canAssign,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = HuaweiDimensions.TouchTarget)
                        .testTag("pending-assign-${pending.id.value}"),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Text("Назначить")
                }
                OutlinedButton(
                    onClick = onPreview,
                    enabled = pending.canPreview,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = HuaweiDimensions.TouchTarget)
                        .testTag("pending-preview-${pending.id.value}"),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Text("Показать без сохранения")
                }
                TextButton(
                    onClick = onDelete,
                    enabled = pending.canDelete,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = HuaweiDimensions.TouchTarget)
                        .testTag("pending-delete-${pending.id.value}"),
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Icon(
                        imageVector = HuaweiIcons.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text("Удалить", modifier = Modifier.padding(start = 5.dp))
                }
            }
        }
    }
}

@Composable
private fun ProcessingStatus(modifier: Modifier = Modifier) {
    HuaweiSurface(
        modifier = modifier.fillMaxWidth(),
        containerColor = HuaweiColors.SurfaceInfo,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text = "Обрабатывается",
            color = MaterialTheme.colorScheme.secondary,
            fontWeight = FontWeight.Medium,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun PendingMeasurementValue(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
        )
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun EmptyPendingQueueCard() {
    HuaweiSurface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("empty-pending-queue"),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 26.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            HuaweiSectionTitle("Нет неназначенных измерений")
            Text(
                "Все измерения обработаны.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
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
    onReferenceInfoClick: (MeasurementUiItem, ReferenceMetricPresentation) -> Unit,
    helpFocusRequesters: MutableMap<String, FocusRequester>,
    summaryHeader: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier.widthIn(max = 680.dp).fillMaxSize()
                .testTag("measurement-summary-list"),
            contentPadding = PaddingValues(
                horizontal = HuaweiDimensions.ContentPadding,
                vertical = HuaweiDimensions.CompactContentPadding,
            ),
            verticalArrangement = Arrangement.spacedBy(HuaweiDimensions.ItemSpacing),
        ) {
            item(key = "summary-header") { summaryHeader() }
            when {
                state.isLoading && state.summary == null -> item {
                    LoadingState("Загрузка последнего измерения")
                }
                state.hasNoLatestMeasurement -> item { NoLatestMeasurementState() }
                state.summary != null -> {
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
                            onReferenceInfoClick = onReferenceInfoClick,
                            helpFocusRequesters = helpFocusRequesters,
                        )
                    }
                    state.homeKgChart?.let { homeKgChart ->
                        item {
                            HomeKgChart(
                                state = homeKgChart,
                                onSeriesToggled = callbacks.onHomeKgChartSeriesToggled,
                            )
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(12.dp)) }
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
    onReferenceInfoClick: (MeasurementUiItem, ReferenceMetricPresentation) -> Unit,
    helpFocusRequesters: MutableMap<String, FocusRequester>,
) {
    var menuExpanded by rememberSaveable(summary.latest.presentationKey) { mutableStateOf(false) }

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
        Column(
            Modifier.padding(
                horizontal = 16.dp,
                vertical = HuaweiDimensions.CompactContentPadding,
            ),
        ) {
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
                        text = formatMeasurementDateTime(summary.latest.measuredAt),
                        modifier = Modifier.weight(1f, fill = false),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (summary.latest.hasSyncPresentation) {
                        HuaweiStatusAction(
                            icon = summary.latest.sync.state.icon,
                            contentDescription = summary.latest.sync.label,
                            onClick = onSyncRequested,
                            tone = summary.latest.sync.state.tone,
                            enabled = summary.latest.canSync,
                            modifier = Modifier.testTag("summary-sync-status"),
                        )
                    }
                }
                if (summary.latest.hasFinalActions) Box {
                    HuaweiIconButton(
                        icon = HuaweiIcons.More,
                        contentDescription = "Действия с последним измерением",
                        onClick = { menuExpanded = true },
                        enabled = summary.latest.canEdit || summary.latest.canDelete,
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
                            enabled = summary.latest.canEdit,
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
                            enabled = summary.latest.canDelete,
                        )
                    }
                }
            }

            val referenceMetrics = summary.latest.referenceMetrics.takeUnless {
                summary.latest.isPreliminary
            }.orEmpty()
            val weightReference = referenceMetrics.firstOrNull {
                it.definition.metric == BodyMetric.WEIGHT
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    if (!expanded && weightReference != null) {
                        CompactSummaryReferenceMetric(
                            presentation = weightReference,
                            primary = true,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    } else if (weightReference == null) {
                        Row(
                            modifier = Modifier.padding(top = 4.dp),
                            verticalAlignment = Alignment.Bottom,
                        ) {
                            Text(
                                text = formatDisplayValue(
                                    MeasurementField.WEIGHT_KG,
                                    summary.latest.values.weightKg,
                                ),
                                fontSize = 32.sp,
                                lineHeight = 36.sp,
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
                    }
                }
                ManualOriginIndicator(summary.latest.origin, Modifier.testTag("summary-manual-origin"))
            }
            if (summary.latest.isWeightOnly) {
                Text(
                    text = "Только вес",
                    modifier = Modifier.testTag("summary-weight-only-label"),
                    color = MaterialTheme.colorScheme.secondary,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            if (summary.latest.isPreliminary) {
                ProcessingStatus(modifier = Modifier.testTag("summary-processing-status"))
            }
            Text(
                text = formatWeightDelta(summary.weightDeltaKg),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )

            if (!expanded) {
                if (referenceMetrics.isNotEmpty()) {
                    CompactReferenceGrid(
                        metrics = summaryKeyReferenceMetrics(referenceMetrics),
                        modifier = Modifier.padding(top = HuaweiDimensions.CompactContentPadding),
                    )
                } else {
                    MetricDetailsGrid(
                        metrics = summary.keyMetrics,
                        modifier = Modifier.padding(top = HuaweiDimensions.CompactContentPadding),
                    )
                }
            }

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
                if (referenceMetrics.isNotEmpty() && weightReference != null) {
                    Column(
                        modifier = Modifier.padding(top = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        MeasurementReferenceMetric(
                            item = summary.latest,
                            metric = weightReference,
                            onInfoClick = onReferenceInfoClick,
                            focusRequesters = helpFocusRequesters,
                        )
                        MeasurementReferenceGroups(
                            item = summary.latest,
                            metrics = referenceMetrics.filterNot {
                                it.definition.metric == BodyMetric.WEIGHT
                            },
                            onInfoClick = onReferenceInfoClick,
                            focusRequesters = helpFocusRequesters,
                        )
                    }
                } else {
                    MetricDetailsGrid(
                        metrics = summary.additionalMetrics,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
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

private fun summaryKeyReferenceMetrics(
    metrics: List<ReferenceMetricPresentation>,
): List<ReferenceMetricPresentation> {
    val order = listOf(
        BodyMetric.BODY_FAT_PERCENT,
        BodyMetric.MUSCLE_MASS,
        BodyMetric.WATER_PERCENT,
        BodyMetric.BMI,
    )
    val byMetric = metrics.associateBy { it.definition.metric }
    return order.mapNotNull(byMetric::get)
}

@Composable
private fun CompactReferenceGrid(
    metrics: List<ReferenceMetricPresentation>,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val columnCount = if (maxWidth >= 300.dp) 2 else 1
        Column(
            modifier = Modifier.testTag(
                if (columnCount == 2) ReferenceComponentTestTags.GridTwoColumns
                else ReferenceComponentTestTags.GridOneColumn,
            ),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            metrics.chunked(columnCount).forEach { rowMetrics ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    rowMetrics.forEach { metric ->
                        CompactSummaryReferenceMetric(metric, modifier = Modifier.weight(1f))
                    }
                    repeat(columnCount - rowMetrics.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun CompactSummaryReferenceMetric(
    presentation: ReferenceMetricPresentation,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
) {
    val referenceContent = ReferencePalette.colors(presentation.tone).content
    Column(
        modifier = modifier
            .clearAndSetSemantics {
                contentDescription = presentation.compactAccessibilityDescription
            }
            .padding(vertical = if (primary) 2.dp else 6.dp)
            .testTag("summary-reference-${presentation.definition.metric.name}"),
        verticalArrangement = Arrangement.spacedBy(if (primary) 0.dp else 2.dp),
    ) {
        if (!primary) {
            Text(
                text = presentation.title,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = presentation.visualNumber
                    ?: stringResource(R.string.reference_missing_value_symbol),
                color = referenceContent,
                fontSize = if (primary) 40.sp else MaterialTheme.typography.titleMedium.fontSize,
                lineHeight = if (primary) 44.sp else MaterialTheme.typography.titleMedium.lineHeight,
                fontWeight = if (primary) FontWeight.Medium else FontWeight.Normal,
                letterSpacing = if (primary) (-1).sp else MaterialTheme.typography.titleMedium.letterSpacing,
                modifier = Modifier
                    .semantics {
                        compactSummaryReferenceContentColor = referenceContent.value.toLong()
                    }
                    .testTag("summary-reference-value-${presentation.definition.metric.name}"),
            )
            if (presentation.visualNumber != null) {
                Text(
                    text = " ${presentation.visibleUnit}",
                    modifier = Modifier
                        .then(if (primary) Modifier.padding(bottom = 5.dp) else Modifier)
                        .semantics {
                            compactSummaryReferenceContentColor = referenceContent.value.toLong()
                        }
                        .testTag("summary-reference-unit-${presentation.definition.metric.name}"),
                    color = referenceContent,
                    style = if (primary) {
                        MaterialTheme.typography.bodyLarge
                    } else {
                        MaterialTheme.typography.bodySmall
                    },
                )
            }
        }
    }
}

internal val CompactSummaryReferenceContentColorKey =
    SemanticsPropertyKey<Long>("CompactSummaryReferenceContentColor")

internal var SemanticsPropertyReceiver.compactSummaryReferenceContentColor by
    CompactSummaryReferenceContentColorKey

@Composable
private fun MeasurementReferenceGroups(
    item: MeasurementUiItem,
    metrics: List<ReferenceMetricPresentation>,
    onInfoClick: (MeasurementUiItem, ReferenceMetricPresentation) -> Unit,
    focusRequesters: MutableMap<String, FocusRequester>,
    modifier: Modifier = Modifier,
) {
    val groups = ReferenceMetricGroup.entries.mapNotNull { group ->
        metrics.filter { it.definition.group == group }
            .takeIf(List<ReferenceMetricPresentation>::isNotEmpty)
            ?.let { ReferenceGroupPresentation(group, group.titleRes?.let { stringResource(it) }, it) }
    }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val width = maxWidth
        val fontScale = LocalDensity.current.fontScale
        val twoColumns = groups.any { group ->
            referenceGroupColumnCount(group.metrics, width, fontScale) == 2
        }
        Column(
            modifier = Modifier.testTag(
                if (twoColumns) ReferenceComponentTestTags.GridTwoColumns
                else ReferenceComponentTestTags.GridOneColumn,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            groups.forEach { group ->
                val columnCount = referenceGroupColumnCount(group.metrics, width, fontScale)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    group.title?.let { title ->
                        Text(
                            title,
                            modifier = Modifier.semantics { heading() },
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    group.metrics.chunked(columnCount).forEach { rowMetrics ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            rowMetrics.forEach { metric ->
                                MeasurementReferenceMetric(
                                    item = item,
                                    metric = metric,
                                    onInfoClick = onInfoClick,
                                    focusRequesters = focusRequesters,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            repeat(columnCount - rowMetrics.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MeasurementReferenceMetric(
    item: MeasurementUiItem,
    metric: ReferenceMetricPresentation,
    onInfoClick: (MeasurementUiItem, ReferenceMetricPresentation) -> Unit,
    focusRequesters: MutableMap<String, FocusRequester>,
    modifier: Modifier = Modifier,
) {
    val key = "${item.presentationKey}:${metric.definition.metric.name}"
    val focusRequester = remember(key) { FocusRequester() }
    DisposableEffect(key, focusRequester) {
        focusRequesters[key] = focusRequester
        onDispose {
            if (focusRequesters[key] === focusRequester) focusRequesters.remove(key)
        }
    }
    ExpandedMetricReference(
        presentation = metric,
        onInfoClick = { onInfoClick(item, metric) },
        modifier = modifier.testTag(
            "reference-metric-${item.presentationKey}-${metric.definition.metric.name}",
        ),
        infoButtonModifier = Modifier.focusRequester(focusRequester),
    )
}

@Composable
private fun referenceUsedDataText(item: MeasurementUiItem): String? {
    val height = item.ratingHeightCm ?: return null
    val age = item.referenceAge ?: return null
    val locale = LocalConfiguration.current.locales[0]
    val formattedHeight = NumberFormat.getNumberInstance(locale).apply {
        maximumFractionDigits = 2
        minimumFractionDigits = 0
    }.format(height)
    return stringResource(R.string.reference_used_data, formattedHeight, age)
}

@Composable
private fun MeasurementHistoryScreen(
    state: MeasurementsUiState,
    expandedIds: List<String>,
    onExpandedChange: (String, Boolean) -> Unit,
    onSyncRequested: (MeasurementUiItem) -> Unit,
    callbacks: MeasurementsCallbacks,
    onReferenceInfoClick: (MeasurementUiItem, ReferenceMetricPresentation) -> Unit,
    helpFocusRequesters: MutableMap<String, FocusRequester>,
) {
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(state.scrollToMeasurementId, state.measurements, state.isLoading) {
        val index = state.measurements.indexOfFirst { it.id == state.scrollToMeasurementId }
        if (state.scrollToMeasurementId != null && index >= 0 && !state.isLoading) {
            listState.scrollToItem(index + 1)
            callbacks.onScrollToMeasurementHandled()
        }
    }
    LazyColumn(
        state = listState,
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
                onAdd = callbacks.onAddWeightRequested,
                addEnabled = state.accountSelector.selectedAccountId != null,
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
            items(state.measurements, key = { it.presentationKey }) { item ->
                MeasurementHistoryCard(
                    item = item,
                    expanded = item.presentationKey in expandedIds,
                    onExpandedChange = { onExpandedChange(item.presentationKey, it) },
                    onSyncRequested = { onSyncRequested(item) },
                    callbacks = callbacks,
                    onReferenceInfoClick = onReferenceInfoClick,
                    helpFocusRequesters = helpFocusRequesters,
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
    onReferenceInfoClick: (MeasurementUiItem, ReferenceMetricPresentation) -> Unit,
    helpFocusRequesters: MutableMap<String, FocusRequester>,
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
                            text = formatMeasurementDateTime(item.measuredAt),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        if (!expanded || item.isWeightOnly) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "${formatDisplayValue(MeasurementField.WEIGHT_KG, item.values.weightKg)} кг",
                                    modifier = Modifier.weight(1f, fill = false).testTag("history-header-weight-${item.presentationKey}"),
                                    style = MaterialTheme.typography.titleMedium,
                                    softWrap = true,
                                )
                                ManualOriginIndicator(item.origin, Modifier.testTag("history-manual-origin-${item.id}"))
                            }
                        }
                        if (item.isWeightOnly) {
                            Text(
                                text = "Только вес",
                                modifier = Modifier.testTag("history-weight-only-label-${item.id}"),
                                color = MaterialTheme.colorScheme.secondary,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                        if (item.isPreliminary) {
                            ProcessingStatus(
                                modifier = Modifier.testTag(
                                    "history-processing-status-${item.presentationKey}",
                                ),
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
                if (item.hasSyncPresentation) {
                    HuaweiStatusAction(
                        icon = item.sync.state.icon,
                        contentDescription = item.sync.label,
                        onClick = onSyncRequested,
                        tone = item.sync.state.tone,
                        enabled = item.canSync,
                        modifier = Modifier.padding(end = 8.dp).testTag("history-sync-${item.id}"),
                    )
                }
            }

            if (expanded) {
                HorizontalDivider()
                Column(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (item.referenceMetrics.isNotEmpty() && !item.isPreliminary) {
                        MeasurementReferenceGroups(
                            item = item,
                            metrics = item.referenceMetrics,
                            onInfoClick = onReferenceInfoClick,
                            focusRequesters = helpFocusRequesters,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    } else {
                        MetricDetailsGrid(
                            metrics = historyAdditionalFields.map { field ->
                                MeasurementMetricPresentation(field, item.values[field])
                            },
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    if (item.isManuallyEdited) {
                        HuaweiSurface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("history-manual-notice-${item.id}"),
                            containerColor = HuaweiColors.SurfaceInfo,
                            contentPadding = PaddingValues(14.dp),
                        ) {
                            Text(
                                MANUALLY_EDITED_HISTORY_MESSAGE,
                                color = MaterialTheme.colorScheme.secondary,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                    if (item.hasProfileSyncMismatch) {
                        HuaweiSurface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("history-profile-mismatch-notice-${item.id}"),
                            containerColor = HuaweiColors.SurfaceInfo,
                            contentPadding = PaddingValues(14.dp),
                        ) {
                            Text(
                                PROFILE_SYNC_MISMATCH_HISTORY_MESSAGE,
                                color = MaterialTheme.colorScheme.secondary,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                    if (item.hasFinalActions) Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            onClick = {
                                callbacks.onEditRequested(item.id, MeasurementEditorOrigin.HISTORY)
                            },
                            enabled = item.canEdit,
                            modifier = Modifier.heightIn(min = HuaweiDimensions.TouchTarget),
                        ) {
                            Icon(HuaweiIcons.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text("Изменить", modifier = Modifier.padding(start = 5.dp))
                        }
                        TextButton(
                            onClick = { callbacks.onDeleteRequested(item.id) },
                            enabled = item.canDelete,
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
                            enabled = item.canRetry,
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
                        text = "Синхронизация",
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
                        formatMeasurementDateTime(editor.measuredAt),
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
    onAdd: (() -> Unit)? = null,
    addEnabled: Boolean = true,
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
            modifier = Modifier.weight(1f).semantics { heading() },
            style = MaterialTheme.typography.titleLarge,
        )
        onAdd?.let { action ->
            TextButton(onClick = action, enabled = addEnabled, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                .testTag("measurement-history-add").semantics { contentDescription = "Добавить вес" }) {
                Text("+", style = MaterialTheme.typography.headlineMedium)
            }
        }
    }
}

@Composable
private fun NoLatestMeasurementState() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
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
                    "${formatMeasurementDateTime(confirmation.measuredAt)} · " +
                        "${formatDisplayValue(MeasurementField.WEIGHT_KG, confirmation.weightKg)} кг",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    "Измерение будет удалено без возможности восстановления. " +
                        "Данные в Health Connect останутся без изменений.",
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
    if (field == MeasurementField.WEIGHT_KG) return formatWeight(value, locale)
    val formatted = formatDisplayValue(field, value, locale)
    return when (unit) {
        "" -> formatted
        "%" -> "$formatted%"
        else -> "$formatted $unit"
    }
}

private fun formatWeightDelta(delta: Double?, locale: Locale = Locale.getDefault()): String = when {
    delta == null -> "Первое измерение"
    kotlin.math.abs(delta) < 0.000_001 -> "Без изменений"
    else -> {
        val prefix = if (delta > 0) "+" else "−"
        val value = formatDisplayValue(MeasurementField.WEIGHT_KG, kotlin.math.abs(delta), locale)
        "$prefix$value кг"
    }
}

private fun formatDisplayValue(
    field: MeasurementField,
    value: Double?,
    locale: Locale = Locale.getDefault(),
): String {
    if (value == null) return MissingMeasurementValue
    if (field == MeasurementField.WEIGHT_KG) return formatWeight(value, locale)
    return NumberFormat.getNumberInstance(locale).run {
        minimumFractionDigits = 0
        maximumFractionDigits = field.decimalPlaces
        isGroupingUsed = true
        format(value)
    }
}

fun formatMeasurementDateTime(
    instant: Instant,
    zoneId: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): String = DateTimeFormatter
    .ofPattern("dd.MM.yyyy HH:mm:ss", locale)
    .format(Instant.ofEpochSecond(instant.epochSecond).atZone(zoneId))

fun formatMeasurementValue(
    field: MeasurementField,
    value: Double,
    locale: Locale = Locale.getDefault(),
): String = String.format(locale, ".${field.decimalPlaces}f".let { "%$it" }, value)
