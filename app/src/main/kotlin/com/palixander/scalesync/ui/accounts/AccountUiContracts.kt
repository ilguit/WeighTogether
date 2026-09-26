package com.palixander.scalesync.ui.accounts

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import com.palixander.scalesync.R
import com.palixander.scalesync.core.Sex
import com.palixander.scalesync.domain.ACCOUNT_NAME_LENGTH
import com.palixander.scalesync.domain.Account
import com.palixander.scalesync.domain.AccountId
import com.palixander.scalesync.domain.AccountProfile
import com.palixander.scalesync.domain.AccountUpdate
import com.palixander.scalesync.domain.NewAccount
import com.palixander.scalesync.domain.PrimaryHistorySyncMode
import com.palixander.scalesync.domain.normalizeAccountName
import com.palixander.scalesync.ui.text.UiText
import com.palixander.scalesync.ui.text.uiText
import java.time.LocalDate

@Immutable
data class AccountEditorDraft(
    val editingAccountId: AccountId? = null,
    val name: String = "",
    val heightCm: String = "",
    val birthDate: LocalDate? = null,
    val sex: Sex? = null,
    val photoPath: String? = null,
) {
    companion object {
        fun add(): AccountEditorDraft = AccountEditorDraft()

        fun edit(account: Account): AccountEditorDraft = AccountEditorDraft(
            editingAccountId = account.id,
            name = account.displayName,
            heightCm = account.profile.heightCm?.let(::formatLocalizedDecimal).orEmpty(),
            birthDate = account.profile.birthDate,
            sex = account.profile.sex,
            photoPath = account.photoPath,
        )
    }
}

val AccountEditorDraftSaver: Saver<AccountEditorDraft, Any> = listSaver(
    save = { draft ->
        listOf(
            draft.editingAccountId?.value.orEmpty(),
            draft.name,
            draft.heightCm,
            draft.birthDate?.toEpochDay() ?: MissingBirthDateEpochDay,
            draft.sex?.name.orEmpty(),
            draft.photoPath.orEmpty(),
        )
    },
    restore = { saved ->
        AccountEditorDraft(
            editingAccountId = (saved[0] as String).takeIf(String::isNotEmpty)?.let(::AccountId),
            name = saved[1] as String,
            heightCm = saved[2] as String,
            birthDate = (saved[3] as Long)
                .takeUnless { it == MissingBirthDateEpochDay }
                ?.let(LocalDate::ofEpochDay),
            sex = (saved[4] as String).takeIf(String::isNotEmpty)?.let(Sex::valueOf),
            photoPath = (saved[5] as String).takeIf(String::isNotEmpty),
        )
    },
)

private const val MissingBirthDateEpochDay = Long.MIN_VALUE

enum class AccountEditorField {
    NAME,
    HEIGHT,
    BIRTH_DATE,
    SEX,
}

@Immutable
data class AccountEditorValidation(
    val errors: Map<AccountEditorField, UiText> = emptyMap(),
    val normalizedName: String? = null,
    val heightCm: Double? = null,
    val birthDate: LocalDate? = null,
    val sex: Sex? = null,
) {
    val isValid: Boolean
        get() = errors.isEmpty()

    fun error(field: AccountEditorField): UiText? = errors[field]
}

fun validateAccountEditor(
    draft: AccountEditorDraft,
    accounts: List<Account>,
    today: LocalDate = LocalDate.now(),
): AccountEditorValidation {
    val errors = linkedMapOf<AccountEditorField, UiText>()
    val displayName = draft.name.trim()
    val normalizedName = normalizeAccountName(displayName)
    if (displayName.length !in ACCOUNT_NAME_LENGTH) {
        errors[AccountEditorField.NAME] = uiText(R.string.account_error_name_length)
    } else if (accounts.any {
            it.id != draft.editingAccountId && it.normalizedName == normalizedName
        }
    ) {
        errors[AccountEditorField.NAME] = uiText(R.string.account_error_duplicate_name)
    }

    val height = parseLocalizedDecimal(draft.heightCm)
    if (height == null || height !in 100.0..230.0) {
        errors[AccountEditorField.HEIGHT] = uiText(R.string.account_error_height)
    }

    val birthDate = draft.birthDate
    if (birthDate == null || birthDate.isAfter(today)) {
        errors[AccountEditorField.BIRTH_DATE] = uiText(R.string.account_error_birth_date)
    }
    if (draft.sex == null) {
        errors[AccountEditorField.SEX] = uiText(R.string.account_error_sex)
    }
    return AccountEditorValidation(
        errors = errors,
        normalizedName = normalizedName.takeIf { displayName.length in ACCOUNT_NAME_LENGTH },
        heightCm = height?.takeIf { it in 100.0..230.0 },
        birthDate = birthDate?.takeUnless { it.isAfter(today) },
        sex = draft.sex,
    )
}

