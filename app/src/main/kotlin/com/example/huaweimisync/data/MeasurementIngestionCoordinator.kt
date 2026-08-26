package com.example.huaweimisync.data

import com.example.huaweimisync.core.BodyCompositionCalculator
import com.example.huaweimisync.core.RawScaleMeasurement
import com.example.huaweimisync.domain.AccountId
import com.example.huaweimisync.domain.AccountMeasurement
import com.example.huaweimisync.domain.AccountProfile
import com.example.huaweimisync.domain.AccountRepository
import com.example.huaweimisync.domain.CreateAccountAndAssignResult
import com.example.huaweimisync.domain.DiscardPendingResult
import com.example.huaweimisync.domain.DiscardPendingAndUpdateIgnorePolicyResult
import com.example.huaweimisync.domain.ExternalSyncPolicy
import com.example.huaweimisync.domain.FinalizePendingResult
import com.example.huaweimisync.domain.NewAccount
import com.example.huaweimisync.domain.PendingDiscardUndoToken
import com.example.huaweimisync.domain.PendingMeasurement
import com.example.huaweimisync.domain.PendingMeasurementId
import com.example.huaweimisync.domain.PendingMeasurementPreview
import com.example.huaweimisync.domain.RestorePendingResult
import com.example.huaweimisync.domain.RoutingDecision
import com.example.huaweimisync.domain.isAwaitingDecisionAt
import com.example.huaweimisync.domain.isComplete
import com.example.huaweimisync.domain.routing.MatchingEngine
import com.example.huaweimisync.domain.routing.WeightHistoryRecord
import com.example.huaweimisync.domain.toRawScaleMeasurement
import com.example.huaweimisync.domain.toUserProfileOrNull
import com.example.huaweimisync.worker.MeasurementSyncScheduler
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Small persistence boundary which keeps ingestion logic unit-testable without Room. */
interface MeasurementRoutingPersistence {
    /** Last stable packet accepted before the current in-memory consumer session. */
    suspend fun latestAcceptedStableMeasurement(): RawScaleMeasurement? = null

    suspend fun enqueue(raw: RawScaleMeasurement): PendingPersistenceResult

    suspend fun enqueue(
        raw: RawScaleMeasurement,
        matchingEngine: MatchingEngine,
    ): PendingPersistenceResult = enqueue(raw)

    suspend fun getPending(id: PendingMeasurementId): PendingMeasurement?

    suspend fun pendingSnapshot(): List<PendingMeasurement>

    /** Refreshes best-effort preliminary matches after account or routing-setting changes. */
    suspend fun reclassifyPending(matchingEngine: MatchingEngine): List<PendingMeasurement>? = null

    suspend fun latestHistoryBefore(
        accountId: AccountId,
        measuredAtExclusive: Instant,
    ): List<WeightHistoryRecord>

    suspend fun finalizePending(
        pendingId: PendingMeasurementId,
        accountId: AccountId,
    ): FinalizePendingResult

    /** Room implementations must re-check the deadline in the finalize transaction. */
    suspend fun finalizePendingIfDue(
        pendingId: PendingMeasurementId,
        accountId: AccountId,
        now: Instant,
    ): DuePendingPersistenceResult {
        val pending = getPending(pendingId) ?: return DuePendingPersistenceResult.PendingNotFound
        if (now < pending.finalizeAfter) return DuePendingPersistenceResult.NotDue(pending)
        return finalizePending(pendingId, accountId).toDuePersistenceResult()
    }

    suspend fun createAccountAndAssignPending(
        pendingId: PendingMeasurementId,
        account: NewAccount,
    ): CreateAccountAndAssignResult

    suspend fun discardPending(pendingId: PendingMeasurementId): DiscardPendingResult

    /** Re-checks the persisted policy and discards in the same transaction. */
    suspend fun discardUnknownPendingIfEnabled(
        pendingId: PendingMeasurementId,
    ): AutoIgnorePendingPersistenceResult

    /** Room implementations must re-check the deadline in the discard transaction. */
    suspend fun discardUnknownPendingIfDue(
        pendingId: PendingMeasurementId,
        now: Instant,
    ): DueUnknownDiscardPersistenceResult {
        val pending = getPending(pendingId)
            ?: return DueUnknownDiscardPersistenceResult.PendingNotFound
        if (now < pending.finalizeAfter) return DueUnknownDiscardPersistenceResult.NotDue(pending)
        return discardUnknownPendingIfEnabled(pendingId).toDueDiscardResult()
    }

