package com.palixander.scalesync.ui.accounts

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.palixander.scalesync.core.Sex
import com.palixander.scalesync.domain.Account
import com.palixander.scalesync.domain.AccountId
import com.palixander.scalesync.domain.AccountProfile
import com.palixander.scalesync.domain.AccountUpdate
import com.palixander.scalesync.domain.NewAccount
import com.palixander.scalesync.domain.PrimaryHistorySyncMode
import com.palixander.scalesync.domain.ProfileHistoryUpdateMode
import com.palixander.scalesync.domain.PetId
import com.palixander.scalesync.domain.PetSpecies
import com.palixander.scalesync.domain.PetWithLatestWeight
import com.palixander.scalesync.ui.components.BirthDateField
import com.palixander.scalesync.ui.components.BirthDateSelectionPolicy
import com.palixander.scalesync.ui.components.HuaweiIconButton
import com.palixander.scalesync.ui.components.HuaweiRowIcon
import com.palixander.scalesync.ui.components.HuaweiSectionTitle
import com.palixander.scalesync.ui.components.HuaweiSurface
import com.palixander.scalesync.ui.icons.HuaweiIcons
import com.palixander.scalesync.ui.theme.HuaweiColors
import com.palixander.scalesync.ui.theme.HuaweiDimensions
import java.time.LocalDate
import java.time.format.DateTimeFormatter

