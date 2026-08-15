package com.example.huaweimisync.data

import androidx.room.withTransaction
import com.example.huaweimisync.domain.Account
import com.example.huaweimisync.domain.AccountId
import com.example.huaweimisync.domain.AccountRepository
import com.example.huaweimisync.domain.AccountSettings
import com.example.huaweimisync.domain.AccountUpdate
import com.example.huaweimisync.domain.NewAccount
import com.example.huaweimisync.domain.PrimaryHistorySyncMode
import com.example.huaweimisync.domain.WEIGHT_DELTA_KG_RANGE
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomAccountRepository(
    private val database: AppDatabase,
    private val accountDao: AccountDao = database.accountDao(),
    private val appStateDao: AppStateDao = database.appStateDao(),
    private val measurementDao: MultiAccountMeasurementDao = database.multiAccountMeasurementDao(),
    private val now: () -> Instant = Instant::now,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : AccountRepository {
    override fun observeAccounts(): Flow<List<Account>> = accountDao.observeAll().map { accounts ->
        accounts.map(AccountEntity::toDomain)
    }

    override fun observeSettings(): Flow<AccountSettings> = appStateDao.observe().map { state ->
        state?.toDomain() ?: AccountSettings()
    }

    override suspend fun getAccount(id: AccountId): Account? = accountDao.get(id.value)?.toDomain()

    override suspend fun createAccount(account: NewAccount): Account = database.withTransaction {
        ensureAppState()
        accountDao.getByNormalizedName(account.normalizedName)?.let {
            throw AccountNameConflictException(account.normalizedName)
        }
        val timestamp = now()
        val entity = account.toEntity(
            id = newId(),
            createdAt = timestamp,
            updatedAt = timestamp,
        )
        if (accountDao.insert(entity) == -1L) {
            throw AccountNameConflictException(account.normalizedName)
        }
        if (accountDao.count() == 1) {
            check(appStateDao.setPrimary(entity.id) == 1) { "App state singleton is missing" }
        }
        entity.toDomain()
    }

    override suspend fun updateAccount(account: AccountUpdate): Account = database.withTransaction {
        val current = accountDao.get(account.id.value)
            ?: throw AccountNotFoundException(account.id)
        accountDao.getByNormalizedName(account.normalizedName)
            ?.takeIf { it.id != current.id }
            ?.let { throw AccountNameConflictException(account.normalizedName) }
        val updated = account.toEntity(current, now())
        if (accountDao.update(updated) != 1) throw AccountNotFoundException(account.id)
        updated.toDomain()
    }

    override suspend fun setPrimaryAccount(
        accountId: AccountId,
        historySyncMode: PrimaryHistorySyncMode,
    ) {
        database.withTransaction {
            ensureAppState()
            if (accountDao.get(accountId.value) == null) throw AccountNotFoundException(accountId)
            check(appStateDao.setPrimary(accountId.value) == 1) { "App state singleton is missing" }
            if (historySyncMode == PrimaryHistorySyncMode.INCLUDE_ELIGIBLE_HISTORY) {
                measurementDao.promoteEligibleHistory(accountId.value)
            }
        }
    }

    override suspend fun deleteAccount(accountId: AccountId) {
        database.withTransaction {
            ensureAppState()
            val state = requireNotNull(appStateDao.get())
            if (state.primaryAccountId == accountId.value) {
                throw PrimaryAccountReplacementRequiredException(accountId)
            }
            if (accountDao.delete(accountId.value) != 1) throw AccountNotFoundException(accountId)
        }
    }

    override suspend fun deletePrimaryWithReplacement(
        primaryAccountId: AccountId,
        replacementAccountId: AccountId?,
        historySyncMode: PrimaryHistorySyncMode,
    ) {
        database.withTransaction {
            ensureAppState()
            val state = requireNotNull(appStateDao.get())
            if (state.primaryAccountId != primaryAccountId.value) {
                throw PrimaryAccountChangedException(primaryAccountId)
            }
            if (replacementAccountId == primaryAccountId) {
                throw IllegalArgumentException("Replacement account must differ from the deleted account")
            }
            val accountCount = accountDao.count()
            if (replacementAccountId == null && accountCount > 1) {
                throw PrimaryAccountReplacementRequiredException(primaryAccountId)
            }
            if (replacementAccountId != null && accountDao.get(replacementAccountId.value) == null) {
                throw AccountNotFoundException(replacementAccountId)
            }

            check(appStateDao.setPrimary(replacementAccountId?.value) == 1) {
                "App state singleton is missing"
            }
            if (replacementAccountId != null &&
                historySyncMode == PrimaryHistorySyncMode.INCLUDE_ELIGIBLE_HISTORY
            ) {
                measurementDao.promoteEligibleHistory(replacementAccountId.value)
            }
            if (accountDao.delete(primaryAccountId.value) != 1) {
                throw AccountNotFoundException(primaryAccountId)
            }
        }
    }

    override suspend fun updateWeightDeltaKg(weightDeltaKg: Double) {
        require(weightDeltaKg.isFinite() && weightDeltaKg in WEIGHT_DELTA_KG_RANGE) {
            "Weight delta must be between 0.1 and 50.0 kg"
        }
        database.withTransaction {
            ensureAppState()
            check(appStateDao.setWeightDelta(weightDeltaKg) == 1) {
                "App state singleton is missing"
            }
        }
    }

    private suspend fun ensureAppState() {
        appStateDao.insertDefault()
    }
}

class AccountNameConflictException(val normalizedName: String) :
    IllegalArgumentException("An account named '$normalizedName' already exists")

class AccountNotFoundException(val accountId: AccountId) :
    NoSuchElementException("Account ${accountId.value} does not exist")

class PrimaryAccountReplacementRequiredException(val accountId: AccountId) :
    IllegalStateException("Primary account ${accountId.value} requires an explicit replacement")

class PrimaryAccountChangedException(val expectedAccountId: AccountId) :
    IllegalStateException("Account ${expectedAccountId.value} is no longer primary")

private fun AppStateEntity.toDomain(): AccountSettings = AccountSettings(
    primaryAccountId = primaryAccountId?.let(::AccountId),
    weightDeltaKg = weightDeltaKg,
)

private fun NewAccount.toEntity(
    id: String,
    createdAt: Instant,
    updatedAt: Instant,
): AccountEntity = AccountEntity(
    id = id,
    displayName = displayName,
    normalizedName = normalizedName,
    heightCm = profile.heightCm,
    birthDateEpochDay = profile.birthDate.toEpochDay(),
    sex = profile.sex.name,
    isProfileComplete = true,
    createdAtEpochMillis = createdAt.toEpochMilli(),
    updatedAtEpochMillis = updatedAt.toEpochMilli(),
)

private fun AccountUpdate.toEntity(current: AccountEntity, updatedAt: Instant): AccountEntity =
    current.copy(
        displayName = displayName,
        normalizedName = normalizedName,
        heightCm = profile.heightCm,
        birthDateEpochDay = profile.birthDate.toEpochDay(),
        sex = profile.sex.name,
        isProfileComplete = true,
        updatedAtEpochMillis = updatedAt.toEpochMilli(),
    )
