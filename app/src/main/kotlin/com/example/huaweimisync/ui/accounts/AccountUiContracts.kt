package com.example.huaweimisync.ui.accounts

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import com.example.huaweimisync.core.Sex
import com.example.huaweimisync.domain.ACCOUNT_NAME_LENGTH
import com.example.huaweimisync.domain.Account
import com.example.huaweimisync.domain.AccountId
import com.example.huaweimisync.domain.AccountProfile
import com.example.huaweimisync.domain.AccountUpdate
import com.example.huaweimisync.domain.NewAccount
import com.example.huaweimisync.domain.PrimaryHistorySyncMode
import com.example.huaweimisync.domain.normalizeAccountName
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle

@Immutable
data class AccountEditorDraft(
    val editingAccountId: AccountId? = null,
    val name: String = "",
    val heightCm: String = "",
    val birthDate: String = "",
    val sex: Sex? = null,
) {
    companion object {
        fun add(): AccountEditorDraft = AccountEditorDraft()

        fun edit(account: Account): AccountEditorDraft = AccountEditorDraft(
            editingAccountId = account.id,
            name = account.displayName,
            heightCm = account.profile.heightCm?.let(::formatLocalizedDecimal).orEmpty(),
            birthDate = account.profile.birthDate?.format(DisplayDateFormatter).orEmpty(),
            sex = account.profile.sex,
        )
    }
}

val AccountEditorDraftSaver: Saver<AccountEditorDraft, Any> = listSaver(
    save = { draft ->
        listOf(
            draft.editingAccountId?.value.orEmpty(),
            draft.name,
            draft.heightCm,
            draft.birthDate,
            draft.sex?.name.orEmpty(),
        )
    },
    restore = { saved ->
        AccountEditorDraft(
            editingAccountId = saved[0].takeIf(String::isNotEmpty)?.let(::AccountId),
            name = saved[1],
            heightCm = saved[2],
            birthDate = saved[3],
            sex = saved[4].takeIf(String::isNotEmpty)?.let(Sex::valueOf),
        )
    },
)

enum class AccountEditorField {
    NAME,
    HEIGHT,
    BIRTH_DATE,
    SEX,
}

@Immutable
data class AccountEditorValidation(
    val errors: Map<AccountEditorField, String> = emptyMap(),
    val normalizedName: String? = null,
    val heightCm: Double? = null,
    val parsedBirthDate: LocalDate? = null,
    val sex: Sex? = null,
) {
    val isValid: Boolean
        get() = errors.isEmpty()

    fun error(field: AccountEditorField): String? = errors[field]
}

fun validateAccountEditor(
    draft: AccountEditorDraft,
    accounts: List<Account>,
    today: LocalDate = LocalDate.now(),
): AccountEditorValidation {
    val errors = linkedMapOf<AccountEditorField, String>()
    val displayName = draft.name.trim()
    val normalizedName = normalizeAccountName(displayName)
    if (displayName.length !in ACCOUNT_NAME_LENGTH) {
        errors[AccountEditorField.NAME] = "Введите имя от 1 до 50 символов"
    } else if (accounts.any {
            it.id != draft.editingAccountId && it.normalizedName == normalizedName
        }
    ) {
        errors[AccountEditorField.NAME] = "Аккаунт с таким именем уже существует"
    }

    val height = parseLocalizedDecimal(draft.heightCm)
    if (height == null || height !in 100.0..230.0) {
        errors[AccountEditorField.HEIGHT] = "Допустимый рост: 100–230 см"
    }

    val birthDate = parseProfileDate(draft.birthDate)
    if (birthDate == null || birthDate.isAfter(today)) {
        errors[AccountEditorField.BIRTH_DATE] = "Введите корректную дату рождения"
    }
    if (draft.sex == null) {
        errors[AccountEditorField.SEX] = "Выберите пол"
    }
    return AccountEditorValidation(
        errors = errors,
        normalizedName = normalizedName.takeIf { displayName.length in ACCOUNT_NAME_LENGTH },
        heightCm = height?.takeIf { it in 100.0..230.0 },
        parsedBirthDate = birthDate?.takeUnless { it.isAfter(today) },
        sex = draft.sex,
    )
}

