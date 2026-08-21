package com.example.huaweimisync

import com.example.huaweimisync.domain.AccountId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

internal sealed interface AccountScopedLoad<out T> {
    data object Loading : AccountScopedLoad<Nothing>

    data class Loaded<T>(val value: T) : AccountScopedLoad<T>
}

/** Clears presentation state before subscribing to a newly selected account. */
internal fun <T> accountScopedLoad(
    accountId: AccountId?,
    emptyValue: T,
    observe: (AccountId) -> Flow<T>,
): Flow<AccountScopedLoad<T>> = flow {
    emit(AccountScopedLoad.Loading)
    if (accountId == null) {
        emit(AccountScopedLoad.Loaded(emptyValue))
    } else {
        emitAll(observe(accountId).map { AccountScopedLoad.Loaded(it) })
    }
}
