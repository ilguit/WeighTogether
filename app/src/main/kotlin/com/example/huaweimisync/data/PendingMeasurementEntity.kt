package com.example.huaweimisync.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.huaweimisync.core.RawScaleMeasurement
import com.example.huaweimisync.domain.PendingMeasurement
import com.example.huaweimisync.domain.PendingMeasurementId
import java.time.Instant
import kotlin.math.roundToInt

@Entity(
    tableName = "pending_measurements",
    indices = [
        Index(value = ["deduplicationHash"], unique = true),
        Index(value = ["enqueuedAtEpochMillis", "id"]),
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
