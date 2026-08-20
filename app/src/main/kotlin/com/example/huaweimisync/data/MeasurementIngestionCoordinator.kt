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
    suspend fun enqueue(raw: RawScaleMeasurement): PendingPersistenceResult

    suspend fun getPending(id: PendingMeasurementId): PendingMeasurement?

    suspend fun pendingSnapshot(): List<PendingMeasurement>

    suspend fun latestHistoryBefore(
        accountId: AccountId,
        measuredAtExclusive: Instant,
    ): List<WeightHistoryRecord>

    suspend fun finalizePending(
        pendingId: PendingMeasurementId,
        accountId: AccountId,
    ): FinalizePendingResult

    suspend fun createAccountAndAssignPending(
        pendingId: PendingMeasurementId,
        account: NewAccount,
    ): CreateAccountAndAssignResult

    suspend fun discardPending(pendingId: PendingMeasurementId): DiscardPendingResult

    /** Re-checks the persisted policy and discards in the same transaction. */
    suspend fun discardUnknownPendingIfEnabled(
        pendingId: PendingMeasurementId,
    ): AutoIgnorePendingPersistenceResult

    suspend fun discardPendingAndUpdateIgnorePolicy(
        pendingId: PendingMeasurementId,
        ignoreUnknownMeasurements: Boolean,
    ): DiscardPendingAndUpdateIgnorePolicyResult

    suspend fun restorePending(undoToken: PendingDiscardUndoToken): RestorePendingResult
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
    /** A new durable debounce aggregate was created; no routing or sync has happened yet. */
    data class CreatedAggregate(val pending: PendingMeasurement) : MeasurementIngestionResult
    /** An existing aggregate was enriched or had its sliding deadline extended. */
    data class UpdatedAggregate(
        val pending: PendingMeasurement,
        val wasEnriched: Boolean,
    ) : MeasurementIngestionResult
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
    /** Compatibility hook for explicit route/recovery calls; normal ingestion only aggregates. */
    private val aggregateOnlyIngestion: Boolean = true,
) {
    private val pendingPresentationMutex = Mutex()

    suspend fun ingest(raw: RawScaleMeasurement): MeasurementIngestionResult {
        if (!raw.isStableWeight) return MeasurementIngestionResult.IgnoredNotFinal

        return when (val enqueued = persistence.enqueue(raw)) {
            is PendingPersistenceResult.Inserted -> if (aggregateOnlyIngestion) {
                MeasurementIngestionResult.CreatedAggregate(enqueued.pending)
            } else {
                route(pending = enqueued.pending, allowAutomaticIgnore = true)
            }
            is PendingPersistenceResult.AlreadyPending -> if (aggregateOnlyIngestion) {
                MeasurementIngestionResult.UpdatedAggregate(
                    pending = enqueued.pending,
                    wasEnriched = enqueued.wasEnriched,
                )
            } else {
                route(enqueued.pending)
            }
            is PendingPersistenceResult.AlreadyFinalized -> if (aggregateOnlyIngestion) {
                MeasurementIngestionResult.SuppressedFinal
            } else {
                MeasurementIngestionResult.Assigned(
                    measurement = enqueued.measurement,
                    wasAlreadyFinalized = true,
                )
            }
            PendingPersistenceResult.Tombstoned -> if (aggregateOnlyIngestion) {
                MeasurementIngestionResult.SuppressedTombstone
            } else {
                MeasurementIngestionResult.Tombstoned
            }
        }
    }

    suspend fun route(pendingId: PendingMeasurementId): MeasurementIngestionResult {
        val pending = persistence.getPending(pendingId)
            ?: return alreadyFinalizedOrMissing(pendingId)
        return route(pending)
    }

    suspend fun chooseAccount(
        pendingId: PendingMeasurementId,
        accountId: AccountId,
    ): FinalizePendingResult {
        val result = persistence.finalizePending(pendingId, accountId)
        when (result) {
            is FinalizePendingResult.Finalized -> scheduleIfEligible(result.measurement)
            is FinalizePendingResult.AlreadyFinalized -> scheduleIfEligible(result.measurement)
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
            is CreateAccountAndAssignResult.AlreadyFinalized -> scheduleIfEligible(result.measurement)
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
        // Snapshot order is FIFO. A finalized earlier item is visible to matching the next item.
        persistence.pendingSnapshot().forEach { pending ->
            when (route(pending)) {
                is MeasurementIngestionResult.Assigned -> assigned += 1
                is MeasurementIngestionResult.AwaitingDecision -> awaiting += 1
                MeasurementIngestionResult.IgnoredNotFinal,
                is MeasurementIngestionResult.CreatedAggregate,
                is MeasurementIngestionResult.UpdatedAggregate,
                MeasurementIngestionResult.SuppressedFinal,
                MeasurementIngestionResult.SuppressedTombstone,
                MeasurementIngestionResult.Tombstoned,
                MeasurementIngestionResult.PendingMissing,
                MeasurementIngestionResult.AutomaticallyIgnoredUnknown,
                MeasurementIngestionResult.LegacyDuplicate,
                MeasurementIngestionResult.LegacyProfileMissing,
                -> Unit
            }
        }
        refreshPendingPresentation()
        return MeasurementIngestionSweepResult(assigned, awaiting)
    }

    suspend fun refreshPendingPresentation(): Int = pendingPresentationMutex.withLock {
        val count = persistence.pendingSnapshot().size
        notifier.updatePendingCount(count)
        count
    }

    private suspend fun route(
        pending: PendingMeasurement,
        allowAutomaticIgnore: Boolean = false,
    ): MeasurementIngestionResult {
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
                    scheduleIfEligible(finalized.measurement)
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

        if (
            allowAutomaticIgnore &&
            decision === RoutingDecision.NoMatch &&
            settings.ignoreUnknownMeasurements
        ) {
            when (val ignored = persistence.discardUnknownPendingIfEnabled(pending.id)) {
                AutoIgnorePendingPersistenceResult.Discarded -> {
                    refreshPendingPresentation()
                    return MeasurementIngestionResult.AutomaticallyIgnoredUnknown
                }
                is AutoIgnorePendingPersistenceResult.AlreadyFinalized -> {
                    scheduleIfEligible(ignored.measurement)
                    refreshPendingPresentation()
                    return MeasurementIngestionResult.Assigned(
                        measurement = ignored.measurement,
                        wasAlreadyFinalized = true,
                    )
                }
                AutoIgnorePendingPersistenceResult.PendingNotFound ->
                    return alreadyFinalizedOrMissing(pending.id)
                AutoIgnorePendingPersistenceResult.PolicyDisabled -> Unit
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
        syncScheduler.enqueue(measurement.measurementId)
    }
}
