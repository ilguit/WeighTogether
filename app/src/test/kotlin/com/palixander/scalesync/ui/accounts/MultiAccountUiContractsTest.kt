package com.palixander.scalesync.ui.accounts

import androidx.compose.runtime.saveable.SaverScope
import com.palixander.scalesync.R
import com.palixander.scalesync.core.Sex
import com.palixander.scalesync.domain.Account
import com.palixander.scalesync.domain.AccountId
import com.palixander.scalesync.domain.AccountProfile
import com.palixander.scalesync.domain.PrimaryHistorySyncMode
import com.palixander.scalesync.ui.text.UiText
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiAccountUiContractsTest {
    @Test
    fun `photo path survives draft reducer saver and create mapping`() {
        val photo = "profile-photos/accounts/new/profile.webp"
        val draft = reduceAccountEditor(
            AccountEditorDraft.add().copy(
                name = "Анна",
                heightCm = "170",
                birthDate = LocalDate.of(1990, 1, 2),
                sex = Sex.FEMALE,
            ),
            AccountEditorAction.PhotoChanged(photo),
        )

        assertEquals(photo, draft.photoPath)
        assertEquals(draft, AccountEditorDraftSaver.restore(saveAccountEditorDraft(draft)))
        assertEquals(
            photo,
            draft.toNewAccountOrNull(validateAccountEditor(draft, emptyList(), LocalDate.of(2026, 1, 1)))?.photoPath,
        )
    }
    @Test
    fun profileUpdateConfirmationCancelRestoresEditorWithoutWritingStateAway() {
        val account = account("one", "One")
        val draft = AccountEditorDraft.edit(account).copy(heightCm = "181")
        val update = requireNotNull(draft.toAccountUpdateOrNull(validateAccountEditor(draft, listOf(account))))
        val prompted = reduceAccountManagement(
            AccountManagementUiState(accounts = listOf(account), editor = draft),
            AccountManagementAction.ProfileUpdateConfirmationRequested(update, draft),
        )

        assertNull(prompted.editor)
        assertEquals(update, prompted.profileUpdateConfirmation?.update)
        val cancelled = reduceAccountManagement(
            prompted,
            AccountManagementAction.ProfileUpdateConfirmationCancelled,
        )
        assertEquals(draft, cancelled.editor)
        assertNull(cancelled.profileUpdateConfirmation)
    }

    @Test
    fun profileUpdatePromptIgnoresActionsWhileSavingAndSurvivesErrorState() {
        val account = account("one", "One")
        val draft = AccountEditorDraft.edit(account)
        val update = requireNotNull(draft.toAccountUpdateOrNull(validateAccountEditor(draft, listOf(account))))
        val busy = AccountManagementUiState(
            accounts = listOf(account),
            profileUpdateConfirmation = ProfileUpdateConfirmation(update, draft),
            operationInProgress = true,
            operationError = UiText.Raw("Ошибка"),
        )

        assertEquals(busy, reduceAccountManagement(busy, AccountManagementAction.ProfileUpdateConfirmationCancelled))
        assertEquals(update, busy.profileUpdateConfirmation?.update)
        assertEquals(UiText.Raw("Ошибка"), busy.operationError)
    }
    @Test
    fun `localized decimal accepts dot and comma but not mixed input`() {
        assertEquals(3.0, parseLocalizedDecimal("3,0")!!, 0.0)
        assertEquals(3.0, parseLocalizedDecimal(" 3.0 ")!!, 0.0)
        assertEquals(0.1, parseLocalizedDecimal(",1")!!, 0.0)
        assertNull(parseLocalizedDecimal("1,2.3"))
        assertNull(parseLocalizedDecimal("NaN"))
        assertNull(parseLocalizedDecimal("1 000,0"))
        assertNull(parseLocalizedDecimal("--3"))
    }

    @Test
    fun `account editor saver round trips a leap birth date as epoch day`() {
        val birthDate = LocalDate.of(2000, 2, 29)
        val draft = AccountEditorDraft(
            editingAccountId = AccountId("account"),
            name = "Анна",
            heightCm = "170,5",
            birthDate = birthDate,
            sex = Sex.FEMALE,
        )

        val saved = saveAccountEditorDraft(draft)

        assertEquals(birthDate.toEpochDay(), (saved as List<*>)[3])
        assertEquals(draft, AccountEditorDraftSaver.restore(saved))
    }

    @Test
    fun `account editor saver round trips a missing birth date`() {
        val draft = AccountEditorDraft.add().copy(name = "Новый")

        val saved = saveAccountEditorDraft(draft)

        assertTrue((saved as List<*>)[3] is Long)
        assertEquals(draft, AccountEditorDraftSaver.restore(saved))
        assertNull(AccountEditorDraftSaver.restore(saved)?.birthDate)
    }

    @Test
    fun `account editor birth date action selects and clears typed value`() {
        val birthDate = LocalDate.of(2000, 2, 29)
        val selected = reduceAccountEditor(
            AccountEditorDraft.add(),
            AccountEditorAction.BirthDateChanged(birthDate),
        )

        assertEquals(birthDate, selected.birthDate)
        assertNull(
            reduceAccountEditor(
                selected,
                AccountEditorAction.BirthDateChanged(null),
            ).birthDate,
        )
    }

    @Test
    fun `account editor accepts leap day through today and rejects future or missing date`() {
        val today = LocalDate.of(2024, 2, 29)
        val validDraft = AccountEditorDraft(
            name = "Анна",
            heightCm = "170",
            birthDate = today,
            sex = Sex.FEMALE,
        )

        val valid = validateAccountEditor(validDraft, emptyList(), today)
        val future = validateAccountEditor(
            validDraft.copy(birthDate = today.plusDays(1)),
            emptyList(),
            today,
        )
        val missing = validateAccountEditor(
            validDraft.copy(birthDate = null),
            emptyList(),
            today,
        )

        assertTrue(valid.isValid)
        assertEquals(today, valid.birthDate)
        assertNotNull(future.error(AccountEditorField.BIRTH_DATE))
        assertNull(future.birthDate)
        assertNotNull(missing.error(AccountEditorField.BIRTH_DATE))
        assertNull(missing.birthDate)
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
            birthDate = LocalDate.of(1990, 5, 1),
            sex = Sex.FEMALE,
        )
        val duplicateValidation = validateAccountEditor(
            duplicate,
            listOf(existing),
            today = LocalDate.of(2026, 8, 15),
        )
        assertEquals(
            UiText.Resource(R.string.account_error_duplicate_name),
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
    fun `last primary deletion remains replacement free`() {
        val primary = account("one", "Анна")

        val state = reduceAccountManagement(
            AccountManagementUiState(
                accounts = listOf(primary),
                primaryAccountId = primary.id,
            ),
            AccountManagementAction.DeleteRequested(primary.id),
        )

        assertTrue(state.deletion?.wasPrimary == true)
        assertNull(state.deletion?.replacementAccountId)
    }

    @Test
    fun `opening another account dialog replaces prior modal and busy state rejects edits`() {
        val primary = account("one", "Анна")
        val secondary = account("two", "Борис")
        val deleting = reduceAccountManagement(
            AccountManagementUiState(
                accounts = listOf(primary, secondary),
                primaryAccountId = primary.id,
            ),
            AccountManagementAction.DeleteRequested(secondary.id),
        )

        val adding = reduceAccountManagement(deleting, AccountManagementAction.AddRequested)
        assertNotNull(adding.editor)
        assertNull(adding.deletion)
        assertNull(adding.primaryChange)

        val busy = adding.copy(operationInProgress = true)
        assertSame(
            busy,
            reduceAccountManagement(busy, AccountManagementAction.DialogDismissed),
        )
        assertSame(
            busy,
            reduceAccountManagement(
                busy,
                AccountManagementAction.EditorChanged(AccountEditorDraft.add().copy(name = "Другое")),
            ),
        )

        val dismissed = adding.copy(editor = null)
        assertSame(
            dismissed,
            reduceAccountManagement(
                dismissed,
                AccountManagementAction.EditorChanged(AccountEditorDraft.add().copy(name = "Запоздалое")),
            ),
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

    @Test
    fun `selector follows primary when shared selection is empty or removed`() {
        val firstPrimary = account("one", "Анна")
        val nextPrimary = account("two", "Борис")

        val initial = reconcileAccountSelection(
            accounts = listOf(firstPrimary, nextPrimary),
            requestedAccountId = null,
            primaryAccountId = firstPrimary.id,
        )
        val afterRemovalAndSettingsChange = reconcileAccountSelection(
            accounts = listOf(nextPrimary),
            requestedAccountId = initial.selectedAccountId,
            primaryAccountId = nextPrimary.id,
        )

        assertEquals(firstPrimary.id, initial.selectedAccountId)
        assertEquals(nextPrimary.id, afterRemovalAndSettingsChange.selectedAccountId)
        assertEquals(
            AccountSelectionFallback.SELECTED_ACCOUNT_REMOVED,
            afterRemovalAndSettingsChange.fallback,
        )
    }

    @Test
    fun `selector drops invalid primary when no fallback account is configured`() {
        val surviving = account("one", "Анна")

        val state = reconcileAccountSelection(
            accounts = listOf(surviving),
            requestedAccountId = AccountId("removed"),
            primaryAccountId = AccountId("also-removed"),
        )

        assertNull(state.selectedAccountId)
        assertNull(state.primaryAccountId)
        assertEquals(AccountSelectionFallback.PRIMARY_ACCOUNT_UNAVAILABLE, state.fallback)
    }

    @Test
    fun `selection change tracker ignores initial snapshot and detects fallback transitions`() {
        val tracker = AccountSelectionChangeTracker()
        val primary = AccountId("primary")
        val secondary = AccountId("secondary")

        assertFalse(tracker.update(primary))
        assertFalse(tracker.update(primary))
        assertTrue(tracker.update(secondary))
        assertTrue(tracker.update(null))
        assertFalse(tracker.update(null))
    }

    @Test
    fun `account dialog drops stale targets and repairs deleted primary replacement`() {
        val primary = account("one", "Анна")
        val removedReplacement = account("two", "Борис")
        val survivingReplacement = account("three", "Вера")
        val state = AccountManagementUiState(
            accounts = listOf(primary, removedReplacement, survivingReplacement),
            primaryAccountId = primary.id,
            deletion = AccountDeletionRequest(
                accountId = primary.id,
                wasPrimary = true,
                replacementAccountId = removedReplacement.id,
            ),
        )

        val reconciled = reconcileAccountManagement(
            state = state,
            accounts = listOf(primary, survivingReplacement),
            primaryAccountId = primary.id,
        )

        assertEquals(survivingReplacement.id, reconciled.deletion?.replacementAccountId)
        assertEquals(listOf(primary, survivingReplacement), reconciled.accounts)

        val targetRemoved = reconcileAccountManagement(
            state = reconciled,
            accounts = listOf(survivingReplacement),
            primaryAccountId = survivingReplacement.id,
        )
        assertNull(targetRemoved.deletion)
    }

    @Test
    fun `account dialog drops stale edit and primary change targets`() {
        val primary = account("one", "Анна")
        val removed = account("two", "Борис")
        val editing = AccountManagementUiState(
            accounts = listOf(primary, removed),
            primaryAccountId = primary.id,
            editor = AccountEditorDraft.edit(removed),
        )
        val changingPrimary = editing.copy(
            editor = null,
            primaryChange = PrimaryAccountChangeRequest(removed.id),
        )

        assertNull(
            reconcileAccountManagement(editing, listOf(primary), primary.id).editor,
        )
        assertNull(
            reconcileAccountManagement(
                changingPrimary,
                listOf(primary),
                primary.id,
            ).primaryChange,
        )
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

    private fun saveAccountEditorDraft(draft: AccountEditorDraft): Any = requireNotNull(
        with(AccountEditorDraftSaver) {
            with(SaveEverythingScope) {
                save(draft)
            }
        },
    )

    private object SaveEverythingScope : SaverScope {
        override fun canBeSaved(value: Any): Boolean = true
    }
}
