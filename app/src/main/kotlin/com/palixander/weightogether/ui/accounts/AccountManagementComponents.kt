package com.palixander.weightogether.ui.accounts

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.selectableGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.palixander.weightogether.R
import com.palixander.weightogether.core.Sex
import com.palixander.weightogether.domain.Account
import com.palixander.weightogether.domain.AccountId
import com.palixander.weightogether.domain.AccountProfile
import com.palixander.weightogether.domain.AccountUpdate
import com.palixander.weightogether.domain.NewAccount
import com.palixander.weightogether.domain.PrimaryHistorySyncMode
import com.palixander.weightogether.domain.ProfileHistoryUpdateMode
import com.palixander.weightogether.domain.PetId
import com.palixander.weightogether.domain.PetSpecies
import com.palixander.weightogether.domain.PetWithLatestWeight
import com.palixander.weightogether.ui.components.BirthDateField
import com.palixander.weightogether.ui.components.BirthDateSelectionPolicy
import com.palixander.weightogether.ui.components.ScaleSyncIconButton
import com.palixander.weightogether.ui.components.ScaleSyncSectionTitle
import com.palixander.weightogether.ui.components.ScaleSyncSurface
import com.palixander.weightogether.ui.components.EditableProfileAvatar
import com.palixander.weightogether.ui.components.ProfileAvatar
import com.palixander.weightogether.ui.components.currentProfilePhotoStore
import com.palixander.weightogether.ui.icons.ScaleSyncIcons
import com.palixander.weightogether.profile.ProfilePhotoError
import com.palixander.weightogether.profile.PreparedProfilePhoto
import com.palixander.weightogether.profile.ProfilePhotoPicker
import com.palixander.weightogether.profile.ProfilePhotoStore
import com.palixander.weightogether.profile.ProfilePhotoOwner
import com.palixander.weightogether.profile.ProfilePhotoOwnerType
import com.palixander.weightogether.profile.rememberProfilePhotoCropController
import com.palixander.weightogether.profile.rememberProfilePhotoPicker
import com.palixander.weightogether.ui.theme.ScaleSyncColors
import com.palixander.weightogether.ui.theme.ScaleSyncDimensions
import com.palixander.weightogether.ui.text.resolve
import com.palixander.weightogether.ui.text.UiText
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch
import com.palixander.weightogether.domain.WeighingReminderOwner
import com.palixander.weightogether.ui.reminder.ReminderSettingsEntry
import com.palixander.weightogether.ui.reminder.ReminderSettingsScreen

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
    const val EditorPhoto = "account-editor-photo"
    const val EditorPhotoGallery = "account-editor-photo-gallery"
    const val EditorPhotoCamera = "account-editor-photo-camera"
    const val EditorPhotoRemove = "account-editor-photo-remove"
    const val EditorSave = "account-editor-save"
    const val EditorDiscardPrompt = "account-editor-discard-prompt"
    const val EditorDiscardConfirm = "account-editor-discard-confirm"
    const val ReminderGuard = "account-editor-reminder-guard"
    const val EditorReminder = "account-editor-reminder"
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
    val onUpdateAndContinue: (AccountUpdate) -> Unit,
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
            onUpdateAndContinue = {},
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
    petSpeciesLabel: ((PetWithLatestWeight) -> String)? = null,
    petWeightLabel: (PetWithLatestWeight) -> String = { "" },
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().testTag(AccountManagementTestTags.List),
        verticalArrangement = Arrangement.spacedBy(ScaleSyncDimensions.CompactItemSpacing),
    ) {
        ScaleSyncSectionTitle(stringResource(R.string.account_profiles_title))
        ScaleSyncSurface(
            modifier = Modifier.fillMaxWidth().testTag(AccountManagementTestTags.PeopleGroup),
            contentPadding = PaddingValues(0.dp),
        ) {
            if (state.accounts.isEmpty()) {
                Text(
                    text = stringResource(R.string.account_profiles_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(ScaleSyncDimensions.ContentPadding)
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
        ) { Text(stringResource(R.string.account_add_profile)) }
        ScaleSyncSectionTitle(stringResource(R.string.account_pets_title))
        ScaleSyncSurface(
            modifier = Modifier.fillMaxWidth().testTag(AccountManagementTestTags.PetsGroup),
            contentPadding = PaddingValues(0.dp),
        ) {
            if (pets.isEmpty()) {
                Text(
                    text = stringResource(R.string.account_pets_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(ScaleSyncDimensions.ContentPadding),
                )
            } else Column {
                pets.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.pet.displayName })
                    .forEachIndexed { index, pet ->
                    PetProfileRow(
                        pet = pet,
                    speciesLabel = petSpeciesLabel?.invoke(pet) ?: stringResource(R.string.account_pet),
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
        ) { Text(stringResource(R.string.account_add_pet)) }
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
    error: UiText?,
    onRecalculate: () -> Unit,
    onKeepExisting: () -> Unit,
    onCancel: () -> Unit,
) {
    val resources = LocalResources.current
    val saveErrorDescription = stringResource(R.string.account_save_error_cd, error?.resolve(resources).orEmpty())
    val recalculateDescription = stringResource(R.string.account_save_recalculate_cd)
    val keepExistingDescription = stringResource(R.string.account_save_without_recalculate_cd)
    val cancelDescription = stringResource(R.string.account_cancel_update_cd)
    AlertDialog(
        modifier = Modifier.testTag(AccountManagementTestTags.ProfileUpdatePrompt),
        onDismissRequest = { if (!operationInProgress) onCancel() },
        title = { Text(stringResource(R.string.account_recalculate_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.account_recalculate_message, request.update.displayName),
                )
                error?.let { message ->
                    val resolvedError = message.resolve(resources)
                    Text(
                        text = resolvedError,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .testTag(AccountManagementTestTags.OperationError)
                            .semantics {
                                contentDescription = saveErrorDescription
                            },
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
                        .semantics {
                            contentDescription = recalculateDescription
                        },
                ) {
                    Text(stringResource(if (operationInProgress) R.string.state_saving else R.string.account_save_recalculate))
                }
                OutlinedButton(
                    onClick = onKeepExisting,
                    enabled = !operationInProgress,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(AccountManagementTestTags.ProfileUpdateKeepExisting)
                        .semantics {
                            contentDescription = keepExistingDescription
                        },
                ) { Text(stringResource(R.string.account_save_without_recalculate)) }
                TextButton(
                    onClick = onCancel,
                    enabled = !operationInProgress,
                    modifier = Modifier
                        .testTag(AccountManagementTestTags.ProfileUpdateCancel)
                        .semantics {
                            contentDescription = cancelDescription
                        },
                ) { Text(stringResource(R.string.action_cancel)) }
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
    val photoStore = currentProfilePhotoStore()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(AccountManagementTestTags.row(account.id))
            .clickable(onClick = onEdit)
            .padding(start = 16.dp, top = 12.dp, end = 4.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val completeProfile = account.profile as? AccountProfile.Complete
        ProfileAvatar(
            photoPath = account.photoPath,
            fallbackIcon = ScaleSyncIcons.Profile,
            contentDescription = when (completeProfile?.sex) {
                Sex.MALE -> stringResource(R.string.account_man)
                Sex.FEMALE -> stringResource(R.string.account_woman)
                null -> stringResource(R.string.account_profile)
            },
            store = photoStore,
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
        ScaleSyncIconButton(
            icon = ScaleSyncIcons.More,
            contentDescription = stringResource(R.string.account_more_actions, account.displayName),
            onClick = { menuExpanded = true },
            modifier = Modifier.testTag(AccountManagementTestTags.humanEdit(account.id)),
        )
        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_edit)) },
                onClick = { menuExpanded = false; onEdit() },
            )
            if (!isPrimary) DropdownMenuItem(
                text = { Text(stringResource(R.string.account_make_primary)) },
                onClick = { menuExpanded = false; onMakePrimary() },
                modifier = Modifier.testTag(AccountManagementTestTags.humanMakePrimary(account.id)),
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_delete)) },
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
    val photoStore = currentProfilePhotoStore()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(AccountManagementTestTags.petRow(pet.pet.id))
            .clickable(onClick = onEdit)
            .padding(start = 16.dp, top = 12.dp, end = 4.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProfileAvatar(
            photoPath = pet.pet.photoPath,
            fallbackIcon = when (pet.pet.species) {
                PetSpecies.CAT -> ScaleSyncIcons.Cat
                PetSpecies.DOG -> ScaleSyncIcons.Dog
                PetSpecies.UNSPECIFIED -> ScaleSyncIcons.Profile
            },
            contentDescription = speciesLabel,
            store = photoStore,
            modifier = Modifier.padding(end = 10.dp),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(pet.pet.displayName, style = MaterialTheme.typography.titleSmall)
            if (weightLabel.isNotEmpty()) Text(weightLabel, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        ScaleSyncIconButton(
            icon = ScaleSyncIcons.More,
            contentDescription = stringResource(R.string.account_more_actions, pet.pet.displayName),
            onClick = { menuExpanded = true },
            modifier = Modifier.testTag(AccountManagementTestTags.petEdit(pet.pet.id)),
        )
        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_edit)) },
                onClick = { menuExpanded = false; onEdit() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_delete)) },
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
    val primaryDescription = stringResource(R.string.account_primary_profile)
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier
            .testTag(AccountManagementTestTags.primaryBadge(accountId))
            .semantics { contentDescription = primaryDescription },
    ) {
        Text(
            text = stringResource(R.string.account_primary),
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
    error: UiText? = null,
    onDraftChanged: (AccountEditorDraft) -> Unit,
    onCreate: (NewAccount) -> Unit,
    onUpdate: (AccountUpdate) -> Unit,
    onUpdateAndContinue: (AccountUpdate) -> Unit = onUpdate,
    openRemindersAfterSave: Boolean = false,
    onOpenRemindersAfterSaveRequested: () -> Unit = {},
    onOpenRemindersAfterSaveConsumed: () -> Unit = {},
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    today: LocalDate = LocalDate.now(),
    profilePhotoStore: ProfilePhotoStore? = currentProfilePhotoStore(),
    photoPickerFactory: @Composable (
        ProfilePhotoStore,
        (PreparedProfilePhoto) -> Unit,
        (ProfilePhotoError) -> Unit,
    ) -> ProfilePhotoPicker = { store, onPrepared, onError ->
        rememberProfilePhotoPicker(store, onPrepared, onError)
    },
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val sexDescription = stringResource(R.string.account_sex)
    val validation = validateAccountEditor(draft, accounts, today)
    val initialDraft = remember(draft.editingAccountId) {
        draft.editingAccountId?.let { id -> accounts.firstOrNull { it.id == id } }
            ?.let(AccountEditorDraft::edit) ?: AccountEditorDraft.add()
    }
    var validationRequested by remember(draft.editingAccountId) { mutableStateOf(false) }
    var discardRequested by remember(draft.editingAccountId) { mutableStateOf(false) }
    var saveSubmitted by remember(draft.editingAccountId) { mutableStateOf(false) }
    var remindersOpen by remember(draft.editingAccountId) { mutableStateOf(false) }
    var reminderGuardOpen by remember(draft.editingAccountId) { mutableStateOf(false) }
    val nameFocus = remember { FocusRequester() }
    val titleFocus = remember { FocusRequester() }
    val sexFocus = remember { FocusRequester() }
    val birthDateFocus = remember { FocusRequester() }
    val heightFocus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val photoStore = profilePhotoStore
    var photoError by remember(draft.editingAccountId) { mutableStateOf<ProfilePhotoError?>(null) }
    val newPhotoOwnerId = rememberSaveable(draft.editingAccountId) {
        "new-account-${java.util.UUID.randomUUID()}"
    }
    val photoOwner = remember(draft.editingAccountId, newPhotoOwnerId) {
        ProfilePhotoOwner(
            ProfilePhotoOwnerType.ACCOUNT,
            draft.editingAccountId?.value ?: newPhotoOwnerId,
        )
    }
    fun deleteTransientPhoto(path: String?) {
        if (path != null && path != initialDraft.photoPath) {
            scope.launch { runCatching { photoStore?.onPhotoDereferenced(path) } }
        }
    }
    val photoCrop = photoStore?.let { store ->
        rememberProfilePhotoCropController(
            store = store,
            owner = photoOwner,
            onPhotoReady = { path ->
                photoError = null
                deleteTransientPhoto(draft.photoPath)
                onDraftChanged(reduceAccountEditor(draft, AccountEditorAction.PhotoChanged(path)))
            },
            onError = { error -> photoError = error },
        )
    }
    val photoPicker = photoStore?.let { store ->
        photoPickerFactory(
            store,
            { photoCrop?.open(it) },
            { error -> photoError = error },
        )
    }
    LaunchedEffect(Unit) {
        titleFocus.requestFocus()
    }
    LaunchedEffect(operationInProgress, error) {
        if (!operationInProgress && error != null) saveSubmitted = false
    }
    LaunchedEffect(openRemindersAfterSave, operationInProgress, error, accounts) {
        if (openRemindersAfterSave && !operationInProgress && error == null &&
            draft.editingAccountId?.let { id -> accounts.firstOrNull { it.id == id } }
                ?.let(AccountEditorDraft::edit) == draft
        ) {
            onOpenRemindersAfterSaveConsumed()
            remindersOpen = true
        }
    }
    if (remindersOpen) {
        ReminderSettingsScreen(
            owner = WeighingReminderOwner.Account(requireNotNull(draft.editingAccountId)),
            onBack = { remindersOpen = false },
        )
        return
    }
    val requestClose = {
        if (!operationInProgress) {
            if (draft == initialDraft) onDismiss() else discardRequested = true
        }
    }
    BackHandler(onBack = requestClose)
    Scaffold(
        modifier = modifier.fillMaxSize().imePadding().testTag(AccountManagementTestTags.Editor),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(if (draft.editingAccountId == null) R.string.account_new_profile else R.string.account_edit_profile),
                        modifier = Modifier
                            .focusRequester(titleFocus)
                            .focusable()
                            .testTag(AccountManagementTestTags.EditorTitle)
                            .semantics { heading() },
                    )
                },
                navigationIcon = {
                    ScaleSyncIconButton(
                        icon = ScaleSyncIcons.Back,
                        contentDescription = stringResource(R.string.account_back_to_profiles),
                        onClick = requestClose,
                        enabled = !operationInProgress,
                        modifier = Modifier.testTag(AccountManagementTestTags.EditorBack),
                    )
                },
            )
        },
        bottomBar = {
            Surface(
                modifier = Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars),
                color = MaterialTheme.colorScheme.background,
                shadowElevation = 2.dp,
            ) {
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
                        .padding(horizontal = ScaleSyncDimensions.ContentPadding, vertical = 12.dp)
                        .heightIn(min = ScaleSyncDimensions.TouchTarget)
                        .testTag(AccountManagementTestTags.EditorSave),
                    shape = MaterialTheme.shapes.medium,
                ) { Text(stringResource(if (operationInProgress) R.string.state_saving else R.string.action_save)) }
            }
        },
    ) { contentPadding ->
        Box(Modifier.fillMaxSize().padding(contentPadding), contentAlignment = Alignment.TopCenter) {
            Column(
                modifier = Modifier.fillMaxWidth().widthIn(max = 720.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(ScaleSyncDimensions.ContentPadding),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                EditableProfileAvatar(
                    photoPath = draft.photoPath,
                    fallbackIcon = ScaleSyncIcons.Profile,
                    contentDescription = stringResource(R.string.account_profile_photo),
                    store = photoStore,
                    size = 96.dp,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                        .testTag(AccountManagementTestTags.EditorPhoto),
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = { photoPicker?.chooseFromGallery?.invoke() },
                        enabled = !operationInProgress && photoPicker != null,
                        modifier = Modifier.heightIn(min = ScaleSyncDimensions.TouchTarget)
                            .testTag(AccountManagementTestTags.EditorPhotoGallery),
                    ) { Text(stringResource(R.string.account_gallery)) }
                    OutlinedButton(
                        onClick = { photoPicker?.takePhoto?.invoke() },
                        enabled = !operationInProgress && photoPicker != null,
                        modifier = Modifier.heightIn(min = ScaleSyncDimensions.TouchTarget)
                            .testTag(AccountManagementTestTags.EditorPhotoCamera),
                    ) { Text(stringResource(R.string.account_camera)) }
                    if (draft.photoPath != null) OutlinedButton(
                        onClick = {
                            deleteTransientPhoto(draft.photoPath)
                            onDraftChanged(reduceAccountEditor(draft, AccountEditorAction.PhotoChanged(null)))
                        },
                        enabled = !operationInProgress,
                        modifier = Modifier.heightIn(min = ScaleSyncDimensions.TouchTarget)
                            .testTag(AccountManagementTestTags.EditorPhotoRemove),
                    ) { Text(stringResource(R.string.account_remove_photo)) }
                }
                photoError?.let { Text(stringResource(it.messageRes), color = MaterialTheme.colorScheme.error) }
                OutlinedTextField(
                    value = draft.name,
                    onValueChange = { onDraftChanged(reduceAccountEditor(draft, AccountEditorAction.NameChanged(it))) },
                    label = { Text(stringResource(R.string.account_name)) },
                    singleLine = true,
                    enabled = !operationInProgress,
                    isError = validationRequested && validation.error(AccountEditorField.NAME) != null,
                    supportingText = validation.error(AccountEditorField.NAME).takeIf { validationRequested }?.let { message ->
                        { Text(message.resolve(resources)) }
                    },
                    modifier = Modifier.fillMaxWidth().focusRequester(nameFocus)
                        .testTag(AccountManagementTestTags.EditorName),
                )
                Text(stringResource(R.string.account_sex), style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min)
                        .testTag(AccountManagementTestTags.EditorSexGroup)
                        .semantics {
                            contentDescription = sexDescription
                            selectableGroup()
                        },
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SexChoice(stringResource(R.string.account_male_choice), Sex.MALE, draft.sex, !operationInProgress,
                        Modifier.weight(1f).fillMaxHeight().focusRequester(sexFocus)
                            .testTag(AccountManagementTestTags.EditorSexMale)) {
                        onDraftChanged(reduceAccountEditor(draft, AccountEditorAction.SexChanged(it)))
                    }
                    SexChoice(stringResource(R.string.account_female_choice), Sex.FEMALE, draft.sex, !operationInProgress,
                        Modifier.weight(1f).fillMaxHeight()
                            .testTag(AccountManagementTestTags.EditorSexFemale)) {
                        onDraftChanged(reduceAccountEditor(draft, AccountEditorAction.SexChanged(it)))
                    }
                }
                validation.error(AccountEditorField.SEX).takeIf { validationRequested }?.let {
                    Text(it.resolve(resources), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
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
                    supportingText = validation.error(AccountEditorField.BIRTH_DATE).takeIf { validationRequested }
                        ?.resolve(resources),
                    modifier = Modifier.focusRequester(birthDateFocus)
                        .testTag(AccountManagementTestTags.EditorBirthDate),
                )
                OutlinedTextField(
                    value = draft.heightCm,
                    onValueChange = { onDraftChanged(reduceAccountEditor(draft, AccountEditorAction.HeightChanged(it))) },
                    label = { Text(stringResource(R.string.profile_height_label)) },
                    singleLine = true,
                    enabled = !operationInProgress,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = validationRequested && validation.error(AccountEditorField.HEIGHT) != null,
                    supportingText = validation.error(AccountEditorField.HEIGHT).takeIf { validationRequested }?.let { message ->
                        { Text(message.resolve(resources)) }
                    },
                    modifier = Modifier.fillMaxWidth().focusRequester(heightFocus)
                        .testTag(AccountManagementTestTags.EditorHeight),
                )
                error?.let { message ->
                    val resolvedError = message.resolve(resources)
                    val saveErrorDescription = stringResource(R.string.account_save_error_cd, resolvedError)
                    Text(
                        text = resolvedError,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .testTag(AccountManagementTestTags.OperationError)
                            .semantics {
                                contentDescription = saveErrorDescription
                            },
                    )
                }
                draft.editingAccountId?.let { accountId ->
                    Box(Modifier.testTag(AccountManagementTestTags.EditorReminder)) {
                        ReminderSettingsEntry(
                            owner = WeighingReminderOwner.Account(accountId),
                            onClick = {
                                if (draft == initialDraft) remindersOpen = true else reminderGuardOpen = true
                            },
                        )
                    }
                }
            }
        }
    }
    if (discardRequested) AlertDialog(
        modifier = Modifier.testTag(AccountManagementTestTags.EditorDiscardPrompt),
        onDismissRequest = { discardRequested = false },
        title = { Text(stringResource(R.string.account_discard_title)) },
        text = { Text(stringResource(R.string.account_discard_message)) },
        confirmButton = {
            TextButton(onClick = {
                deleteTransientPhoto(draft.photoPath)
                onDismiss()
            },
                modifier = Modifier.testTag(AccountManagementTestTags.EditorDiscardConfirm)) {
                Text(stringResource(R.string.account_discard))
            }
        },
        dismissButton = {
            TextButton(onClick = { discardRequested = false }) { Text(stringResource(R.string.account_continue_editing)) }
        },
    )
    if (reminderGuardOpen) AlertDialog(
        modifier = Modifier.testTag(AccountManagementTestTags.ReminderGuard),
        onDismissRequest = { reminderGuardOpen = false },
        title = { Text(stringResource(R.string.reminder_unsaved_title)) },
        text = { Text(stringResource(R.string.reminder_unsaved_text)) },
        confirmButton = {
            TextButton(onClick = {
                val update = draft.toAccountUpdateOrNull(validation)
                if (update != null) {
                    reminderGuardOpen = false
                    onOpenRemindersAfterSaveRequested()
                    onUpdateAndContinue(update)
                } else validationRequested = true
            }) { Text(stringResource(R.string.reminder_save_continue)) }
        },
        dismissButton = {
            Column {
                TextButton(onClick = {
                    reminderGuardOpen = false
                    onDraftChanged(initialDraft)
                    remindersOpen = true
                }) { Text(stringResource(R.string.reminder_discard_continue)) }
                TextButton(onClick = { reminderGuardOpen = false }) { Text(stringResource(R.string.reminder_keep_editing)) }
            }
        },
    )
}

