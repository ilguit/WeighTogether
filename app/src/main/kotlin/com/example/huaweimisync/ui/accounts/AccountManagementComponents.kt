package com.example.huaweimisync.ui.accounts

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.huaweimisync.core.Sex
import com.example.huaweimisync.domain.Account
import com.example.huaweimisync.domain.AccountId
import com.example.huaweimisync.domain.AccountProfile
import com.example.huaweimisync.domain.AccountUpdate
import com.example.huaweimisync.domain.NewAccount
import com.example.huaweimisync.domain.PrimaryHistorySyncMode
import com.example.huaweimisync.ui.components.HuaweiIconButton
import com.example.huaweimisync.ui.components.HuaweiSectionTitle
import com.example.huaweimisync.ui.components.HuaweiSurface
import com.example.huaweimisync.ui.icons.HuaweiIcons
import com.example.huaweimisync.ui.theme.HuaweiColors
import com.example.huaweimisync.ui.theme.HuaweiDimensions
import java.time.format.DateTimeFormatter

object AccountManagementTestTags {
    const val List = "account-management-list"
    const val Add = "account-management-add"
    const val Editor = "account-editor"
    const val EditorSave = "account-editor-save"
    const val DeleteWarning = "account-delete-warning"
    const val DeleteConfirm = "account-delete-confirm"
    const val PrimaryChange = "account-primary-change"
    fun row(accountId: AccountId): String = "account-row-${accountId.value}"
    fun primaryBadge(accountId: AccountId): String = "account-primary-badge-${accountId.value}"
    fun replacement(accountId: AccountId): String = "account-replacement-${accountId.value}"
}

data class AccountManagementCallbacks(
    val onAction: (AccountManagementAction) -> Unit,
    val onCreate: (NewAccount) -> Unit,
    val onUpdate: (AccountUpdate) -> Unit,
    val onSetPrimary: (AccountId, PrimaryHistorySyncMode) -> Unit,
    val onDelete: (AccountId) -> Unit,
    val onDeletePrimary: (AccountDeletionRequest) -> Unit,
) {
    companion object {
        val None = AccountManagementCallbacks(
            onAction = {},
            onCreate = {},
            onUpdate = {},
            onSetPrimary = { _, _ -> },
            onDelete = {},
            onDeletePrimary = {},
        )
    }
}

@Composable
fun AccountManagementSection(
    state: AccountManagementUiState,
    callbacks: AccountManagementCallbacks,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(HuaweiDimensions.CompactItemSpacing),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HuaweiSectionTitle("Аккаунты", Modifier.weight(1f))
            TextButton(
                onClick = { callbacks.onAction(AccountManagementAction.AddRequested) },
                enabled = !state.operationInProgress,
                modifier = Modifier.testTag(AccountManagementTestTags.Add),
            ) { Text("Добавить") }
        }
        HuaweiSurface(
            modifier = Modifier.fillMaxWidth().testTag(AccountManagementTestTags.List),
            contentPadding = PaddingValues(0.dp),
        ) {
            if (state.accounts.isEmpty()) {
                Text(
                    text = "Аккаунтов пока нет. Добавьте профиль для расчёта состава тела.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(HuaweiDimensions.ContentPadding),
                )
            } else {
                Column {
                    state.accounts.forEachIndexed { index, account ->
                        AccountRow(
                            account = account,
                            isPrimary = account.id == state.primaryAccountId,
                            enabled = !state.operationInProgress,
                            onEdit = {
                                callbacks.onAction(AccountManagementAction.EditRequested(account.id))
                            },
                            onMakePrimary = {
                                callbacks.onAction(
                                    AccountManagementAction.MakePrimaryRequested(account.id),
                                )
                            },
                            onDelete = {
                                callbacks.onAction(AccountManagementAction.DeleteRequested(account.id))
                            },
                        )
                        if (index != state.accounts.lastIndex) HorizontalDivider()
                    }
                }
            }
        }
    }

    state.editor?.let { draft ->
        AccountEditorDialog(
            draft = draft,
            accounts = state.accounts,
            operationInProgress = state.operationInProgress,
            onDraftChanged = { callbacks.onAction(AccountManagementAction.EditorChanged(it)) },
            onCreate = callbacks.onCreate,
            onUpdate = callbacks.onUpdate,
            onDismiss = { callbacks.onAction(AccountManagementAction.DialogDismissed) },
        )
    }
    state.primaryChange?.let { request ->
        PrimaryAccountChangeDialog(
            request = request,
            account = state.accounts.firstOrNull { it.id == request.accountId },
            operationInProgress = state.operationInProgress,
            onModeChanged = {
                callbacks.onAction(AccountManagementAction.SyncModeSelected(it))
            },
            onConfirm = { callbacks.onSetPrimary(request.accountId, request.historySyncMode) },
            onDismiss = { callbacks.onAction(AccountManagementAction.DialogDismissed) },
        )
    }
    state.deletion?.let { request ->
        AccountDeletionDialog(
            request = request,
            accounts = state.accounts,
            operationInProgress = state.operationInProgress,
            onReplacementChanged = {
                callbacks.onAction(AccountManagementAction.ReplacementSelected(it))
            },
            onModeChanged = {
                callbacks.onAction(AccountManagementAction.SyncModeSelected(it))
            },
            onConfirm = {
                if (request.wasPrimary) callbacks.onDeletePrimary(request)
                else callbacks.onDelete(request.accountId)
            },
            onDismiss = { callbacks.onAction(AccountManagementAction.DialogDismissed) },
        )
    }
}

