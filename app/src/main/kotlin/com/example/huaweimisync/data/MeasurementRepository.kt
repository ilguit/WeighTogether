package com.example.huaweimisync.data

import com.example.huaweimisync.core.BodyCompositionCalculator
import com.example.huaweimisync.core.RawScaleMeasurement
import com.example.huaweimisync.core.UserProfile
import com.example.huaweimisync.core.measurementFingerprint
import com.example.huaweimisync.worker.MeasurementSyncScheduler
import java.time.Instant
import kotlinx.coroutines.flow.Flow

class MeasurementRepository(
    private val dao: MeasurementDao,
    private val profileProvider: () -> UserProfile?,
    private val calculator: BodyCompositionCalculator,
    private val syncScheduler: MeasurementSyncScheduler,
    private val huaweiSyncEnabled: Boolean,
) {
    fun observeRecent(): Flow<List<MeasurementEntity>> = dao.observeLatest()

    fun observeAll(): Flow<List<MeasurementEntity>> = dao.observeAll()

    fun observeRange(
        startInclusive: Instant,
        endExclusive: Instant,
    ): Flow<List<MeasurementEntity>> = dao.observeRange(
        startInclusive = startInclusive.toEpochMilli(),
        endExclusive = endExclusive.toEpochMilli(),
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
                syncScheduler.enqueue(result.value.id)
                StoreResult.Inserted(result.value)
            }
            is MeasurementUpsertResult.Upgraded -> {
                syncScheduler.enqueue(result.value.id)
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
            deviceAddress = "manual",
            measuredAt = measuredAt,
            weightKg = weightKg,
            impedanceOhm = impedanceOhm,
            isStable = true,
            hasImpedance = impedanceOhm in 80..3_000,
            rawPayload = byteArrayOf(),
        ),
    )

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

    suspend fun delete(id: String): MeasurementMutationResult {
        if (dao.markLocalOnly(id) == 0) return MeasurementMutationResult.NotFound
        syncScheduler.cancel(id)
        return if (dao.delete(id) > 0) {
            MeasurementMutationResult.Success
        } else {
            MeasurementMutationResult.NotFound
        }
    }

    suspend fun retry(id: String) {
        val value = dao.get(id) ?: return
        if (value.huaweiStatus.isHuaweiRetryable() || value.healthConnectStatus.isHealthRetryable()) {
            syncScheduler.enqueue(id)
        }
    }

    suspend fun retryPendingHealthConnect() {
        dao.idsNeedingHealthConnectSync().forEach(syncScheduler::enqueue)
    }

    suspend fun retryPendingHuawei() {
        dao.idsNeedingHuaweiSync().forEach(syncScheduler::enqueue)
    }

    private suspend fun persistEdited(
        current: MeasurementEntity,
        edited: MeasurementEntity,
    ): MeasurementMutationResult {
        val huaweiStatus = current.huaweiStatus.requeueAfterEdit()
        val healthConnectStatus = current.healthConnectStatus.requeueAfterEdit()
        val updated = edited.copy(
            huaweiStatus = huaweiStatus,
            healthConnectStatus = healthConnectStatus,
            huaweiError = current.huaweiError.keepUnlessRequeued(huaweiStatus),
            healthConnectError = current.healthConnectError.keepUnlessRequeued(healthConnectStatus),
        )
        if (dao.update(updated) == 0) return MeasurementMutationResult.NotFound
        syncScheduler.cancel(current.id)
        if (huaweiStatus == SyncStatus.PENDING.name ||
            healthConnectStatus == SyncStatus.PENDING.name
        ) {
            syncScheduler.enqueue(current.id)
        }
        return MeasurementMutationResult.Success
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

private fun String.requeueAfterEdit(): String = when (this) {
    SyncStatus.DISABLED.name, SyncStatus.LOCAL_ONLY.name -> this
    else -> SyncStatus.PENDING.name
}

private fun String?.keepUnlessRequeued(status: String): String? =
    if (status == SyncStatus.PENDING.name) null else this

private fun String.isHuaweiRetryable(): Boolean = this !in setOf(
    SyncStatus.SYNCED.name,
    SyncStatus.DISABLED.name,
    SyncStatus.LOCAL_ONLY.name,
)

private fun String.isHealthRetryable(): Boolean = this !in setOf(
    SyncStatus.SYNCED.name,
    SyncStatus.LOCAL_ONLY.name,
)

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
