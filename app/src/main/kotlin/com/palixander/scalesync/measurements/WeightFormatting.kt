package com.palixander.scalesync.measurements

import com.palixander.scalesync.domain.formatWeightNumber
import java.util.Locale

/** Weight retains gram precision; other measurements retain their own precision. */
fun formatWeight(value: Double, locale: Locale = Locale.getDefault()): String =
    formatWeightNumber(value, locale)
