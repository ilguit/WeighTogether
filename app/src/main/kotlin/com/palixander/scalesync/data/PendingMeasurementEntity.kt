package com.palixander.scalesync.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.palixander.scalesync.core.RawScaleMeasurement
import com.palixander.scalesync.domain.AccountId
import com.palixander.scalesync.domain.PendingMeasurement
import com.palixander.scalesync.domain.PendingMeasurementId
import java.time.Instant
import kotlin.math.roundToInt

@Entity(
    tableName = "pending_measurements",
    indices = [
        Index(value = ["deduplicationHash"], unique = true),
        Index(value = ["enqueuedAtEpochMillis", "id"]),
        Index(value = ["provisionalAccountId", "measuredAtEpochSecond", "id"]),
    ],
)
data class PendingMeasurementEntity(
    @PrimaryKey val id: String,
    val deviceAddress: String,
    val measuredAtEpochSecond: Long,
    val weightKg: Double,
    val impedanceOhm: Int,
    val isStable: Boolean,
    val hasImpedance: Boolean,
    val rawPayload: ByteArray,
    val deduplicationHash: String,
    val enqueuedAtEpochMillis: Long,
    val rawWeight: Int = (weightKg / RawScaleMeasurement.WEIGHT_RESOLUTION_KG).roundToInt(),
    val finalizeAfterEpochMillis: Long = enqueuedAtEpochMillis + 10_000L,
    val provisionalAccountId: String? = null,
) {
    fun toDomain(): PendingMeasurement = PendingMeasurement(
        id = PendingMeasurementId(id),
        deviceAddress = deviceAddress,
        measuredAt = Instant.ofEpochSecond(measuredAtEpochSecond),
        weightKg = weightKg,
        impedanceOhm = impedanceOhm,
        isStable = isStable,
        hasImpedance = hasImpedance,
        rawPayload = rawPayload.copyOf(),
        deduplicationHash = deduplicationHash,
        enqueuedAt = Instant.ofEpochMilli(enqueuedAtEpochMillis),
        rawWeight = rawWeight,
        finalizeAfter = Instant.ofEpochMilli(finalizeAfterEpochMillis),
        provisionalAccountId = provisionalAccountId?.let(::AccountId),
    )
}

@Entity(tableName = "measurement_tombstones")
data class MeasurementTombstoneEntity(
    @PrimaryKey val deduplicationHash: String,
    val expiresAtEpochMillis: Long,
    val deviceAddress: String? = null,
    val measuredAtEpochSecond: Long? = null,
    val rawWeight: Int? = null,
)
