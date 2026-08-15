package com.example.huaweimisync.ui.routing

import androidx.compose.runtime.Immutable
import com.example.huaweimisync.domain.Account
import com.example.huaweimisync.domain.AccountId
import com.example.huaweimisync.domain.PendingMeasurement
import com.example.huaweimisync.domain.PendingMeasurementId
import com.example.huaweimisync.domain.RoutingCandidate
import com.example.huaweimisync.domain.sortedForRouting
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

@Immutable
data class ResolverAccountOption(
    val accountId: AccountId,
    val displayName: String,
    val isPrimary: Boolean,
    val differenceKg: Double? = null,
    val medianWeightKg: Double? = null,
) {
    init {
        require(displayName.isNotBlank()) { "Resolver account name must not be blank" }
        require((differenceKg == null) == (medianWeightKg == null)) {
            "Candidate difference and median must either both be present or both be absent"
        }
        require(differenceKg == null || differenceKg.isFinite() && differenceKg >= 0.0)
        require(medianWeightKg == null || medianWeightKg.isFinite() && medianWeightKg >= 0.0)
    }

    val isCandidate: Boolean
        get() = differenceKg != null
}

fun buildResolverAccountOptions(
    accounts: List<Account>,
    primaryAccountId: AccountId?,
    candidates: List<RoutingCandidate>,
): List<ResolverAccountOption> {
    val uniqueAccounts = accounts.distinctBy(Account::id)
    val accountsById = uniqueAccounts.associateBy(Account::id)
    val orderedCandidates = candidates.sortedForRouting()
        .distinctBy(RoutingCandidate::accountId)
        .mapNotNull { candidate ->
            accountsById[candidate.accountId]?.let { account ->
                ResolverAccountOption(
                    accountId = account.id,
                    displayName = account.displayName,
                    isPrimary = account.id == primaryAccountId,
                    differenceKg = candidate.differenceKg,
                    medianWeightKg = candidate.medianWeightKg,
                )
            }
        }
    val candidateIds = orderedCandidates.mapTo(mutableSetOf(), ResolverAccountOption::accountId)
    return orderedCandidates + uniqueAccounts
        .asSequence()
        .filterNot { it.id in candidateIds }
        .map { account ->
            ResolverAccountOption(
                accountId = account.id,
                displayName = account.displayName,
                isPrimary = account.id == primaryAccountId,
            )
        }
        .toList()
}

@Immutable
data class ResolverQueueState(
    val pending: List<PendingMeasurement> = emptyList(),
    val isResolverVisible: Boolean = false,
    val notificationPermissionGranted: Boolean = true,
) {
    init {
        require(pending.distinctBy(PendingMeasurement::id).size == pending.size) {
            "Pending queue must not contain duplicate ids"
        }
        require(pending == pending.sortedWith(PendingFifoComparator)) {
            "Pending queue must be ordered by enqueue time and durable id"
        }
    }

    val current: PendingMeasurement?
        get() = pending.firstOrNull()

    val pendingCount: Int
        get() = pending.size

    /** The foreground screen should expose an entry point when notifications cannot do it. */
    val showForegroundFallback: Boolean
        get() = pending.isNotEmpty() && !notificationPermissionGranted && !isResolverVisible

    companion object {
        fun from(
            pending: List<PendingMeasurement>,
            isResolverVisible: Boolean = false,
            notificationPermissionGranted: Boolean = true,
        ): ResolverQueueState = ResolverQueueState(
            pending = pending.sortedWith(PendingFifoComparator),
            isResolverVisible = isResolverVisible && pending.isNotEmpty(),
            notificationPermissionGranted = notificationPermissionGranted,
        )
    }
}

