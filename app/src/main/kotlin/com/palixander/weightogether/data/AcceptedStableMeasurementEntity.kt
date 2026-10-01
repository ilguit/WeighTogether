package com.palixander.weightogether.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.palixander.weightogether.core.RawScaleMeasurement
import java.time.Instant
import java.util.Locale

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
        private const val DEVICE_ID_PREFIX = "device:"

        fun deviceId(deviceAddress: String): String =
            DEVICE_ID_PREFIX + deviceAddress.trim().uppercase(Locale.ROOT)

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

        fun forDevice(raw: RawScaleMeasurement): AcceptedStableMeasurementEntity =
            latest(raw).copy(id = deviceId(raw.deviceAddress))
    }
}
