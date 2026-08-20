package com.example.huaweimisync.data

import com.example.huaweimisync.domain.Account
import com.example.huaweimisync.domain.AccountId
import com.example.huaweimisync.domain.AccountRepository
import com.example.huaweimisync.domain.AccountSettings
import com.example.huaweimisync.domain.AccountSettingsWriter
import com.example.huaweimisync.domain.AccountUpdate
import com.example.huaweimisync.domain.NewAccount
import com.example.huaweimisync.domain.PrimaryHistorySyncMode
import com.example.huaweimisync.worker.MeasurementSyncScheduler
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
) : AccountRepository, AccountSettingsWriter {
    override fun observeAccounts(): Flow<List<Account>> = delegate.observeAccounts()

    override fun observeSettings(): Flow<AccountSettings> = delegate.observeSettings()

    override suspend fun getAccount(id: AccountId): Account? = delegate.getAccount(id)

    override suspend fun createAccount(account: NewAccount): Account = delegate.createAccount(account).also {
        measurements.sweepPendingRouting()
    }

    override suspend fun updateAccount(account: AccountUpdate): Account =
        delegate.updateAccount(account).also { measurements.sweepPendingRouting() }

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