    suspend fun discardPendingAndUpdateIgnorePolicy(
        pendingId: PendingMeasurementId,
        ignoreUnknownMeasurements: Boolean,
    ): DiscardPendingAndUpdateIgnorePolicyResult

    suspend fun restorePending(undoToken: PendingDiscardUndoToken): RestorePendingResult

    /** Optional Room fast path which matches and writes under one database transaction. */
    suspend fun routeDueAtomically(
        pendingId: PendingMeasurementId,
        now: Instant,
        matchingEngine: MatchingEngine,
    ): AtomicDueRoutingResult? = null
}

sealed interface AtomicDueRoutingResult {
    data class NotDue(val pending: PendingMeasurement) : AtomicDueRoutingResult
    data class Finalized(val measurement: AccountMeasurement) : AtomicDueRoutingResult
    data class AlreadyFinalized(val measurement: AccountMeasurement) : AtomicDueRoutingResult
    data class AwaitingDecision(
        val pending: PendingMeasurement,
        val decision: RoutingDecision,
    ) : AtomicDueRoutingResult
    data object AutomaticallyIgnoredUnknown : AtomicDueRoutingResult
    data object PendingNotFound : AtomicDueRoutingResult
    data object AccountUnavailable : AtomicDueRoutingResult
}

sealed interface DuePendingPersistenceResult {
    data class NotDue(val pending: PendingMeasurement) : DuePendingPersistenceResult
    data class Finalized(val measurement: AccountMeasurement) : DuePendingPersistenceResult
    data class AlreadyFinalized(val measurement: AccountMeasurement) : DuePendingPersistenceResult
    data object PendingNotFound : DuePendingPersistenceResult
    data object AccountNotFound : DuePendingPersistenceResult
    data object ProfileIncomplete : DuePendingPersistenceResult
}

sealed interface DueUnknownDiscardPersistenceResult {
    data class NotDue(val pending: PendingMeasurement) : DueUnknownDiscardPersistenceResult
    data object Discarded : DueUnknownDiscardPersistenceResult
    data object PolicyDisabled : DueUnknownDiscardPersistenceResult
    data object PendingNotFound : DueUnknownDiscardPersistenceResult
    data class AlreadyFinalized(val measurement: AccountMeasurement) :
        DueUnknownDiscardPersistenceResult
}

sealed interface AggregateFinalizationResult {
    data class Reschedule(val pending: PendingMeasurement) : AggregateFinalizationResult
    data class Completed(val outcome: MeasurementIngestionResult) : AggregateFinalizationResult
}

sealed interface AutoIgnorePendingPersistenceResult {
    data object Discarded : AutoIgnorePendingPersistenceResult
    data object PolicyDisabled : AutoIgnorePendingPersistenceResult
    data object PendingNotFound : AutoIgnorePendingPersistenceResult
    data class AlreadyFinalized(val measurement: AccountMeasurement) :
        AutoIgnorePendingPersistenceResult
}

interface PendingDecisionNotifier {
    /** Implementations must treat repeated values as idempotent updates. */
    fun updatePendingCount(count: Int)
}

object NoOpPendingDecisionNotifier : PendingDecisionNotifier {
    override fun updatePendingCount(count: Int) = Unit
}

sealed interface MeasurementIngestionResult {
    data object IgnoredNotFinal : MeasurementIngestionResult
    /** The stable packet exactly repeats the durable last-accepted packet. */
    data object ExactReplay : MeasurementIngestionResult
    /** A new durable debounce aggregate was created; no routing or sync has happened yet. */
    data class CreatedAggregate(val pending: PendingMeasurement) : MeasurementIngestionResult
    /** An existing aggregate was enriched or had its sliding deadline extended. */
    data class UpdatedAggregate(
        val pending: PendingMeasurement,
        val wasEnriched: Boolean,
        val shouldScheduleFinalization: Boolean = true,
    ) : MeasurementIngestionResult
    /** A previously finalized weight-only row was enriched atomically and retained its identity. */
    data class UpgradedFinalized(val measurement: AccountMeasurement) : MeasurementIngestionResult
    /** A nearby finalized measurement authoritatively suppressed the packet. */
    data object SuppressedFinal : MeasurementIngestionResult
    /** A nearby active tombstone authoritatively suppressed the packet. */
    data object SuppressedTombstone : MeasurementIngestionResult
    data object Tombstoned : MeasurementIngestionResult
    data object PendingMissing : MeasurementIngestionResult
    /** A NoMatch pending value was durably tombstoned according to the saved policy. */
    data object AutomaticallyIgnoredUnknown : MeasurementIngestionResult
    /** Compatibility-only outcomes for the pre-integration repository constructor. */
    data object LegacyDuplicate : MeasurementIngestionResult
    data object LegacyProfileMissing : MeasurementIngestionResult

