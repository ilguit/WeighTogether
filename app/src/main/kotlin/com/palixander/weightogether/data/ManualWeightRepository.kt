package com.palixander.weightogether.data

import androidx.room.withTransaction
import com.palixander.weightogether.domain.ExternalSyncPolicy
import com.palixander.weightogether.domain.ManualWeightOwner
import com.palixander.weightogether.domain.ManualWeightRequest
import com.palixander.weightogether.domain.ManualWeightResult
import com.palixander.weightogether.domain.MeasurementOrigin
import com.palixander.weightogether.domain.canonicalManualWeight
import com.palixander.weightogether.domain.isValidManualWeight
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** Direct owner-bound persistence; intentionally bypasses scale routing and deduplication. */
class ManualWeightRepository(
    private val database: AppDatabase,
    private val enqueueSync: (String) -> Unit,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    suspend fun save(request: ManualWeightRequest, allowDuplicate: Boolean = false): ManualWeightResult {
        if (!isValidManualWeight(request.weightKg) ||
            request.measuredAt > clock.instant() || request.measuredAt.nano != 0 ||
            runCatching { request.measuredAt.atZone(clock.zone).second == 0 }.getOrDefault(false).not() ||
            runCatching { UUID.fromString(request.requestId).toString() == request.requestId }.getOrDefault(false).not()
        ) return ManualWeightResult.Invalid
        // App display/integration boundaries require epoch milliseconds to fit in Long.
        if (runCatching { request.measuredAt.toEpochMilli() }.isFailure) return ManualWeightResult.Invalid
        val result = database.withTransaction {
            when (val owner = request.owner) {
                is ManualWeightOwner.Human -> saveHuman(request, owner, allowDuplicate)
                is ManualWeightOwner.Pet -> savePet(request, owner, allowDuplicate)
            }
        }
        if (result is ManualWeightResult.Saved && request.owner is ManualWeightOwner.Human) {
            // Work sweeps also recover PENDING records if enqueue fails after the durable commit.
            runCatching { enqueueSync(result.measurementId) }
        }
        return result
    }

    private suspend fun saveHuman(
        request: ManualWeightRequest,
        owner: ManualWeightOwner.Human,
        allowDuplicate: Boolean,
    ): ManualWeightResult {
        val account = database.accountDao().get(owner.accountId.value) ?: return ManualWeightResult.OwnerUnavailable
        val dao = database.multiAccountMeasurementDao()
        dao.get(request.requestId)?.let { existing ->
            return if (existing.accountId == owner.accountId.value && existing.origin == MeasurementOrigin.MANUAL) {
                ManualWeightResult.Saved(existing.id, existing.measuredAt)
            } else ManualWeightResult.Invalid
        }
        if (database.petDao().getMeasurement(request.requestId) != null) return ManualWeightResult.Invalid
        if (!allowDuplicate) {
            val canonicalWeight = canonicalManualWeight(request.weightKg)
            dao.findManualDuplicateCandidates(owner.accountId.value, request.measuredAt.epochSecond)
                .firstOrNull { runCatching { canonicalManualWeight(it.weightKg) }.getOrNull() == canonicalWeight }
                ?.let { return ManualWeightResult.Duplicate(it.id) }
        }
        val isPrimary = database.appStateDao().get()?.primaryAccountId == owner.accountId.value
        val measurement = MeasurementEntity(
            id = request.requestId,
            deviceAddress = "manual-entry",
            measuredAtEpochSecond = request.measuredAt.epochSecond,
            measurementType = MeasurementType.WEIGHT_ONLY,
            origin = MeasurementOrigin.MANUAL,
            rawPayloadHex = "",
            rawWeight = 0,
            weightKg = request.weightKg,
            impedanceOhm = null, bmi = null, bodyFatPercent = null, bodyFatMassKg = null,
            waterPercent = null, waterMassKg = null, muscleMassKg = null, skeletalMuscleMassKg = null,
            boneMassKg = null, proteinPercent = null, proteinMassKg = null, visceralFatLevel = null,
            basalMetabolicRateKcal = null, metabolicAge = null, leanBodyMassKg = null, algorithmVersion = null,
            accountId = owner.accountId.value,
            createdAtEpochMillis = clock.millis(),
            ratingHeightCm = account.heightCm,
            externalSyncPolicy = if (isPrimary) ExternalSyncPolicy.AUTO.name else ExternalSyncPolicy.ACCOUNT_LOCAL.name,
            healthConnectStatus = if (isPrimary) SyncStatus.PENDING.name else SyncStatus.LOCAL_ONLY.name,
        )
        check(dao.insert(measurement) != -1L) { "Manual weight insert conflict" }
        return ManualWeightResult.Saved(measurement.id, measurement.measuredAt)
    }

    private suspend fun savePet(
        request: ManualWeightRequest,
        owner: ManualWeightOwner.Pet,
        allowDuplicate: Boolean,
    ): ManualWeightResult {
        val dao = database.petDao()
        if (dao.getPet(owner.petId.value) == null) return ManualWeightResult.OwnerUnavailable
        dao.getMeasurement(request.requestId)?.let { existing ->
            return if (existing.petId == owner.petId.value && existing.origin == MeasurementOrigin.MANUAL) {
                ManualWeightResult.Saved(existing.id, Instant.ofEpochSecond(existing.measuredAtEpochSecond))
            } else ManualWeightResult.Invalid
        }
        if (database.multiAccountMeasurementDao().get(request.requestId) != null) return ManualWeightResult.Invalid
        if (!allowDuplicate) {
            val canonicalWeight = canonicalManualWeight(request.weightKg)
            dao.findManualDuplicateCandidates(owner.petId.value, request.measuredAt.epochSecond)
                .firstOrNull { runCatching { canonicalManualWeight(it.petWeightKg) }.getOrNull() == canonicalWeight }
                ?.let { return ManualWeightResult.Duplicate(it.id) }
        }
        dao.insertMeasurement(
            PetMeasurementEntity(request.requestId, owner.petId.value, request.measuredAt.epochSecond,
                null, null, request.weightKg, MeasurementOrigin.MANUAL),
        )
        check(dao.updatePetTimestamp(owner.petId.value, clock.millis()) == 1)
        return ManualWeightResult.Saved(request.requestId, request.measuredAt)
    }
}
