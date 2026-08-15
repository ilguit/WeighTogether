package com.example.huaweimisync.data

import com.example.huaweimisync.core.BodyCompositionCalculator
import com.example.huaweimisync.core.RawScaleMeasurement
import com.example.huaweimisync.domain.AccountId
import com.example.huaweimisync.domain.AccountMeasurement
import com.example.huaweimisync.domain.AccountProfile
import com.example.huaweimisync.domain.AccountRepository
import com.example.huaweimisync.domain.CreateAccountAndAssignResult
import com.example.huaweimisync.domain.ExternalSyncPolicy
import com.example.huaweimisync.domain.FinalizePendingResult
import com.example.huaweimisync.domain.NewAccount
import com.example.huaweimisync.domain.PendingMeasurement
import com.example.huaweimisync.domain.PendingMeasurementId
import com.example.huaweimisync.domain.PendingMeasurementPreview
import com.example.huaweimisync.domain.RoutingDecision
import com.example.huaweimisync.domain.isComplete
import com.example.huaweimisync.domain.routing.MatchingEngine
import com.example.huaweimisync.domain.routing.WeightHistoryRecord
import com.example.huaweimisync.domain.toRawScaleMeasurement
import com.example.huaweimisync.domain.toUserProfileOrNull
import com.example.huaweimisync.worker.MeasurementSyncScheduler
import java.time.Instant
import kotlinx.coroutines.flow.first

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

    suspend fun discardPending(pendingId: PendingMeasurementId): Boolean
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
    data object Tombstoned : MeasurementIngestionResult
    data object PendingMissing : MeasurementIngestionResult
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
    suspend fun ingest(raw: RawScaleMeasurement): MeasurementIngestionResult {
        if (!raw.isFinal) return MeasurementIngestionResult.IgnoredNotFinal

        return when (val enqueued = persistence.enqueue(raw)) {
            is PendingPersistenceResult.Inserted -> route(enqueued.pending)
            is PendingPersistenceResult.AlreadyPending -> route(enqueued.pending)
            is PendingPersistenceResult.AlreadyFinalized -> {
                scheduleIfEligible(enqueued.measurement)
                refreshPendingPresentation()
                MeasurementIngestionResult.Assigned(
                    measurement = enqueued.measurement,
                    wasAlreadyFinalized = true,
                )
            }
            PendingPersistenceResult.Tombstoned -> MeasurementIngestionResult.Tombstoned
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

    suspend fun discard(pendingId: PendingMeasurementId): Boolean {
        val discarded = persistence.discardPending(pendingId)
        refreshPendingPresentation()
        return discarded
    }

    /** Calculates only in memory; the supplied profile and result never cross persistence. */
    suspend fun previewWithoutSaving(
        pendingId: PendingMeasurementId,
        oneShotProfile: AccountProfile.Complete? = null,
    ): PendingMeasurementPreview? {
        val pending = persistence.getPending(pendingId) ?: return null
        val composition = oneShotProfile?.toUserProfileOrNull()?.let { profile ->
            calculator.calculate(pending.toRawScaleMeasurement(), profile)
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
                MeasurementIngestionResult.Tombstoned,
                MeasurementIngestionResult.PendingMissing,
                MeasurementIngestionResult.LegacyDuplicate,
                MeasurementIngestionResult.LegacyProfileMissing,
                -> Unit
            }
        }
        refreshPendingPresentation()
        return MeasurementIngestionSweepResult(assigned, awaiting)
    }

    suspend fun refreshPendingPresentation(): Int {
        val count = persistence.pendingSnapshot().size
        notifier.updatePendingCount(count)
        return count
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
        syncScheduler.enqueue(measurement.composition.measurementId)
    }
}
