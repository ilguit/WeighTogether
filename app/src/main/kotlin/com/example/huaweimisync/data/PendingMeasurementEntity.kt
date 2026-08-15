package com.example.huaweimisync.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.huaweimisync.domain.PendingMeasurement
import com.example.huaweimisync.domain.PendingMeasurementId
import java.time.Instant

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
    val measuredAtNano: Int,
    val weightKg: Double,
    val impedanceOhm: Int,
    val isStable: Boolean,
    val hasImpedance: Boolean,
    val rawPayload: ByteArray,
    val deduplicationHash: String,
    val enqueuedAtEpochMillis: Long,
) {
    fun toDomain(): PendingMeasurement = PendingMeasurement(
        id = PendingMeasurementId(id),
        deviceAddress = deviceAddress,
        measuredAt = Instant.ofEpochSecond(measuredAtEpochSecond, measuredAtNano.toLong()),
        weightKg = weightKg,
        impedanceOhm = impedanceOhm,
        isStable = isStable,
        hasImpedance = hasImpedance,
        rawPayload = rawPayload.copyOf(),
        deduplicationHash = deduplicationHash,
        enqueuedAt = Instant.ofEpochMilli(enqueuedAtEpochMillis),
    )
}

@Entity(tableName = "measurement_tombstones")
data class MeasurementTombstoneEntity(
    @PrimaryKey val deduplicationHash: String,
    val expiresAtEpochMillis: Long,
)