private val ProfilePhotoError.messageRes: Int get() = when (this) {
    ProfilePhotoError.UNREADABLE_SOURCE -> R.string.account_photo_unreadable
    ProfilePhotoError.INVALID_IMAGE -> R.string.account_photo_invalid
    ProfilePhotoError.PROCESSING_FAILED -> R.string.account_photo_processing_failed
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
        title = { Text(stringResource(R.string.account_make_primary_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(
                        R.string.account_primary_sync_message,
                        account?.displayName.orEmpty(),
                    ),
                )
                SyncModeChoices(request.historySyncMode, onModeChanged, !operationInProgress)
                Text(
                    stringResource(R.string.account_primary_existing_data_message),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, enabled = account != null && !operationInProgress) {
                Text(stringResource(R.string.action_continue))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !operationInProgress) {
                Text(stringResource(R.string.action_cancel))
            }
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
        title = {
            Text(stringResource(R.string.account_delete_title, account?.displayName.orEmpty()))
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Surface(
                    color = ScaleSyncColors.WarningContainer,
                    contentColor = ScaleSyncColors.OnWarningContainer,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Text(
                        stringResource(R.string.account_delete_message),
                        modifier = Modifier.padding(12.dp),
                    )
                }
                if (requiresReplacement) {
                    Text(
                        stringResource(R.string.account_replacement_title),
                        style = MaterialTheme.typography.labelLarge,
                    )
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
                    Text(stringResource(R.string.account_delete_last_primary_message))
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = account != null && !operationInProgress &&
                    (!requiresReplacement || replacementIsValid),
                modifier = Modifier.testTag(AccountManagementTestTags.DeleteConfirm),
            ) { Text(stringResource(R.string.action_delete)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !operationInProgress) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun SyncModeChoices(
    selectedMode: PrimaryHistorySyncMode,
    onModeChanged: (PrimaryHistorySyncMode) -> Unit,
    enabled: Boolean,
) {
    Text(
        stringResource(R.string.account_primary_history_title),
        style = MaterialTheme.typography.labelLarge,
    )
    SelectionRow(
        label = stringResource(R.string.account_sync_future_only),
        selected = selectedMode == PrimaryHistorySyncMode.FUTURE_ONLY,
        enabled = enabled,
        onClick = { onModeChanged(PrimaryHistorySyncMode.FUTURE_ONLY) },
    )
    SelectionRow(
        label = stringResource(R.string.account_sync_eligible_history),
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

@Composable
private fun formatAccountProfile(profile: AccountProfile): String = when (profile) {
    is AccountProfile.Complete -> {
        val sex = stringResource(
            if (profile.sex == Sex.MALE) R.string.sex_male else R.string.sex_female,
        )
        stringResource(
            R.string.account_profile_summary,
            formatLocalizedDecimal(profile.heightCm),
            profile.birthDate.format(AccountDateFormatter),
            sex,
        )
    }
    is AccountProfile.IncompleteRecovery -> stringResource(R.string.account_profile_incomplete)
}
