package com.palixander.weightogether.data

import com.palixander.weightogether.domain.Account
import com.palixander.weightogether.domain.AccountId
import com.palixander.weightogether.domain.AccountRepository
import com.palixander.weightogether.domain.AccountSettings
import com.palixander.weightogether.domain.AccountSettingsWriter
import com.palixander.weightogether.domain.AccountUpdate
import com.palixander.weightogether.domain.NewAccount
import com.palixander.weightogether.domain.PrimaryHistorySyncMode
import com.palixander.weightogether.domain.ProfileHistoryUpdateMode
import com.palixander.weightogether.domain.ProfileUpdateAttemptResult
import com.palixander.weightogether.worker.ExternalSyncOperationSerializer
import com.palixander.weightogether.worker.MeasurementSyncScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * Write-side decorator that couples primary-account changes to WorkManager cancellation and a
 * filtered enqueue sweep. RoomAccountRepository remains the owner of the atomic state/history
 * transaction; this class owns only work orchestration around that transaction.
 */
class SyncAwareAccountRepository(
    private val delegate: AccountRepository,
    private val settingsWriter: AccountSettingsWriter,
    private val persistence: RoomMeasurementPersistence,
    private val measurements: MeasurementRepository,
    private val syncScheduler: MeasurementSyncScheduler,
    private val externalSyncOperations: ExternalSyncOperationSerializer,
) : AccountRepository, AccountSettingsWriter {
    private val accountUpdater = SerializedAccountUpdater(
        updateDelegate = delegate::updateAccount,
        attemptDelegate = delegate::attemptProfileUpdate,
        sweepPendingRouting = measurements::sweepPendingRouting,
        operations = externalSyncOperations,
    )

    override fun observeAccounts(): Flow<List<Account>> = delegate.observeAccounts()

    override fun observeSettings(): Flow<AccountSettings> = delegate.observeSettings()

    override suspend fun getAccount(id: AccountId): Account? = delegate.getAccount(id)

    override suspend fun createAccount(account: NewAccount): Account = delegate.createAccount(account).also {
        measurements.sweepPendingRouting()
    }

    override suspend fun hasProfileRecalculationCandidates(accountId: AccountId): Boolean =
        delegate.hasProfileRecalculationCandidates(accountId)

    override suspend fun updateAccount(
        account: AccountUpdate,
        historyUpdateMode: ProfileHistoryUpdateMode,
    ): Account = accountUpdater.update(account, historyUpdateMode)

    override suspend fun attemptProfileUpdate(account: AccountUpdate): ProfileUpdateAttemptResult =
        accountUpdater.attempt(account)

    override suspend fun setPrimaryAccount(
        accountId: AccountId,
        historySyncMode: PrimaryHistorySyncMode,
    ) {
        val previousPrimary = delegate.observeSettings().first().primaryAccountId
        val previousWork = previousPrimary?.let { persistence.activeSyncWorkIds(it) }.orEmpty()
        delegate.setPrimaryAccount(accountId, historySyncMode)
        if (previousPrimary != accountId) syncScheduler.cancelAll(previousWork)
        measurements.sweepPendingRouting()
        measurements.sweepPendingSync()
    }

    override suspend fun deleteAccount(accountId: AccountId) {
        delegate.deleteAccount(accountId)
        measurements.sweepPendingRouting()
    }

    override suspend fun deletePrimaryWithReplacement(
        primaryAccountId: AccountId,
        replacementAccountId: AccountId?,
        historySyncMode: PrimaryHistorySyncMode,
    ) {
        val previousWork = persistence.activeSyncWorkIds(primaryAccountId)
        delegate.deletePrimaryWithReplacement(
            primaryAccountId = primaryAccountId,
            replacementAccountId = replacementAccountId,
            historySyncMode = historySyncMode,
        )
        syncScheduler.cancelAll(previousWork)
        measurements.sweepPendingRouting()
        measurements.sweepPendingSync()
    }

    override suspend fun updateWeightDeltaKg(weightDeltaKg: Double) {
        settingsWriter.updateWeightDeltaKg(weightDeltaKg)
        // A larger delta can resolve an existing FIFO item without another BLE packet.
        measurements.sweepPendingRouting()
    }

    override suspend fun updateIgnoreUnknownMeasurements(enabled: Boolean) {
        // This policy intentionally applies only to routing triggered after the setting changes.
        // Do not sweep the durable queue here.
        settingsWriter.updateIgnoreUnknownMeasurements(enabled)
    }
}

/**
 * Owns the cross-system ordering for account writes. Routing is deliberately outside the
 * serializer: it may start workers, but it must not keep their external gateway locked out.
 */
internal class SerializedAccountUpdater(
    private val updateDelegate: suspend (AccountUpdate, ProfileHistoryUpdateMode) -> Account,
    private val attemptDelegate: suspend (AccountUpdate) -> ProfileUpdateAttemptResult = { account ->
        ProfileUpdateAttemptResult.Saved(
            updateDelegate(account, ProfileHistoryUpdateMode.KEEP_EXISTING),
        )
    },
    private val sweepPendingRouting: suspend () -> Unit,
    private val operations: ExternalSyncOperationSerializer,
) {
    suspend fun attempt(account: AccountUpdate): ProfileUpdateAttemptResult {
        val result = operations.runExclusive { attemptDelegate(account) }
        if (result is ProfileUpdateAttemptResult.Saved) sweepPendingRouting()
        return result
    }

    suspend fun update(
        account: AccountUpdate,
        historyUpdateMode: ProfileHistoryUpdateMode,
    ): Account {
        val updated = operations.runExclusive { updateDelegate(account, historyUpdateMode) }
        sweepPendingRouting()
        return updated
    }
}
