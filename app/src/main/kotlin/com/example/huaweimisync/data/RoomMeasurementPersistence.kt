package com.example.huaweimisync.data

import androidx.room.withTransaction
import com.example.huaweimisync.core.BodyCompositionCalculator
import com.example.huaweimisync.core.RawScaleMeasurement
import com.example.huaweimisync.core.measurementFingerprint
import com.example.huaweimisync.domain.AccountId
import com.example.huaweimisync.domain.AccountMeasurement
import com.example.huaweimisync.domain.CreateAccountAndAssignResult
import com.example.huaweimisync.domain.DiscardPendingResult
import com.example.huaweimisync.domain.DiscardPendingWithoutUndoResult
import com.example.huaweimisync.domain.ExternalSyncPolicy
import com.example.huaweimisync.domain.FinalizePendingResult
import com.example.huaweimisync.domain.NewAccount
import com.example.huaweimisync.domain.PendingDiscardUndoToken
import com.example.huaweimisync.domain.PendingMeasurement
import com.example.huaweimisync.domain.PendingMeasurementId
import com.example.huaweimisync.domain.RestorePendingResult
import com.example.huaweimisync.domain.toRawScaleMeasurement
import com.example.huaweimisync.domain.toUserProfileOrNull
import com.example.huaweimisync.domain.routing.WeightHistoryRecord
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
    data class Inserted(val pending: PendingMeasurement) : PendingPersistenceResult
    data class AlreadyPending(val pending: PendingMeasurement) : PendingPersistenceResult
    data class AlreadyFinalized(val measurement: AccountMeasurement) : PendingPersistenceResult
    data object Tombstoned : PendingPersistenceResult
}