    data class Assigned(
        val measurement: AccountMeasurement,
        val wasAlreadyFinalized: Boolean = false,
    ) : MeasurementIngestionResult

    data class AwaitingDecision(
        val pending: PendingMeasurement,
        val decision: RoutingDecision,
        val pendingCount: Int,
    ) : MeasurementIngestionResult
}

data class MeasurementIngestionSweepResult(
    val assignedCount: Int,
    val awaitingDecisionCount: Int,
)

/**
 * Durable-first account routing. Body composition is never calculated until a concrete account
 * has been selected, and Room owns the final calculation/insert/delete transaction.
 */
class MeasurementIngestionCoordinator(
    private val persistence: MeasurementRoutingPersistence,
    private val accounts: AccountRepository,
    private val calculator: BodyCompositionCalculator,
    private val syncScheduler: MeasurementSyncScheduler,
    private val notifier: PendingDecisionNotifier = NoOpPendingDecisionNotifier,
    private val matchingEngine: MatchingEngine = MatchingEngine(),
) {
    private val pendingPresentationMutex = Mutex()

    suspend fun ingest(raw: RawScaleMeasurement): MeasurementIngestionResult {
        if (!raw.isStableWeight) return MeasurementIngestionResult.IgnoredNotFinal

        return when (val enqueued = persistence.enqueue(raw, matchingEngine)) {
            PendingPersistenceResult.ExactReplay -> MeasurementIngestionResult.ExactReplay
            is PendingPersistenceResult.Inserted ->
                MeasurementIngestionResult.CreatedAggregate(enqueued.pending)
            is PendingPersistenceResult.AlreadyPending -> {
                // An awaiting aggregate may be enriched after it was already presented. Re-posting
                // the authoritative count keeps notification/fallback state aligned with Room.
                if (enqueued.pending.isAwaitingDecisionAt(Instant.now())) {
                    refreshPendingPresentation()
                }
                MeasurementIngestionResult.UpdatedAggregate(
                    pending = enqueued.pending,
                    wasEnriched = enqueued.wasEnriched,
                    shouldScheduleFinalization = enqueued.shouldScheduleFinalization,
                )
            }
            is PendingPersistenceResult.AlreadyFinalized -> MeasurementIngestionResult.SuppressedFinal
            is PendingPersistenceResult.UpgradedFinalized -> {
                scheduleIfEligible(enqueued.measurement)
                MeasurementIngestionResult.UpgradedFinalized(enqueued.measurement)
            }
            PendingPersistenceResult.Tombstoned -> MeasurementIngestionResult.SuppressedTombstone
        }
    }

    suspend fun route(pendingId: PendingMeasurementId): MeasurementIngestionResult {
        val pending = persistence.getPending(pendingId)
            ?: return alreadyFinalizedOrMissing(pendingId)
        return route(pending)
    }

    /** Worker-only entry point: routing starts only after the persisted sliding deadline. */
    suspend fun finalizeDue(
        pendingId: PendingMeasurementId,
        timestamp: Instant = Instant.now(),
    ): AggregateFinalizationResult {
        val pending = persistence.getPending(pendingId)
            ?: return AggregateFinalizationResult.Completed(
                alreadyFinalizedOrMissing(pendingId),
            )
        if (timestamp < pending.finalizeAfter) {
            return AggregateFinalizationResult.Reschedule(pending)
        }

        persistence.routeDueAtomically(pendingId, timestamp, matchingEngine)?.let { atomic ->
            return when (atomic) {
                is AtomicDueRoutingResult.NotDue ->
                    AggregateFinalizationResult.Reschedule(atomic.pending)
                is AtomicDueRoutingResult.Finalized -> {
                    scheduleIfEligible(atomic.measurement)
                    refreshPendingPresentation()
                    AggregateFinalizationResult.Completed(
                        MeasurementIngestionResult.Assigned(atomic.measurement),
                    )
                }
                is AtomicDueRoutingResult.AlreadyFinalized -> {
                    refreshPendingPresentation()
                    AggregateFinalizationResult.Completed(
                        MeasurementIngestionResult.Assigned(
                            atomic.measurement,
                            wasAlreadyFinalized = true,
                        ),
                    )
                }
                is AtomicDueRoutingResult.AwaitingDecision ->
                    awaitingDecision(atomic.pending, atomic.decision)
                AtomicDueRoutingResult.AutomaticallyIgnoredUnknown -> {
                    refreshPendingPresentation()
                    AggregateFinalizationResult.Completed(
                        MeasurementIngestionResult.AutomaticallyIgnoredUnknown,
                    )
                }
                AtomicDueRoutingResult.PendingNotFound ->
                    AggregateFinalizationResult.Completed(alreadyFinalizedOrMissing(pendingId))
                AtomicDueRoutingResult.AccountUnavailable -> awaitingDecision(
                    pending,
                    RoutingDecision.NoMatch,
                )
            }
        }

        val accountSnapshot = accounts.observeAccounts().first()
        val settings = accounts.observeSettings().first()
        val histories = accountSnapshot.associate { account ->
            account.id to persistence.latestHistoryBefore(account.id, pending.measuredAt)
        }
        val decision = matchingEngine.match(pending, accountSnapshot, histories, settings)
        val selectedAccountId = when (decision) {
            is RoutingDecision.AssignPrimary -> decision.accountId
            is RoutingDecision.AssignSingle -> decision.candidate.accountId
            is RoutingDecision.ChooseAccount,
            RoutingDecision.NoMatch,
            -> null
        }

        if (selectedAccountId != null) {
            return when (
                val result = persistence.finalizePendingIfDue(
                    pending.id,
                    selectedAccountId,
                    timestamp,
                )
            ) {
                is DuePendingPersistenceResult.NotDue ->
                    AggregateFinalizationResult.Reschedule(result.pending)
                is DuePendingPersistenceResult.Finalized -> {
                    scheduleIfEligible(result.measurement)
                    refreshPendingPresentation()
                    AggregateFinalizationResult.Completed(
                        MeasurementIngestionResult.Assigned(result.measurement),
                    )
                }
                is DuePendingPersistenceResult.AlreadyFinalized -> {
                    refreshPendingPresentation()
                    AggregateFinalizationResult.Completed(
                        MeasurementIngestionResult.Assigned(
                            result.measurement,
                            wasAlreadyFinalized = true,
                        ),
                    )
                }
                DuePendingPersistenceResult.PendingNotFound ->
                    AggregateFinalizationResult.Completed(alreadyFinalizedOrMissing(pending.id))
                DuePendingPersistenceResult.AccountNotFound,
                DuePendingPersistenceResult.ProfileIncomplete,
                -> null
            } ?: awaitingDecision(pending, decision)
        }

        if (decision === RoutingDecision.NoMatch && settings.ignoreUnknownMeasurements) {
            return when (val ignored = persistence.discardUnknownPendingIfDue(pending.id, timestamp)) {
                is DueUnknownDiscardPersistenceResult.NotDue ->
                    AggregateFinalizationResult.Reschedule(ignored.pending)
                DueUnknownDiscardPersistenceResult.Discarded -> {
                    refreshPendingPresentation()
                    AggregateFinalizationResult.Completed(
                        MeasurementIngestionResult.AutomaticallyIgnoredUnknown,
                    )
                }
                is DueUnknownDiscardPersistenceResult.AlreadyFinalized -> {
                    refreshPendingPresentation()
                    AggregateFinalizationResult.Completed(
                        MeasurementIngestionResult.Assigned(
                            ignored.measurement,
                            wasAlreadyFinalized = true,
                        ),
                    )
                }
                DueUnknownDiscardPersistenceResult.PendingNotFound ->
                    AggregateFinalizationResult.Completed(alreadyFinalizedOrMissing(pending.id))
                DueUnknownDiscardPersistenceResult.PolicyDisabled -> awaitingDecision(
                    pending,
                    decision,
                )
            }
        }
        return awaitingDecision(pending, decision)
    }

    private suspend fun awaitingDecision(
        pending: PendingMeasurement,
        decision: RoutingDecision,
    ): AggregateFinalizationResult {
        val count = refreshPendingPresentation()
        return AggregateFinalizationResult.Completed(
            MeasurementIngestionResult.AwaitingDecision(pending, decision, count),
        )
    }

    suspend fun chooseAccount(
        pendingId: PendingMeasurementId,
        accountId: AccountId,
    ): FinalizePendingResult {
        val result = persistence.finalizePending(pendingId, accountId)
        when (result) {
            is FinalizePendingResult.Finalized -> scheduleIfEligible(result.measurement)
            is FinalizePendingResult.AlreadyFinalized -> Unit
            FinalizePendingResult.PendingNotFound,
            FinalizePendingResult.AccountNotFound,
            FinalizePendingResult.ProfileIncomplete,
            -> Unit
        }
        refreshPendingPresentation()
        return result
    }

    suspend fun createAccountAndAssign(
        pendingId: PendingMeasurementId,
        account: NewAccount,
    ): CreateAccountAndAssignResult {
        val result = persistence.createAccountAndAssignPending(pendingId, account)
        when (result) {
            is CreateAccountAndAssignResult.Created -> scheduleIfEligible(result.measurement)
            is CreateAccountAndAssignResult.AlreadyFinalized -> Unit
            CreateAccountAndAssignResult.PendingNotFound,
            is CreateAccountAndAssignResult.NameConflict,
            -> Unit
        }
        refreshPendingPresentation()
        return result
    }

    suspend fun discard(pendingId: PendingMeasurementId): DiscardPendingResult {
        val result = persistence.discardPending(pendingId)
        if (result is DiscardPendingResult.Discarded) refreshPendingPresentation()
        return result
    }

    suspend fun discardAndUpdateIgnorePolicy(
        pendingId: PendingMeasurementId,
        ignoreUnknownMeasurements: Boolean,
    ): DiscardPendingAndUpdateIgnorePolicyResult {
        val result = persistence.discardPendingAndUpdateIgnorePolicy(
            pendingId,
            ignoreUnknownMeasurements,
        )
        if (result is DiscardPendingAndUpdateIgnorePolicyResult.Discarded) {
            refreshPendingPresentation()
        }
        return result
    }

    suspend fun restore(undoToken: PendingDiscardUndoToken): RestorePendingResult {
        val result = persistence.restorePending(undoToken)
        if (result is RestorePendingResult.Restored) refreshPendingPresentation()
        return result
    }

    /** Calculates only in memory; the supplied profile and result never cross persistence. */
    suspend fun previewWithoutSaving(
        pendingId: PendingMeasurementId,
        oneShotProfile: AccountProfile.Complete? = null,
    ): PendingMeasurementPreview? {
        val pending = persistence.getPending(pendingId) ?: return null
        val raw = pending.toRawScaleMeasurement()
        val composition = oneShotProfile?.toUserProfileOrNull()?.takeIf {
            raw.hasFullBodyComposition
        }?.let { profile ->
            calculator.calculate(raw, profile)
        }
        return PendingMeasurementPreview(pending, composition)
    }

    suspend fun sweepPendingRouting(): MeasurementIngestionSweepResult {
        var assigned = 0
        var awaiting = 0
        val timestamp = Instant.now()
        // Snapshot order is FIFO. Only aggregates whose debounce deadline elapsed are routable.
        val pendingSnapshot = persistence.reclassifyPending(matchingEngine)
            ?: persistence.pendingSnapshot()
        pendingSnapshot.forEach { pending ->
            when (val finalized = finalizeDue(pending.id, timestamp)) {
                is AggregateFinalizationResult.Reschedule -> Unit
                is AggregateFinalizationResult.Completed -> when (finalized.outcome) {
                    is MeasurementIngestionResult.Assigned -> assigned += 1
                    is MeasurementIngestionResult.AwaitingDecision -> awaiting += 1
                    else -> Unit
                }
            }
        }
        refreshPendingPresentation()
        return MeasurementIngestionSweepResult(assigned, awaiting)
    }

    suspend fun refreshPendingPresentation(): Int = pendingPresentationMutex.withLock {
        val timestamp = Instant.now()
        val count = persistence.pendingSnapshot().count { it.isAwaitingDecisionAt(timestamp) }
        notifier.updatePendingCount(count)
        count
    }

    private suspend fun route(pending: PendingMeasurement): MeasurementIngestionResult {
        val accountSnapshot = accounts.observeAccounts().first()
        val settings = accounts.observeSettings().first()
        val histories = accountSnapshot.associate { account ->
            account.id to persistence.latestHistoryBefore(account.id, pending.measuredAt)
        }
        val decision = matchingEngine.match(pending, accountSnapshot, histories, settings)
        val selectedAccountId = when (decision) {
            is RoutingDecision.AssignPrimary -> decision.accountId
            is RoutingDecision.AssignSingle -> decision.candidate.accountId
            is RoutingDecision.ChooseAccount,
            RoutingDecision.NoMatch,
            -> null
        }

        if (selectedAccountId != null) {
            when (val finalized = persistence.finalizePending(pending.id, selectedAccountId)) {
                is FinalizePendingResult.Finalized -> {
                    scheduleIfEligible(finalized.measurement)
                    refreshPendingPresentation()
                    return MeasurementIngestionResult.Assigned(finalized.measurement)
                }
                is FinalizePendingResult.AlreadyFinalized -> {
                    refreshPendingPresentation()
                    return MeasurementIngestionResult.Assigned(
                        measurement = finalized.measurement,
                        wasAlreadyFinalized = true,
                    )
                }
                FinalizePendingResult.PendingNotFound -> return alreadyFinalizedOrMissing(pending.id)
                FinalizePendingResult.AccountNotFound,
                FinalizePendingResult.ProfileIncomplete,
                -> Unit // Keep the durable pending value for resolver/recovery.
            }
        }

        val count = refreshPendingPresentation()
        return MeasurementIngestionResult.AwaitingDecision(pending, decision, count)
    }

    private suspend fun alreadyFinalizedOrMissing(
        pendingId: PendingMeasurementId,
    ): MeasurementIngestionResult {
        refreshPendingPresentation()
        return MeasurementIngestionResult.PendingMissing
    }

    private suspend fun scheduleIfEligible(measurement: AccountMeasurement) {
        if (measurement.externalSyncPolicy != ExternalSyncPolicy.AUTO) return
        val settings = accounts.observeSettings().first()
        if (settings.primaryAccountId != measurement.accountId) return
        val account = accounts.getAccount(measurement.accountId) ?: return
        if (!account.profile.isComplete) return
        syncScheduler.enqueueInitial(measurement.measurementId)
    }
}

