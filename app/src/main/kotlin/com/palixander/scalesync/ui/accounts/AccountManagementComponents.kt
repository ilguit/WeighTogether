package com.palixander.scalesync.ui.accounts

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.selectableGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
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
import com.palixander.scalesync.ui.components.HuaweiSectionTitle
import com.palixander.scalesync.ui.components.HuaweiSurface
import com.palixander.scalesync.ui.icons.HuaweiIcons
import com.palixander.scalesync.ui.theme.HuaweiColors
import com.palixander.scalesync.ui.theme.HuaweiDimensions
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

object AccountManagementTestTags {
    const val List = "account-management-list"
    const val Add = "account-management-add"
    const val AddPet = "profile-management-add-pet"
    const val Empty = "profile-management-empty"
    const val PeopleGroup = "profile-management-people-group"
    const val PetsGroup = "profile-management-pets-group"
    const val Editor = "account-editor"
    const val EditorTitle = "account-editor-title"
    const val EditorBack = "account-editor-back"
    const val EditorName = "account-editor-name"
    const val EditorSexMale = "account-editor-sex-male"
    const val EditorSexFemale = "account-editor-sex-female"
    const val EditorSexGroup = "account-editor-sex-group"
    const val EditorHeight = "account-editor-height"
    const val EditorBirthDate = "account-editor-birth-date"
    const val EditorSave = "account-editor-save"
    const val EditorDiscardPrompt = "account-editor-discard-prompt"
    const val EditorDiscardConfirm = "account-editor-discard-confirm"
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
                    sortedAccounts(state.accounts, state.primaryAccountId)
                        .forEachIndexed { index, account ->
                        AccountRow(
                            account = account,
                            isPrimary = account.id == state.primaryAccountId,
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
        OutlinedButton(
            onClick = { callbacks.onAction(AccountManagementAction.AddRequested) },
            modifier = Modifier.fillMaxWidth().testTag(AccountManagementTestTags.Add),
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
                pets.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.pet.displayName })
                    .forEachIndexed { index, pet ->
                    PetProfileRow(
                        pet = pet,
                        speciesLabel = petSpeciesLabel(pet),
                        weightLabel = petWeightLabel(pet),
                        onEdit = { onEditPet(pet) },
                        onDelete = { onDeletePet(pet.pet.id) },
                    )
                    if (index != pets.lastIndex) HorizontalDivider()
                }
            }
        }
        OutlinedButton(
            onClick = onAddPet,
            modifier = Modifier.fillMaxWidth().testTag(AccountManagementTestTags.AddPet),
        ) { Text("Добавить питомца") }
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
    onEdit: () -> Unit,
    onMakePrimary: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(AccountManagementTestTags.row(account.id))
            .clickable(onClick = onEdit)
            .padding(start = 16.dp, top = 12.dp, end = 4.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val completeProfile = account.profile as? AccountProfile.Complete
        ProfileGlyph(
            glyph = when (completeProfile?.sex) {
                Sex.MALE -> "♂"
                Sex.FEMALE -> "♀"
                null -> "?"
            },
            description = when (completeProfile?.sex) {
                Sex.MALE -> "Мужчина"
                Sex.FEMALE -> "Женщина"
                null -> "Профиль"
            },
            modifier = Modifier.padding(end = 10.dp),
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
                )
                if (isPrimary) PrimaryBadge(account.id)
            }
        }
        HuaweiIconButton(
            icon = HuaweiIcons.More,
            contentDescription = "Дополнительные действия для ${account.displayName}",
            onClick = { menuExpanded = true },
            modifier = Modifier.testTag(AccountManagementTestTags.humanEdit(account.id)),
        )
        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
            DropdownMenuItem(
                text = { Text("Изменить") },
                onClick = { menuExpanded = false; onEdit() },
            )
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

