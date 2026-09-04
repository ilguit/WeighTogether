package com.palixander.scalesync.measurements

import java.text.NumberFormat
import java.util.Locale

/** Weight retains gram precision; other measurements retain their own precision. */
fun formatWeight(value: Double, locale: Locale = Locale.getDefault()): String =
    NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = 0
        maximumFractionDigits = 3
        isGroupingUsed = false
    }.format(value)
