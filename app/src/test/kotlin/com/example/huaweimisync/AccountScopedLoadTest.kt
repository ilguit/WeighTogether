package com.example.huaweimisync

import com.example.huaweimisync.domain.AccountId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountScopedLoadTest {
    @Test
    fun `load always clears stale account values before first snapshot`() = runBlocking {
        val accountId = AccountId("secondary")

        val states = accountScopedLoad(accountId, emptyList<String>()) {
            flowOf(listOf("secondary-value"))
        }.toList()

        assertEquals(AccountScopedLoad.Loading, states[0])
        assertEquals(AccountScopedLoad.Loaded(listOf("secondary-value")), states[1])
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `switch cancels old account and cannot publish its later values`() = runBlocking {
        val oldAccount = AccountId("old")
        val newAccount = AccountId("new")
        val selection = MutableStateFlow(oldAccount)
        val oldSnapshotPublished = CompletableDeferred<Unit>()
        val oldCollectionCancelled = CompletableDeferred<Unit>()
        val states = mutableListOf<AccountScopedLoad<List<String>>>()

        val collection = launch {
            selection.flatMapLatest { accountId ->
                accountScopedLoad(accountId, emptyList()) { selected ->
                    if (selected == oldAccount) {
                        flow {
                            emit(listOf("old-value"))
                            oldSnapshotPublished.complete(Unit)
                            try {
                                awaitCancellation()
                            } finally {
                                oldCollectionCancelled.complete(Unit)
                            }
                        }
                    } else {
                        flowOf(listOf("new-value"))
                    }
                }
            }.take(4).toList(states)
        }

        oldSnapshotPublished.await()
        selection.value = newAccount
        collection.join()

        assertTrue(oldCollectionCancelled.isCompleted)
        assertEquals(
            listOf(
                AccountScopedLoad.Loading,
                AccountScopedLoad.Loaded(listOf("old-value")),
                AccountScopedLoad.Loading,
                AccountScopedLoad.Loaded(listOf("new-value")),
            ),
            states,
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `selection switch publishes matching loading and data without mixed account frame`() = runBlocking {
        data class Selection(val id: AccountId, val header: String)

        val oldSelection = Selection(AccountId("old"), "Old account")
        val newSelection = Selection(AccountId("new"), "New account")
        val selection = MutableStateFlow(oldSelection)
        val oldSnapshotPublished = CompletableDeferred<Unit>()
        val oldCollectionCancelled = CompletableDeferred<Unit>()
        val states = mutableListOf<AccountSelectionScopedLoad<Selection, List<String>>>()

        val collection = launch {
            accountSelectionScopedLoad(
                selections = selection,
                accountId = Selection::id,
                emptyValue = emptyList(),
            ) { accountId, _ ->
                if (accountId == oldSelection.id) {
                    flow {
                        emit(listOf("old-history"))
                        oldSnapshotPublished.complete(Unit)
                        try {
                            awaitCancellation()
                        } finally {
                            oldCollectionCancelled.complete(Unit)
                        }
                    }
                } else {
                    flowOf(listOf("new-history"))
                }
            }.take(4).toList(states)
        }

        oldSnapshotPublished.await()
        selection.value = newSelection
        collection.join()

        assertTrue(oldCollectionCancelled.isCompleted)
        assertEquals(
            listOf(
                oldSelection to AccountScopedLoad.Loading,
                oldSelection to AccountScopedLoad.Loaded(listOf("old-history")),
                newSelection to AccountScopedLoad.Loading,
                newSelection to AccountScopedLoad.Loaded(listOf("new-history")),
            ),
            states.map { it.selection to it.load },
        )
        assertEquals(1, states.count { it.selection == newSelection && it.load is AccountScopedLoad.Loading })
    }
}
