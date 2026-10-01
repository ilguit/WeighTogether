package com.palixander.weightogether.ui.accounts

import androidx.compose.runtime.Immutable
import com.palixander.weightogether.R
import com.palixander.weightogether.domain.Account
import com.palixander.weightogether.domain.AccountId
import com.palixander.weightogether.ui.text.UiText
import com.palixander.weightogether.ui.text.uiText

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

    val fallbackMessage: UiText?
        get() = when (fallback) {
            AccountSelectionFallback.NONE -> null
            AccountSelectionFallback.SELECTED_ACCOUNT_REMOVED ->
                uiText(R.string.account_selector_removed)
            AccountSelectionFallback.PRIMARY_ACCOUNT_UNAVAILABLE ->
                uiText(R.string.account_selector_primary_unavailable)
            AccountSelectionFallback.NO_ACCOUNTS ->
                uiText(R.string.account_selector_no_accounts)
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

/**
 * Distinguishes the first durable selection from later account changes.
 *
 * ViewModels use this to clear account-scoped transient UI only after a real transition, while
 * leaving their initial navigation state alone when Room publishes the startup selection.
 */
internal class AccountSelectionChangeTracker {
    private var initialized = false
    private var previousAccountId: AccountId? = null

    fun update(selectedAccountId: AccountId?): Boolean {
        val changed = initialized && previousAccountId != selectedAccountId
        initialized = true
        previousAccountId = selectedAccountId
        return changed
    }
}
