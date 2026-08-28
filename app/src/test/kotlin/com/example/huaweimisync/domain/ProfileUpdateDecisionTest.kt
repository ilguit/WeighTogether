package com.example.huaweimisync.domain

import com.example.huaweimisync.core.Sex
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileUpdateDecisionTest {
    @Test
    fun nameOnlyUpdateKeepsHistoryWithoutQueryingCandidates() = runBlocking {
        val repository = DecisionRepository(current, hasCandidates = true)
        val decision = DecideProfileUpdate(repository)(update(displayName = "Новое имя"))

        assertEquals(ProfileUpdateDecision.SAVE_KEEP_EXISTING, decision)
        assertEquals(0, repository.candidateQueries)
    }

    @Test
    fun calculationInputChangeWithHistoryRequestsConfirmation() = runBlocking {
        val repository = DecisionRepository(current, hasCandidates = true)
        val changed = update(profile = profile.copy(heightCm = 181.0))

        assertTrue(current.hasCalculationInputChanges(changed))
        assertEquals(ProfileUpdateDecision.ASK_HISTORY_RECALCULATION, DecideProfileUpdate(repository)(changed))
        assertEquals(1, repository.candidateQueries)
    }

    @Test
    fun calculationInputChangeWithoutHistorySavesWithoutRecalculation() = runBlocking {
        val repository = DecisionRepository(current, hasCandidates = false)

        assertEquals(
            ProfileUpdateDecision.SAVE_KEEP_EXISTING,
            DecideProfileUpdate(repository)(update(profile = profile.copy(birthDate = LocalDate.of(1991, 2, 3)))),
        )
        assertEquals(1, repository.candidateQueries)
    }

    @Test
    fun eachCalculationInputIsClassifiedButNameIsNot() {
        assertFalse(current.hasCalculationInputChanges(update(displayName = "Другое")))
        assertTrue(current.hasCalculationInputChanges(update(profile = profile.copy(heightCm = 170.0))))
        assertTrue(current.hasCalculationInputChanges(update(profile = profile.copy(birthDate = LocalDate.of(1980, 1, 1)))))
        assertTrue(current.hasCalculationInputChanges(update(profile = profile.copy(sex = Sex.FEMALE))))
    }

    private class DecisionRepository(
        private val account: Account,
        private val hasCandidates: Boolean,
    ) : AccountRepository {
        var candidateQueries = 0
        override fun observeAccounts(): Flow<List<Account>> = flowOf(listOf(account))
        override fun observeSettings(): Flow<AccountSettings> = flowOf(AccountSettings())
        override suspend fun getAccount(id: AccountId): Account? = account.takeIf { it.id == id }
        override suspend fun hasProfileRecalculationCandidates(accountId: AccountId): Boolean {
            candidateQueries++
            return hasCandidates
        }
        override suspend fun createAccount(account: NewAccount): Account = error("unused")
        override suspend fun updateAccount(account: AccountUpdate, historyUpdateMode: ProfileHistoryUpdateMode): Account = error("unused")
        override suspend fun setPrimaryAccount(accountId: AccountId, historySyncMode: PrimaryHistorySyncMode) = error("unused")
        override suspend fun updateWeightDeltaKg(weightDeltaKg: Double) = error("unused")
        override suspend fun updateIgnoreUnknownMeasurements(enabled: Boolean) = error("unused")
        override suspend fun deleteAccount(accountId: AccountId) = error("unused")
        override suspend fun deletePrimaryWithReplacement(primaryAccountId: AccountId, replacementAccountId: AccountId?, historySyncMode: PrimaryHistorySyncMode) = error("unused")
    }

    private companion object {
        val profile = AccountProfile.Complete(180.0, LocalDate.of(1990, 1, 2), Sex.MALE)
        val current = Account(
            id = AccountId("account"),
            displayName = "Имя",
            profile = profile,
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
        )
        fun update(displayName: String = current.displayName, profile: AccountProfile.Complete = this.profile) =
            AccountUpdate(current.id, displayName, profile)
    }
}
