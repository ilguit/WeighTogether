package com.example.huaweimisync.ui.accounts

import java.util.Locale

/** Parses a decimal entered with either a comma or a dot, without accepting mixed separators. */
fun parseLocalizedDecimal(input: String): Double? {
    val normalized = input.trim()
    if (normalized.isEmpty() || normalized.count { it == ',' || it == '.' } > 1) return null
    if (!normalized.matches(Regex("[+-]?(?:\\d+(?:[.,]\\d*)?|[.,]\\d+)"))) return null
    return normalized.replace(',', '.').toDoubleOrNull()?.takeIf(Double::isFinite)
}

fun formatLocalizedDecimal(value: Double, decimalPlaces: Int = 1): String =
    String.format(Locale.US, "%.${decimalPlaces}f", value).replace('.', ',')
