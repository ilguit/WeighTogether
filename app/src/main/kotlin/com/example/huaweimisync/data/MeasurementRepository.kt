package com.example.huaweimisync.data

import com.example.huaweimisync.core.BodyCompositionCalculator
import com.example.huaweimisync.core.RawScaleMeasurement
import com.example.huaweimisync.core.UserProfile
import com.example.huaweimisync.worker.MeasurementSyncScheduler
import java.time.Instant
import kotlinx.coroutines.flow.Flow

class MeasurementRepository(
    private val dao: MeasurementDao,
    private val profileProvider: () -> UserProfile?,
    private val scaleAddressProvider: () -> String?,
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
        val profile = profileProvider() ?: return StoreResult.ProfileMissing
        val composition = calculator.calculate(raw, profile)
        val entity = composition.toEntity(raw.rawPayload, huaweiSyncEnabled)
        val inserted = dao.insert(entity) != -1L
        if (inserted) syncScheduler.enqueue(entity.id)
        return if (inserted) StoreResult.Inserted(entity) else StoreResult.Duplicate
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

    suspend fun update(
        id: String,
        values: MeasurementValues,
    ): MeasurementMutationResult {
        if (!values.isValid()) return MeasurementMutationResult.Invalid
        val current = dao.get(id) ?: return MeasurementMutationResult.NotFound
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
            huaweiStatus = current.huaweiStatus.toLocalOnlyUnlessDisabled(),
            healthConnectStatus = SyncStatus.LOCAL_ONLY.name,
            huaweiError = null,
            healthConnectError = null,
        )
        if (dao.update(updated) == 0) return MeasurementMutationResult.NotFound
        syncScheduler.cancel(id)
        return MeasurementMutationResult.Success
    }

    suspend fun delete(id: String): MeasurementMutationResult {
        val current = dao.get(id) ?: return MeasurementMutationResult.NotFound
        val scaleAddress = scaleAddressProvider()?.takeIf(String::isNotBlank)
        if (scaleAddress != null &&
            current.isFromScale(scaleAddress) &&
            dao.getLatestForDevice(scaleAddress)?.id == current.id
        ) {
            return MeasurementMutationResult.ProtectedLatest
        }
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
        if (value.huaweiStatus != SyncStatus.LOCAL_ONLY.name &&
            value.healthConnectStatus != SyncStatus.LOCAL_ONLY.name
        ) {
            syncScheduler.enqueue(id)
        }
    }

    suspend fun retryPendingHealthConnect() {
        dao.idsNeedingHealthConnectSync().forEach(syncScheduler::enqueue)
    }

    suspend fun retryPendingHuawei() {
        dao.idsNeedingHuaweiSync().forEach(syncScheduler::enqueue)
    }

    fun protectedLatestId(measurements: List<MeasurementEntity>): String? {
        val scaleAddress = scaleAddressProvider()?.takeIf(String::isNotBlank) ?: return null
        return measurements
            .asSequence()
            .filter { it.isFromScale(scaleAddress) }
            .maxWithOrNull(
                compareBy<MeasurementEntity>(MeasurementEntity::measuredAtEpochMillis)
                    .thenBy(MeasurementEntity::createdAtEpochMillis)
                    .thenBy(MeasurementEntity::id),
            )
            ?.id
    }

    private fun MeasurementEntity.isFromScale(scaleAddress: String): Boolean =
        !deviceAddress.equals(MANUAL_DEVICE_ADDRESS, ignoreCase = true) &&
            deviceAddress.equals(scaleAddress, ignoreCase = true)

    private companion object {
        const val MANUAL_DEVICE_ADDRESS = "manual"
    }
}

sealed interface StoreResult {
    data class Inserted(val value: MeasurementEntity) : StoreResult
    data object Duplicate : StoreResult
    data object ProfileMissing : StoreResult
}

sealed interface MeasurementMutationResult {
    data object Success : MeasurementMutationResult
    data object NotFound : MeasurementMutationResult
    data object Invalid : MeasurementMutationResult
    data object ProtectedLatest : MeasurementMutationResult
}

private fun String.toLocalOnlyUnlessDisabled(): String =
    if (this == SyncStatus.DISABLED.name) this else SyncStatus.LOCAL_ONLY.name

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
