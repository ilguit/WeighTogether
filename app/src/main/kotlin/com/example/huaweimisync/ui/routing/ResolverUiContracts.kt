package com.example.huaweimisync.ui.routing

import androidx.compose.runtime.Immutable
import com.example.huaweimisync.domain.Account
import com.example.huaweimisync.domain.AccountId
import com.example.huaweimisync.domain.PendingMeasurement
import com.example.huaweimisync.domain.PendingMeasurementId
import com.example.huaweimisync.domain.RoutingCandidate
import com.example.huaweimisync.domain.isAwaitingDecisionAt
import com.example.huaweimisync.domain.sortedForRouting
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

enum class PendingResolverSource {
    PENDING_QUEUE,
    EXTERNAL,
}

enum class PendingResolverReturnDestination {
    PENDING_QUEUE,
    PRESERVE_CURRENT,
}

@Immutable
internal data class PendingResolverSession(
    val pendingId: PendingMeasurementId,
    val source: PendingResolverSource,
) {
    val returnDestination: PendingResolverReturnDestination
        get() = when (source) {
            PendingResolverSource.PENDING_QUEUE -> PendingResolverReturnDestination.PENDING_QUEUE
            PendingResolverSource.EXTERNAL -> PendingResolverReturnDestination.PRESERVE_CURRENT
        }

    fun completionFor(requestedPendingId: PendingMeasurementId): PendingResolverCompletion? =
        takeIf { pendingId == requestedPendingId }?.let {
            PendingResolverCompletion(
                pendingId = pendingId,
                returnDestination = returnDestination,
            )
        }
}

/** Shared terminal callback contract for assignment now and discard once it is connected. */
@Immutable
internal data class PendingResolverCompletion(
    val pendingId: PendingMeasurementId,
    val returnDestination: PendingResolverReturnDestination,
)

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
    val selectedPendingId: PendingMeasurementId? = null,
    val notificationPermissionGranted: Boolean = true,
) {
    init {
        require(pending.distinctBy(PendingMeasurement::id).size == pending.size) {
            "Pending queue must not contain duplicate ids"
        }
        require(pending == pending.sortedWith(PendingFifoComparator)) {
            "Pending queue must be ordered by enqueue time and durable id"
        }
        require(selectedPendingId == null || pending.any { it.id == selectedPendingId }) {
            "Selected pending id must belong to the pending queue"
        }
    }

    val current: PendingMeasurement?
        get() = pending.firstOrNull()

    val selected: PendingMeasurement?
        get() = pending.firstOrNull { it.id == selectedPendingId }

    val isResolverVisible: Boolean
        get() = selectedPendingId != null

    val pendingCount: Int
        get() = pending.size

    /** The foreground screen should expose an entry point when notifications cannot do it. */
    val showForegroundFallback: Boolean
        get() = pending.isNotEmpty() && !notificationPermissionGranted && !isResolverVisible

    companion object {
        fun from(
            pending: List<PendingMeasurement>,
            selectedPendingId: PendingMeasurementId? = null,
            notificationPermissionGranted: Boolean = true,
            now: Instant = Instant.now(),
        ): ResolverQueueState {
            val ordered = pending
                .filter { it.isAwaitingDecisionAt(now) }
                .sortedWith(PendingFifoComparator)
            return ResolverQueueState(
                pending = ordered,
                selectedPendingId = selectedPendingId?.takeIf { selectedId ->
                    ordered.any { it.id == selectedId }
                },
                notificationPermissionGranted = notificationPermissionGranted,
            )
        }
    }
}

sealed interface ResolverQueueAction {
    data class PendingChanged(val pending: List<PendingMeasurement>) : ResolverQueueAction
    data class NotificationPermissionChanged(val granted: Boolean) : ResolverQueueAction
    data class OpenRequested(
        val pendingId: PendingMeasurementId? = null,
    ) : ResolverQueueAction
    /** Hides the resolver but deliberately retains the durable FIFO head. */
    data object LaterRequested : ResolverQueueAction
    /** Dispatch only after the repository has durably finalized the addressed pending item. */
    data class PendingFinalized(val pendingId: PendingMeasurementId) : ResolverQueueAction
    /** Dispatch only after the repository has durably replaced the addressed item with a tombstone. */
    data class PendingDiscarded(val pendingId: PendingMeasurementId) : ResolverQueueAction
}