@Composable
private fun PetProfileRow(
    pet: PetWithLatestWeight,
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
            .clickable(onClick = onEdit)
            .padding(start = 16.dp, top = 12.dp, end = 4.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProfileGlyph(
            glyph = when (pet.pet.species) {
                PetSpecies.CAT -> "🐱"
                PetSpecies.DOG -> "🐶"
                PetSpecies.UNSPECIFIED -> "🐾"
            },
            description = speciesLabel,
            modifier = Modifier.padding(end = 10.dp),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(pet.pet.displayName, style = MaterialTheme.typography.titleSmall)
            if (weightLabel.isNotEmpty()) Text(weightLabel, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        HuaweiIconButton(
            icon = HuaweiIcons.More,
            contentDescription = "Дополнительные действия для ${pet.pet.displayName}",
            onClick = { menuExpanded = true },
            modifier = Modifier.testTag(AccountManagementTestTags.petEdit(pet.pet.id)),
        )
        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
            DropdownMenuItem(
                text = { Text("Изменить") },
                onClick = { menuExpanded = false; onEdit() },
            )
            DropdownMenuItem(
                text = { Text("Удалить") },
                onClick = { menuExpanded = false; onDelete() },
                modifier = Modifier.testTag(AccountManagementTestTags.petDelete(pet.pet.id)),
            )
        }
    }
}

@Composable
private fun ProfileGlyph(
    glyph: String,
    description: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .size(40.dp)
            .semantics { contentDescription = description },
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(glyph, style = MaterialTheme.typography.titleLarge)
        }
    }
}

