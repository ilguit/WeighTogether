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
import com.example.huaweimisync.domain.ProfileHistoryUpdateMode
import com.example.huaweimisync.domain.PetId
import com.example.huaweimisync.domain.PetWithLatestWeight
import com.example.huaweimisync.ui.components.BirthDateField
import com.example.huaweimisync.ui.components.BirthDateSelectionPolicy
import com.example.huaweimisync.ui.components.HuaweiIconButton
import com.example.huaweimisync.ui.components.HuaweiSectionTitle
import com.example.huaweimisync.ui.components.HuaweiSurface
import com.example.huaweimisync.ui.icons.HuaweiIcons
import com.example.huaweimisync.ui.theme.HuaweiColors
import com.example.huaweimisync.ui.theme.HuaweiDimensions
import java.time.LocalDate
import java.time.format.DateTimeFormatter

object AccountManagementTestTags {
    const val List = "account-management-list"
    const val Add = "account-management-add"
    const val AddPet = "profile-management-add-pet"
    const val Empty = "profile-management-empty"
    const val Editor = "account-editor"
    const val EditorBirthDate = "account-editor-birth-date"
    const val EditorSave = "account-editor-save"
    const val DeleteWarning = "account-delete-warning"
    const val DeleteConfirm = "account-delete-confirm"
    const val PrimaryChange = "account-primary-change"
    const val ProfileUpdatePrompt = "account-profile-update-prompt"
    const val ProfileUpdateRecalculate = "account-profile-update-recalculate"
    const val ProfileUpdateKeepExisting = "account-profile-update-keep-existing"
    const val ProfileUpdateCancel = "account-profile-update-cancel"
    const val OperationError = "account-management-operation-error"
    fun row(accountId: AccountId): String = "account-row-${accountId.value}"
    fun primaryBadge(accountId: AccountId): String = "account-primary-badge-${accountId.value}"
    fun replacement(accountId: AccountId): String = "account-replacement-${accountId.value}"
    fun petRow(petId: PetId): String = "profile-management-pet-${petId.value}"
    fun petEdit(petId: PetId): String = "profile-management-pet-edit-${petId.value}"
    fun petDelete(petId: PetId): String = "profile-management-pet-delete-${petId.value}"
    fun humanEdit(accountId: AccountId): String = "profile-management-human-edit-${accountId.value}"
    fun humanDelete(accountId: AccountId): String = "profile-management-human-delete-${accountId.value}"
    fun humanMakePrimary(accountId: AccountId): String =
        "profile-management-human-primary-${accountId.value}"
}

