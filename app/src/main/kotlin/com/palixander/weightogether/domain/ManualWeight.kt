package com.palixander.weightogether.domain

import java.math.BigDecimal
import java.time.Instant
import java.util.Locale

sealed interface ManualWeightOwner {
    data class Human(val accountId: AccountId) : ManualWeightOwner
    data class Pet(val petId: PetId) : ManualWeightOwner
}

data class ManualWeightRequest(
    val requestId: String,
    val owner: ManualWeightOwner,
    val weightKg: Double,
    val measuredAt: Instant,
)

sealed interface ManualWeightResult {
    data class Saved(val measurementId: String, val measuredAt: Instant) : ManualWeightResult
    data class Duplicate(val measurementId: String) : ManualWeightResult
    data object OwnerUnavailable : ManualWeightResult
    data object Invalid : ManualWeightResult
}

/** Decimal input only, with no silent rounding or scale hardware range restriction. */
fun parseManualWeight(input: String): Double? {
    val normalized = input.trim().replace(',', '.')
    if (!Regex("[0-9]+(?:\\.[0-9]{1,3})?").matches(normalized)) return null
    return normalized.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }
}

fun isValidManualWeight(weightKg: Double): Boolean =
    weightKg.isFinite() && weightKg > 0.0 && BigDecimal.valueOf(weightKg).stripTrailingZeros().scale() <= 3

/** Compare the same rounded Double that the weight UI displays, including binary ties. */
fun canonicalManualWeight(weightKg: Double): Double {
    require(weightKg.isFinite())
    return formatWeightNumber(weightKg, Locale.ROOT).toDouble()
}
