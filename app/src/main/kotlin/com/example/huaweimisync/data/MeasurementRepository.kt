package com.example.huaweimisync.data

import com.example.huaweimisync.core.BodyCompositionCalculator
import com.example.huaweimisync.core.RawScaleMeasurement
import com.example.huaweimisync.core.UserProfile
import com.example.huaweimisync.core.measurementFingerprint
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
import com.example.huaweimisync.domain.PendingEnqueueResult
import com.example.huaweimisync.domain.PendingMeasurement
import com.example.huaweimisync.domain.PendingMeasurementId
import com.example.huaweimisync.domain.PendingMeasurementPreview
import com.example.huaweimisync.domain.RestorePendingResult
import com.example.huaweimisync.domain.isComplete
import com.example.huaweimisync.domain.toRawScaleMeasurement
import com.example.huaweimisync.domain.toUserProfileOrNull
import com.example.huaweimisync.domain.routing.MatchingEngine
import com.example.huaweimisync.worker.MeasurementSyncScheduler
import com.example.huaweimisync.worker.ExternalSyncOperationSerializer
import com.example.huaweimisync.worker.PendingFinalizationScheduler
import com.example.huaweimisync.worker.MeasurementWorkSweepResult
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

class MeasurementRepository(
    private val dao: MeasurementDao,
    private val profileProvider: () -> UserProfile?,
    private val calculator: BodyCompositionCalculator,
    private val syncScheduler: MeasurementSyncScheduler,
    private val huaweiSyncEnabled: Boolean,
    private val multiAccountPersistence: RoomMeasurementPersistence? = null,
    private val accountRepository: AccountRepository? = null,
    pendingDecisionNotifier: PendingDecisionNotifier = NoOpPendingDecisionNotifier,
    matchingEngine: MatchingEngine = MatchingEngine(),
    private val pendingFinalizationScheduler: PendingFinalizationScheduler? = null,
    private val externalSyncOperations: ExternalSyncOperationSerializer =
        ExternalSyncOperationSerializer(),
) : com.example.huaweimisync.domain.MeasurementRepository {
    private val ingestionCoordinator = if (
        multiAccountPersistence != null && accountRepository != null
    ) {
        MeasurementIngestionCoordinator(
            persistence = multiAccountPersistence,
            accounts = accountRepository,
            calculator = calculator,
            syncScheduler = syncScheduler,
            notifier = pendingDecisionNotifier,
            matchingEngine = matchingEngine,
        )
    } else {
        null
    }

    fun observeRecent(): Flow<List<MeasurementEntity>> = dao.observeLatest()

    suspend fun latestAcceptedStableMeasurement(): RawScaleMeasurement? =
        requireMultiAccountPersistence().latestAcceptedStableMeasurement()

    fun observeAll(): Flow<List<MeasurementEntity>> = dao.observeAll()

    fun observeAllEntities(accountId: AccountId): Flow<List<MeasurementEntity>> =
        requireMultiAccountPersistence().observeAllEntities(accountId)

    fun observePreliminary(accountId: AccountId): Flow<List<PendingMeasurement>> =
        requireMultiAccountPersistence().observePreliminary(accountId)

    /** Calculates an account-scoped preview in memory; no entity or sync work is created. */
    fun preliminaryComposition(
        pending: PendingMeasurement,
        profile: AccountProfile,
    ) = calculatePreliminaryComposition(pending, profile, calculator)

    fun observeRange(
        startInclusive: Instant,
        endExclusive: Instant,
    ): Flow<List<MeasurementEntity>> = dao.observeRange(
        startInclusive = startInclusive.ceilToEpochSecond(),
        endExclusive = endExclusive.ceilToEpochSecond(),
    )

    fun observeRangeEntities(
        accountId: AccountId,
        startInclusive: Instant,
        endExclusive: Instant,
    ): Flow<List<MeasurementEntity>> = requireMultiAccountPersistence().observeRangeEntities(
        accountId = accountId,
        startInclusive = startInclusive,
        endExclusive = endExclusive,
    )

    suspend fun store(raw: RawScaleMeasurement): StoreResult {
        if (!raw.isStableWeight) return StoreResult.Rejected
        val entity = if (raw.hasFullBodyComposition) {
            val profile = profileProvider() ?: return StoreResult.ProfileMissing
            calculator.calculate(raw, profile).toEntity(
                rawPayload = raw.rawPayload,
                fingerprint = measurementFingerprint(raw),
                huaweiSyncEnabled = huaweiSyncEnabled,
            )
        } else {
            raw.toWeightOnlyEntity(huaweiSyncEnabled)
        }
        return when (val result = dao.upsertScaleMeasurement(entity)) {
            is MeasurementUpsertResult.Inserted -> {
                syncScheduler.enqueueInitial(result.value.id)
                StoreResult.Inserted(result.value)
            }
            is MeasurementUpsertResult.Upgraded -> {
                if (result.value.needsSync()) syncScheduler.enqueueInitial(result.value.id)
                StoreResult.Upgraded(result.value)
            }
            MeasurementUpsertResult.Duplicate -> StoreResult.Duplicate
        }
    }

    suspend fun insertManual(
        weightKg: Double,
        impedanceOhm: Int,
        measuredAt: Instant = Instant.now(),
    ): StoreResult = store(
        RawScaleMeasurement(
            deviceAddress = MANUAL_DEVICE_ADDRESS,
            measuredAt = measuredAt,
            weightKg = weightKg,
            impedanceOhm = impedanceOhm,
            isStable = true,
            hasImpedance = impedanceOhm in 80..3_000,
            rawPayload = byteArrayOf(),
        ),
    )

    /** Test packets use the same durable pending pipeline as BLE packets. */
    suspend fun ingestTestMeasurement(
        weightKg: Double,
        impedanceOhm: Int,
        measuredAt: Instant = Instant.now(),
    ): MeasurementIngestionResult {
        val result = ingest(
            RawScaleMeasurement(
                deviceAddress = "manual",
                measuredAt = measuredAt,
                weightKg = weightKg,
                impedanceOhm = impedanceOhm,
                isStable = true,
                hasImpedance = impedanceOhm in 80..3_000,
                rawPayload = byteArrayOf(),
            ),
        )
        when (result) {
            is MeasurementIngestionResult.CreatedAggregate ->
                pendingFinalizationScheduler?.enqueueIfAbsent(result.pending)
            is MeasurementIngestionResult.UpdatedAggregate -> if (
                result.shouldScheduleFinalization
            ) {
                pendingFinalizationScheduler?.enqueueIfAbsent(result.pending)
            }
            else -> Unit
        }
        return result
    }

    suspend fun ingest(raw: RawScaleMeasurement): MeasurementIngestionResult {
        val coordinator = ingestionCoordinator
        if (coordinator != null) return coordinator.ingest(raw)
        return when (val legacy = store(raw)) {
            is StoreResult.Inserted -> MeasurementIngestionResult.Assigned(
                legacy.value.toAccountMeasurement(),
            )
            is StoreResult.Upgraded -> MeasurementIngestionResult.Assigned(
                legacy.value.toAccountMeasurement(),
                wasAlreadyFinalized = true,
            )
            StoreResult.Duplicate -> MeasurementIngestionResult.LegacyDuplicate
            StoreResult.ProfileMissing -> MeasurementIngestionResult.LegacyProfileMissing
            StoreResult.Rejected -> MeasurementIngestionResult.IgnoredNotFinal
        }
    }

    suspend fun finalizeDue(
        pendingId: PendingMeasurementId,
        now: Instant = Instant.now(),
    ): AggregateFinalizationResult = requireNotNull(ingestionCoordinator) {
        "Multi-account ingestion is not configured"
    }.finalizeDue(pendingId, now)

    suspend fun update(
        id: String,
        values: MeasurementValues,
    ): MeasurementMutationResult {
        if (!values.isValid()) return MeasurementMutationResult.Invalid
        val current = dao.get(id) ?: return MeasurementMutationResult.NotFound
        if (current.measurementType != MeasurementType.FULL) return MeasurementMutationResult.Invalid
        val updated = current.copy(
            weightKg = values.weightKg,
            impedanceOhm = values.impedanceOhm,
            bmi = values.bmi,
            bodyFatPercent = values.bodyFatPercent,
            bodyFatMassKg = values.bodyFatMassKg,
            waterPercent = values.waterPercent,
            waterMassKg = values.waterMassKg,
            muscleMassKg = values.muscleMassKg,
            skeletalMuscleMassKg = values.skeletalMuscleMassKg,
            boneMassKg = values.boneMassKg,
            proteinPercent = values.proteinPercent,
            proteinMassKg = values.proteinMassKg,
            visceralFatLevel = values.visceralFatLevel,
            basalMetabolicRateKcal = values.basalMetabolicRateKcal,
            metabolicAge = values.metabolicAge,
            leanBodyMassKg = values.leanBodyMassKg,
        )
        return persistEdited(current, updated)
    }

    suspend fun updateWeightOnly(
        id: String,
        weightKg: Double,
    ): MeasurementMutationResult {
        if (!weightKg.isFinite() || weightKg < 0.0) return MeasurementMutationResult.Invalid
        val current = dao.get(id) ?: return MeasurementMutationResult.NotFound
        if (current.measurementType != MeasurementType.WEIGHT_ONLY) {
            return MeasurementMutationResult.Invalid
        }
        return persistEdited(current, current.copy(weightKg = weightKg))
    }

    suspend fun delete(id: String): MeasurementMutationResult =
        externalSyncOperations.runExclusive {
            dao.get(id) ?: return@runExclusive MeasurementMutationResult.NotFound
            if (dao.markLocalOnly(id) == 0) {
                return@runExclusive MeasurementMutationResult.NotFound
            }
            syncScheduler.cancel(id)
            if (dao.delete(id) > 0) {
                MeasurementMutationResult.Success
            } else {
                MeasurementMutationResult.NotFound
            }
        }

    suspend fun retry(id: String) {
        val value = dao.get(id) ?: return
        if (isEligibleForSync(value) && value.hasPendingDestination()) {
            syncScheduler.enqueueImmediately(id)
        }
    }

    suspend fun retryPendingHealthConnect() {
        if (accountRepository == null) {
            dao.idsNeedingHealthConnectSync().forEach(syncScheduler::enqueue)
        } else {
            eligiblePendingEntities()
                .filter { it.healthConnectStatus !in HEALTH_CONNECT_TERMINAL_STATUSES }
                .forEach { syncScheduler.enqueue(it.id) }
        }
    }

    suspend fun retryPendingHuawei() {
        if (accountRepository == null) {
            dao.idsNeedingHuaweiSync().forEach(syncScheduler::enqueue)
        } else {
            eligiblePendingEntities()
                .filter { it.huaweiStatus !in HUAWEI_TERMINAL_STATUSES }
                .forEach { syncScheduler.enqueue(it.id) }
        }
    }

    suspend fun sweepPendingSync(): Int {
        val ids = currentPendingSyncIds()
        ids.forEach(syncScheduler::enqueue)
        return ids.size
    }

    suspend fun currentPendingSyncIds(): List<String> =
        if (accountRepository == null) {
            dao.idsNeedingSync()
        } else {
            eligiblePendingEntities().filter(MeasurementEntity::hasPendingDestination)
                .map(MeasurementEntity::id)
        }

    suspend fun sweepPendingRouting(): MeasurementIngestionSweepResult =
        requireNotNull(ingestionCoordinator) { "Multi-account ingestion is not configured" }
            .sweepPendingRouting()

    suspend fun sweepPendingWork(): MeasurementWorkSweepResult {
        val routing = ingestionCoordinator?.sweepPendingRouting()
        return MeasurementWorkSweepResult(
            routing = routing,
            syncEnqueuedCount = sweepPendingSync(),
        )
    }

    suspend fun refreshPendingPresentation(): Int =
        requireNotNull(ingestionCoordinator) { "Multi-account ingestion is not configured" }
            .refreshPendingPresentation()

    suspend fun routePending(pendingId: PendingMeasurementId): MeasurementIngestionResult =
        requireNotNull(ingestionCoordinator) { "Multi-account ingestion is not configured" }
            .route(pendingId)

    suspend fun previewWithoutSaving(
        pendingId: PendingMeasurementId,
        oneShotProfile: AccountProfile.Complete? = null,
    ): PendingMeasurementPreview? = requireNotNull(ingestionCoordinator) {
        "Multi-account ingestion is not configured"
    }.previewWithoutSaving(pendingId, oneShotProfile)

    override fun observeAll(accountId: AccountId): Flow<List<AccountMeasurement>> =
        requireMultiAccountPersistence().observeAll(accountId)

    override fun observeLatest(
        accountId: AccountId,
        limit: Int,
    ): Flow<List<AccountMeasurement>> =
        requireMultiAccountPersistence().observeLatest(accountId, limit)

    override fun observeRange(
        accountId: AccountId,
        startInclusive: Instant,
        endExclusive: Instant,
    ): Flow<List<AccountMeasurement>> =
        requireMultiAccountPersistence().observeRange(accountId, startInclusive, endExclusive)

    override fun observePending(): Flow<List<PendingMeasurement>> =
        requireMultiAccountPersistence().observePending()

    override fun observeUnassignedPending(): Flow<List<PendingMeasurement>> =
        requireMultiAccountPersistence().observeUnassignedPending()

    override suspend fun getPending(id: PendingMeasurementId): PendingMeasurement? =
        requireMultiAccountPersistence().getPending(id)

    override suspend fun enqueuePending(raw: RawScaleMeasurement): PendingEnqueueResult =
        when (val result = requireMultiAccountPersistence().enqueue(raw)) {
            PendingPersistenceResult.ExactReplay -> PendingEnqueueResult.ExactReplay
            is PendingPersistenceResult.Inserted -> PendingEnqueueResult.Enqueued(result.pending)
            is PendingPersistenceResult.AlreadyPending ->
                PendingEnqueueResult.AlreadyPending(result.pending)
            is PendingPersistenceResult.AlreadyFinalized ->
                PendingEnqueueResult.AlreadyFinalized(result.measurement)
            is PendingPersistenceResult.UpgradedFinalized ->
                PendingEnqueueResult.AlreadyFinalized(result.measurement)
            PendingPersistenceResult.Tombstoned -> PendingEnqueueResult.Tombstoned
        }

    override suspend fun finalizePending(
        pendingId: PendingMeasurementId,
        accountId: AccountId,
    ): FinalizePendingResult = requireNotNull(ingestionCoordinator) {
        "Multi-account ingestion is not configured"
    }.chooseAccount(pendingId, accountId)

    override suspend fun createAccountAndAssignPending(
        pendingId: PendingMeasurementId,
        account: NewAccount,
    ): CreateAccountAndAssignResult = requireNotNull(ingestionCoordinator) {
        "Multi-account ingestion is not configured"
    }.createAccountAndAssign(pendingId, account)

    override suspend fun discardPending(
        pendingId: PendingMeasurementId,
    ): DiscardPendingResult =
        requireNotNull(ingestionCoordinator) { "Multi-account ingestion is not configured" }
            .discard(pendingId)

    override suspend fun discardPendingAndUpdateIgnorePolicy(
        pendingId: PendingMeasurementId,
        ignoreUnknownMeasurements: Boolean,
    ): DiscardPendingAndUpdateIgnorePolicyResult =
        requireNotNull(ingestionCoordinator) { "Multi-account ingestion is not configured" }
            .discardAndUpdateIgnorePolicy(pendingId, ignoreUnknownMeasurements)

    override suspend fun restorePending(
        undoToken: PendingDiscardUndoToken,
    ): RestorePendingResult =
        requireNotNull(ingestionCoordinator) { "Multi-account ingestion is not configured" }
            .restore(undoToken)

    private suspend fun eligiblePendingEntities(): List<MeasurementEntity> {
        val accounts = requireNotNull(accountRepository)
        val primaryId = accounts.observeSettings().first().primaryAccountId ?: return emptyList()
        val primary = accounts.getAccount(primaryId) ?: return emptyList()
        if (!primary.profile.isComplete) return emptyList()
        val ids = requireMultiAccountPersistence().eligiblePendingSyncIds(primaryId)
        return ids.mapNotNull { dao.get(it) }.filter { isEligibleForSync(it) }
    }

    private suspend fun isEligibleForSync(value: MeasurementEntity): Boolean {
        if (value.externalSyncPolicy != ExternalSyncPolicy.AUTO.name) return false
        if (accountRepository == null) return true
        val settings = accountRepository.observeSettings().first()
        if (settings.primaryAccountId?.value != value.accountId) return false
        val account = accountRepository.getAccount(AccountId(value.accountId)) ?: return false
        return account.profile.isComplete
    }

    private fun requireMultiAccountPersistence(): RoomMeasurementPersistence =
        requireNotNull(multiAccountPersistence) { "Multi-account persistence is not configured" }

    private suspend fun persistEdited(
        current: MeasurementEntity,
        edited: MeasurementEntity,
    ): MeasurementMutationResult {
        val updated = edited.copy(
            rawWeight = current.rawWeight,
            huaweiStatus = current.huaweiStatus.toLocalOnlyUnlessDisabled(),
            healthConnectStatus = SyncStatus.LOCAL_ONLY.name,
            huaweiError = null,
            healthConnectError = null,
            externalSyncPolicy = ExternalSyncPolicy.USER_LOCAL.name,
        )
        if (dao.updateIfSameType(updated) == 0) return MeasurementMutationResult.NotFound
        syncScheduler.cancel(current.id)
        return MeasurementMutationResult.Success
    }

    private companion object {
        const val MANUAL_DEVICE_ADDRESS = "manual"
    }
}

