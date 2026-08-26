package com.example.huaweimisync.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.huaweimisync.core.RawScaleMeasurement
import java.time.Instant

/** Durable raw reading used as the baseline for accepting the next stable measurement. */
@Entity(tableName = "accepted_stable_measurements")
data class AcceptedStableMeasurementEntity(
    @PrimaryKey val id: String,
    val deviceAddress: String,
    val measuredAtEpochSecond: Long,
    val measuredAtNano: Int,
    val weightKg: Double,
    val rawWeight: Int,
    val impedanceOhm: Int,
    val isStable: Boolean,
    val hasImpedance: Boolean,
    val rawPayload: ByteArray,
) {
    fun exactlyMatches(raw: RawScaleMeasurement): Boolean =
        deviceAddress == raw.deviceAddress &&
            measuredAtEpochSecond == raw.measuredAt.epochSecond &&
            measuredAtNano == raw.measuredAt.nano &&
            weightKg == raw.weightKg &&
            rawWeight == raw.rawWeight &&
            impedanceOhm == raw.impedanceOhm &&
            isStable == raw.isStable &&
            hasImpedance == raw.hasImpedance &&
            rawPayload.contentEquals(raw.rawPayload)

    fun toRawScaleMeasurement(): RawScaleMeasurement = RawScaleMeasurement(
        deviceAddress = deviceAddress,
        measuredAt = Instant.ofEpochSecond(measuredAtEpochSecond, measuredAtNano.toLong()),
        weightKg = weightKg,
        impedanceOhm = impedanceOhm,
        isStable = isStable,
        hasImpedance = hasImpedance,
        rawPayload = rawPayload.copyOf(),
        rawWeight = rawWeight,
    )

    companion object {
        const val LATEST_ID = "latest"

        fun latest(raw: RawScaleMeasurement): AcceptedStableMeasurementEntity =
            AcceptedStableMeasurementEntity(
                id = LATEST_ID,
                deviceAddress = raw.deviceAddress,
                measuredAtEpochSecond = raw.measuredAt.epochSecond,
                measuredAtNano = raw.measuredAt.nano,
                weightKg = raw.weightKg,
                rawWeight = raw.rawWeight,
                impedanceOhm = raw.impedanceOhm,
                isStable = raw.isStable,
                hasImpedance = raw.hasImpedance,
                rawPayload = raw.rawPayload.copyOf(),
            )
    }
}
