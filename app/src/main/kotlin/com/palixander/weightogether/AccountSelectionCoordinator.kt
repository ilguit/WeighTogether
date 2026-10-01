package com.palixander.weightogether

import com.palixander.weightogether.domain.AccountId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class AccountSelection(
    val accountId: AccountId? = null,
    val epoch: Long = 0L,
)

internal class AccountSelectionCoordinator {
    private val mutableSelection = MutableStateFlow(AccountSelection())

    val selection: StateFlow<AccountSelection> = mutableSelection.asStateFlow()

    fun select(accountId: AccountId?): AccountSelection {
        while (true) {
            val current = mutableSelection.value
            if (current.accountId == accountId) return current
            val updated = AccountSelection(accountId, current.epoch + 1L)
            if (mutableSelection.compareAndSet(current, updated)) return updated
        }
    }

    fun selectIfCurrent(expected: AccountSelection, accountId: AccountId?): AccountSelection {
        if (expected.accountId == accountId) return mutableSelection.value
        val updated = AccountSelection(accountId, expected.epoch + 1L)
        mutableSelection.compareAndSet(expected, updated)
        return mutableSelection.value
    }
}