object AccountManagementTestTags {
    const val List = "account-management-list"
    const val Add = "account-management-add"
    const val AddPet = "profile-management-add-pet"
    const val Empty = "profile-management-empty"
    const val PeopleGroup = "profile-management-people-group"
    const val PetsGroup = "profile-management-pets-group"
    const val Editor = "account-editor"
    const val EditorBirthDate = "account-editor-birth-date"
    const val EditorName = "account-editor-name"
    const val EditorHeight = "account-editor-height"
    const val EditorSave = "account-editor-save"
    const val EditorBack = "account-editor-back"
    const val EditorContent = "account-editor-content"
    const val EditorDiscard = "account-editor-discard"
    const val EditorKeepEditing = "account-editor-keep-editing"
    const val EditorMale = "account-editor-male"
    const val EditorFemale = "account-editor-female"
    const val DeleteWarning = "account-delete-warning"
    const val DeleteConfirm = "account-delete-confirm"
    const val PrimaryChange = "account-primary-change"
    const val PrimaryChangeBack = "account-primary-change-back"
    const val PrimaryChangeContent = "account-primary-change-content"
    const val PrimaryChangeContinue = "account-primary-change-continue"
    const val PrimaryChangeFutureOnly = "account-primary-change-future-only"
    const val PrimaryChangeIncludeHistory = "account-primary-change-include-history"
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
    fun humanMenu(accountId: AccountId): String = "profile-management-human-menu-${accountId.value}"
    fun petMenu(petId: PetId): String = "profile-management-pet-menu-${petId.value}"
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
    val sortedAccounts = state.accounts.sortedWith(
        compareByDescending<Account> { it.id == state.primaryAccountId }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.displayName },
    )
    val sortedPets = pets.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.pet.displayName })
    Column(
        modifier = modifier.fillMaxWidth().testTag(AccountManagementTestTags.List),
        verticalArrangement = Arrangement.spacedBy(HuaweiDimensions.CompactItemSpacing),
    ) {
        HuaweiSectionTitle("Профили")
        HuaweiSurface(
            modifier = Modifier.fillMaxWidth().testTag(AccountManagementTestTags.PeopleGroup),
            contentPadding = PaddingValues(0.dp),
        ) {
            if (state.accounts.isEmpty()) {
                Text(
                    text = "Профилей пока нет.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(HuaweiDimensions.ContentPadding)
                        .then(
                            if (pets.isEmpty()) Modifier.testTag(AccountManagementTestTags.Empty)
                            else Modifier,
                        ),
                )
            } else {
                Column {
                    sortedAccounts.forEach { account ->
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
                        if (account != sortedAccounts.last()) HorizontalDivider()
                    }
                }
            }
        }
        TextButton(
            onClick = { callbacks.onAction(AccountManagementAction.AddRequested) },
            enabled = !state.operationInProgress,
            modifier = Modifier.align(Alignment.End).testTag(AccountManagementTestTags.Add),
        ) { Text("Добавить профиль") }
        HuaweiSectionTitle("Питомцы")
        HuaweiSurface(
            modifier = Modifier.fillMaxWidth().testTag(AccountManagementTestTags.PetsGroup),
            contentPadding = PaddingValues(0.dp),
        ) {
            if (pets.isEmpty()) {
                Text(
                    text = "Питомцев пока нет.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(HuaweiDimensions.ContentPadding),
                )
            } else Column {
                sortedPets.forEachIndexed { index, pet ->
                    PetProfileRow(
                        pet = pet,
                        enabled = !state.operationInProgress,
                        speciesLabel = petSpeciesLabel(pet),
                        weightLabel = petWeightLabel(pet),
                        onEdit = { onEditPet(pet) },
                        onDelete = { onDeletePet(pet.pet.id) },
                    )
                    if (index != sortedPets.lastIndex) HorizontalDivider()
                }
            }
        }
        TextButton(
            onClick = onAddPet,
            enabled = !state.operationInProgress,
            modifier = Modifier.align(Alignment.End).testTag(AccountManagementTestTags.AddPet),
        ) { Text("Добавить питомца") }
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
            error = state.operationError,
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
                    "Новые данные профиля могут изменить состав тела в предыдущих измерениях профиля «${request.update.displayName}».",
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
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onRecalculate,
                    enabled = !operationInProgress,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(AccountManagementTestTags.ProfileUpdateRecalculate)
                        .semantics { contentDescription = "Сохранить и пересчитать историю" },
                ) {
                    Text(if (operationInProgress) "Сохранение…" else "Сохранить и пересчитать")
                }
                OutlinedButton(
                    onClick = onKeepExisting,
                    enabled = !operationInProgress,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(AccountManagementTestTags.ProfileUpdateKeepExisting)
                        .semantics { contentDescription = "Сохранить без пересчёта истории" },
                ) { Text("Сохранить без пересчёта") }
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
    var menuExpanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(AccountManagementTestTags.row(account.id))
            .clickable(enabled = enabled, role = Role.Button, onClick = onEdit)
            .padding(start = 16.dp, top = 12.dp, end = 4.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HuaweiRowIcon(
            icon = when (account.profile.sex) {
                Sex.MALE -> HuaweiIcons.Male
                Sex.FEMALE -> HuaweiIcons.Female
                else -> HuaweiIcons.Profile
            },
            contentDescription = when (account.profile.sex) {
                Sex.MALE -> "Мужской пол"
                Sex.FEMALE -> "Женский пол"
                else -> "Пол не указан"
            },
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = account.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f, fill = false),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (isPrimary) PrimaryBadge(account.id)
            }
        }
        Box {
            HuaweiIconButton(
                icon = HuaweiIcons.More,
                contentDescription = "Действия с профилем ${account.displayName}",
                onClick = { menuExpanded = true },
                enabled = enabled,
                modifier = Modifier.testTag(AccountManagementTestTags.humanMenu(account.id)),
            )
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                if (!isPrimary) DropdownMenuItem(
                    text = { Text("Сделать основным") },
                    onClick = { menuExpanded = false; onMakePrimary() },
                    modifier = Modifier.testTag(AccountManagementTestTags.humanMakePrimary(account.id)),
                )
                DropdownMenuItem(
                    text = { Text("Удалить") },
                    onClick = { menuExpanded = false; onDelete() },
                    modifier = Modifier.testTag(AccountManagementTestTags.humanDelete(account.id)),
                )
            }
        }
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
    var menuExpanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(AccountManagementTestTags.petRow(pet.pet.id))
            .clickable(enabled = enabled, role = Role.Button, onClick = onEdit)
            .padding(start = 16.dp, top = 12.dp, end = 4.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HuaweiRowIcon(
            icon = when (pet.pet.species) {
                PetSpecies.CAT -> HuaweiIcons.Cat
                PetSpecies.DOG -> HuaweiIcons.Dog
                else -> HuaweiIcons.Profile
            },
            contentDescription = speciesLabel,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = pet.pet.displayName,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = weightLabel.ifEmpty { "—" },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                modifier = if (weightLabel.isEmpty()) Modifier.semantics {
                    contentDescription = "Вес пока не измерен"
                } else Modifier,
            )
        }
        Box {
            HuaweiIconButton(
                icon = HuaweiIcons.More,
                contentDescription = "Действия с питомцем ${pet.pet.displayName}",
                onClick = { menuExpanded = true },
                enabled = enabled,
                modifier = Modifier.testTag(AccountManagementTestTags.petMenu(pet.pet.id)),
            )
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                DropdownMenuItem(
                    text = { Text("Удалить") },
                    onClick = { menuExpanded = false; onDelete() },
                    modifier = Modifier.testTag(AccountManagementTestTags.petDelete(pet.pet.id)),
                )
            }
        }
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
            .semantics { contentDescription = "Основной профиль" },
    ) {
        Text(
            text = "Основной",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
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
    val initialDraft = remember(draft.editingAccountId) { draft }
    var discardConfirmationVisible by remember(draft.editingAccountId) { mutableStateOf(false) }
    var validationRequested by remember(draft.editingAccountId) { mutableStateOf(false) }
    var submitAttempt by remember(draft.editingAccountId) { mutableStateOf(0) }
    val nameFocusRequester = remember { FocusRequester() }
    val sexFocusRequester = remember { FocusRequester() }
    val birthDateFocusRequester = remember { FocusRequester() }
    val heightFocusRequester = remember { FocusRequester() }
    LaunchedEffect(submitAttempt) {
        if (submitAttempt == 0 || validation.isValid) return@LaunchedEffect
        val target = when {
            validation.error(AccountEditorField.NAME) != null -> nameFocusRequester
            validation.error(AccountEditorField.SEX) != null -> sexFocusRequester
            validation.error(AccountEditorField.BIRTH_DATE) != null -> birthDateFocusRequester
            else -> heightFocusRequester
        }
        target.requestFocus()
    }
    val requestDismiss = {
        if (!operationInProgress) {
            if (draft == initialDraft) onDismiss() else discardConfirmationVisible = true
        }
    }

    Dialog(
        onDismissRequest = requestDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize().testTag(AccountManagementTestTags.Editor),
            color = MaterialTheme.colorScheme.background,
        ) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            Text(
                                if (draft.editingAccountId == null) "Новый профиль"
                                else "Изменить профиль",
                            )
                        },
                        navigationIcon = {
                            HuaweiIconButton(
                                icon = HuaweiIcons.Back,
                                contentDescription = "Назад",
                                onClick = requestDismiss,
                                enabled = !operationInProgress,
                                modifier = Modifier.testTag(AccountManagementTestTags.EditorBack),
                            )
                        },
                    )
                },
                bottomBar = {
                    Surface(shadowElevation = 3.dp) {
                        Button(
                            onClick = {
                                validationRequested = true
                                submitAttempt++
                                if (validation.isValid) {
                                    draft.toNewAccountOrNull(validation)?.let(onCreate)
                                        ?: draft.toAccountUpdateOrNull(validation)?.let(onUpdate)
                                }
                            },
                            enabled = !operationInProgress,
                            modifier = Modifier
                                .fillMaxWidth()
                                .navigationBarsPadding()
                                .imePadding()
                                .padding(horizontal = 16.dp, vertical = 12.dp)
                                .heightIn(min = 48.dp)
                                .testTag(AccountManagementTestTags.EditorSave),
                        ) {
                            if (operationInProgress) {
                                CircularProgressIndicator(
                                    modifier = Modifier.padding(end = 8.dp),
                                    strokeWidth = 2.dp,
                                )
                                Text("Сохранение…")
                            } else {
                                Text("Сохранить")
                            }
                        }
                    }
                },
            ) { contentPadding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(contentPadding)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 24.dp)
                        .testTag(AccountManagementTestTags.EditorContent),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                OutlinedTextField(
                    value = draft.name,
                    onValueChange = { onDraftChanged(reduceAccountEditor(draft, AccountEditorAction.NameChanged(it))) },
                    label = { Text("Имя") },
                    singleLine = true,
                    enabled = !operationInProgress,
                    isError = validationRequested && validation.error(AccountEditorField.NAME) != null,
                    supportingText = validation.error(AccountEditorField.NAME)
                        ?.takeIf { validationRequested }?.let { message ->
                        { Text(message) }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(nameFocusRequester)
                        .testTag(AccountManagementTestTags.EditorName),
                )
                Text("Пол", style = MaterialTheme.typography.labelLarge)
                BoxWithConstraints {
                    val showSexIcons = maxWidth >= 360.dp && LocalDensity.current.fontScale <= 1.3f
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SexChoice(
                        label = "Мужчина",
                        icon = HuaweiIcons.Male.takeIf { showSexIcons },
                        value = Sex.MALE,
                        selectedSex = draft.sex,
                        enabled = !operationInProgress,
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(sexFocusRequester)
                            .testTag(AccountManagementTestTags.EditorMale),
                    ) {
                        onDraftChanged(reduceAccountEditor(draft, AccountEditorAction.SexChanged(it)))
                    }
                    SexChoice(
                        label = "Женщина",
                        icon = HuaweiIcons.Female.takeIf { showSexIcons },
                        value = Sex.FEMALE,
                        selectedSex = draft.sex,
                        enabled = !operationInProgress,
                        modifier = Modifier.weight(1f).testTag(AccountManagementTestTags.EditorFemale),
                    ) {
                        onDraftChanged(reduceAccountEditor(draft, AccountEditorAction.SexChanged(it)))
                    }
                    }
                }
                validation.error(AccountEditorField.SEX)?.takeIf { validationRequested }?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
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
                    isError = validationRequested && validation.error(AccountEditorField.BIRTH_DATE) != null,
                    supportingText = validation.error(AccountEditorField.BIRTH_DATE)
                        ?.takeIf { validationRequested },
                    modifier = Modifier
                        .focusRequester(birthDateFocusRequester)
                        .testTag(AccountManagementTestTags.EditorBirthDate),
                )
                OutlinedTextField(
                    value = draft.heightCm,
                    onValueChange = { onDraftChanged(reduceAccountEditor(draft, AccountEditorAction.HeightChanged(it))) },
                    label = { Text("Рост, см") },
                    singleLine = true,
                    enabled = !operationInProgress,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = validationRequested && validation.error(AccountEditorField.HEIGHT) != null,
                    supportingText = validation.error(AccountEditorField.HEIGHT)
                        ?.takeIf { validationRequested }?.let { message ->
                        { Text(message) }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(heightFocusRequester)
                        .testTag(AccountManagementTestTags.EditorHeight),
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
            }
        }
    }
    if (discardConfirmationVisible) {
        AlertDialog(
            onDismissRequest = { discardConfirmationVisible = false },
            title = { Text("Отказаться от изменений?") },
            text = { Text("Введённые данные не сохранятся.") },
            confirmButton = {
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.testTag(AccountManagementTestTags.EditorDiscard),
                ) { Text("Отказаться") }
            },
            dismissButton = {
                TextButton(
                    onClick = { discardConfirmationVisible = false },
                    modifier = Modifier.testTag(AccountManagementTestTags.EditorKeepEditing),
                ) { Text("Продолжить редактирование") }
            },
        )
    }
}