class RoomMeasurementPersistence(
    private val database: AppDatabase,
    private val calculator: BodyCompositionCalculator,
    private val huaweiSyncEnabled: Boolean,
    private val accountDao: AccountDao = database.accountDao(),
    private val appStateDao: AppStateDao = database.appStateDao(),
    private val measurementDao: MultiAccountMeasurementDao = database.multiAccountMeasurementDao(),
    private val pendingDao: PendingMeasurementDao = database.pendingMeasurementDao(),
    private val now: () -> Instant = Instant::now,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : MeasurementRoutingPersistence {
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
            startInclusive = startInclusive.ceilToEpochMilli(),
            endExclusive = endExclusive.ceilToEpochMilli(),
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
            startInclusive = startInclusive.ceilToEpochMilli(),
            endExclusive = endExclusive.ceilToEpochMilli(),
        ).map { values -> values.map(MeasurementEntity::toAccountMeasurement) }
    }

    fun observePending(): Flow<List<PendingMeasurement>> = pendingDao.observeAll().map { values ->
        values.map(PendingMeasurementEntity::toDomain)
    }

    override suspend fun getPending(id: PendingMeasurementId): PendingMeasurement? =
        pendingDao.get(id.value)?.toDomain()

    suspend fun latestWeightsBefore(
        accountId: AccountId,
        measuredAtExclusive: Instant,
    ): List<Double> = measurementDao.latestWeightsBefore(
        accountId.value,
        measuredAtExclusive.ceilToEpochMilli(),
    )

    override suspend fun latestHistoryBefore(
        accountId: AccountId,
        measuredAtExclusive: Instant,
    ): List<WeightHistoryRecord> = measurementDao.latestHistoryBefore(
        accountId.value,
        measuredAtExclusive.ceilToEpochMilli(),
    ).map { value ->
        WeightHistoryRecord(
            measuredAt = Instant.ofEpochMilli(value.measuredAtEpochMillis),
            weightKg = value.weightKg,
        )
    }

    override suspend fun pendingSnapshot(): List<PendingMeasurement> =
        pendingDao.getAll().map(PendingMeasurementEntity::toDomain)

    suspend fun eligiblePendingSyncIds(primaryAccountId: AccountId): List<String> =
        measurementDao.eligiblePendingSyncIds(primaryAccountId.value)

    suspend fun activeSyncWorkIds(accountId: AccountId): List<String> =
        measurementDao.activeSyncWorkIds(accountId.value)

    override suspend fun enqueue(raw: RawScaleMeasurement): PendingPersistenceResult =
        database.withTransaction {
            val timestamp = now()
            val timestampMillis = timestamp.toEpochMilli()
            pendingDao.deleteExpiredTombstones(timestampMillis)
            val hash = raw.deduplicationHash()
            if (pendingDao.getActiveTombstone(hash, timestampMillis) != null) {
                return@withTransaction PendingPersistenceResult.Tombstoned
            }
            measurementDao.getByDeduplicationHash(hash)?.let { finalized ->
                val current = upgradeFinalizedIfNeeded(finalized, raw)
                return@withTransaction PendingPersistenceResult.AlreadyFinalized(
                    current.toAccountMeasurement(),
                )
            }
            measurementDao.getByFingerprint(measurementFingerprint(raw))?.let { finalized ->
                val current = upgradeFinalizedIfNeeded(finalized, raw)
                return@withTransaction PendingPersistenceResult.AlreadyFinalized(
                    current.toAccountMeasurement(),
                )
            }
            pendingDao.getByHash(hash)?.let { pending ->
                val current = if (!pending.hasFullBodyComposition() && raw.hasFullBodyComposition) {
                    pending.copy(
                        impedanceOhm = raw.impedanceOhm,
                        isStable = raw.isStable,
                        hasImpedance = raw.hasImpedance,
                        rawPayload = raw.rawPayload.copyOf(),
                        rawWeight = raw.rawWeight,
                    ).also { upgraded -> check(pendingDao.update(upgraded) == 1) }
                } else {
                    pending
                }
                return@withTransaction PendingPersistenceResult.AlreadyPending(current.toDomain())
            }
            val entity = PendingMeasurementEntity(
                id = newId(),
                deviceAddress = raw.deviceAddress,
                measuredAtEpochSecond = raw.measuredAt.epochSecond,
                measuredAtNano = raw.measuredAt.nano,
                weightKg = raw.weightKg,
                impedanceOhm = raw.impedanceOhm,
                isStable = raw.isStable,
                hasImpedance = raw.hasImpedance,
                rawPayload = raw.rawPayload.copyOf(),
                deduplicationHash = hash,
                enqueuedAtEpochMillis = timestampMillis,
                rawWeight = raw.rawWeight,
            )
            if (pendingDao.insert(entity) == -1L) {
                val concurrent = requireNotNull(pendingDao.getByHash(hash))
                PendingPersistenceResult.AlreadyPending(concurrent.toDomain())
            } else {
                PendingPersistenceResult.Inserted(entity.toDomain())
            }
        }

    override suspend fun finalizePending(
        pendingId: PendingMeasurementId,
        accountId: AccountId,
    ): FinalizePendingResult = database.withTransaction {
        finalizePendingLocked(pendingId, accountId)
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

    override suspend fun discardPendingAndUpdateIgnorePolicy(
        pendingId: PendingMeasurementId,
        ignoreUnknownMeasurements: Boolean,
    ): DiscardPendingWithoutUndoResult = database.withTransaction {
        measurementDao.getByPendingId(pendingId.value)?.let { finalized ->
            return@withTransaction DiscardPendingWithoutUndoResult.AlreadyFinalized(
                finalized.toAccountMeasurement(),
            )
        }
        val pending = pendingDao.get(pendingId.value)
            ?: return@withTransaction DiscardPendingWithoutUndoResult.PendingNotFound
        discardPendingWithoutUndoLocked(pending)
        appStateDao.insertDefault()
        check(appStateDao.setIgnoreUnknownMeasurements(ignoreUnknownMeasurements) == 1) {
            "App state singleton is missing"
        }
        DiscardPendingWithoutUndoResult.Discarded
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

    private suspend fun upgradeFinalizedIfNeeded(
        current: MeasurementEntity,
        raw: RawScaleMeasurement,
    ): MeasurementEntity {
        if (current.measurementType != MeasurementType.WEIGHT_ONLY ||
            !raw.hasFullBodyComposition
        ) {
            return current
        }
        val account = accountDao.get(current.accountId) ?: return current
        val profile = account.toDomain().profile.toUserProfileOrNull() ?: return current
        val composition = calculator.calculate(raw, profile)
        val candidate = composition.toEntity(
            rawPayload = raw.rawPayload,
            fingerprint = current.fingerprint,
            huaweiSyncEnabled = huaweiSyncEnabled,
            accountId = AccountId(current.accountId),
            externalSyncPolicy = ExternalSyncPolicy.valueOf(current.externalSyncPolicy),
            sourcePendingId = current.sourcePendingId,
            deduplicationHash = current.deduplicationHash,
        )
        val upgraded = candidate.copy(
            id = current.id,
            huaweiStatus = current.huaweiStatus.requeueUnlessTerminal(),
            healthConnectStatus = current.healthConnectStatus.requeueUnlessTerminal(),
            huaweiError = current.huaweiError.preserveForTerminalStatus(current.huaweiStatus),
            healthConnectError = current.healthConnectError.preserveForTerminalStatus(
                current.healthConnectStatus,
            ),
            huaweiWeightSynced = current.huaweiWeightSynced,
            healthConnectWeightSynced = current.healthConnectWeightSynced,
            createdAtEpochMillis = current.createdAtEpochMillis,
        )
        check(measurementDao.update(upgraded) == 1) {
            "Finalized measurement disappeared during full-composition upgrade"
        }
        return upgraded
    }

    companion object {
        val TOMBSTONE_TTL: Duration = Duration.ofDays(30)
    }
}

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

private fun PendingMeasurement.toEntity(): PendingMeasurementEntity = PendingMeasurementEntity(
    id = id.value,
    deviceAddress = deviceAddress,
    measuredAtEpochSecond = measuredAt.epochSecond,
    measuredAtNano = measuredAt.nano,
    weightKg = weightKg,
    impedanceOhm = impedanceOhm,
    isStable = isStable,
    hasImpedance = hasImpedance,
    rawPayload = rawPayload.copyOf(),
    deduplicationHash = deduplicationHash,
    enqueuedAtEpochMillis = enqueuedAt.toEpochMilli(),
    rawWeight = rawWeight,
)

private fun String.requeueUnlessTerminal(): String = when (this) {
    SyncStatus.DISABLED.name, SyncStatus.LOCAL_ONLY.name -> this
    else -> SyncStatus.PENDING.name
}

private fun String?.preserveForTerminalStatus(status: String): String? = when (status) {
    SyncStatus.DISABLED.name, SyncStatus.LOCAL_ONLY.name -> this
    else -> null
}

/** Smallest epoch-millisecond timestamp which is not before this instant. */
private fun Instant.ceilToEpochMilli(): Long {
    val epochMillis = toEpochMilli()
    return if (nano % NANOS_PER_MILLISECOND == 0) {
        epochMillis
    } else {
        Math.addExact(epochMillis, 1L)
    }
}

private const val NANOS_PER_MILLISECOND: Int = 1_000_000
