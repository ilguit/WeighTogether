package com.example.huaweimisync.data

import com.example.huaweimisync.core.BodyComposition
import com.example.huaweimisync.core.BodyCompositionCalculator
import com.example.huaweimisync.core.RawScaleMeasurement
import com.example.huaweimisync.core.Sex
import com.example.huaweimisync.domain.Account
import com.example.huaweimisync.domain.AccountId
import com.example.huaweimisync.domain.AccountMeasurement
import com.example.huaweimisync.domain.AccountProfile
import com.example.huaweimisync.domain.AccountRepository
import com.example.huaweimisync.domain.AccountSettings
import com.example.huaweimisync.domain.AccountUpdate
import com.example.huaweimisync.domain.CreateAccountAndAssignResult
import com.example.huaweimisync.domain.DiscardPendingResult
import com.example.huaweimisync.domain.DiscardPendingWithoutUndoResult
import com.example.huaweimisync.domain.ExternalSyncPolicy
import com.example.huaweimisync.domain.FinalizePendingResult
import com.example.huaweimisync.domain.NewAccount
import com.example.huaweimisync.domain.PendingDiscardUndoToken
import com.example.huaweimisync.domain.PendingMeasurement
import com.example.huaweimisync.domain.PendingMeasurementId
import com.example.huaweimisync.domain.PrimaryHistorySyncMode
import com.example.huaweimisync.domain.RestorePendingResult
import com.example.huaweimisync.domain.RoutingDecision
import com.example.huaweimisync.domain.routing.WeightHistoryRecord
import com.example.huaweimisync.domain.toPendingMeasurement
import com.example.huaweimisync.worker.MeasurementSyncScheduler
import com.example.huaweimisync.worker.PendingDecisionFallback
import com.example.huaweimisync.worker.PendingDecisionPresentationCoordinator
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MeasurementIngestionCoordinatorTest {
    private val primary = testAccount("primary", "Alice")
    private val secondary = testAccount("secondary", "Bob")

    @Test
    fun nonFinalPacketNeverCrossesTheDurableBoundary() = runBlocking {
        val events = mutableListOf<String>()
        val accounts = FakeAccountRepository(listOf(primary), primary.id, events)
        val persistence = FakeRoutingPersistence(accounts, events)
        val scheduler = UniqueFakeScheduler(events)
        val notifier = RecordingNotifier()

        val result = coordinator(persistence, accounts, scheduler, notifier).ingest(
            raw(70.0).copy(isStable = false),
        )

        assertEquals(MeasurementIngestionResult.IgnoredNotFinal, result)
        assertTrue(events.isEmpty())
        assertTrue(persistence.pendingSnapshot().isEmpty())
        assertTrue(scheduler.enqueued.isEmpty())
        assertTrue(notifier.counts.isEmpty())
    }

    @Test
    fun primaryAssignmentIsDurableBeforeMatchingAndSchedulesAfterAtomicFinalize() = runBlocking {
        val events = mutableListOf<String>()
        val accounts = FakeAccountRepository(listOf(primary), primary.id, events)
        val persistence = FakeRoutingPersistence(accounts, events)
        val scheduler = UniqueFakeScheduler(events)
        val coordinator = coordinator(persistence, accounts, scheduler)

        val result = coordinator.ingest(raw(70.0)) as MeasurementIngestionResult.Assigned

        assertEquals(ExternalSyncPolicy.AUTO, result.measurement.externalSyncPolicy)
        assertTrue(events.indexOf("enqueue") < events.indexOf("accounts"))
        assertTrue(events.indexOf("history:primary") < events.indexOf("finalize:primary"))
        assertTrue(events.indexOf("finalize:primary") < events.indexOf("schedule"))
        assertEquals(setOf(result.measurement.measurementId), scheduler.enqueued)
        assertTrue(persistence.pendingSnapshot().isEmpty())
    }

    @Test
    fun matchingSecondaryIsAccountLocalAndNeverReachesScheduler() = runBlocking {
        val accounts = FakeAccountRepository(listOf(primary, secondary), primary.id)
        val persistence = FakeRoutingPersistence(accounts).apply {
            histories[primary.id] = listOf(history(50.0))
            histories[secondary.id] = listOf(history(70.0))
        }
        val scheduler = UniqueFakeScheduler()

        val result = coordinator(persistence, accounts, scheduler).ingest(raw(70.0))
            as MeasurementIngestionResult.Assigned

        assertEquals(secondary.id, result.measurement.accountId)
        assertEquals(ExternalSyncPolicy.ACCOUNT_LOCAL, result.measurement.externalSyncPolicy)
        assertTrue(scheduler.enqueued.isEmpty())
    }

    @Test
    fun ambiguousMeasurementsStayDurableInFifoAndUpdateCount() = runBlocking {
        val third = testAccount("third", "Cara")
        val accounts = FakeAccountRepository(listOf(primary, secondary, third), primary.id)
        val persistence = FakeRoutingPersistence(accounts).apply {
            histories[primary.id] = listOf(history(50.0))
            histories[secondary.id] = listOf(history(69.0))
            histories[third.id] = listOf(history(71.0))
        }
        val notifier = RecordingNotifier()
        val coordinator = coordinator(
            persistence,
            accounts,
            UniqueFakeScheduler(),
            notifier,
        )

        val first = coordinator.ingest(raw(70.0, RAW_TIME))
            as MeasurementIngestionResult.AwaitingDecision
        val second = coordinator.ingest(raw(70.5, RAW_TIME.plusSeconds(1)))
            as MeasurementIngestionResult.AwaitingDecision

        assertTrue(first.decision is RoutingDecision.ChooseAccount)
        assertTrue(second.decision is RoutingDecision.ChooseAccount)
        assertEquals(listOf(first.pending.id, second.pending.id), persistence.pendingSnapshot().map { it.id })
        assertEquals(listOf(1, 2), notifier.counts)
    }

    @Test
    fun noMatchStaysPendingWhenIgnorePolicyIsDisabled() = runBlocking {
        val accounts = FakeAccountRepository(emptyList(), null)
        val persistence = FakeRoutingPersistence(accounts)
        val notifier = RecordingNotifier()

        val result = coordinator(
            persistence,
            accounts,
            UniqueFakeScheduler(),
            notifier,
        ).ingest(raw(70.0)) as MeasurementIngestionResult.AwaitingDecision

        assertEquals(RoutingDecision.NoMatch, result.decision)
        assertEquals(listOf(result.pending), persistence.pendingSnapshot())
        assertEquals(listOf(1), notifier.counts)
    }

    @Test
    fun enabledPolicyAutomaticallyTombstonesOnlyNoMatchAndClearsPresentation() = runBlocking {
        val accounts = FakeAccountRepository(emptyList(), null).apply {
            updateIgnoreUnknownMeasurements(true)
        }
        val persistence = FakeRoutingPersistence(accounts)
        val notifier = RecordingNotifier()
        val coordinator = coordinator(
            persistence,
            accounts,
            UniqueFakeScheduler(),
            notifier,
        )
        val packet = raw(70.0)

        assertEquals(
            MeasurementIngestionResult.AutomaticallyIgnoredUnknown,
            coordinator.ingest(packet),
        )
        assertTrue(persistence.pendingSnapshot().isEmpty())
        assertEquals(listOf(0), notifier.counts)
        assertEquals(MeasurementIngestionResult.Tombstoned, coordinator.ingest(packet))
    }

    @Test
    fun enabledIgnorePolicyDoesNotChangeChooseAccountRouting() = runBlocking {
        val third = testAccount("third", "Cara")
        val accounts = FakeAccountRepository(listOf(primary, secondary, third), primary.id).apply {
            updateIgnoreUnknownMeasurements(true)
        }
        val persistence = FakeRoutingPersistence(accounts).apply {
            histories[primary.id] = listOf(history(50.0))
            histories[secondary.id] = listOf(history(69.0))
            histories[third.id] = listOf(history(71.0))
        }

        val result = coordinator(persistence, accounts, UniqueFakeScheduler()).ingest(raw(70.0))
            as MeasurementIngestionResult.AwaitingDecision

        assertTrue(result.decision is RoutingDecision.ChooseAccount)
        assertEquals(listOf(result.pending), persistence.pendingSnapshot())
    }

    @Test
    fun policyChangeDoesNotSweepExistingPendingAndAtomicDeleteHasNoUndoToken() = runBlocking {
        val accounts = FakeAccountRepository(emptyList(), null)
        val persistence = FakeRoutingPersistence(accounts)
        val notifier = RecordingNotifier()
        val coordinator = coordinator(
            persistence,
            accounts,
            UniqueFakeScheduler(),
            notifier,
        )
        val waiting = coordinator.ingest(raw(70.0)) as MeasurementIngestionResult.AwaitingDecision

        accounts.updateIgnoreUnknownMeasurements(true)

        assertEquals(listOf(waiting.pending), persistence.pendingSnapshot())
        assertEquals(
            DiscardPendingWithoutUndoResult.Discarded,
            coordinator.discardAndUpdateIgnorePolicy(waiting.pending.id, false),
        )
        assertFalse(accounts.settings.value.ignoreUnknownMeasurements)
        assertTrue(persistence.pendingSnapshot().isEmpty())
        assertEquals(listOf(1, 0), notifier.counts)
    }

    @Test
    fun duplicateWorkerAndDoubleResolverTapCreateOneMeasurement() = runBlocking {
        val accounts = FakeAccountRepository(listOf(primary), primary.id)
        val persistence = FakeRoutingPersistence(accounts)
        val scheduler = UniqueFakeScheduler()
        val coordinator = coordinator(persistence, accounts, scheduler)
        val packet = raw(70.0)

        val first = coordinator.ingest(packet) as MeasurementIngestionResult.Assigned
        val duplicate = coordinator.ingest(packet) as MeasurementIngestionResult.Assigned

        assertTrue(duplicate.wasAlreadyFinalized)
        assertEquals(1, persistence.finalized.size)
        assertEquals(setOf(first.measurement.measurementId), scheduler.enqueued)

        val waitingPersistence = FakeRoutingPersistence(
            FakeAccountRepository(listOf(primary, secondary), primary.id),
        )
        val waitingAccounts = waitingPersistence.accounts
        waitingPersistence.histories[primary.id] = listOf(history(50.0))
        val waitingCoordinator = coordinator(waitingPersistence, waitingAccounts, UniqueFakeScheduler())
        val waiting = waitingCoordinator.ingest(packet) as MeasurementIngestionResult.AwaitingDecision
        val chosen = waitingCoordinator.chooseAccount(waiting.pending.id, secondary.id)
        val tappedAgain = waitingCoordinator.chooseAccount(waiting.pending.id, secondary.id)

        assertTrue(chosen is FinalizePendingResult.Finalized)
        assertTrue(tappedAgain is FinalizePendingResult.AlreadyFinalized)
        assertEquals(1, waitingPersistence.finalized.size)
        assertTrue(
            waitingCoordinator.discard(waiting.pending.id) is
                DiscardPendingResult.AlreadyFinalized,
        )
    }

    @Test
    fun incompleteAutoTargetStaysPendingAndOneShotPreviewDoesNotCrossPersistence() = runBlocking {
        val incomplete = primary.copy(profile = AccountProfile.IncompleteRecovery(heightCm = 175.0))
        val accounts = FakeAccountRepository(listOf(incomplete), incomplete.id)
        val persistence = FakeRoutingPersistence(accounts)
        val scheduler = UniqueFakeScheduler()
        val coordinator = coordinator(persistence, accounts, scheduler)

        val waiting = coordinator.ingest(raw(70.0)) as MeasurementIngestionResult.AwaitingDecision
        val pendingBeforePreview = persistence.pendingSnapshot()
        val accountsBeforePreview = accounts.observeAccounts().first()
        val historiesBeforePreview = persistence.histories.toMap()
        persistence.writeOperations.clear()
        val rawPreview = coordinator.previewWithoutSaving(waiting.pending.id)
        val calculated = coordinator.previewWithoutSaving(waiting.pending.id, completeProfile())

        assertNull(rawPreview?.composition)
        assertNotNull(calculated?.composition)
        assertEquals(pendingBeforePreview, persistence.pendingSnapshot())
        assertEquals(accountsBeforePreview, accounts.observeAccounts().first())
        assertEquals(historiesBeforePreview, persistence.histories)
        assertTrue(persistence.writeOperations.isEmpty())
        assertTrue(persistence.finalized.isEmpty())
        assertTrue(scheduler.enqueued.isEmpty())
        val discarded = coordinator.discard(waiting.pending.id)
            as DiscardPendingResult.Discarded

        assertEquals(waiting.pending.id, discarded.undoToken.pendingId)
        assertEquals(waiting.pending.deduplicationHash, discarded.undoToken.deduplicationHash)
        assertEquals(waiting.pending.enqueuedAt, discarded.undoToken.enqueuedAt)
        assertEquals(waiting.pending, discarded.undoToken.pending)
        assertNull(persistence.getPending(waiting.pending.id))
        assertEquals(
            DiscardPendingResult.PendingNotFound,
            coordinator.discard(waiting.pending.id),
        )
    }

    @Test
    fun discardFailureDoesNotMintTokenOrRefreshPendingPresentation() = runBlocking {
        val incomplete = primary.copy(profile = AccountProfile.IncompleteRecovery(heightCm = 175.0))
        val accounts = FakeAccountRepository(listOf(incomplete), incomplete.id)
        val persistence = FakeRoutingPersistence(accounts)
        val notifier = RecordingNotifier()
        val coordinator = coordinator(
            persistence,
            accounts,
            UniqueFakeScheduler(),
            notifier,
        )
        val waiting = coordinator.ingest(raw(70.0))
            as MeasurementIngestionResult.AwaitingDecision
        persistence.discardFailure = IllegalStateException("discard failed")

        val failure = runCatching { coordinator.discard(waiting.pending.id) }

        assertTrue(failure.exceptionOrNull() is IllegalStateException)
        assertNotNull(persistence.getPending(waiting.pending.id))
        assertEquals(listOf(1), notifier.counts)
    }

    @Test
    fun deletingLastPendingCancelsNotificationAndRestoreRepostsExactlyOnce() = runBlocking {
        val incomplete = primary.copy(profile = AccountProfile.IncompleteRecovery(heightCm = 175.0))
        val accounts = FakeAccountRepository(listOf(incomplete), incomplete.id)
        val persistence = FakeRoutingPersistence(accounts)
        val posted = mutableListOf<Int>()
        var cancelled = 0
        val presentation = PendingDecisionPresentationCoordinator(
            notificationsAllowed = { true },
            postNotification = posted::add,
            cancelNotification = { cancelled += 1 },
        )
        val presentedCounts = mutableListOf<Int>()
        val notifier = object : PendingDecisionNotifier {
            override fun updatePendingCount(count: Int) {
                presentedCounts += count
                presentation.updatePendingCount(count)
            }
        }
        val coordinator = coordinator(
            persistence,
            accounts,
            UniqueFakeScheduler(),
            notifier,
        )
        val waiting = coordinator.ingest(raw(70.0))
            as MeasurementIngestionResult.AwaitingDecision
        val token = (coordinator.discard(waiting.pending.id) as DiscardPendingResult.Discarded)
            .undoToken
        val repeatedDelete = coordinator.discard(waiting.pending.id)

        val restored = coordinator.restore(token)
        val repeated = coordinator.restore(token)

        assertEquals(DiscardPendingResult.PendingNotFound, repeatedDelete)
        assertEquals(RestorePendingResult.Restored(waiting.pending), restored)
        assertEquals(RestorePendingResult.AlreadyRestored(waiting.pending), repeated)
        assertEquals(listOf(waiting.pending), persistence.pendingSnapshot())
        assertEquals(listOf(1, 0, 1), presentedCounts)
        assertEquals(listOf(1, 1), posted)
        assertEquals(1, cancelled)
        assertEquals(PendingDecisionFallback.Hidden, presentation.notificationDeniedFallback.value)
    }

    @Test
    fun restoreFailureKeepsDiscardedStateAndDoesNotRefreshPresentation() = runBlocking {
        val incomplete = primary.copy(profile = AccountProfile.IncompleteRecovery(heightCm = 175.0))
        val accounts = FakeAccountRepository(listOf(incomplete), incomplete.id)
        val persistence = FakeRoutingPersistence(accounts)
        val notifier = RecordingNotifier()
        val coordinator = coordinator(
            persistence,
            accounts,
            UniqueFakeScheduler(),
            notifier,
        )
        val waiting = coordinator.ingest(raw(70.0))
            as MeasurementIngestionResult.AwaitingDecision
        val token = (coordinator.discard(waiting.pending.id) as DiscardPendingResult.Discarded)
            .undoToken
        persistence.restoreFailure = IllegalStateException("restore failed")

        val failure = runCatching { coordinator.restore(token) }

        assertTrue(failure.exceptionOrNull() is IllegalStateException)
        assertNull(persistence.getPending(waiting.pending.id))
        assertEquals(listOf(1, 0), notifier.counts)
    }

    @Test
    fun concurrentRefreshCannotPublishAnOlderPendingCountLast() = runBlocking {
        val accounts = FakeAccountRepository(listOf(primary), primary.id)
        val persistence = FakeRoutingPersistence(accounts)
        val inserted = persistence.enqueue(raw(70.0)) as PendingPersistenceResult.Inserted
        val notifier = RecordingNotifier()
        val coordinator = coordinator(
            persistence,
            accounts,
            UniqueFakeScheduler(),
            notifier,
        )
        val firstSnapshotCaptured = CompletableDeferred<Unit>()
        val releaseFirstSnapshot = CompletableDeferred<Unit>()
        persistence.firstPendingSnapshotCaptured = firstSnapshotCaptured
        persistence.releaseFirstPendingSnapshot = releaseFirstSnapshot

        val olderRefresh = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.refreshPendingPresentation()
        }
        firstSnapshotCaptured.await()
        assertTrue(persistence.discardPending(inserted.pending.id) is DiscardPendingResult.Discarded)
        val newerRefresh = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.refreshPendingPresentation()
        }

        releaseFirstSnapshot.complete(Unit)
        olderRefresh.await()
        newerRefresh.await()

        assertEquals(listOf(1, 0), notifier.counts)
    }

    private fun coordinator(
        persistence: FakeRoutingPersistence,
        accounts: FakeAccountRepository,
        scheduler: UniqueFakeScheduler,
        notifier: PendingDecisionNotifier = NoOpPendingDecisionNotifier,
    ) = MeasurementIngestionCoordinator(
        persistence = persistence,
        accounts = accounts,
        calculator = BodyCompositionCalculator(ZoneId.of("UTC")),
        syncScheduler = scheduler,
        notifier = notifier,
    )

    private fun history(weight: Double) = WeightHistoryRecord(RAW_TIME.minusSeconds(1), weight)
}

