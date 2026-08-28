package com.palixander.scalesync.core

import java.time.Instant
import java.time.LocalDate
import kotlin.math.roundToInt

enum class Sex {
    MALE,
    FEMALE,
}

data class UserProfile(
    val heightCm: Double,
    val birthDate: LocalDate,
    val sex: Sex,
) {
    init {
        require(heightCm in 100.0..230.0) { "Height must be between 100 and 230 cm" }
    }
}

data class RawScaleMeasurement(
    val deviceAddress: String,
    val measuredAt: Instant,
    val weightKg: Double,
    val impedanceOhm: Int,
    val isStable: Boolean,
    val hasImpedance: Boolean,
    val rawPayload: ByteArray,
    val rawWeight: Int = (weightKg / WEIGHT_RESOLUTION_KG).roundToInt(),
) {
    /** A settled scale reading that is safe to persist even when BIA did not complete. */
    val isStableWeight: Boolean
        get() = isStable && weightKg in MIN_WEIGHT_KG..MAX_WEIGHT_KG

    /** A settled reading with impedance suitable for body-composition calculation. */
    val hasFullBodyComposition: Boolean
        get() = isStableWeight && hasImpedance && impedanceOhm in MIN_IMPEDANCE_OHM..MAX_IMPEDANCE_OHM

    @Deprecated("Use isStableWeight or hasFullBodyComposition explicitly")
    val isFinal: Boolean
        get() = hasFullBodyComposition

    override fun equals(other: Any?): Boolean =
        other is RawScaleMeasurement &&
            deviceAddress == other.deviceAddress &&
            measuredAt == other.measuredAt &&
            weightKg == other.weightKg &&
            impedanceOhm == other.impedanceOhm &&
            isStable == other.isStable &&
            hasImpedance == other.hasImpedance &&
            rawWeight == other.rawWeight &&
            rawPayload.contentEquals(other.rawPayload)

    override fun hashCode(): Int =
        arrayOf<Any>(deviceAddress, measuredAt, weightKg, impedanceOhm, isStable, hasImpedance, rawWeight)
            .contentHashCode() * 31 + rawPayload.contentHashCode()

    companion object {
        const val WEIGHT_RESOLUTION_KG = 0.005
        const val MIN_WEIGHT_KG = 10.0
        const val MAX_WEIGHT_KG = 300.0
        const val MIN_IMPEDANCE_OHM = 80
        const val MAX_IMPEDANCE_OHM = 3_000
    }
}

data class BodyComposition(
    val measurementId: String,
    val deviceAddress: String,
    val measuredAt: Instant,
    val weightKg: Double,
    val impedanceOhm: Int,
    val bmi: Double,
    val bodyFatPercent: Double,
    val bodyFatMassKg: Double,
    val waterPercent: Double,
    val waterMassKg: Double,
    val muscleMassKg: Double,
    val skeletalMuscleMassKg: Double,
    val boneMassKg: Double,
    val proteinPercent: Double,
    val proteinMassKg: Double,
    val visceralFatLevel: Double,
    val basalMetabolicRateKcal: Double,
    val metabolicAge: Int,
    val leanBodyMassKg: Double,
    val algorithmVersion: String,
)