private fun FinalizePendingResult.toDuePersistenceResult(): DuePendingPersistenceResult = when (this) {
    is FinalizePendingResult.Finalized -> DuePendingPersistenceResult.Finalized(measurement)
    is FinalizePendingResult.AlreadyFinalized ->
        DuePendingPersistenceResult.AlreadyFinalized(measurement)
    FinalizePendingResult.PendingNotFound -> DuePendingPersistenceResult.PendingNotFound
    FinalizePendingResult.AccountNotFound -> DuePendingPersistenceResult.AccountNotFound
    FinalizePendingResult.ProfileIncomplete -> DuePendingPersistenceResult.ProfileIncomplete
}

private fun AutoIgnorePendingPersistenceResult.toDueDiscardResult():
    DueUnknownDiscardPersistenceResult = when (this) {
        AutoIgnorePendingPersistenceResult.Discarded -> DueUnknownDiscardPersistenceResult.Discarded
        AutoIgnorePendingPersistenceResult.PolicyDisabled ->
            DueUnknownDiscardPersistenceResult.PolicyDisabled
        AutoIgnorePendingPersistenceResult.PendingNotFound ->
            DueUnknownDiscardPersistenceResult.PendingNotFound
        is AutoIgnorePendingPersistenceResult.AlreadyFinalized ->
            DueUnknownDiscardPersistenceResult.AlreadyFinalized(measurement)
    }