internal fun calculatePreliminaryComposition(
    pending: PendingMeasurement,
    profile: AccountProfile,
    calculator: BodyCompositionCalculator,
) = profile.toUserProfileOrNull()?.let { completeProfile ->
    pending.toRawScaleMeasurement().takeIf { it.hasFullBodyComposition }?.let { raw ->
        calculator.calculate(raw, completeProfile)
    }
}

sealed interface StoreResult {
    data class Inserted(val value: MeasurementEntity) : StoreResult
    data class Upgraded(val value: MeasurementEntity) : StoreResult
    data object Duplicate : StoreResult
    data object ProfileMissing : StoreResult
    data object Rejected : StoreResult
}

sealed interface MeasurementMutationResult {
    data object Success : MeasurementMutationResult
    data object NotFound : MeasurementMutationResult
    data object Invalid : MeasurementMutationResult
}

private fun String.toLocalOnlyUnlessDisabled(): String =
    if (this == SyncStatus.DISABLED.name) this else SyncStatus.LOCAL_ONLY.name

private fun String.isHuaweiRetryable(): Boolean = this !in setOf(
    SyncStatus.SYNCED.name,
    SyncStatus.DISABLED.name,
    SyncStatus.LOCAL_ONLY.name,
)

private fun String.isHealthRetryable(): Boolean = this !in setOf(
    SyncStatus.SYNCED.name,
    SyncStatus.LOCAL_ONLY.name,
)