fun AccountEditorDraft.toNewAccountOrNull(validation: AccountEditorValidation): NewAccount? {
    if (editingAccountId != null || !validation.isValid) return null
    return NewAccount(
        displayName = name.trim(),
        profile = AccountProfile.Complete(
            heightCm = requireNotNull(validation.heightCm),
            birthDate = requireNotNull(validation.birthDate),
            sex = requireNotNull(validation.sex),
        ),
        photoPath = photoPath,
    )
}

fun AccountEditorDraft.toAccountUpdateOrNull(validation: AccountEditorValidation): AccountUpdate? {
    val accountId = editingAccountId ?: return null
    if (!validation.isValid) return null
    return AccountUpdate(
        id = accountId,
        displayName = name.trim(),
        profile = AccountProfile.Complete(
            heightCm = requireNotNull(validation.heightCm),
            birthDate = requireNotNull(validation.birthDate),
            sex = requireNotNull(validation.sex),
        ),
        photoPath = photoPath,
    )
}

sealed interface AccountEditorAction {
    data class NameChanged(val value: String) : AccountEditorAction
    data class HeightChanged(val value: String) : AccountEditorAction
    data class BirthDateChanged(val value: LocalDate?) : AccountEditorAction
    data class SexChanged(val value: Sex) : AccountEditorAction
    data class PhotoChanged(val value: String?) : AccountEditorAction
}

fun reduceAccountEditor(
    state: AccountEditorDraft,
    action: AccountEditorAction,
): AccountEditorDraft = when (action) {
    is AccountEditorAction.NameChanged -> state.copy(name = action.value)
    is AccountEditorAction.HeightChanged -> state.copy(heightCm = action.value)
    is AccountEditorAction.BirthDateChanged -> state.copy(birthDate = action.value)
    is AccountEditorAction.SexChanged -> state.copy(sex = action.value)
    is AccountEditorAction.PhotoChanged -> state.copy(photoPath = action.value)
}

@Immutable
data class PrimaryAccountChangeRequest(
    val accountId: AccountId,
    val historySyncMode: PrimaryHistorySyncMode = PrimaryHistorySyncMode.FUTURE_ONLY,
)

@Immutable
data class AccountDeletionRequest(
    val accountId: AccountId,
    val wasPrimary: Boolean,
    val replacementAccountId: AccountId? = null,
    val historySyncMode: PrimaryHistorySyncMode = PrimaryHistorySyncMode.FUTURE_ONLY,
)

@Immutable
data class ProfileUpdateConfirmation(
    val update: AccountUpdate,
    val editorDraft: AccountEditorDraft,
)

@Immutable
data class AccountManagementUiState(
    val accounts: List<Account> = emptyList(),
    val primaryAccountId: AccountId? = null,
    val editor: AccountEditorDraft? = null,
    val primaryChange: PrimaryAccountChangeRequest? = null,
    val deletion: AccountDeletionRequest? = null,
    val profileUpdateConfirmation: ProfileUpdateConfirmation? = null,
    val operationInProgress: Boolean = false,
    val operationError: String? = null,
) {
    init {
        require(accounts.distinctBy(Account::id).size == accounts.size)
        require(listOfNotNull(editor, primaryChange, deletion, profileUpdateConfirmation).size <= 1) {
            "Only one account-management dialog may be open"
        }
    }

    val primaryAccount: Account?
        get() = accounts.firstOrNull { it.id == primaryAccountId }
}

/**
 * Reconciles an open dialog with the latest durable account snapshot.
 *
 * Account mutations may originate outside the dialog (or finish while Room is emitting its new
 * snapshot), so every target carried by the UI must be checked again before it can be submitted.
 */
fun reconcileAccountManagement(
    state: AccountManagementUiState,
    accounts: List<Account>,
    primaryAccountId: AccountId?,
): AccountManagementUiState {
    val uniqueAccounts = accounts.distinctBy(Account::id)
    val accountIds = uniqueAccounts.mapTo(mutableSetOf(), Account::id)
    val validPrimaryAccountId = primaryAccountId?.takeIf(accountIds::contains)
    val editor = state.editor?.takeIf { draft ->
        draft.editingAccountId == null || draft.editingAccountId in accountIds
    }
    val primaryChange = state.primaryChange?.takeIf { request ->
        request.accountId in accountIds && request.accountId != validPrimaryAccountId
    }
    val deletion = state.deletion?.takeIf { request ->
        request.accountId in accountIds
    }?.let { request ->
        val isPrimary = request.accountId == validPrimaryAccountId
        val replacementAccountId = if (isPrimary) {
            request.replacementAccountId?.takeIf { replacement ->
                replacement in accountIds && replacement != request.accountId
            } ?: uniqueAccounts.firstOrNull { it.id != request.accountId }?.id
        } else {
            null
        }
        request.copy(
            wasPrimary = isPrimary,
            replacementAccountId = replacementAccountId,
        )
    }
    val profileUpdateConfirmation = state.profileUpdateConfirmation?.takeIf {
        it.update.id in accountIds
    }
    return state.copy(
        accounts = uniqueAccounts,
        primaryAccountId = validPrimaryAccountId,
        editor = editor,
        primaryChange = primaryChange,
        deletion = deletion,
        profileUpdateConfirmation = profileUpdateConfirmation,
    )
}

