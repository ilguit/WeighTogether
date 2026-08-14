package com.example.huaweimisync.core

import java.time.Instant
import java.time.LocalDate

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
) {
    val isFinal: Boolean
        get() = isStable && hasImpedance && weightKg in 10.0..300.0 && impedanceOhm in 80..3_000

    override fun equals(other: Any?): Boolean =
        other is RawScaleMeasurement &&
            deviceAddress == other.deviceAddress &&
            measuredAt == other.measuredAt &&
            weightKg == other.weightKg &&
            impedanceOhm == other.impedanceOhm &&
            isStable == other.isStable &&
            hasImpedance == other.hasImpedance &&
            rawPayload.contentEquals(other.rawPayload)

    override fun hashCode(): Int =
        arrayOf<Any>(deviceAddress, measuredAt, weightKg, impedanceOhm, isStable, hasImpedance)
            .contentHashCode() * 31 + rawPayload.contentHashCode()
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