@Composable
private fun SexChoice(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector?,
    value: Sex,
    selectedSex: Sex?,
    enabled: Boolean,
    modifier: Modifier = Modifier,
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
        modifier = modifier.heightIn(min = 48.dp).semantics {
            role = Role.RadioButton
            this.selected = selected
        },
    ) {
        icon?.let {
            Icon(it, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
        }
        Text(label)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PrimaryAccountChangeDialog(
    request: PrimaryAccountChangeRequest,
    account: Account?,
    operationInProgress: Boolean,
    error: String?,
    onModeChanged: (PrimaryHistorySyncMode) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = { if (!operationInProgress) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize().testTag(AccountManagementTestTags.PrimaryChange),
            color = MaterialTheme.colorScheme.background,
        ) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text("Сделать основным") },
                        navigationIcon = {
                            HuaweiIconButton(
                                icon = HuaweiIcons.Back,
                                contentDescription = "Назад",
                                onClick = onDismiss,
                                enabled = !operationInProgress,
                                modifier = Modifier.testTag(AccountManagementTestTags.PrimaryChangeBack),
                            )
                        },
                    )
                },
                bottomBar = {
                    Surface(shadowElevation = 3.dp) {
                        Button(
                            onClick = onConfirm,
                            enabled = account != null && !operationInProgress,
                            modifier = Modifier
                                .fillMaxWidth()
                                .navigationBarsPadding()
                                .padding(horizontal = 16.dp, vertical = 12.dp)
                                .heightIn(min = 48.dp)
                                .testTag(AccountManagementTestTags.PrimaryChangeContinue),
                        ) {
                            if (operationInProgress) {
                                CircularProgressIndicator(
                                    modifier = Modifier.padding(end = 8.dp),
                                    strokeWidth = 2.dp,
                                )
                                Text("Применение…")
                            } else {
                                Text("Продолжить")
                            }
                        }
                    }
                },
            ) { contentPadding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(contentPadding)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 24.dp)
                        .testTag(AccountManagementTestTags.PrimaryChangeContent),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        "Профиль «${account?.displayName.orEmpty()}» станет основным. " +
                            "Внешняя синхронизация будет доступна только для него.",
                    )
                    SyncModeChoices(request.historySyncMode, onModeChanged, !operationInProgress)
                    Text(
                        "Уже отправленные данные прежнего основного профиля не удаляются.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    error?.let {
                        Text(
                            text = it,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier
                                .testTag(AccountManagementTestTags.OperationError)
                                .semantics { contentDescription = "Ошибка изменения профиля: $it" },
                        )
                    }
                }
            }
        }
    }
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
        title = { Text("Удалить профиль «${account?.displayName.orEmpty()}»?") },
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
                        "Локальная история профиля будет удалена безвозвратно. Записи во внешних сервисах останутся.",
                        modifier = Modifier.padding(12.dp),
                    )
                }
                if (requiresReplacement) {
                    Text("Новый основной профиль", style = MaterialTheme.typography.labelLarge)
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
                    Text("После удаления последнего профиля основного профиля не будет.")
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
        modifier = Modifier.testTag(AccountManagementTestTags.PrimaryChangeFutureOnly),
        onClick = { onModeChanged(PrimaryHistorySyncMode.FUTURE_ONLY) },
    )
    SelectionRow(
        label = "Добавить локальную историю",
        selected = selectedMode == PrimaryHistorySyncMode.INCLUDE_ELIGIBLE_HISTORY,
        enabled = enabled,
        modifier = Modifier.testTag(AccountManagementTestTags.PrimaryChangeIncludeHistory),
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
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
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