data class AccountManagementCallbacks(
    val onAction: (AccountManagementAction) -> Unit,
    val onCreate: (NewAccount) -> Unit,
    val onUpdate: (AccountUpdate) -> Unit,
    val onConfirmProfileUpdate: (ProfileHistoryUpdateMode) -> Unit,
    val onSetPrimary: (AccountId, PrimaryHistorySyncMode) -> Unit,
    val onDelete: (AccountId) -> Unit,
    val onDeletePrimary: (AccountDeletionRequest) -> Unit,
) {
    companion object {
        val None = AccountManagementCallbacks(
            onAction = {},
            onCreate = {},
            onUpdate = {},
            onConfirmProfileUpdate = {},
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
    pets: List<PetWithLatestWeight> = emptyList(),
    onAddPet: () -> Unit = {},
    onEditPet: (PetWithLatestWeight) -> Unit = {},
    onDeletePet: (PetId) -> Unit = {},
    petSpeciesLabel: (PetWithLatestWeight) -> String = { "Питомец" },
    petWeightLabel: (PetWithLatestWeight) -> String = { "" },
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(HuaweiDimensions.CompactItemSpacing),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HuaweiSectionTitle("Аккаунты и питомцы", Modifier.weight(1f))
            TextButton(
                onClick = { callbacks.onAction(AccountManagementAction.AddRequested) },
                enabled = !state.operationInProgress,
                modifier = Modifier.testTag(AccountManagementTestTags.Add),
            ) { Text("Добавить человека") }
        }
        TextButton(
            onClick = onAddPet,
            enabled = !state.operationInProgress,
            modifier = Modifier.testTag(AccountManagementTestTags.AddPet),
        ) { Text("Добавить питомца") }
        HuaweiSurface(
            modifier = Modifier.fillMaxWidth().testTag(AccountManagementTestTags.List),
            contentPadding = PaddingValues(0.dp),
        ) {
            if (state.accounts.isEmpty() && pets.isEmpty()) {
                Text(
                    text = "Аккаунтов и питомцев пока нет.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(HuaweiDimensions.ContentPadding)
                        .testTag(AccountManagementTestTags.Empty),
                )
            } else {
                Column {
                    state.accounts.forEach { account ->
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
                        if (account != state.accounts.last() || pets.isNotEmpty()) HorizontalDivider()
                    }
                    pets.forEachIndexed { index, pet ->
                        PetProfileRow(
                            pet = pet,
                            enabled = !state.operationInProgress,
                            speciesLabel = petSpeciesLabel(pet),
                            weightLabel = petWeightLabel(pet),
                            onEdit = { onEditPet(pet) },
                            onDelete = { onDeletePet(pet.pet.id) },
                        )
                        if (index != pets.lastIndex) HorizontalDivider()
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
            error = state.operationError,
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
    state.profileUpdateConfirmation?.let { request ->
        ProfileUpdateConfirmationDialog(
            request = request,
            operationInProgress = state.operationInProgress,
            error = state.operationError,
            onRecalculate = { callbacks.onConfirmProfileUpdate(ProfileHistoryUpdateMode.RECALCULATE) },
            onKeepExisting = { callbacks.onConfirmProfileUpdate(ProfileHistoryUpdateMode.KEEP_EXISTING) },
            onCancel = {
                callbacks.onAction(AccountManagementAction.ProfileUpdateConfirmationCancelled)
            },
        )
    }
}

@Composable
private fun ProfileUpdateConfirmationDialog(
    request: ProfileUpdateConfirmation,
    operationInProgress: Boolean,
    error: String?,
    onRecalculate: () -> Unit,
    onKeepExisting: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        modifier = Modifier.testTag(AccountManagementTestTags.ProfileUpdatePrompt),
        onDismissRequest = { if (!operationInProgress) onCancel() },
        title = { Text("Пересчитать историю?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Новые данные профиля могут изменить состав тела в предыдущих измерениях аккаунта «${request.update.displayName}».",
                )
                error?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .testTag(AccountManagementTestTags.OperationError)
                            .semantics { contentDescription = "Ошибка сохранения: $it" },
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onRecalculate,
                enabled = !operationInProgress,
                modifier = Modifier
                    .testTag(AccountManagementTestTags.ProfileUpdateRecalculate)
                    .semantics { contentDescription = "Сохранить и пересчитать историю" },
            ) { Text(if (operationInProgress) "Сохранение…" else "Сохранить и пересчитать") }
        },
        dismissButton = {
            Column(horizontalAlignment = Alignment.End) {
                TextButton(
                    onClick = onKeepExisting,
                    enabled = !operationInProgress,
                    modifier = Modifier
                        .testTag(AccountManagementTestTags.ProfileUpdateKeepExisting)
                        .semantics { contentDescription = "Сохранить без пересчёта истории" },
                ) { Text("Без пересчёта") }
                TextButton(
                    onClick = onCancel,
                    enabled = !operationInProgress,
                    modifier = Modifier
                        .testTag(AccountManagementTestTags.ProfileUpdateCancel)
                        .semantics { contentDescription = "Отменить изменение профиля" },
                ) { Text("Отмена") }
            }
        },
    )
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
                text = "Человек",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelMedium,
            )
            Text(
                text = formatAccountProfile(account.profile),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            if (!isPrimary) {
                TextButton(
                    onClick = onMakePrimary,
                    enabled = enabled,
                    modifier = Modifier.testTag(AccountManagementTestTags.humanMakePrimary(account.id)),
                ) {
                    Text("Сделать основным")
                }
            }
        }
        HuaweiIconButton(
            icon = HuaweiIcons.Edit,
            contentDescription = "Изменить аккаунт ${account.displayName}",
            onClick = onEdit,
            enabled = enabled,
            modifier = Modifier.testTag(AccountManagementTestTags.humanEdit(account.id)),
        )
        HuaweiIconButton(
            icon = HuaweiIcons.Delete,
            contentDescription = "Удалить аккаунт ${account.displayName}",
            onClick = onDelete,
            enabled = enabled,
            modifier = Modifier.testTag(AccountManagementTestTags.humanDelete(account.id)),
        )
    }
}

@Composable
private fun PetProfileRow(
    pet: PetWithLatestWeight,
    enabled: Boolean,
    speciesLabel: String,
    weightLabel: String,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(AccountManagementTestTags.petRow(pet.pet.id))
            .padding(start = 16.dp, top = 12.dp, end = 4.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(pet.pet.displayName, style = MaterialTheme.typography.titleSmall)
            Text("Питомец · $speciesLabel", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
            if (weightLabel.isNotEmpty()) Text(weightLabel, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        HuaweiIconButton(
            icon = HuaweiIcons.Edit,
            contentDescription = "Изменить питомца ${pet.pet.displayName}",
            onClick = onEdit,
            enabled = enabled,
            modifier = Modifier.testTag(AccountManagementTestTags.petEdit(pet.pet.id)),
        )
        HuaweiIconButton(
            icon = HuaweiIcons.Delete,
            contentDescription = "Удалить питомца ${pet.pet.displayName}",
            onClick = onDelete,
            enabled = enabled,
            modifier = Modifier.testTag(AccountManagementTestTags.petDelete(pet.pet.id)),
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
    error: String? = null,
    onDraftChanged: (AccountEditorDraft) -> Unit,
    onCreate: (NewAccount) -> Unit,
    onUpdate: (AccountUpdate) -> Unit,
    onDismiss: () -> Unit,
    today: LocalDate = LocalDate.now(),
) {
    val validation = validateAccountEditor(draft, accounts, today)
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
                BirthDateField(
                    value = draft.birthDate,
                    onValueChange = { birthDate ->
                        onDraftChanged(
                            reduceAccountEditor(
                                draft,
                                AccountEditorAction.BirthDateChanged(birthDate),
                            ),
                        )
                    },
                    selectionPolicy = BirthDateSelectionPolicy.forAccount(today),
                    enabled = !operationInProgress,
                    isError = validation.error(AccountEditorField.BIRTH_DATE) != null,
                    supportingText = validation.error(AccountEditorField.BIRTH_DATE),
                    modifier = Modifier.testTag(AccountManagementTestTags.EditorBirthDate),
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
                error?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .testTag(AccountManagementTestTags.OperationError)
                            .semantics { contentDescription = "Ошибка сохранения: $it" },
                    )
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