private class FakeRoutingPersistence(
    val accounts: FakeAccountRepository,
    private val events: MutableList<String> = mutableListOf(),
) : MeasurementRoutingPersistence {
    val histories = mutableMapOf<AccountId, List<WeightHistoryRecord>>()
    val finalized = linkedMapOf<String, AccountMeasurement>()
    val writeOperations = mutableListOf<String>()
    private val pending = linkedMapOf<PendingMeasurementId, PendingMeasurement>()
    private val finalizedByHash = mutableMapOf<String, AccountMeasurement>()
    private val tombstones = mutableSetOf<String>()
    private var nextId = 0
    var discardFailure: Throwable? = null
    var restoreFailure: Throwable? = null
    var firstPendingSnapshotCaptured: CompletableDeferred<Unit>? = null
    var releaseFirstPendingSnapshot: CompletableDeferred<Unit>? = null
    private var pendingSnapshotCallCount = 0

    override suspend fun enqueue(raw: RawScaleMeasurement): PendingPersistenceResult {
        events += "enqueue"
        writeOperations += "enqueue"
        val hash = hash(raw)
        if (hash in tombstones) return PendingPersistenceResult.Tombstoned
        finalizedByHash[hash]?.let { return PendingPersistenceResult.AlreadyFinalized(it) }
        pending.values.firstOrNull { it.deduplicationHash == hash }?.let {
            return PendingPersistenceResult.AlreadyPending(it)
        }
        nextId += 1
        val value = raw.toPendingMeasurement(
            PendingMeasurementId("pending-$nextId"),
            hash,
            RAW_TIME.plusSeconds(nextId.toLong()),
        )
        pending[value.id] = value
        return PendingPersistenceResult.Inserted(value)
    }

    override suspend fun getPending(id: PendingMeasurementId): PendingMeasurement? = pending[id]

    override suspend fun pendingSnapshot(): List<PendingMeasurement> {
        val snapshot = pending.values.toList()
        pendingSnapshotCallCount += 1
        if (pendingSnapshotCallCount == 1) {
            firstPendingSnapshotCaptured?.complete(Unit)
            releaseFirstPendingSnapshot?.await()
        }
        return snapshot
    }

    override suspend fun latestHistoryBefore(
        accountId: AccountId,
        measuredAtExclusive: Instant,
    ): List<WeightHistoryRecord> {
        events += "history:${accountId.value}"
        return histories[accountId].orEmpty().filter { it.measuredAt.isBefore(measuredAtExclusive) }
    }

    override suspend fun finalizePending(
        pendingId: PendingMeasurementId,
        accountId: AccountId,
    ): FinalizePendingResult {
        writeOperations += "finalize"
        finalized[pendingId.value]?.let { return FinalizePendingResult.AlreadyFinalized(it) }
        val value = pending[pendingId] ?: return FinalizePendingResult.PendingNotFound
        val account = accounts.getAccount(accountId) ?: return FinalizePendingResult.AccountNotFound
        if (account.profile !is AccountProfile.Complete) return FinalizePendingResult.ProfileIncomplete
        events += "finalize:${accountId.value}"
        val policy = if (accounts.settings.value.primaryAccountId == accountId) {
            ExternalSyncPolicy.AUTO
        } else {
            ExternalSyncPolicy.ACCOUNT_LOCAL
        }
        val measurement = AccountMeasurement(
            accountId = accountId,
            composition = composition(value, "measurement-${finalized.size + 1}"),
            externalSyncPolicy = policy,
            createdAt = RAW_TIME,
        )
        finalized[pendingId.value] = measurement
        finalizedByHash[value.deduplicationHash] = measurement
        pending.remove(pendingId)
        return FinalizePendingResult.Finalized(measurement)
    }

    override suspend fun createAccountAndAssignPending(
        pendingId: PendingMeasurementId,
        account: NewAccount,
    ): CreateAccountAndAssignResult {
        writeOperations += "create-account-and-assign"
        val created = accounts.createAccount(account)
        return when (val result = finalizePending(pendingId, created.id)) {
            is FinalizePendingResult.Finalized -> CreateAccountAndAssignResult.Created(created, result.measurement)
            is FinalizePendingResult.AlreadyFinalized -> CreateAccountAndAssignResult.AlreadyFinalized(result.measurement)
            FinalizePendingResult.PendingNotFound -> CreateAccountAndAssignResult.PendingNotFound
            FinalizePendingResult.AccountNotFound,
            FinalizePendingResult.ProfileIncomplete,
            -> error("fake created a complete account")
        }
    }

    override suspend fun discardPending(
        pendingId: PendingMeasurementId,
    ): DiscardPendingResult {
        writeOperations += "discard"
        discardFailure?.let { throw it }
        finalized[pendingId.value]?.let { finalizedMeasurement ->
            return DiscardPendingResult.AlreadyFinalized(finalizedMeasurement)
        }
        val value = pending.remove(pendingId) ?: return DiscardPendingResult.PendingNotFound
        tombstones += value.deduplicationHash
        return DiscardPendingResult.Discarded(PendingDiscardUndoToken(value))
    }

    override suspend fun discardUnknownPendingIfEnabled(
        pendingId: PendingMeasurementId,
    ): AutoIgnorePendingPersistenceResult {
        if (!accounts.settings.value.ignoreUnknownMeasurements) {
            return AutoIgnorePendingPersistenceResult.PolicyDisabled
        }
        finalized[pendingId.value]?.let {
            return AutoIgnorePendingPersistenceResult.AlreadyFinalized(it)
        }
        val value = pending.remove(pendingId)
            ?: return AutoIgnorePendingPersistenceResult.PendingNotFound
        tombstones += value.deduplicationHash
        return AutoIgnorePendingPersistenceResult.Discarded
    }

    override suspend fun discardPendingAndUpdateIgnorePolicy(
        pendingId: PendingMeasurementId,
        ignoreUnknownMeasurements: Boolean,
    ): DiscardPendingWithoutUndoResult {
        finalized[pendingId.value]?.let {
            return DiscardPendingWithoutUndoResult.AlreadyFinalized(it)
        }
        val value = pending.remove(pendingId)
            ?: return DiscardPendingWithoutUndoResult.PendingNotFound
        tombstones += value.deduplicationHash
        accounts.updateIgnoreUnknownMeasurements(ignoreUnknownMeasurements)
        return DiscardPendingWithoutUndoResult.Discarded
    }

    override suspend fun restorePending(
        undoToken: PendingDiscardUndoToken,
    ): RestorePendingResult {
        restoreFailure?.let { throw it }
        val value = undoToken.pending
        finalized[value.id.value]?.let { return RestorePendingResult.AlreadyFinalized(it) }
        finalizedByHash[value.deduplicationHash]?.let {
            return RestorePendingResult.AlreadyFinalized(it)
        }
        pending[value.id]?.let { existing ->
            return if (existing == value) {
                RestorePendingResult.AlreadyRestored(existing)
            } else {
                RestorePendingResult.Conflict(existing)
            }
        }
        pending.values.firstOrNull {
            it.deduplicationHash == value.deduplicationHash
        }?.let { return RestorePendingResult.Conflict(it) }
        pending[value.id] = value
        tombstones -= value.deduplicationHash
        return RestorePendingResult.Restored(value)
    }
}