sealed interface ResolverQueueAction {
    data class PendingChanged(val pending: List<PendingMeasurement>) : ResolverQueueAction
    data class NotificationPermissionChanged(val granted: Boolean) : ResolverQueueAction
    data object OpenRequested : ResolverQueueAction
    /** Hides the resolver but deliberately retains the durable FIFO head. */
    data object LaterRequested : ResolverQueueAction
    /** Dispatch only after the repository has durably finalized the FIFO head. */
    data class HeadFinalized(val pendingId: PendingMeasurementId) : ResolverQueueAction
    /** Dispatch only after the repository has durably replaced the FIFO head with a tombstone. */
    data class HeadDiscarded(val pendingId: PendingMeasurementId) : ResolverQueueAction
}

fun reduceResolverQueue(
    state: ResolverQueueState,
    action: ResolverQueueAction,
): ResolverQueueState = when (action) {
    is ResolverQueueAction.PendingChanged -> ResolverQueueState.from(
        pending = action.pending,
        isResolverVisible = state.isResolverVisible,
        notificationPermissionGranted = state.notificationPermissionGranted,
    )
    is ResolverQueueAction.NotificationPermissionChanged -> state.copy(
        notificationPermissionGranted = action.granted,
    )
    ResolverQueueAction.OpenRequested -> state.copy(
        isResolverVisible = state.pending.isNotEmpty(),
    )
    ResolverQueueAction.LaterRequested -> state.copy(isResolverVisible = false)
    is ResolverQueueAction.HeadFinalized -> state.removeHead(action.pendingId)
    is ResolverQueueAction.HeadDiscarded -> state.removeHead(action.pendingId)
}

/**
 * Resolves a notification navigation request against durable state after process recreation.
 *
 * The eagerly shared UI flow starts with an empty placeholder, so a cold-launch intent must wait
 * for the repository's first Room snapshot before deciding that the notification is stale.
 */
internal suspend fun hasPendingResolverTarget(
    observedPending: List<PendingMeasurement>,
    durablePendingSnapshots: Flow<List<PendingMeasurement>>,
): Boolean = observedPending.isNotEmpty() || durablePendingSnapshots.first().isNotEmpty()

private fun ResolverQueueState.removeHead(id: PendingMeasurementId): ResolverQueueState {
    if (current?.id != id) return this
    val remaining = pending.drop(1)
    return copy(
        pending = remaining,
        isResolverVisible = isResolverVisible && remaining.isNotEmpty(),
    )
}

private val PendingFifoComparator = compareBy<PendingMeasurement> { it.enqueuedAt }
    .thenBy { it.id.value }

@Immutable
data class MeasurementResolverUiState(
    val pending: PendingMeasurement,
    val accountOptions: List<ResolverAccountOption>,
    val operationInProgress: Boolean = false,
) {
    init {
        require(accountOptions.distinctBy(ResolverAccountOption::accountId).size == accountOptions.size) {
            "Resolver account options must have unique ids"
        }
        val firstNonCandidate = accountOptions.indexOfFirst { !it.isCandidate }
        require(firstNonCandidate < 0 || accountOptions.drop(firstNonCandidate).none { it.isCandidate }) {
            "Candidate accounts must precede every other account"
        }
        require(
            accountOptions.filter(ResolverAccountOption::isCandidate)
                .zipWithNext()
                .all { (left, right) ->
                    requireNotNull(left.differenceKg) <= requireNotNull(right.differenceKg)
                },
        ) { "Candidate accounts must be ordered by weight difference" }
    }

    val candidateCount: Int
        get() = accountOptions.count(ResolverAccountOption::isCandidate)
}

data class MeasurementResolverCallbacks(
    val onAccountSelected: (PendingMeasurementId, AccountId) -> Unit,
    val onCreateAccount: (PendingMeasurementId) -> Unit,
    val onShowWithoutSaving: (PendingMeasurementId) -> Unit,
    val onLater: () -> Unit,
) {
    companion object {
        val None = MeasurementResolverCallbacks(
            onAccountSelected = { _, _ -> },
            onCreateAccount = {},
            onShowWithoutSaving = {},
            onLater = {},
        )
    }
}
