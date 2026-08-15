package com.example.huaweimisync.ui.accounts

import androidx.compose.runtime.Immutable
import com.example.huaweimisync.domain.Account
import com.example.huaweimisync.domain.AccountId

enum class AccountSelectionFallback {
    NONE,
    SELECTED_ACCOUNT_REMOVED,
    PRIMARY_ACCOUNT_UNAVAILABLE,
    NO_ACCOUNTS,
}

@Immutable
data class AccountSelectorUiState(
    val accounts: List<Account>,
    val selectedAccountId: AccountId?,
    val primaryAccountId: AccountId?,
    val fallback: AccountSelectionFallback = AccountSelectionFallback.NONE,
    val isLoading: Boolean = false,
) {
    val selectedAccount: Account?
        get() = accounts.firstOrNull { it.id == selectedAccountId }

    val fallbackMessage: String?
        get() = when (fallback) {
            AccountSelectionFallback.NONE -> null
            AccountSelectionFallback.SELECTED_ACCOUNT_REMOVED ->
                "Выбранный аккаунт удалён. Показан основной аккаунт."
            AccountSelectionFallback.PRIMARY_ACCOUNT_UNAVAILABLE ->
                "Основной аккаунт недоступен. Выберите аккаунт."
            AccountSelectionFallback.NO_ACCOUNTS ->
                "Создайте аккаунт, чтобы сохранять и просматривать измерения."
        }
}

fun reconcileAccountSelection(
    accounts: List<Account>,
    requestedAccountId: AccountId?,
    primaryAccountId: AccountId?,
): AccountSelectorUiState {
    val requestedExists = accounts.any { it.id == requestedAccountId }
    val primaryExists = accounts.any { it.id == primaryAccountId }
    val selectedId = when {
        requestedExists -> requestedAccountId
        primaryExists -> primaryAccountId
        else -> null
    }
    val fallback = when {
        accounts.isEmpty() -> AccountSelectionFallback.NO_ACCOUNTS
        requestedAccountId != null && !requestedExists && primaryExists ->
            AccountSelectionFallback.SELECTED_ACCOUNT_REMOVED
        selectedId == null -> AccountSelectionFallback.PRIMARY_ACCOUNT_UNAVAILABLE
        else -> AccountSelectionFallback.NONE
    }
    return AccountSelectorUiState(
        accounts = accounts,
        selectedAccountId = selectedId,
        primaryAccountId = primaryAccountId?.takeIf { primaryExists },
        fallback = fallback,
    )
}
