package com.palixander.weightogether

import com.palixander.weightogether.domain.AccountId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

internal sealed interface AccountScopedLoad<out T> {
    data object Loading : AccountScopedLoad<Nothing>

    data class Loaded<T>(val value: T) : AccountScopedLoad<T>
}

internal data class AccountSelectionScopedLoad<S, T>(
    val selection: S,
    val load: AccountScopedLoad<T>,
)

/** Keeps the selected-account header and its loading/data snapshot in one atomic stream value. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun <S, T> accountSelectionScopedLoad(
    selections: Flow<S>,
    accountId: (S) -> AccountId?,
    emptyValue: T,
    observe: (AccountId, S) -> Flow<T>,
): Flow<AccountSelectionScopedLoad<S, T>> = selections.flatMapLatest { selection ->
    accountScopedLoad(
        accountId = accountId(selection),
        emptyValue = emptyValue,
        observe = { selectedId -> observe(selectedId, selection) },
    ).map { load -> AccountSelectionScopedLoad(selection, load) }
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
