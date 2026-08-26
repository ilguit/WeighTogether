package com.example.huaweimisync

import com.example.huaweimisync.domain.AccountId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

class AccountSelectionCoordinatorTest {
    @Test
    fun accountTransitionsAdvanceEpochIncludingReturnToSameAccount() {
        val coordinator = AccountSelectionCoordinator()
        val accountA = AccountId("account-a")
        val accountB = AccountId("account-b")

        val firstA = coordinator.select(accountA)
        val selectedB = coordinator.select(accountB)
        val returnedA = coordinator.select(accountA)

        assertEquals(1L, firstA.epoch)
        assertEquals(2L, selectedB.epoch)
        assertEquals(3L, returnedA.epoch)
        assertEquals(accountA, returnedA.accountId)
        assertFalse(firstA == returnedA)
    }

    @Test
    fun selectingCurrentAccountPreservesEpochAndInstance() {
        val coordinator = AccountSelectionCoordinator()
        val selected = coordinator.select(AccountId("account-a"))

        assertSame(selected, coordinator.select(selected.accountId))
    }

    @Test
    fun staleFallbackCannotOverwriteNewerUserSelection() {
        val coordinator = AccountSelectionCoordinator()
        val staleSnapshot = coordinator.select(AccountId("removed"))
        val userSelection = coordinator.select(AccountId("account-b"))

        val authoritative = coordinator.selectIfCurrent(
            expected = staleSnapshot,
            accountId = AccountId("primary"),
        )

        assertEquals(userSelection, authoritative)
        assertEquals(userSelection, coordinator.selection.value)
    }
}
