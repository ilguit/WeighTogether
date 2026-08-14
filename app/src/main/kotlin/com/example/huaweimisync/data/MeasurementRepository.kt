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
    private val calculator: BodyCompositionCalculator,
    private val syncScheduler: MeasurementSyncScheduler,
    private val huaweiSyncEnabled: Boolean,
) {
    fun observeRecent(): Flow<List<MeasurementEntity>> = dao.observeLatest()

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
            deviceAddress = "manual",
            measuredAt = measuredAt,
            weightKg = weightKg,
            impedanceOhm = impedanceOhm,
            isStable = true,
            hasImpedance = impedanceOhm in 80..3_000,
            rawPayload = byteArrayOf(),
        ),
    )

    suspend fun retry(id: String) {
        syncScheduler.enqueue(id)
    }

    suspend fun retryPendingHealthConnect() {
        dao.idsNeedingHealthConnectSync().forEach(syncScheduler::enqueue)
    }

    suspend fun retryPendingHuawei() {
        dao.idsNeedingHuaweiSync().forEach(syncScheduler::enqueue)
    }
}

sealed interface StoreResult {
    data class Inserted(val value: MeasurementEntity) : StoreResult
    data object Duplicate : StoreResult
    data object ProfileMissing : StoreResult
}