fun AccountEditorDraft.toNewAccountOrNull(validation: AccountEditorValidation): NewAccount? {
    if (editingAccountId != null || !validation.isValid) return null
    return NewAccount(
        displayName = name.trim(),
        profile = AccountProfile.Complete(
            heightCm = requireNotNull(validation.heightCm),
            birthDate = requireNotNull(validation.parsedBirthDate),
            sex = requireNotNull(validation.sex),
        ),
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
            birthDate = requireNotNull(validation.parsedBirthDate),
            sex = requireNotNull(validation.sex),
        ),
    )
}

sealed interface AccountEditorAction {
    data class NameChanged(val value: String) : AccountEditorAction
    data class HeightChanged(val value: String) : AccountEditorAction
    data class BirthDateChanged(val value: String) : AccountEditorAction
    data class SexChanged(val value: Sex) : AccountEditorAction
}

fun reduceAccountEditor(
    state: AccountEditorDraft,
    action: AccountEditorAction,
): AccountEditorDraft = when (action) {
    is AccountEditorAction.NameChanged -> state.copy(name = action.value)
    is AccountEditorAction.HeightChanged -> state.copy(heightCm = action.value)
    is AccountEditorAction.BirthDateChanged -> state.copy(birthDate = action.value)
    is AccountEditorAction.SexChanged -> state.copy(sex = action.value)
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
data class AccountManagementUiState(
    val accounts: List<Account> = emptyList(),
    val primaryAccountId: AccountId? = null,
    val editor: AccountEditorDraft? = null,
    val primaryChange: PrimaryAccountChangeRequest? = null,
    val deletion: AccountDeletionRequest? = null,
    val operationInProgress: Boolean = false,
) {
    init {
        require(accounts.distinctBy(Account::id).size == accounts.size)
        require(listOfNotNull(editor, primaryChange, deletion).size <= 1) {
            "Only one account-management dialog may be open"
        }
    }

    val primaryAccount: Account?
        get() = accounts.firstOrNull { it.id == primaryAccountId }
}

sealed interface AccountManagementAction {
    data object AddRequested : AccountManagementAction
    data class EditRequested(val accountId: AccountId) : AccountManagementAction
    data class MakePrimaryRequested(val accountId: AccountId) : AccountManagementAction
    data class DeleteRequested(val accountId: AccountId) : AccountManagementAction
    data class EditorChanged(val draft: AccountEditorDraft) : AccountManagementAction
    data class ReplacementSelected(val accountId: AccountId) : AccountManagementAction
    data class SyncModeSelected(val mode: PrimaryHistorySyncMode) : AccountManagementAction
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
        )
        is AccountManagementAction.EditRequested -> state.accounts
            .firstOrNull { it.id == action.accountId }
            ?.let {
                state.copy(
                    editor = AccountEditorDraft.edit(it),
                    primaryChange = null,
                    deletion = null,
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
                )
            }
            ?: state
        is AccountManagementAction.EditorChanged -> if (
            state.editor != null &&
            state.editor.editingAccountId == action.draft.editingAccountId
        ) {
            state.copy(editor = action.draft)
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
        AccountManagementAction.DialogDismissed -> state.copy(
            editor = null,
            primaryChange = null,
            deletion = null,
        )
    }
}

private val DisplayDateFormatter: DateTimeFormatter = DateTimeFormatter
    .ofPattern("dd.MM.uuuu")
    .withResolverStyle(ResolverStyle.STRICT)

fun parseProfileDate(input: String): LocalDate? {
    val value = input.trim()
    return try {
        LocalDate.parse(value, DisplayDateFormatter)
    } catch (_: DateTimeParseException) {
        try {
            LocalDate.parse(value)
        } catch (_: DateTimeParseException) {
            null
        }
    }
}