@Composable
private fun AccountRow(
    account: Account,
    isPrimary: Boolean,
    enabled: Boolean,
    onEdit: () -> Unit,
    onMakePrimary: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(AccountManagementTestTags.row(account.id))
            .padding(start = 16.dp, top = 12.dp, end = 4.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = account.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (isPrimary) PrimaryBadge(account.id)
            }
            Text(
                text = formatAccountProfile(account.profile),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            if (!isPrimary) {
                TextButton(onClick = onMakePrimary, enabled = enabled) {
                    Text("Сделать основным")
                }
            }
        }
        HuaweiIconButton(
            icon = HuaweiIcons.Edit,
            contentDescription = "Изменить аккаунт ${account.displayName}",
            onClick = onEdit,
            enabled = enabled,
        )
        HuaweiIconButton(
            icon = HuaweiIcons.Delete,
            contentDescription = "Удалить аккаунт ${account.displayName}",
            onClick = onDelete,
            enabled = enabled,
        )
    }
}

@Composable
private fun PrimaryBadge(accountId: AccountId) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier
            .testTag(AccountManagementTestTags.primaryBadge(accountId))
            .semantics { contentDescription = "Основной аккаунт" },
    ) {
        Text(
            text = "Основной",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
fun AccountEditorDialog(
    draft: AccountEditorDraft,
    accounts: List<Account>,
    operationInProgress: Boolean,
    onDraftChanged: (AccountEditorDraft) -> Unit,
    onCreate: (NewAccount) -> Unit,
    onUpdate: (AccountUpdate) -> Unit,
    onDismiss: () -> Unit,
) {
    val validation = validateAccountEditor(draft, accounts)
    AlertDialog(
        modifier = Modifier.testTag(AccountManagementTestTags.Editor),
        onDismissRequest = { if (!operationInProgress) onDismiss() },
        title = { Text(if (draft.editingAccountId == null) "Новый аккаунт" else "Изменить аккаунт") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = draft.name,
                    onValueChange = { onDraftChanged(reduceAccountEditor(draft, AccountEditorAction.NameChanged(it))) },
                    label = { Text("Имя") },
                    singleLine = true,
                    enabled = !operationInProgress,
                    isError = validation.error(AccountEditorField.NAME) != null,
                    supportingText = validation.error(AccountEditorField.NAME)?.let { message ->
                        { Text(message) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = draft.heightCm,
                    onValueChange = { onDraftChanged(reduceAccountEditor(draft, AccountEditorAction.HeightChanged(it))) },
                    label = { Text("Рост, см") },
                    singleLine = true,
                    enabled = !operationInProgress,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = validation.error(AccountEditorField.HEIGHT) != null,
                    supportingText = validation.error(AccountEditorField.HEIGHT)?.let { message ->
                        { Text(message) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = draft.birthDate?.format(EditorDateFormatter).orEmpty(),
                    onValueChange = { value ->
                        val birthDate = value.takeIf(String::isNotBlank)?.let(::parseProfileDate)
                        onDraftChanged(
                            reduceAccountEditor(
                                draft,
                                AccountEditorAction.BirthDateChanged(birthDate),
                            ),
                        )
                    },
                    label = { Text("Дата рождения") },
                    placeholder = { Text("ДД.ММ.ГГГГ") },
                    singleLine = true,
                    enabled = !operationInProgress,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = validation.error(AccountEditorField.BIRTH_DATE) != null,
                    supportingText = validation.error(AccountEditorField.BIRTH_DATE)?.let { message ->
                        { Text(message) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Пол", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SexChoice("Мужской", Sex.MALE, draft.sex, !operationInProgress) {
                        onDraftChanged(reduceAccountEditor(draft, AccountEditorAction.SexChanged(it)))
                    }
                    SexChoice("Женский", Sex.FEMALE, draft.sex, !operationInProgress) {
                        onDraftChanged(reduceAccountEditor(draft, AccountEditorAction.SexChanged(it)))
                    }
                }
                validation.error(AccountEditorField.SEX)?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    draft.toNewAccountOrNull(validation)?.let(onCreate)
                        ?: draft.toAccountUpdateOrNull(validation)?.let(onUpdate)
                },
                enabled = validation.isValid && !operationInProgress,
                modifier = Modifier.testTag(AccountManagementTestTags.EditorSave),
            ) { Text("Сохранить") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !operationInProgress) { Text("Отмена") }
        },
    )
}

private val EditorDateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.uuuu")

@Composable
private fun SexChoice(
    label: String,
    value: Sex,
    selectedSex: Sex?,
    enabled: Boolean,
    onSelect: (Sex) -> Unit,
) {
    val selected = selectedSex == value
    OutlinedButton(
        onClick = { onSelect(value) },
        enabled = enabled,
        border = BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        ),
        modifier = Modifier.semantics {
            role = Role.RadioButton
            this.selected = selected
        },
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Text(label)
    }
}

@Composable
private fun PrimaryAccountChangeDialog(
    request: PrimaryAccountChangeRequest,
    account: Account?,
    operationInProgress: Boolean,
    onModeChanged: (PrimaryHistorySyncMode) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        modifier = Modifier.testTag(AccountManagementTestTags.PrimaryChange),
        onDismissRequest = { if (!operationInProgress) onDismiss() },
        title = { Text("Сделать основным") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Внешняя синхронизация будет доступна только для «${account?.displayName.orEmpty()}».")
                SyncModeChoices(request.historySyncMode, onModeChanged, !operationInProgress)
                Text(
                    "Уже отправленные данные прежнего основного аккаунта не удаляются.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, enabled = account != null && !operationInProgress) {
                Text("Продолжить")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !operationInProgress) { Text("Отмена") }
        },
    )
}

@Composable
private fun AccountDeletionDialog(
    request: AccountDeletionRequest,
    accounts: List<Account>,
    operationInProgress: Boolean,
    onReplacementChanged: (AccountId) -> Unit,
    onModeChanged: (PrimaryHistorySyncMode) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val account = accounts.firstOrNull { it.id == request.accountId }
    val replacements = accounts.filterNot { it.id == request.accountId }
    val requiresReplacement = request.wasPrimary && replacements.isNotEmpty()
    val replacementIsValid = replacements.any { it.id == request.replacementAccountId }
    AlertDialog(
        modifier = Modifier.testTag(AccountManagementTestTags.DeleteWarning),
        onDismissRequest = { if (!operationInProgress) onDismiss() },
        title = { Text("Удалить аккаунт «${account?.displayName.orEmpty()}»?") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Surface(
                    color = HuaweiColors.WarningContainer,
                    contentColor = HuaweiColors.OnWarningContainer,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Text(
                        "Локальная история аккаунта будет удалена безвозвратно. Записи во внешних сервисах останутся.",
                        modifier = Modifier.padding(12.dp),
                    )
                }
                if (requiresReplacement) {
                    Text("Новый основной аккаунт", style = MaterialTheme.typography.labelLarge)
                    replacements.forEach { replacement ->
                        SelectionRow(
                            label = replacement.displayName,
                            selected = replacement.id == request.replacementAccountId,
                            modifier = Modifier.testTag(
                                AccountManagementTestTags.replacement(replacement.id),
                            ),
                            enabled = !operationInProgress,
                            onClick = { onReplacementChanged(replacement.id) },
                        )
                    }
                    SyncModeChoices(request.historySyncMode, onModeChanged, !operationInProgress)
                } else if (request.wasPrimary) {
                    Text("После удаления последнего аккаунта основного аккаунта не будет.")
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = account != null && !operationInProgress &&
                    (!requiresReplacement || replacementIsValid),
                modifier = Modifier.testTag(AccountManagementTestTags.DeleteConfirm),
            ) { Text("Удалить") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !operationInProgress) { Text("Отмена") }
        },
    )
}

@Composable
private fun SyncModeChoices(
    selectedMode: PrimaryHistorySyncMode,
    onModeChanged: (PrimaryHistorySyncMode) -> Unit,
    enabled: Boolean,
) {
    Text("История нового основного", style = MaterialTheme.typography.labelLarge)
    SelectionRow(
        label = "Только новые измерения",
        selected = selectedMode == PrimaryHistorySyncMode.FUTURE_ONLY,
        enabled = enabled,
        onClick = { onModeChanged(PrimaryHistorySyncMode.FUTURE_ONLY) },
    )
    SelectionRow(
        label = "Синхронизировать подходящую историю",
        selected = selectedMode == PrimaryHistorySyncMode.INCLUDE_ELIGIBLE_HISTORY,
        enabled = enabled,
        onClick = { onModeChanged(PrimaryHistorySyncMode.INCLUDE_ELIGIBLE_HISTORY) },
    )
}

@Composable
private fun SelectionRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().semantics {
            role = Role.RadioButton
            this.selected = selected
        },
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = null, enabled = enabled)
            Text(label, Modifier.padding(start = 8.dp))
        }
    }
}

private val AccountDateFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")

private fun formatAccountProfile(profile: AccountProfile): String = when (profile) {
    is AccountProfile.Complete -> {
        val sex = if (profile.sex == Sex.MALE) "мужской" else "женский"
        "${formatLocalizedDecimal(profile.heightCm)} см · ${profile.birthDate.format(AccountDateFormatter)} · $sex"
    }
    is AccountProfile.IncompleteRecovery -> "Профиль нужно заполнить"
}
