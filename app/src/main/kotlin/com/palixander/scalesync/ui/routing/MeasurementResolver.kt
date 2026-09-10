package com.palixander.scalesync.ui.routing

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.palixander.scalesync.R
import com.palixander.scalesync.domain.AccountId
import com.palixander.scalesync.measurements.formatMeasurementDateTime
import com.palixander.scalesync.ui.accounts.formatLocalizedDecimal
import com.palixander.scalesync.ui.theme.HuaweiColors
import com.palixander.scalesync.ui.theme.HuaweiDimensions

object MeasurementResolverTestTags {
    const val Dialog = "measurement-resolver"
    const val CreateAccount = "measurement-resolver-create-account"
    const val WithoutSaving = "measurement-resolver-without-saving"
    const val Delete = "measurement-resolver-delete"
    const val IgnoreUnknown = "measurement-resolver-ignore-unknown"
    const val Later = "measurement-resolver-later"
    const val Progress = "measurement-resolver-progress"
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
    val progressDescription = stringResource(R.string.measurement_resolver_progress)
    AlertDialog(
        modifier = modifier.testTag(MeasurementResolverTestTags.Dialog),
        onDismissRequest = {
            if (!state.operationInProgress) callbacks.onLater()
        },
        title = { Text(stringResource(R.string.measurement_resolver_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(HuaweiDimensions.CompactItemSpacing),
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                ) {
                    Column(Modifier.padding(HuaweiDimensions.CompactContentPadding)) {
                        Text(
                            "${formatLocalizedDecimal(state.pending.weightKg)} кг",
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(
                            formatMeasurementDateTime(state.pending.measuredAt),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                Text(
                    stringResource(R.string.measurement_resolver_assignment_question),
                    style = MaterialTheme.typography.titleSmall,
                )
                state.accountOptions.forEachIndexed { index, option ->
                    ResolverAccountButton(
                        option = option,
                        recommended = index == 0 && option.isCandidate,
                        enabled = !state.operationInProgress,
                        onClick = {
                            callbacks.onAccountSelected(state.pending.id, option.accountId)
                        },
                    )
                }
                OutlinedButton(
                    onClick = { callbacks.onShowWithoutSaving(state.pending.id) },
                    enabled = !state.operationInProgress,
                    modifier = Modifier.fillMaxWidth().testTag(MeasurementResolverTestTags.WithoutSaving),
                ) { Text(stringResource(R.string.measurement_resolver_preview)) }
                OutlinedButton(
                    onClick = { callbacks.onCreateAccount(state.pending.id) },
                    enabled = !state.operationInProgress,
                    modifier = Modifier.fillMaxWidth().testTag(MeasurementResolverTestTags.CreateAccount),
                ) { Text(stringResource(R.string.measurement_resolver_create_profile)) }
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
                            stringResource(R.string.measurement_resolver_ignore_unknown),
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
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth().testTag(MeasurementResolverTestTags.Delete),
                ) {
                    Text(stringResource(R.string.measurement_resolver_delete))
                }
                if (state.operationInProgress) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .size(24.dp)
                                .testTag(MeasurementResolverTestTags.Progress)
                                .semantics {
                                    contentDescription = progressDescription
                                },
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(
                onClick = callbacks.onLater,
                enabled = !state.operationInProgress,
                modifier = Modifier.fillMaxWidth().testTag(MeasurementResolverTestTags.Later),
            ) { Text(stringResource(R.string.measurement_resolver_later)) }
        },
    )
}

@Composable
private fun ResolverAccountButton(
    option: ResolverAccountOption,
    recommended: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val recommendation = stringResource(R.string.measurement_resolver_recommended)
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(MeasurementResolverTestTags.account(option.accountId))
            .semantics {
                contentDescription = buildString {
                    append(option.displayName)
                    if (recommended) append(". $recommendation")
                    if (option.isPrimary) append(". Основной профиль")
                }
            },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = option.displayName, modifier = Modifier.weight(1f))
            if (recommended) {
                Text(
                    text = recommendation,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}
