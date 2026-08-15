package com.example.huaweimisync.ui.accounts

import com.example.huaweimisync.core.Sex
import com.example.huaweimisync.domain.Account
import com.example.huaweimisync.domain.AccountId
import com.example.huaweimisync.domain.AccountProfile
import com.example.huaweimisync.domain.PrimaryHistorySyncMode
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiAccountUiContractsTest {
    @Test
    fun `localized decimal accepts dot and comma but not mixed input`() {
        assertEquals(3.0, parseLocalizedDecimal("3,0")!!, 0.0)
        assertEquals(3.0, parseLocalizedDecimal(" 3.0 ")!!, 0.0)
        assertEquals(0.1, parseLocalizedDecimal(",1")!!, 0.0)
        assertNull(parseLocalizedDecimal("1,2.3"))
        assertNull(parseLocalizedDecimal("NaN"))
    }

    @Test
    fun `weight delta defaults to three and validates inclusive range`() {
        val default = WeightDeltaEditorState()
        assertEquals("3,0", default.input)
        assertEquals(3.0, default.parsedValue!!, 0.0)
        assertTrue(default.canSave)

        assertTrue(WeightDeltaEditorState("0,1").canSave)
        assertTrue(WeightDeltaEditorState("50.0").canSave)
        assertFalse(WeightDeltaEditorState("0,09").canSave)
        assertFalse(WeightDeltaEditorState("50,1").canSave)
    }

    @Test
    fun `account editor validates case insensitive uniqueness and builds domain request`() {
        val existing = account("one", "Анна")
        val duplicate = AccountEditorDraft(
            name = "  АННА  ",
            heightCm = "170,5",
            birthDate = "01.05.1990",
            sex = Sex.FEMALE,
        )
        val duplicateValidation = validateAccountEditor(
            duplicate,
            listOf(existing),
            today = LocalDate.of(2026, 8, 15),
        )
        assertEquals(
            "Аккаунт с таким именем уже существует",
            duplicateValidation.error(AccountEditorField.NAME),
        )

        val valid = duplicate.copy(name = " Мария ")
        val validation = validateAccountEditor(
            valid,
            listOf(existing),
            today = LocalDate.of(2026, 8, 15),
        )
        val request = valid.toNewAccountOrNull(validation)
        assertNotNull(request)
        assertEquals("Мария", request!!.displayName)
        assertEquals(170.5, request.profile.heightCm, 0.0)
        assertEquals(LocalDate.of(1990, 5, 1), request.profile.birthDate)
    }

    @Test
    fun `editing keeps own normalized name valid`() {
        val existing = account("one", "Анна")
        val draft = AccountEditorDraft.edit(existing).copy(name = "АННА")

        val validation = validateAccountEditor(
            draft,
            listOf(existing),
            today = LocalDate.of(2026, 8, 15),
        )

        assertTrue(validation.isValid)
        assertEquals(existing.id, draft.toAccountUpdateOrNull(validation)?.id)
    }

    @Test
    fun `primary deletion selects replacement and reducer retains sync choice`() {
        val primary = account("one", "Анна")
        val replacement = account("two", "Борис")
        val initial = AccountManagementUiState(
            accounts = listOf(primary, replacement),
            primaryAccountId = primary.id,
        )
        val withDeletion = reduceAccountManagement(
            initial,
            AccountManagementAction.DeleteRequested(primary.id),
        )
        assertTrue(withDeletion.deletion?.wasPrimary == true)
        assertEquals(replacement.id, withDeletion.deletion?.replacementAccountId)

        val withHistory = reduceAccountManagement(
            withDeletion,
            AccountManagementAction.SyncModeSelected(
                PrimaryHistorySyncMode.INCLUDE_ELIGIBLE_HISTORY,
            ),
        )
        assertEquals(
            PrimaryHistorySyncMode.INCLUDE_ELIGIBLE_HISTORY,
            withHistory.deletion?.historySyncMode,
        )
    }

    @Test
    fun `selector falls back to primary after selected account deletion`() {
        val primary = account("one", "Анна")
        val state = reconcileAccountSelection(
            accounts = listOf(primary),
            requestedAccountId = AccountId("removed"),
            primaryAccountId = primary.id,
        )

        assertEquals(primary.id, state.selectedAccountId)
        assertEquals(AccountSelectionFallback.SELECTED_ACCOUNT_REMOVED, state.fallback)
        assertNotNull(state.fallbackMessage)
    }

    @Test
    fun `selector exposes empty state when no account survives`() {
        val state = reconcileAccountSelection(
            accounts = emptyList(),
            requestedAccountId = AccountId("removed"),
            primaryAccountId = AccountId("primary"),
        )

        assertNull(state.selectedAccountId)
        assertEquals(AccountSelectionFallback.NO_ACCOUNTS, state.fallback)
    }

    private fun account(id: String, name: String): Account = Account(
        id = AccountId(id),
        displayName = name,
        profile = AccountProfile.Complete(
            heightCm = 170.0,
            birthDate = LocalDate.of(1990, 1, 1),
            sex = Sex.FEMALE,
        ),
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
        updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
    )
}
