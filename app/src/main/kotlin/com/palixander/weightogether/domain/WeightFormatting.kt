package com.palixander.weightogether.domain

import java.text.NumberFormat
import java.util.Locale

/** Shared display rounding for weight labels and duplicate detection. */
fun formatWeightNumber(value: Double, locale: Locale): String =
    NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = 0
        maximumFractionDigits = 3
        isGroupingUsed = false
    }.format(value)