fun reduceResolverQueue(
    state: ResolverQueueState,
    action: ResolverQueueAction,
): ResolverQueueState = when (action) {
    is ResolverQueueAction.PendingChanged -> ResolverQueueState.from(
        pending = action.pending,
        selectedPendingId = state.selectedPendingId,
        notificationPermissionGranted = state.notificationPermissionGranted,
    )
    is ResolverQueueAction.NotificationPermissionChanged -> state.copy(
        notificationPermissionGranted = action.granted,
    )
    is ResolverQueueAction.OpenRequested -> action.pendingId?.let { requestedId ->
        if (state.pending.any { it.id == requestedId }) {
            state.copy(selectedPendingId = requestedId)
        } else {
            state
        }
    } ?: state.copy(selectedPendingId = state.pending.firstOrNull()?.id)
    ResolverQueueAction.LaterRequested -> state.copy(selectedPendingId = null)
    is ResolverQueueAction.PendingFinalized -> state.removePending(action.pendingId)
    is ResolverQueueAction.PendingDiscarded -> state.removePending(action.pendingId)
}

/**
 * Validates a resolver callback against both its displayed target and the latest durable queue.
 *
 * The callback carries an id so an already composed resolver cannot process a different item after
 * selection changes. The durable lookup also rejects a selection that became stale between frames.
 */
internal fun isActivePendingResolverTarget(
    pending: List<PendingMeasurement>,
    selectedPendingId: PendingMeasurementId?,
    requestedPendingId: PendingMeasurementId,
): Boolean = selectedPendingId == requestedPendingId &&
    pending.any { it.id == requestedPendingId }

/** Builds a terminal action only for the pending item still displayed by this resolver session. */
internal fun PendingResolverSession.activeCompletionFor(
    pending: List<PendingMeasurement>,
    requestedPendingId: PendingMeasurementId,
): PendingResolverCompletion? = completionFor(requestedPendingId)?.takeIf {
    isActivePendingResolverTarget(
        pending = pending,
        selectedPendingId = pendingId,
        requestedPendingId = requestedPendingId,
    )
}

/**
 * Resolves a notification navigation request against durable state after process recreation.
 *
 * The eagerly shared UI flow starts with an empty placeholder, so a cold-launch intent must wait
 * for the repository's first Room snapshot before deciding that the notification is stale.
 */
internal suspend fun oldestPendingResolverTarget(
    observedPending: List<PendingMeasurement>,
    durablePendingSnapshots: Flow<List<PendingMeasurement>>,
    now: Instant = Instant.now(),
): PendingMeasurementId? {
    val available = observedPending.ifEmpty { durablePendingSnapshots.first() }
    return available
        .filter { it.isAwaitingDecisionAt(now) }
        .minWithOrNull(PendingFifoComparator)
        ?.id
}

private fun ResolverQueueState.removePending(id: PendingMeasurementId): ResolverQueueState {
    if (pending.none { it.id == id }) return this
    val remaining = pending.filterNot { it.id == id }
    return copy(
        pending = remaining,
        selectedPendingId = selectedPendingId.takeUnless { it == id },
    )
}

private val PendingFifoComparator = compareBy<PendingMeasurement> { it.enqueuedAt }
    .thenBy { it.id.value }

@Immutable
data class MeasurementResolverUiState(
    val pending: PendingMeasurement,
    val accountOptions: List<ResolverAccountOption>,
    /** Null for every routing path except NoMatch. */
    val ignoreUnknownMeasurements: Boolean? = null,
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
    val onIgnoreUnknownMeasurementsChanged: (PendingMeasurementId, Boolean) -> Unit,
    val onDelete: (PendingMeasurementId) -> Unit,
    val onLater: () -> Unit,
) {
    companion object {
        val None = MeasurementResolverCallbacks(
            onAccountSelected = { _, _ -> },
            onCreateAccount = {},
            onShowWithoutSaving = {},
            onIgnoreUnknownMeasurementsChanged = { _, _ -> },
            onDelete = {},
            onLater = {},
        )
    }
}
