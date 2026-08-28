package com.example.huaweimisync.data

import androidx.room.withTransaction
import com.example.huaweimisync.core.BodyCompositionCalculator
import com.example.huaweimisync.core.RawScaleMeasurement
import com.example.huaweimisync.core.measurementFingerprint
import com.example.huaweimisync.domain.Account
import com.example.huaweimisync.domain.AccountId
import com.example.huaweimisync.domain.AccountMeasurement
import com.example.huaweimisync.domain.AccountSettings
import com.example.huaweimisync.domain.CreateAccountAndAssignResult
import com.example.huaweimisync.domain.DiscardPendingResult
import com.example.huaweimisync.domain.DiscardPendingAndUpdateIgnorePolicyResult
import com.example.huaweimisync.domain.ExternalSyncPolicy
import com.example.huaweimisync.domain.FinalizePendingResult
import com.example.huaweimisync.domain.NewAccount
import com.example.huaweimisync.domain.PendingDiscardUndoToken
import com.example.huaweimisync.domain.PendingMeasurement
import com.example.huaweimisync.domain.PendingMeasurementId
import com.example.huaweimisync.domain.RestorePendingResult
import com.example.huaweimisync.domain.RoutingDecision
import com.example.huaweimisync.domain.toRawScaleMeasurement
import com.example.huaweimisync.domain.toUserProfileOrNull
import com.example.huaweimisync.domain.routing.WeightHistoryRecord
import com.example.huaweimisync.domain.routing.MatchingEngine
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

sealed interface PendingPersistenceResult {
    /** The packet is byte-for-byte and field-for-field equal to the last accepted stable packet. */
    data object ExactReplay : PendingPersistenceResult
    data class Inserted(val pending: PendingMeasurement) : PendingPersistenceResult
    data class AlreadyPending(
        val pending: PendingMeasurement,
        val wasEnriched: Boolean = false,
        val shouldScheduleFinalization: Boolean = true,
    ) : PendingPersistenceResult
    data class AlreadyFinalized(val measurement: AccountMeasurement) : PendingPersistenceResult
    /** A finalized weight-only row was atomically enriched in place. */
    data class UpgradedFinalized(val measurement: AccountMeasurement) : PendingPersistenceResult
    data object Tombstoned : PendingPersistenceResult
}

internal data class PendingReplayPlan(
    val shouldSlideDeadline: Boolean,
    val shouldScheduleFinalization: Boolean,
)

internal fun pendingReplayPlan(
    exactReplay: Boolean,
    isBeforeDeadline: Boolean,
): PendingReplayPlan = PendingReplayPlan(
    shouldSlideDeadline = !exactReplay && isBeforeDeadline,
    // Scheduling is an idempotent watchdog repair, independent of whether the deadline moves.
    // An exact replay can be the durable fallback after the original enqueue attempt failed.
    shouldScheduleFinalization = exactReplay || isBeforeDeadline,
)

internal data class PendingEnrichmentUpdate(
    val entity: PendingMeasurementEntity,
    val shouldScheduleFinalization: Boolean,
)

internal fun activePendingEnrichment(
    candidate: PendingMeasurementEntity,
    incoming: RawScaleMeasurement,
    receivedAt: Instant,
): PendingEnrichmentUpdate? {
    val receivedAtEpochMillis = receivedAt.toEpochMilli()
    if (receivedAtEpochMillis >= candidate.finalizeAfterEpochMillis) return null
    if (!MeasurementEnrichmentPolicy.canEnrich(candidate.toDomain().toRawScaleMeasurement(), incoming)) {
        return null
    }

    val replayPlan = pendingReplayPlan(
        exactReplay = candidate.deduplicationHash == incoming.deduplicationHash(),
        isBeforeDeadline = true,
    )
    return PendingEnrichmentUpdate(
        entity = candidate.copy(
            impedanceOhm = incoming.impedanceOhm,
            isStable = incoming.isStable,
            hasImpedance = incoming.hasImpedance,
            rawPayload = incoming.rawPayload.copyOf(),
            finalizeAfterEpochMillis = if (replayPlan.shouldSlideDeadline) {
                receivedAt.plusSeconds(RoomMeasurementPersistence.DEBOUNCE_SECONDS).toEpochMilli()
            } else {
                candidate.finalizeAfterEpochMillis
            },
        ),
        shouldScheduleFinalization = replayPlan.shouldScheduleFinalization,
    )
}