private fun sortedAccounts(accounts: List<Account>, primaryAccountId: AccountId?): List<Account> =
    accounts.sortedWith(
        compareBy<Account> { it.id != primaryAccountId }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.displayName },
    )

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
fun AccountEditorScreen(
    draft: AccountEditorDraft,
    accounts: List<Account>,
    operationInProgress: Boolean,
    error: String? = null,
    onDraftChanged: (AccountEditorDraft) -> Unit,
    onCreate: (NewAccount) -> Unit,
    onUpdate: (AccountUpdate) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    today: LocalDate = LocalDate.now(),
) {
    val validation = validateAccountEditor(draft, accounts, today)
    val initialDraft = remember(draft.editingAccountId) {
        draft.editingAccountId?.let { id -> accounts.firstOrNull { it.id == id } }
            ?.let(AccountEditorDraft::edit) ?: AccountEditorDraft.add()
    }
    var validationRequested by remember(draft.editingAccountId) { mutableStateOf(false) }
    var discardRequested by remember(draft.editingAccountId) { mutableStateOf(false) }
    var saveSubmitted by remember(draft.editingAccountId) { mutableStateOf(false) }
    val nameFocus = remember { FocusRequester() }
    val titleFocus = remember { FocusRequester() }
    val sexFocus = remember { FocusRequester() }
    val birthDateFocus = remember { FocusRequester() }
    val heightFocus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        titleFocus.requestFocus()
    }
    LaunchedEffect(operationInProgress, error) {
        if (!operationInProgress && error != null) saveSubmitted = false
    }
    val requestClose = {
        if (!operationInProgress) {
            if (draft == initialDraft) onDismiss() else discardRequested = true
        }
    }
    BackHandler(enabled = !operationInProgress, onBack = requestClose)
    Scaffold(
        modifier = modifier.fillMaxSize().testTag(AccountManagementTestTags.Editor),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (draft.editingAccountId == null) "Новый профиль" else "Изменить профиль",
                        modifier = Modifier
                            .focusRequester(titleFocus)
                            .focusable()
                            .testTag(AccountManagementTestTags.EditorTitle)
                            .semantics { heading() },
                    )
                },
                navigationIcon = {
                    HuaweiIconButton(
                        icon = HuaweiIcons.Back,
                        contentDescription = "Вернуться к профилям",
                        onClick = requestClose,
                        modifier = Modifier.testTag(AccountManagementTestTags.EditorBack),
                    )
                },
            )
        },
        bottomBar = {
            Surface(shadowElevation = 8.dp) {
                Button(
                    onClick = {
                        validationRequested = true
                        if (validation.isValid) {
                            if (saveSubmitted) return@Button
                            saveSubmitted = true
                            draft.toNewAccountOrNull(validation)?.let(onCreate)
                                ?: draft.toAccountUpdateOrNull(validation)?.let(onUpdate)
                        } else scope.launch {
                            when {
                                validation.error(AccountEditorField.NAME) != null -> nameFocus
                                validation.error(AccountEditorField.SEX) != null -> sexFocus
                                validation.error(AccountEditorField.BIRTH_DATE) != null -> birthDateFocus
                                validation.error(AccountEditorField.HEIGHT) != null -> heightFocus
                                else -> null
                            }?.requestFocus()
                        }
                    },
                    enabled = !operationInProgress && !saveSubmitted,
                    modifier = Modifier.fillMaxWidth()
                        .padding(HuaweiDimensions.ContentPadding)
                        .testTag(AccountManagementTestTags.EditorSave),
                ) { Text(if (operationInProgress) "Сохранение…" else "Сохранить") }
            }
        },
    ) { contentPadding ->
        Box(Modifier.fillMaxSize().padding(contentPadding), contentAlignment = Alignment.TopCenter) {
            Column(
                modifier = Modifier.fillMaxWidth().widthIn(max = 720.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(HuaweiDimensions.ContentPadding),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = draft.name,
                    onValueChange = { onDraftChanged(reduceAccountEditor(draft, AccountEditorAction.NameChanged(it))) },
                    label = { Text("Имя") },
                    singleLine = true,
                    enabled = !operationInProgress,
                    isError = validationRequested && validation.error(AccountEditorField.NAME) != null,
                    supportingText = validation.error(AccountEditorField.NAME).takeIf { validationRequested }?.let { message ->
                        { Text(message) }
                    },
                    modifier = Modifier.fillMaxWidth().focusRequester(nameFocus)
                        .testTag(AccountManagementTestTags.EditorName),
                )
                Text("Пол", style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(AccountManagementTestTags.EditorSexGroup)
                        .semantics {
                            contentDescription = "Пол"
                            selectableGroup()
                        },
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SexChoice("♂ Мужчина", Sex.MALE, draft.sex, !operationInProgress,
                        Modifier.weight(1f).focusRequester(sexFocus)
                            .testTag(AccountManagementTestTags.EditorSexMale)) {
                        onDraftChanged(reduceAccountEditor(draft, AccountEditorAction.SexChanged(it)))
                    }
                    SexChoice("♀ Женщина", Sex.FEMALE, draft.sex, !operationInProgress,
                        Modifier.weight(1f).testTag(AccountManagementTestTags.EditorSexFemale)) {
                        onDraftChanged(reduceAccountEditor(draft, AccountEditorAction.SexChanged(it)))
                    }
                }
                validation.error(AccountEditorField.SEX).takeIf { validationRequested }?.let {
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
                    supportingText = validation.error(AccountEditorField.BIRTH_DATE).takeIf { validationRequested },
                    modifier = Modifier.focusRequester(birthDateFocus)
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
                    supportingText = validation.error(AccountEditorField.HEIGHT).takeIf { validationRequested }?.let { message ->
                        { Text(message) }
                    },
                    modifier = Modifier.fillMaxWidth().focusRequester(heightFocus)
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
    if (discardRequested) AlertDialog(
        modifier = Modifier.testTag(AccountManagementTestTags.EditorDiscardPrompt),
        onDismissRequest = { discardRequested = false },
        title = { Text("Отменить изменения?") },
        text = { Text("Несохранённые изменения профиля будут потеряны.") },
        confirmButton = {
            TextButton(onClick = onDismiss,
                modifier = Modifier.testTag(AccountManagementTestTags.EditorDiscardConfirm)) {
                Text("Отменить изменения")
            }
        },
        dismissButton = {
            TextButton(onClick = { discardRequested = false }) { Text("Продолжить редактирование") }
        },
    )
}

@Composable
private fun SexChoice(
    label: String,
    value: Sex,
    selectedSex: Sex?,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onSelect: (Sex) -> Unit,
) {
    val selected = selectedSex == value
    val choiceModifier = modifier.semantics {
        role = Role.RadioButton
        this.selected = selected
    }
    if (selected) {
        Button(
            onClick = { onSelect(value) },
            enabled = enabled,
            modifier = choiceModifier,
        ) { Text(label) }
    } else {
        OutlinedButton(
            onClick = { onSelect(value) },
            enabled = enabled,
            modifier = choiceModifier,
        ) { Text(label) }
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
                    "Уже отправленные данные прежнего основного профиля не удаляются.",
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
