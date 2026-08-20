package com.example.huaweimisync.ui.routing

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.huaweimisync.domain.AccountId
import com.example.huaweimisync.measurements.formatMeasurementDateTime
import com.example.huaweimisync.ui.accounts.formatLocalizedDecimal
import com.example.huaweimisync.ui.theme.HuaweiColors
import com.example.huaweimisync.ui.theme.HuaweiDimensions

object MeasurementResolverTestTags {
    const val Dialog = "measurement-resolver"
    const val CreateAccount = "measurement-resolver-create-account"
    const val WithoutSaving = "measurement-resolver-without-saving"
    const val Delete = "measurement-resolver-delete"
    const val IgnoreUnknown = "measurement-resolver-ignore-unknown"
    const val Later = "measurement-resolver-later"
    const val ForegroundFallback = "measurement-resolver-foreground-fallback"
    fun account(accountId: AccountId): String = "measurement-resolver-account-${accountId.value}"
}

@Composable
fun PendingResolverForegroundFallback(
    state: ResolverQueueState,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!state.showForegroundFallback) return
    Surface(
        modifier = modifier.fillMaxWidth().testTag(MeasurementResolverTestTags.ForegroundFallback),
        shape = MaterialTheme.shapes.large,
        color = HuaweiColors.WarningContainer,
        contentColor = HuaweiColors.OnWarningContainer,
    ) {
        Row(
            modifier = Modifier.padding(HuaweiDimensions.CompactContentPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(HuaweiDimensions.CompactItemSpacing),
        ) {
            Text(
                text = "Ожидают решения: ${state.pendingCount}",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = onOpen) { Text("Открыть") }
        }
    }
}

@Composable
fun MeasurementResolverDialog(
    state: MeasurementResolverUiState,
    callbacks: MeasurementResolverCallbacks,
    modifier: Modifier = Modifier,
) {
    AlertDialog(
        modifier = modifier.testTag(MeasurementResolverTestTags.Dialog),
        onDismissRequest = {
            if (!state.operationInProgress) callbacks.onLater()
        },
        title = { Text("Кому сохранить измерение?") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(HuaweiDimensions.CompactItemSpacing),
            ) {
                Text(
                    "${formatLocalizedDecimal(state.pending.weightKg)} кг · импеданс ${state.pending.impedanceOhm} Ом",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    formatMeasurementDateTime(state.pending.measuredAt),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                if (state.candidateCount > 0) {
                    Text(
                        "Сначала показаны подходящие аккаунты",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                state.accountOptions.forEach { option ->
                    ResolverAccountButton(
                        option = option,
                        enabled = !state.operationInProgress,
                        onClick = {
                            callbacks.onAccountSelected(state.pending.id, option.accountId)
                        },
                    )
                }
                OutlinedButton(
                    onClick = { callbacks.onCreateAccount(state.pending.id) },
                    enabled = !state.operationInProgress,
                    modifier = Modifier.fillMaxWidth().testTag(MeasurementResolverTestTags.CreateAccount),
                ) { Text("Создать новый аккаунт") }
                OutlinedButton(
                    onClick = { callbacks.onShowWithoutSaving(state.pending.id) },
                    enabled = !state.operationInProgress,
                    modifier = Modifier.fillMaxWidth().testTag(MeasurementResolverTestTags.WithoutSaving),
                ) { Text("Показать без сохранения") }
                state.ignoreUnknownMeasurements?.let { checked ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = { enabled ->
                                callbacks.onIgnoreUnknownMeasurementsChanged(
                                    state.pending.id,
                                    enabled,
                                )
                            },
                            enabled = !state.operationInProgress,
                            modifier = Modifier.testTag(
                                MeasurementResolverTestTags.IgnoreUnknown,
                            ),
                        )
                        Text(
                            "Всегда игнорировать неизвестные показания",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                OutlinedButton(
                    onClick = { callbacks.onDelete(state.pending.id) },
                    enabled = !state.operationInProgress,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                    modifier = Modifier.fillMaxWidth().testTag(MeasurementResolverTestTags.Delete),
                ) {
                    Text("Удалить")
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(
                onClick = callbacks.onLater,
                enabled = !state.operationInProgress,
                modifier = Modifier.testTag(MeasurementResolverTestTags.Later),
            ) { Text("Позже") }
        },
    )
}

@Composable
private fun ResolverAccountButton(
    option: ResolverAccountOption,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val supportingText = if (option.isCandidate) {
        "Подходит · разница ${formatLocalizedDecimal(requireNotNull(option.differenceKg))} кг"
    } else {
        "Другой аккаунт"
    }
    val primaryDescription = if (option.isPrimary) ". Основной аккаунт" else ""
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(MeasurementResolverTestTags.account(option.accountId))
            .semantics {
                role = Role.Button
                contentDescription = "${option.displayName}. $supportingText$primaryDescription"
            },
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(
            1.dp,
            if (option.isCandidate) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
        color = if (option.isCandidate) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = option.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = supportingText,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (option.isPrimary) Text("Основной", style = MaterialTheme.typography.labelSmall)
        }
    }
}