class RoomMeasurementPersistence(
    private val database: AppDatabase,
    private val calculator: BodyCompositionCalculator,
    private val huaweiSyncEnabled: Boolean,
    private val accountDao: AccountDao = database.accountDao(),
    private val appStateDao: AppStateDao = database.appStateDao(),
    private val measurementDao: MultiAccountMeasurementDao = database.multiAccountMeasurementDao(),
    private val pendingDao: PendingMeasurementDao = database.pendingMeasurementDao(),
    private val acceptedStableMeasurementDao: AcceptedStableMeasurementDao =
        database.acceptedStableMeasurementDao(),
    private val now: () -> Instant = Instant::now,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : MeasurementRoutingPersistence {
    override suspend fun latestAcceptedStableMeasurement(
        deviceAddress: String,
    ): RawScaleMeasurement? {
        acceptedStableMeasurementDao.getForDevice(deviceAddress)?.let {
            return it.toRawScaleMeasurement()
        }
        return acceptedStableMeasurementDao.getLatest()
            ?.takeIf { it.deviceAddress.equals(deviceAddress.trim(), ignoreCase = true) }
            ?.toRawScaleMeasurement()
    }

    fun observeAllEntities(accountId: AccountId): Flow<List<MeasurementEntity>> =
        measurementDao.observeAll(accountId.value)

    fun observeRangeEntities(
        accountId: AccountId,
        startInclusive: Instant,
        endExclusive: Instant,
    ): Flow<List<MeasurementEntity>> {
        require(startInclusive < endExclusive) { "Measurement range must be non-empty" }
        return measurementDao.observeRange(
            accountId = accountId.value,
            startInclusive = startInclusive.ceilToEpochSecond(),
            endExclusive = endExclusive.ceilToEpochSecond(),
        )
    }

    fun observeAll(accountId: AccountId): Flow<List<AccountMeasurement>> =
        measurementDao.observeAll(accountId.value).map { values ->
            values.map(MeasurementEntity::toAccountMeasurement)
        }

    fun observeLatest(accountId: AccountId, limit: Int = 30): Flow<List<AccountMeasurement>> {
        require(limit > 0) { "Limit must be positive" }
        return measurementDao.observeLatest(accountId.value, limit).map { values ->
            values.map(MeasurementEntity::toAccountMeasurement)
        }
    }

    fun observeRange(
        accountId: AccountId,
        startInclusive: Instant,
        endExclusive: Instant,
    ): Flow<List<AccountMeasurement>> {
        require(startInclusive < endExclusive) { "Measurement range must be non-empty" }
        return measurementDao.observeRange(
            accountId = accountId.value,
            startInclusive = startInclusive.ceilToEpochSecond(),
            endExclusive = endExclusive.ceilToEpochSecond(),
        ).map { values -> values.map(MeasurementEntity::toAccountMeasurement) }
    }

    fun observePending(): Flow<List<PendingMeasurement>> = pendingDao.observeAll().map { values ->
        values.map(PendingMeasurementEntity::toDomain)
    }

    fun observeUnassignedPending(): Flow<List<PendingMeasurement>> =
        pendingDao.observeUnassigned().map { values ->
            values.map(PendingMeasurementEntity::toDomain)
        }

    fun observePreliminary(accountId: AccountId): Flow<List<PendingMeasurement>> =
        pendingDao.observeForAccount(accountId.value).map { values ->
            values.map(PendingMeasurementEntity::toDomain)
        }

    override suspend fun getPending(id: PendingMeasurementId): PendingMeasurement? =
        pendingDao.get(id.value)?.toDomain()

    suspend fun latestWeightsBefore(
        accountId: AccountId,
        measuredAtExclusive: Instant,
    ): List<Double> = measurementDao.latestWeightsBefore(
        accountId.value,
        measuredAtExclusive.ceilToEpochSecond(),
    )

    override suspend fun latestHistoryBefore(
        accountId: AccountId,
        measuredAtExclusive: Instant,
    ): List<WeightHistoryRecord> = measurementDao.latestHistoryBefore(
        accountId.value,
        measuredAtExclusive.ceilToEpochSecond(),
    ).map { value ->
        WeightHistoryRecord(
            measuredAt = value.measuredAt,
            weightKg = value.weightKg,
        )
    }

    override suspend fun pendingSnapshot(): List<PendingMeasurement> =
        pendingDao.getAll().map(PendingMeasurementEntity::toDomain)

    override suspend fun unassignedPendingSnapshot(): List<PendingMeasurement> =
        pendingDao.getUnassigned().map(PendingMeasurementEntity::toDomain)

    suspend fun eligiblePendingSyncIds(primaryAccountId: AccountId): List<String> =
        measurementDao.eligiblePendingSyncIds(primaryAccountId.value)

    suspend fun activeSyncWorkIds(accountId: AccountId): List<String> =
        measurementDao.activeSyncWorkIds(accountId.value)

    override suspend fun enqueue(raw: RawScaleMeasurement): PendingPersistenceResult =
        enqueue(raw, MatchingEngine())

    override suspend fun enqueue(
        raw: RawScaleMeasurement,
        matchingEngine: MatchingEngine,
    ): PendingPersistenceResult =
        database.withTransaction {
            if (raw.isStableWeight &&
                acceptedStableMeasurementDao.getLatest()?.exactlyMatches(raw) == true
            ) {
                return@withTransaction PendingPersistenceResult.ExactReplay
            }

            suspend fun accepted(result: PendingPersistenceResult): PendingPersistenceResult {
                if (raw.isStableWeight && result.updatesAcceptedStableBaseline()) {
                    acceptedStableMeasurementDao.replaceLatestAndDevice(
                        AcceptedStableMeasurementEntity.latest(raw),
                    )
                }
                return result
            }

            val timestamp = now()
            val timestampMillis = timestamp.toEpochMilli()
            pendingDao.deleteExpiredTombstones(timestampMillis)
            val hash = raw.deduplicationHash()
            // v3 tombstones do not have provenance columns. Preserve their exact-hash behavior.
            if (pendingDao.getActiveTombstone(hash, timestampMillis) != null) {
                return@withTransaction accepted(PendingPersistenceResult.Tombstoned)
            }

            val measuredAtEpochSecond = raw.measuredAt.epochSecond
            val candidateBounds = MeasurementDeduplicationPolicy.epochSecondBounds(
                measuredAtEpochSecond,
            )
            val candidate = listOfNotNull(
                pendingDao.findNearestActiveTombstone(
                    deviceAddress = raw.deviceAddress,
                    rawWeight = raw.rawWeight,
                    measuredAtEpochSecond = measuredAtEpochSecond,
                    minimumEpochSecond = candidateBounds.first,
                    maximumEpochSecond = candidateBounds.last,
                    nowEpochMillis = timestampMillis,
                )?.let { DeduplicationCandidate.Tombstone(it) },
                measurementDao.findNearestDeduplicationCandidate(
                    deviceAddress = raw.deviceAddress,
                    rawWeight = raw.rawWeight,
                    measuredAtEpochSecond = measuredAtEpochSecond,
                    minimumEpochSecond = candidateBounds.first,
                    maximumEpochSecond = candidateBounds.last,
                )?.let { DeduplicationCandidate.Finalized(it) },
                pendingDao.findNearestAggregate(
                    deviceAddress = raw.deviceAddress,
                    rawWeight = raw.rawWeight,
                    measuredAtEpochSecond = measuredAtEpochSecond,
                    minimumEpochSecond = candidateBounds.first,
                    maximumEpochSecond = candidateBounds.last,
                )?.let { DeduplicationCandidate.Pending(it) },
            ).filter { it.isSameMeasurement(raw) }.minWithOrNull(
                compareBy<DeduplicationCandidate> {
                    MeasurementDeduplicationPolicy.secondDifference(
                        it.measuredAtEpochSecond,
                        measuredAtEpochSecond,
                    )
                }.thenBy(DeduplicationCandidate::tieBreakPriority),
            )

            when (candidate) {
                is DeduplicationCandidate.Tombstone ->
                    return@withTransaction accepted(PendingPersistenceResult.Tombstoned)
                is DeduplicationCandidate.Finalized -> {
                    val result = upgradeFinalizedMeasurement(candidate.entity, raw)
                        ?: PendingPersistenceResult.AlreadyFinalized(
                            candidate.entity.toAccountMeasurement(),
                        )
                    return@withTransaction accepted(result)
                }
                is DeduplicationCandidate.Pending -> {
                    val pending = candidate.entity
                    val enrich = !pending.hasFullBodyComposition() && raw.hasFullBodyComposition
                    val exactReplay = pending.deduplicationHash == hash
                    val replayPlan = pendingReplayPlan(
                        exactReplay = exactReplay,
                        isBeforeDeadline = timestampMillis < pending.finalizeAfterEpochMillis,
                    )
                    val shouldScheduleFinalization = replayPlan.shouldScheduleFinalization
                    // Direct processing and its durable fallback can both observe the same packet.
                    // That exact replay may enrich the aggregate but must not slide its deadline.
                    // Once the aggregate is due it is resolver-visible. Keep that transition
                    // irreversible even if a delayed BLE callback is processed afterwards.
                    val finalizeAfterEpochMillis = if (replayPlan.shouldSlideDeadline) {
                        timestamp.plusSeconds(DEBOUNCE_SECONDS).toEpochMilli()
                    } else {
                        pending.finalizeAfterEpochMillis
                    }
                    val current = pending.copy(
                        impedanceOhm = if (enrich) raw.impedanceOhm else pending.impedanceOhm,
                        isStable = if (enrich) raw.isStable else pending.isStable,
                        hasImpedance = if (enrich) raw.hasImpedance else pending.hasImpedance,
                        rawPayload = if (enrich) raw.rawPayload.copyOf() else pending.rawPayload,
                        finalizeAfterEpochMillis = finalizeAfterEpochMillis,
                    ).let { withPreliminaryDecision(it, matchingEngine) }
                    if (current != pending) {
                        check(pendingDao.update(current) == 1) {
                            "Pending aggregate disappeared during ingestion"
                        }
                    }
                    return@withTransaction accepted(
                        PendingPersistenceResult.AlreadyPending(
                            pending = current.toDomain(),
                            wasEnriched = enrich,
                            shouldScheduleFinalization = shouldScheduleFinalization,
                        ),
                    )
                }
                null -> Unit
            }

            if (raw.hasFullBodyComposition) {
                val enrichmentBounds = MeasurementEnrichmentPolicy.candidateEpochSecondBounds(
                    measuredAtEpochSecond,
                )
                val enrichmentCandidate = pendingDao.findNearestActiveIncompletePredecessor(
                    deviceAddress = raw.deviceAddress,
                    rawWeight = raw.rawWeight,
                    minimumEpochSecond = enrichmentBounds.first,
                    maximumEpochSecond = enrichmentBounds.last,
                    nowEpochMillis = timestampMillis,
                    minimumImpedanceOhm = RawScaleMeasurement.MIN_IMPEDANCE_OHM,
                    maximumImpedanceOhm = RawScaleMeasurement.MAX_IMPEDANCE_OHM,
                )
                val enrichment = enrichmentCandidate?.let {
                    activePendingEnrichment(it, raw, timestamp)
                }
                if (enrichment != null) {
                    check(pendingDao.update(enrichment.entity) == 1) {
                        "Pending aggregate disappeared during enrichment"
                    }
                    return@withTransaction accepted(
                        PendingPersistenceResult.AlreadyPending(
                            pending = enrichment.entity.toDomain(),
                            wasEnriched = true,
                            shouldScheduleFinalization = enrichment.shouldScheduleFinalization,
                        ),
                    )
                }

                measurementDao.findExactFinalizedFullReplay(
                    deviceAddress = raw.deviceAddress,
                    rawWeight = raw.rawWeight,
                    rawPayloadHex = raw.rawPayload.toHexString(),
                    minimumEpochSecond = enrichmentBounds.first,
                    maximumEpochSecond = enrichmentBounds.last,
                )?.let { exactReplay ->
                    return@withTransaction accepted(
                        PendingPersistenceResult.AlreadyFinalized(
                            exactReplay.toAccountMeasurement(),
                        ),
                    )
                }

                val finalizedCandidate = measurementDao
                    .findNearestFinalizedEnrichmentPredecessor(
                        deviceAddress = raw.deviceAddress,
                        rawWeight = raw.rawWeight,
                        minimumEpochSecond = enrichmentBounds.first,
                        maximumEpochSecond = enrichmentBounds.last,
                    )
                if (finalizedCandidate != null) {
                    upgradeFinalizedMeasurement(finalizedCandidate, raw)?.let {
                        return@withTransaction accepted(it)
                    }
                }
            }
            val entity = PendingMeasurementEntity(
                id = newId(),
                deviceAddress = raw.deviceAddress,
                measuredAtEpochSecond = raw.measuredAt.epochSecond,
                weightKg = raw.weightKg,
                impedanceOhm = raw.impedanceOhm,
                isStable = raw.isStable,
                hasImpedance = raw.hasImpedance,
                rawPayload = raw.rawPayload.copyOf(),
                deduplicationHash = hash,
                enqueuedAtEpochMillis = timestampMillis,
                rawWeight = raw.rawWeight,
                finalizeAfterEpochMillis = timestamp.plusSeconds(DEBOUNCE_SECONDS).toEpochMilli(),
            ).let { withPreliminaryDecision(it, matchingEngine) }
            val result = if (pendingDao.insert(entity) == -1L) {
                val concurrent = requireNotNull(pendingDao.getByHash(hash))
                PendingPersistenceResult.AlreadyPending(concurrent.toDomain())
            } else {
                PendingPersistenceResult.Inserted(entity.toDomain())
            }
            accepted(result)
        }

    private fun PendingPersistenceResult.updatesAcceptedStableBaseline(): Boolean = when (this) {
        is PendingPersistenceResult.Inserted,
        is PendingPersistenceResult.AlreadyPending,
        is PendingPersistenceResult.UpgradedFinalized,
        -> true
        PendingPersistenceResult.ExactReplay,
        is PendingPersistenceResult.AlreadyFinalized,
        PendingPersistenceResult.Tombstoned,
        -> false
    }

    private suspend fun upgradeFinalizedMeasurement(
        candidate: MeasurementEntity,
        incoming: RawScaleMeasurement,
    ): PendingPersistenceResult? {
        if (candidate.measurementType == MeasurementType.FULL &&
            candidate.rawPayloadHex == incoming.rawPayload.toHexString()
        ) {
            return PendingPersistenceResult.AlreadyFinalized(candidate.toAccountMeasurement())
        }
        if (!MeasurementEnrichmentPolicy.canEnrich(candidate.toRawScaleMeasurement(), incoming)) {
            return null
        }

        val account = accountDao.get(candidate.accountId)
        val profile = account?.toDomain()?.profile?.toUserProfileOrNull()
            ?: return PendingPersistenceResult.AlreadyFinalized(candidate.toAccountMeasurement())
        val compositionRaw = incoming.copy(measuredAt = candidate.measuredAt)
        val calculated = calculator.calculate(compositionRaw, profile).toEntity(
            rawPayload = incoming.rawPayload,
            fingerprint = candidate.fingerprint,
            huaweiSyncEnabled = huaweiSyncEnabled,
            accountId = AccountId(candidate.accountId),
            externalSyncPolicy = ExternalSyncPolicy.valueOf(candidate.externalSyncPolicy),
            sourcePendingId = candidate.sourcePendingId,
            deduplicationHash = candidate.deduplicationHash,
        )
        val upgraded = calculated.copy(
            id = candidate.id,
            huaweiStatus = candidate.huaweiStatus.requeueUnlessTerminal(),
            healthConnectStatus = candidate.healthConnectStatus.requeueUnlessTerminal(),
            huaweiError = candidate.huaweiError.preserveForTerminalStatus(candidate.huaweiStatus),
            healthConnectError = candidate.healthConnectError.preserveForTerminalStatus(
                candidate.healthConnectStatus,
            ),
            huaweiWeightSynced = candidate.huaweiWeightSynced,
            healthConnectWeightSynced = candidate.healthConnectWeightSynced,
            createdAtEpochMillis = candidate.createdAtEpochMillis,
            accountId = candidate.accountId,
            externalSyncPolicy = candidate.externalSyncPolicy,
            sourcePendingId = candidate.sourcePendingId,
            deduplicationHash = candidate.deduplicationHash,
            huaweiSyncedCalculatedValues = candidate.huaweiSyncedCalculatedValues,
            healthConnectSyncedCalculatedValues = candidate.healthConnectSyncedCalculatedValues,
        )
        check(measurementDao.update(upgraded) == 1) {
            "Finalized measurement disappeared during enrichment"
        }
        return PendingPersistenceResult.UpgradedFinalized(upgraded.toAccountMeasurement())
    }

    override suspend fun finalizePending(
        pendingId: PendingMeasurementId,
        accountId: AccountId,
    ): FinalizePendingResult = database.withTransaction {
        finalizePendingLocked(pendingId, accountId)
    }

    override suspend fun finalizePendingIfDue(
        pendingId: PendingMeasurementId,
        accountId: AccountId,
        now: Instant,
    ): DuePendingPersistenceResult = database.withTransaction {
        measurementDao.getByPendingId(pendingId.value)?.let {
            return@withTransaction DuePendingPersistenceResult.AlreadyFinalized(
                it.toAccountMeasurement(),
            )
        }
        val pending = pendingDao.get(pendingId.value)
            ?: return@withTransaction DuePendingPersistenceResult.PendingNotFound
        if (now.toEpochMilli() < pending.finalizeAfterEpochMillis) {
            return@withTransaction DuePendingPersistenceResult.NotDue(pending.toDomain())
        }
        finalizePendingLocked(pendingId, accountId).toDueResult()
    }

    override suspend fun routeDueAtomically(
        pendingId: PendingMeasurementId,
        now: Instant,
        matchingEngine: MatchingEngine,
    ): AtomicDueRoutingResult = database.withTransaction {
        measurementDao.getByPendingId(pendingId.value)?.let {
            return@withTransaction AtomicDueRoutingResult.AlreadyFinalized(
                it.toAccountMeasurement(),
            )
        }
        val pendingEntity = pendingDao.get(pendingId.value)
            ?: return@withTransaction AtomicDueRoutingResult.PendingNotFound
        val pending = pendingEntity.toDomain()
        if (now.toEpochMilli() < pendingEntity.finalizeAfterEpochMillis) {
            return@withTransaction AtomicDueRoutingResult.NotDue(pending)
        }
        val snapshot = routingSnapshotLocked(pendingEntity.measuredAtEpochSecond)
        val decision = snapshot.decide(pending, matchingEngine)
        val accountId = decision.unambiguousAccountId()
        if (accountId != null) {
            return@withTransaction when (val result = finalizePendingLocked(pendingId, accountId)) {
                is FinalizePendingResult.Finalized ->
                    AtomicDueRoutingResult.Finalized(result.measurement)
                is FinalizePendingResult.AlreadyFinalized ->
                    AtomicDueRoutingResult.AlreadyFinalized(result.measurement)
                FinalizePendingResult.PendingNotFound -> AtomicDueRoutingResult.PendingNotFound
                FinalizePendingResult.AccountNotFound,
                FinalizePendingResult.ProfileIncomplete,
                -> AtomicDueRoutingResult.AccountUnavailable
            }
        }
        if (decision === RoutingDecision.NoMatch && snapshot.settings.ignoreUnknownMeasurements) {
            discardPendingWithoutUndoLocked(pendingEntity)
            return@withTransaction AtomicDueRoutingResult.AutomaticallyIgnoredUnknown
        }
        check(pendingDao.notifyAwaitingDecision(pendingEntity.id) == 1) {
            "Pending measurement disappeared while becoming resolver-visible"
        }
        AtomicDueRoutingResult.AwaitingDecision(pending, decision)
    }

    override suspend fun reclassifyPending(
        matchingEngine: MatchingEngine,
    ): List<PendingMeasurement> = database.withTransaction {
        pendingDao.getAll().map { pending ->
            val reclassified = withPreliminaryDecision(pending, matchingEngine)
            if (reclassified.provisionalAccountId != pending.provisionalAccountId) {
                check(pendingDao.update(reclassified) == 1) {
                    "Pending measurement disappeared during preliminary reclassification"
                }
            }
            reclassified.toDomain()
        }
    }

    override suspend fun createAccountAndAssignPending(
        pendingId: PendingMeasurementId,
        account: NewAccount,
    ): CreateAccountAndAssignResult = database.withTransaction {
        measurementDao.getByPendingId(pendingId.value)?.let { finalized ->
            return@withTransaction CreateAccountAndAssignResult.AlreadyFinalized(
                finalized.toAccountMeasurement(),
            )
        }
        if (pendingDao.get(pendingId.value) == null) {
            return@withTransaction CreateAccountAndAssignResult.PendingNotFound
        }
        if (accountDao.getByNormalizedName(account.normalizedName) != null) {
            return@withTransaction CreateAccountAndAssignResult.NameConflict(account.normalizedName)
        }
        appStateDao.insertDefault()
        val timestamp = now()
        val entity = AccountEntity(
            id = newId(),
            displayName = account.displayName,
            normalizedName = account.normalizedName,
            heightCm = account.profile.heightCm,
            birthDateEpochDay = account.profile.birthDate.toEpochDay(),
            sex = account.profile.sex.name,
            isProfileComplete = true,
            createdAtEpochMillis = timestamp.toEpochMilli(),
            updatedAtEpochMillis = timestamp.toEpochMilli(),
        )
        if (accountDao.insert(entity) == -1L) {
            return@withTransaction CreateAccountAndAssignResult.NameConflict(account.normalizedName)
        }
        if (accountDao.count() == 1) {
            check(appStateDao.setPrimary(entity.id) == 1) { "App state singleton is missing" }
        }
        when (val result = finalizePendingLocked(pendingId, AccountId(entity.id))) {
            is FinalizePendingResult.Finalized -> CreateAccountAndAssignResult.Created(
                account = entity.toDomain(),
                measurement = result.measurement,
            )
            is FinalizePendingResult.AlreadyFinalized ->
                CreateAccountAndAssignResult.AlreadyFinalized(result.measurement)
            FinalizePendingResult.PendingNotFound -> CreateAccountAndAssignResult.PendingNotFound
            FinalizePendingResult.AccountNotFound,
            FinalizePendingResult.ProfileIncomplete,
            -> error("A newly inserted complete account must be usable in the same transaction")
        }
    }

    override suspend fun discardPending(
        pendingId: PendingMeasurementId,
    ): DiscardPendingResult = database.withTransaction {
        measurementDao.getByPendingId(pendingId.value)?.let { finalized ->
            return@withTransaction DiscardPendingResult.AlreadyFinalized(
                finalized.toAccountMeasurement(),
            )
        }
        val pending = pendingDao.get(pendingId.value)
            ?: return@withTransaction DiscardPendingResult.PendingNotFound
        val undoToken = PendingDiscardUndoToken(pending.toDomain())
        val expiresAt = now().plus(TOMBSTONE_TTL).toEpochMilli()
        pendingDao.upsertTombstone(
            MeasurementTombstoneEntity(
                deduplicationHash = pending.deduplicationHash,
                expiresAtEpochMillis = expiresAt,
                deviceAddress = pending.deviceAddress,
                measuredAtEpochSecond = pending.measuredAtEpochSecond,
                rawWeight = pending.rawWeight,
            ),
        )
        check(pendingDao.delete(pendingId.value) == 1) {
            "Pending measurement disappeared inside its discard transaction"
        }
        DiscardPendingResult.Discarded(undoToken)
    }

    override suspend fun discardUnknownPendingIfEnabled(
        pendingId: PendingMeasurementId,
    ): AutoIgnorePendingPersistenceResult = database.withTransaction {
        measurementDao.getByPendingId(pendingId.value)?.let { finalized ->
            return@withTransaction AutoIgnorePendingPersistenceResult.AlreadyFinalized(
                finalized.toAccountMeasurement(),
            )
        }
        val pending = pendingDao.get(pendingId.value)
            ?: return@withTransaction AutoIgnorePendingPersistenceResult.PendingNotFound
        if (appStateDao.get()?.ignoreUnknownMeasurements != true) {
            return@withTransaction AutoIgnorePendingPersistenceResult.PolicyDisabled
        }
        discardPendingWithoutUndoLocked(pending)
        AutoIgnorePendingPersistenceResult.Discarded
    }

    override suspend fun discardUnknownPendingIfDue(
        pendingId: PendingMeasurementId,
        now: Instant,
    ): DueUnknownDiscardPersistenceResult = database.withTransaction {
        measurementDao.getByPendingId(pendingId.value)?.let {
            return@withTransaction DueUnknownDiscardPersistenceResult.AlreadyFinalized(
                it.toAccountMeasurement(),
            )
        }
        val pending = pendingDao.get(pendingId.value)
            ?: return@withTransaction DueUnknownDiscardPersistenceResult.PendingNotFound
        if (now.toEpochMilli() < pending.finalizeAfterEpochMillis) {
            return@withTransaction DueUnknownDiscardPersistenceResult.NotDue(pending.toDomain())
        }
        if (appStateDao.get()?.ignoreUnknownMeasurements != true) {
            return@withTransaction DueUnknownDiscardPersistenceResult.PolicyDisabled
        }
        discardPendingWithoutUndoLocked(pending)
        DueUnknownDiscardPersistenceResult.Discarded
    }

    override suspend fun discardPendingAndUpdateIgnorePolicy(
        pendingId: PendingMeasurementId,
        ignoreUnknownMeasurements: Boolean,
    ): DiscardPendingAndUpdateIgnorePolicyResult = database.withTransaction {
        measurementDao.getByPendingId(pendingId.value)?.let { finalized ->
            return@withTransaction DiscardPendingAndUpdateIgnorePolicyResult.AlreadyFinalized(
                finalized.toAccountMeasurement(),
            )
        }
        val pending = pendingDao.get(pendingId.value)
            ?: return@withTransaction DiscardPendingAndUpdateIgnorePolicyResult.PendingNotFound
        val undoToken = PendingDiscardUndoToken(pending.toDomain())
        discardPendingWithoutUndoLocked(pending)
        appStateDao.insertDefault()
        check(appStateDao.setIgnoreUnknownMeasurements(ignoreUnknownMeasurements) == 1) {
            "App state singleton is missing"
        }
        DiscardPendingAndUpdateIgnorePolicyResult.Discarded(
            undoToken = undoToken.takeUnless { ignoreUnknownMeasurements },
        )
    }

    override suspend fun restorePending(
        undoToken: PendingDiscardUndoToken,
    ): RestorePendingResult = database.withTransaction {
        val pending = undoToken.pending
        val raw = pending.toRawScaleMeasurement()
        val finalized = measurementDao.getByPendingId(pending.id.value)
            ?: measurementDao.getByDeduplicationHash(pending.deduplicationHash)
            ?: measurementDao.getByFingerprint(measurementFingerprint(raw))
        if (finalized != null) {
            return@withTransaction RestorePendingResult.AlreadyFinalized(
                finalized.toAccountMeasurement(),
            )
        }

        pendingDao.get(pending.id.value)?.let { existing ->
            val existingPending = existing.toDomain()
            return@withTransaction if (existingPending == pending) {
                RestorePendingResult.AlreadyRestored(existingPending)
            } else {
                RestorePendingResult.Conflict(existingPending)
            }
        }
        pendingDao.getByHash(pending.deduplicationHash)?.let { existing ->
            return@withTransaction RestorePendingResult.Conflict(existing.toDomain())
        }

        val entity = pending.toEntity()
        if (pendingDao.insert(entity) == -1L) {
            val conflicting = pendingDao.get(pending.id.value)
                ?: pendingDao.getByHash(pending.deduplicationHash)
                ?: error("Pending restore conflicted without a durable matching record")
            val conflictingPending = conflicting.toDomain()
            return@withTransaction if (conflictingPending == pending) {
                RestorePendingResult.AlreadyRestored(conflictingPending)
            } else {
                RestorePendingResult.Conflict(conflictingPending)
            }
        }

        // The insert and tombstone deletion share this transaction. If deletion fails, Room rolls
        // the restored row back and the tombstone remains authoritative.
        pendingDao.deleteTombstone(pending.deduplicationHash)
        RestorePendingResult.Restored(entity.toDomain())
    }

    suspend fun cleanupExpiredTombstones(): Int =
        pendingDao.deleteExpiredTombstones(now().toEpochMilli())

    private suspend fun discardPendingWithoutUndoLocked(pending: PendingMeasurementEntity) {
        pendingDao.upsertTombstone(
            MeasurementTombstoneEntity(
                deduplicationHash = pending.deduplicationHash,
                expiresAtEpochMillis = now().plus(TOMBSTONE_TTL).toEpochMilli(),
                deviceAddress = pending.deviceAddress,
                measuredAtEpochSecond = pending.measuredAtEpochSecond,
                rawWeight = pending.rawWeight,
            ),
        )
        check(pendingDao.delete(pending.id) == 1) {
            "Pending measurement disappeared inside its discard transaction"
        }
    }

    private suspend fun finalizePendingLocked(
        pendingId: PendingMeasurementId,
        accountId: AccountId,
    ): FinalizePendingResult {
        measurementDao.getByPendingId(pendingId.value)?.let { finalized ->
            return FinalizePendingResult.AlreadyFinalized(finalized.toAccountMeasurement())
        }
        val pending = pendingDao.get(pendingId.value)
            ?: return FinalizePendingResult.PendingNotFound
        val account = accountDao.get(accountId.value)
            ?: return FinalizePendingResult.AccountNotFound
        val profile = account.toDomain().profile.toUserProfileOrNull()
            ?: return FinalizePendingResult.ProfileIncomplete
        val state = appStateDao.get()
        val policy = if (state?.primaryAccountId == accountId.value) {
            ExternalSyncPolicy.AUTO
        } else {
            ExternalSyncPolicy.ACCOUNT_LOCAL
        }
        val raw = pending.toDomain().toRawScaleMeasurement()
        var measurement = if (raw.hasFullBodyComposition) {
            calculator.calculate(raw, profile).toEntity(
                rawPayload = pending.rawPayload,
                fingerprint = measurementFingerprint(raw),
                huaweiSyncEnabled = huaweiSyncEnabled,
                accountId = accountId,
                externalSyncPolicy = policy,
                sourcePendingId = pending.id,
                deduplicationHash = pending.deduplicationHash,
            )
        } else {
            raw.toWeightOnlyEntity(
                huaweiSyncEnabled = huaweiSyncEnabled,
                accountId = accountId,
                externalSyncPolicy = policy,
                sourcePendingId = pending.id,
                deduplicationHash = pending.deduplicationHash,
            )
        }.copy(createdAtEpochMillis = now().toEpochMilli())
        if (policy == ExternalSyncPolicy.ACCOUNT_LOCAL) {
            measurement = measurement.copy(
                huaweiStatus = if (huaweiSyncEnabled) {
                    SyncStatus.LOCAL_ONLY.name
                } else {
                    SyncStatus.DISABLED.name
                },
                healthConnectStatus = SyncStatus.LOCAL_ONLY.name,
                huaweiError = if (huaweiSyncEnabled) null else measurement.huaweiError,
                healthConnectError = null,
            )
        }
        if (measurementDao.insert(measurement) == -1L) {
            val existing = measurementDao.getByPendingId(pending.id)
                ?: measurementDao.getByDeduplicationHash(pending.deduplicationHash)
                ?: measurementDao.get(measurement.id)
                ?: error("Measurement insert conflicted without a durable matching record")
            pendingDao.delete(pending.id)
            return FinalizePendingResult.AlreadyFinalized(existing.toAccountMeasurement())
        }
        check(pendingDao.delete(pending.id) == 1) {
            "Pending measurement disappeared inside its finalize transaction"
        }
        return FinalizePendingResult.Finalized(measurement.toAccountMeasurement())
    }

    private suspend fun routingSnapshotLocked(
        measuredAtEpochSecond: Long,
    ): RoutingSnapshot {
        val accounts = accountDao.getAll().map(AccountEntity::toDomain)
        val state = appStateDao.get()
        val settings = state?.let {
            AccountSettings(
                primaryAccountId = it.primaryAccountId?.let(::AccountId),
                weightDeltaKg = it.weightDeltaKg,
                ignoreUnknownMeasurements = it.ignoreUnknownMeasurements,
            )
        } ?: AccountSettings()
        val histories = accounts.associate { account ->
            account.id to measurementDao.latestHistoryBefore(
                account.id.value,
                measuredAtEpochSecond,
            ).map { WeightHistoryRecord(it.measuredAt, it.weightKg) }
        }
        return RoutingSnapshot(accounts, histories, settings)
    }

    private suspend fun withPreliminaryDecision(
        pending: PendingMeasurementEntity,
        matchingEngine: MatchingEngine,
    ): PendingMeasurementEntity {
        val decision = routingSnapshotLocked(pending.measuredAtEpochSecond)
            .decide(pending.toDomain(), matchingEngine)
        return pending.copy(provisionalAccountId = decision.unambiguousAccountId()?.value)
    }

    companion object {
        val TOMBSTONE_TTL: Duration = Duration.ofDays(30)
        const val DEBOUNCE_SECONDS: Long = MeasurementDeduplicationPolicy.WINDOW_SECONDS
    }
}

private fun FinalizePendingResult.toDueResult(): DuePendingPersistenceResult = when (this) {
    is FinalizePendingResult.Finalized -> DuePendingPersistenceResult.Finalized(measurement)
    is FinalizePendingResult.AlreadyFinalized -> DuePendingPersistenceResult.AlreadyFinalized(measurement)
    FinalizePendingResult.PendingNotFound -> DuePendingPersistenceResult.PendingNotFound
    FinalizePendingResult.AccountNotFound -> DuePendingPersistenceResult.AccountNotFound
    FinalizePendingResult.ProfileIncomplete -> DuePendingPersistenceResult.ProfileIncomplete
}

private sealed interface DeduplicationCandidate {
    val deviceAddress: String
    val rawWeight: Int
    val measuredAtEpochSecond: Long
    val tieBreakPriority: Int

    data class Tombstone(val entity: MeasurementTombstoneEntity) : DeduplicationCandidate {
        override val deviceAddress: String = requireNotNull(entity.deviceAddress)
        override val rawWeight: Int = requireNotNull(entity.rawWeight)
        override val measuredAtEpochSecond: Long = requireNotNull(entity.measuredAtEpochSecond)
        override val tieBreakPriority: Int = 0
    }

    data class Finalized(val entity: MeasurementEntity) : DeduplicationCandidate {
        override val deviceAddress: String = entity.deviceAddress
        override val rawWeight: Int = entity.rawWeight
        override val measuredAtEpochSecond: Long = entity.measuredAtEpochSecond
        override val tieBreakPriority: Int = 1
    }

    data class Pending(val entity: PendingMeasurementEntity) : DeduplicationCandidate {
        override val deviceAddress: String = entity.deviceAddress
        override val rawWeight: Int = entity.rawWeight
        override val measuredAtEpochSecond: Long = entity.measuredAtEpochSecond
        override val tieBreakPriority: Int = 2
    }
}

private fun DeduplicationCandidate.isSameMeasurement(raw: RawScaleMeasurement): Boolean =
    MeasurementDeduplicationPolicy.isSameMeasurement(
        deviceAddress = raw.deviceAddress,
        rawWeight = raw.rawWeight,
        measuredAtEpochSecond = raw.measuredAt.epochSecond,
        candidateDeviceAddress = deviceAddress,
        candidateRawWeight = rawWeight,
        candidateMeasuredAtEpochSecond = measuredAtEpochSecond,
    )

fun RawScaleMeasurement.deduplicationHash(): String {
    val material = buildString {
        append(deviceAddress.uppercase(Locale.ROOT))
        append('|')
        append(measuredAt.epochSecond)
        append('|')
        append(rawWeight)
    }
    return MessageDigest.getInstance("SHA-256")
        .digest(material.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}

private fun PendingMeasurementEntity.hasFullBodyComposition(): Boolean =
    toDomain().toRawScaleMeasurement().hasFullBodyComposition

private fun MeasurementEntity.toRawScaleMeasurement(): RawScaleMeasurement = RawScaleMeasurement(
    deviceAddress = deviceAddress,
    measuredAt = measuredAt,
    weightKg = weightKg,
    impedanceOhm = impedanceOhm ?: 0,
    isStable = true,
    hasImpedance = measurementType == MeasurementType.FULL,
    rawPayload = byteArrayOf(),
)

private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }

private fun PendingMeasurement.toEntity(): PendingMeasurementEntity = PendingMeasurementEntity(
    id = id.value,
    deviceAddress = deviceAddress,
    measuredAtEpochSecond = measuredAt.epochSecond,
    weightKg = weightKg,
    impedanceOhm = impedanceOhm,
    isStable = isStable,
    hasImpedance = hasImpedance,
    rawPayload = rawPayload.copyOf(),
    deduplicationHash = deduplicationHash,
    enqueuedAtEpochMillis = enqueuedAt.toEpochMilli(),
    rawWeight = rawWeight,
    finalizeAfterEpochMillis = finalizeAfter.toEpochMilli(),
    provisionalAccountId = provisionalAccountId?.value,
)

private data class RoutingSnapshot(
    val accounts: List<Account>,
    val histories: Map<AccountId, List<WeightHistoryRecord>>,
    val settings: AccountSettings,
) {
    fun decide(pending: PendingMeasurement, matchingEngine: MatchingEngine): RoutingDecision =
        matchingEngine.match(pending, accounts, histories, settings)
}

private fun RoutingDecision.unambiguousAccountId(): AccountId? = when (this) {
    is RoutingDecision.AssignPrimary -> accountId
    is RoutingDecision.AssignSingle -> candidate.accountId
    is RoutingDecision.ChooseAccount,
    RoutingDecision.NoMatch,
    -> null
}

/** Smallest whole-second timestamp which is not before this instant. */
private fun Instant.ceilToEpochSecond(): Long {
    return if (nano == 0) {
        epochSecond
    } else {
        Math.addExact(epochSecond, 1L)
    }
}