private class FakeAccountRepository(
    initialAccounts: List<Account>,
    primaryId: AccountId?,
    private val events: MutableList<String> = mutableListOf(),
) : AccountRepository {
    private val values = MutableStateFlow(initialAccounts)
    val settings = MutableStateFlow(AccountSettings(primaryId))

    override fun observeAccounts(): Flow<List<Account>> {
        events += "accounts"
        return values
    }

    override fun observeSettings(): Flow<AccountSettings> {
        events += "settings"
        return settings
    }

    override suspend fun getAccount(id: AccountId): Account? = values.value.firstOrNull { it.id == id }

    override suspend fun createAccount(account: NewAccount): Account {
        val value = testAccount("created-${values.value.size}", account.displayName, account.profile)
        values.value = values.value + value
        if (settings.value.primaryAccountId == null) settings.value = settings.value.copy(primaryAccountId = value.id)
        return value
    }

    override suspend fun updateAccount(account: AccountUpdate): Account = error("not needed")

    override suspend fun setPrimaryAccount(accountId: AccountId, historySyncMode: PrimaryHistorySyncMode) {
        settings.value = settings.value.copy(primaryAccountId = accountId)
    }

    override suspend fun updateWeightDeltaKg(weightDeltaKg: Double) {
        settings.value = settings.value.copy(weightDeltaKg = weightDeltaKg)
    }

    override suspend fun updateIgnoreUnknownMeasurements(enabled: Boolean) {
        settings.value = settings.value.copy(ignoreUnknownMeasurements = enabled)
    }

    override suspend fun deleteAccount(accountId: AccountId) {
        values.value = values.value.filterNot { it.id == accountId }
    }

    override suspend fun deletePrimaryWithReplacement(
        primaryAccountId: AccountId,
        replacementAccountId: AccountId?,
        historySyncMode: PrimaryHistorySyncMode,
    ) {
        deleteAccount(primaryAccountId)
        settings.value = settings.value.copy(primaryAccountId = replacementAccountId)
    }
}