private fun MeasurementEntity.needsSync(): Boolean =
    huaweiStatus.isHuaweiRetryable() || healthConnectStatus.isHealthRetryable()

private fun MeasurementValues.isValid(): Boolean {
    val doubleValues = listOf(
        weightKg,
        bmi,
        bodyFatPercent,
        bodyFatMassKg,
        waterPercent,
        waterMassKg,
        muscleMassKg,
        skeletalMuscleMassKg,
        boneMassKg,
        proteinPercent,
        proteinMassKg,
        visceralFatLevel,
        basalMetabolicRateKcal,
        leanBodyMassKg,
    )
    return doubleValues.all { it.isFinite() && it >= 0.0 } &&
        bodyFatPercent <= 100.0 &&
        waterPercent <= 100.0 &&
        proteinPercent <= 100.0 &&
        impedanceOhm >= 0 &&
        metabolicAge >= 0
}

private fun MeasurementEntity.hasPendingDestination(): Boolean =
    huaweiStatus !in HUAWEI_TERMINAL_STATUSES ||
        healthConnectStatus !in HEALTH_CONNECT_TERMINAL_STATUSES

private val HUAWEI_TERMINAL_STATUSES = setOf(
    SyncStatus.SYNCED.name,
    SyncStatus.DISABLED.name,
    SyncStatus.LOCAL_ONLY.name,
)

private val HEALTH_CONNECT_TERMINAL_STATUSES = setOf(
    SyncStatus.SYNCED.name,
    SyncStatus.LOCAL_ONLY.name,
)

/** Smallest whole-second timestamp which is not before this instant. */
private fun Instant.ceilToEpochSecond(): Long =
    if (nano == 0) epochSecond else Math.addExact(epochSecond, 1L)
