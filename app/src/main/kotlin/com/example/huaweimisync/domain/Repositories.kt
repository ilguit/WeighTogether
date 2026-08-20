package com.example.huaweimisync.domain

import com.example.huaweimisync.core.BodyComposition
import com.example.huaweimisync.core.RawScaleMeasurement
import java.time.Instant
import kotlinx.coroutines.flow.Flow

interface AccountRepository {
    fun observeAccounts(): Flow<List<Account>>

    fun observeSettings(): Flow<AccountSettings>

    suspend fun getAccount(id: AccountId): Account?

    suspend fun createAccount(account: NewAccount): Account

    suspend fun updateAccount(account: AccountUpdate): Account

    suspend fun setPrimaryAccount(
        accountId: AccountId,
        historySyncMode: PrimaryHistorySyncMode,
    )

    suspend fun updateWeightDeltaKg(weightDeltaKg: Double)

    suspend fun updateIgnoreUnknownMeasurements(enabled: Boolean)

    suspend fun deleteAccount(accountId: AccountId)

    /**
     * Atomically deletes [primaryAccountId], installs [replacementAccountId] (or no primary when
     * deleting the last account), and applies [historySyncMode] to eligible replacement history.
     */
    suspend fun deletePrimaryWithReplacement(
        primaryAccountId: AccountId,
        replacementAccountId: AccountId?,
        historySyncMode: PrimaryHistorySyncMode,
    )
}

/** Explicit write side for account-level settings. */
interface AccountSettingsWriter {
    suspend fun updateWeightDeltaKg(weightDeltaKg: Double)

    suspend fun updateIgnoreUnknownMeasurements(enabled: Boolean)
}

data class AccountMeasurement(
    val accountId: AccountId,
    val composition: BodyComposition?,
    val externalSyncPolicy: ExternalSyncPolicy,
    val createdAt: Instant,
    val measurementId: String = requireNotNull(composition).measurementId,
    val weightKg: Double = requireNotNull(composition).weightKg,
)

sealed interface PendingEnqueueResult {
    data class Enqueued(val pending: PendingMeasurement) : PendingEnqueueResult
    data class AlreadyPending(val pending: PendingMeasurement) : PendingEnqueueResult
    data class AlreadyFinalized(val measurement: AccountMeasurement) : PendingEnqueueResult
    data object Tombstoned : PendingEnqueueResult
}

data class PendingMeasurementPreview(
    val pending: PendingMeasurement,
    /** Present only when the caller supplied a complete one-shot profile. */
    val composition: BodyComposition? = null,
)

sealed interface FinalizePendingResult {
    data class Finalized(val measurement: AccountMeasurement) : FinalizePendingResult
    data object PendingNotFound : FinalizePendingResult
    data object AccountNotFound : FinalizePendingResult
    data object ProfileIncomplete : FinalizePendingResult
    data class AlreadyFinalized(val measurement: AccountMeasurement) : FinalizePendingResult
}

sealed interface CreateAccountAndAssignResult {
    data class Created(
        val account: Account,
        val measurement: AccountMeasurement,
    ) : CreateAccountAndAssignResult

    data object PendingNotFound : CreateAccountAndAssignResult
    data class NameConflict(val normalizedName: String) : CreateAccountAndAssignResult
    data class AlreadyFinalized(val measurement: AccountMeasurement) : CreateAccountAndAssignResult
}

/**
 * Opaque, process-local capability for undoing one successful pending discard.
 *
 * The full pending snapshot is intentionally held only by this in-memory object. The token is not
 * a Room entity, Parcelable, or Serializable, so losing the process also loses the ability to undo.
 */
class PendingDiscardUndoToken internal constructor(
    internal val pending: PendingMeasurement,
) {
    val pendingId: PendingMeasurementId
        get() = pending.id

    val deduplicationHash: String
        get() = pending.deduplicationHash

    val enqueuedAt: Instant
        get() = pending.enqueuedAt
}

sealed interface DiscardPendingResult {
    /** The only outcome which carries an undo capability. */
    data class Discarded(val undoToken: PendingDiscardUndoToken) : DiscardPendingResult

    data class AlreadyFinalized(val measurement: AccountMeasurement) : DiscardPendingResult

    data object PendingNotFound : DiscardPendingResult
}

/** Result of an atomic discard coupled to an unknown-measurement policy change. */
sealed interface DiscardPendingAndUpdateIgnorePolicyResult {
    /** A null token means the newly enabled policy intentionally made deletion non-undoable. */
    data class Discarded(val undoToken: PendingDiscardUndoToken?) :
        DiscardPendingAndUpdateIgnorePolicyResult

    data class AlreadyFinalized(val measurement: AccountMeasurement) :
        DiscardPendingAndUpdateIgnorePolicyResult

    data object PendingNotFound : DiscardPendingAndUpdateIgnorePolicyResult
}

sealed interface RestorePendingResult {
    data class Restored(val pending: PendingMeasurement) : RestorePendingResult

    /** The same token was already restored; no second row was inserted. */
    data class AlreadyRestored(val pending: PendingMeasurement) : RestorePendingResult

    data class AlreadyFinalized(val measurement: AccountMeasurement) : RestorePendingResult

    /** Another pending row already owns either the durable id or deduplication hash. */
    data class Conflict(val conflictingPending: PendingMeasurement) : RestorePendingResult
}

interface MeasurementRepository {
    fun observeAll(accountId: AccountId): Flow<List<AccountMeasurement>>

    fun observeLatest(
        accountId: AccountId,
        limit: Int = 30,
    ): Flow<List<AccountMeasurement>>

    fun observeRange(
        accountId: AccountId,
        startInclusive: Instant,
        endExclusive: Instant,
    ): Flow<List<AccountMeasurement>>

    /** Pending values are emitted in FIFO order (enqueuedAt, then durable id). */
    fun observePending(): Flow<List<PendingMeasurement>>

    suspend fun getPending(id: PendingMeasurementId): PendingMeasurement?

    suspend fun enqueuePending(raw: RawScaleMeasurement): PendingEnqueueResult

    /** Calculates, inserts, and removes pending state in one transaction. */
    suspend fun finalizePending(
        pendingId: PendingMeasurementId,
        accountId: AccountId,
    ): FinalizePendingResult

    /**
     * Creates the account, applies the first-account primary rule, finalizes the reading, and
     * removes pending state in one transaction. A failure must leave both account and pending
     * state unchanged.
     */
    suspend fun createAccountAndAssignPending(
        pendingId: PendingMeasurementId,
        account: NewAccount,
    ): CreateAccountAndAssignResult

    /**
     * Removes pending raw data and retains only its expiring deduplication tombstone.
     * Only a successful discard returns an in-memory undo token.
     */
    suspend fun discardPending(pendingId: PendingMeasurementId): DiscardPendingResult

    /**
     * Atomically discards [pendingId] and persists [ignoreUnknownMeasurements]. A successful
     * operation creates the normal deduplication tombstone. Enabling the policy intentionally
     * exposes no undo capability; disabling it returns the normal undo token. Missing/finalized
     * pending data must leave the policy unchanged.
     */
    suspend fun discardPendingAndUpdateIgnorePolicy(
        pendingId: PendingMeasurementId,
        ignoreUnknownMeasurements: Boolean,
    ): DiscardPendingAndUpdateIgnorePolicyResult

    /** Restores a discarded snapshot atomically and consumes its tombstone only on success. */
    suspend fun restorePending(undoToken: PendingDiscardUndoToken): RestorePendingResult
}