private class UniqueFakeScheduler(
    private val events: MutableList<String> = mutableListOf(),
) : MeasurementSyncScheduler {
    val enqueued = linkedSetOf<String>()
    val cancelled = linkedSetOf<String>()

    override fun enqueue(measurementId: String) {
        events += "schedule"
        enqueued += measurementId
    }

    override fun cancel(measurementId: String) {
        cancelled += measurementId
    }
}

private class RecordingNotifier : PendingDecisionNotifier {
    val counts = mutableListOf<Int>()
    override fun updatePendingCount(count: Int) {
        counts += count
    }
}

private fun testAccount(
    id: String,
    name: String,
    profile: AccountProfile = completeProfile(),
) = Account(
    id = AccountId(id),
    displayName = name,
    profile = profile,
    createdAt = RAW_TIME.minusSeconds(100),
    updatedAt = RAW_TIME.minusSeconds(100),
)

private fun completeProfile() = AccountProfile.Complete(
    heightCm = 175.0,
    birthDate = LocalDate.of(1990, 1, 1),
    sex = Sex.MALE,
)

private fun raw(weight: Double, measuredAt: Instant = RAW_TIME) = RawScaleMeasurement(
    deviceAddress = "AA:BB:CC:DD:EE:FF",
    measuredAt = measuredAt,
    weightKg = weight,
    impedanceOhm = 500,
    isStable = true,
    hasImpedance = true,
    rawPayload = byteArrayOf(1, 2, 3),
)

private fun hash(raw: RawScaleMeasurement): String =
    "${raw.deviceAddress}|${raw.measuredAt}|${raw.weightKg}|${raw.impedanceOhm}"

private fun composition(pending: PendingMeasurement, id: String) = BodyComposition(
    measurementId = id,
    deviceAddress = pending.deviceAddress,
    measuredAt = pending.measuredAt,
    weightKg = pending.weightKg,
    impedanceOhm = pending.impedanceOhm,
    bmi = 22.0,
    bodyFatPercent = 20.0,
    bodyFatMassKg = 14.0,
    waterPercent = 55.0,
    waterMassKg = 38.5,
    muscleMassKg = 50.0,
    skeletalMuscleMassKg = 25.0,
    boneMassKg = 3.0,
    proteinPercent = 18.0,
    proteinMassKg = 12.6,
    visceralFatLevel = 7.0,
    basalMetabolicRateKcal = 1_500.0,
    metabolicAge = 35,
    leanBodyMassKg = 56.0,
    algorithmVersion = "test",
)

private val RAW_TIME: Instant = Instant.parse("2026-08-15T12:00:00Z")