sealed interface AccountManagementAction {
    data object AddRequested : AccountManagementAction
    data class EditRequested(val accountId: AccountId) : AccountManagementAction
    data class MakePrimaryRequested(val accountId: AccountId) : AccountManagementAction
    data class DeleteRequested(val accountId: AccountId) : AccountManagementAction
    data class EditorChanged(val draft: AccountEditorDraft) : AccountManagementAction
    data class ReplacementSelected(val accountId: AccountId) : AccountManagementAction
    data class SyncModeSelected(val mode: PrimaryHistorySyncMode) : AccountManagementAction
    data class ProfileUpdateConfirmationRequested(
        val update: AccountUpdate,
        val editorDraft: AccountEditorDraft,
    ) : AccountManagementAction
    data object ProfileUpdateConfirmationCancelled : AccountManagementAction
    data object DialogDismissed : AccountManagementAction
}

fun reduceAccountManagement(
    state: AccountManagementUiState,
    action: AccountManagementAction,
): AccountManagementUiState {
    if (state.operationInProgress) return state
    return when (action) {
        AccountManagementAction.AddRequested -> state.copy(
            editor = AccountEditorDraft.add(),
            primaryChange = null,
            deletion = null,
            profileUpdateConfirmation = null,
            operationError = null,
        )
        is AccountManagementAction.EditRequested -> state.accounts
            .firstOrNull { it.id == action.accountId }
            ?.let {
                state.copy(
                    editor = AccountEditorDraft.edit(it),
                    primaryChange = null,
                    deletion = null,
                    profileUpdateConfirmation = null,
                    operationError = null,
                )
            }
            ?: state
        is AccountManagementAction.MakePrimaryRequested -> if (
            action.accountId == state.primaryAccountId || state.accounts.none { it.id == action.accountId }
        ) {
            state
        } else {
            state.copy(
                editor = null,
                primaryChange = PrimaryAccountChangeRequest(action.accountId),
                deletion = null,
                profileUpdateConfirmation = null,
                operationError = null,
            )
        }
        is AccountManagementAction.DeleteRequested -> state.accounts
            .firstOrNull { it.id == action.accountId }
            ?.let { account ->
                val isPrimary = account.id == state.primaryAccountId
                val replacement = if (isPrimary) {
                    state.accounts.firstOrNull { it.id != account.id }?.id
                } else {
                    null
                }
                state.copy(
                    editor = null,
                    primaryChange = null,
                    deletion = AccountDeletionRequest(
                        accountId = account.id,
                        wasPrimary = isPrimary,
                        replacementAccountId = replacement,
                    ),
                    profileUpdateConfirmation = null,
                    operationError = null,
                )
            }
            ?: state
        is AccountManagementAction.EditorChanged -> if (
            state.editor != null &&
            state.editor.editingAccountId == action.draft.editingAccountId
        ) {
            state.copy(editor = action.draft, operationError = null)
        } else {
            state
        }
        is AccountManagementAction.ReplacementSelected -> {
            val deletion = state.deletion
            if (deletion == null || !deletion.wasPrimary || state.accounts.none {
                    it.id == action.accountId && it.id != deletion.accountId
                }
            ) {
                state
            } else {
                state.copy(deletion = deletion.copy(replacementAccountId = action.accountId))
            }
        }
        is AccountManagementAction.SyncModeSelected -> when {
            state.deletion != null -> state.copy(
                deletion = state.deletion.copy(historySyncMode = action.mode),
            )
            state.primaryChange != null -> state.copy(
                primaryChange = state.primaryChange.copy(historySyncMode = action.mode),
            )
            else -> state
        }
        is AccountManagementAction.ProfileUpdateConfirmationRequested -> {
            if (state.editor?.editingAccountId != action.update.id) state else state.copy(
                editor = null,
                primaryChange = null,
                deletion = null,
                profileUpdateConfirmation = ProfileUpdateConfirmation(
                    update = action.update,
                    editorDraft = action.editorDraft,
                ),
                operationError = null,
            )
        }
        AccountManagementAction.ProfileUpdateConfirmationCancelled -> {
            val pending = state.profileUpdateConfirmation ?: return state
            state.copy(
                editor = pending.editorDraft,
                profileUpdateConfirmation = null,
                operationError = null,
            )
        }
        AccountManagementAction.DialogDismissed -> state.copy(
            editor = null,
            primaryChange = null,
            deletion = null,
            profileUpdateConfirmation = null,
            operationError = null,
        )
    }
}
