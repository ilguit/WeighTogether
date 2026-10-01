package com.palixander.weightogether

import java.time.LocalDate
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale
import java.time.YearMonth

internal enum class PetBirthDatePart { YEAR, MONTH, DAY }

internal fun petBirthDatePartLabel(
    part: PetBirthDatePart,
    value: Int,
    locale: Locale = Locale.getDefault(),
): String =
    if (part == PetBirthDatePart.MONTH && value in 1..12) {
        Month.of(value).getDisplayName(TextStyle.FULL_STANDALONE, locale)
            .replaceFirstChar { it.titlecase(locale) }
    } else {
        value.toString()
    }

internal fun PetBirthDateInput.component(part: PetBirthDatePart): Int? = when (part) {
    PetBirthDatePart.YEAR -> when (this) {
        PetBirthDateInput.Empty -> null
        is PetBirthDateInput.Year -> year.toIntOrNull()
        is PetBirthDateInput.Month -> year.toIntOrNull()
        is PetBirthDateInput.Day -> year.toIntOrNull()
    }
    PetBirthDatePart.MONTH -> when (this) {
        is PetBirthDateInput.Month -> month.toIntOrNull()
        is PetBirthDateInput.Day -> month.toIntOrNull()
        else -> null
    }
    PetBirthDatePart.DAY -> (this as? PetBirthDateInput.Day)?.day?.toIntOrNull()
}

internal fun petBirthDateOptions(
    input: PetBirthDateInput,
    part: PetBirthDatePart,
    today: LocalDate,
): List<Int> {
    if (part == PetBirthDatePart.YEAR) return (today.year downTo 1).toList()
    val year = input.component(PetBirthDatePart.YEAR)?.takeIf { it in 1..today.year }
        ?: return emptyList()
    if (part == PetBirthDatePart.MONTH) {
        return (1..if (year == today.year) today.monthValue else 12).toList()
    }
    val month = input.component(PetBirthDatePart.MONTH)
        ?.takeIf { it in petBirthDateOptions(input, PetBirthDatePart.MONTH, today) }
        ?: return emptyList()
    val lastDay = if (year == today.year && month == today.monthValue) {
        today.dayOfMonth
    } else {
        YearMonth.of(year, month).lengthOfMonth()
    }
    return (1..lastDay).toList()
}

/** Keep compatible known parts; drop impossible lower precision instead of inventing a date. */
internal fun selectPetBirthDatePart(
    input: PetBirthDateInput,
    part: PetBirthDatePart,
    selected: Int?,
    today: LocalDate,
): PetBirthDateInput {
    if (selected != null && selected !in petBirthDateOptions(input, part, today)) return input
    val year = if (part == PetBirthDatePart.YEAR) selected else input.component(PetBirthDatePart.YEAR)
    if (year == null) return PetBirthDateInput.Empty
    val yearInput = PetBirthDateInput.Year(year.toString())
    val month = if (part == PetBirthDatePart.MONTH) selected else input.component(PetBirthDatePart.MONTH)
    if (month == null || month !in petBirthDateOptions(yearInput, PetBirthDatePart.MONTH, today)) return yearInput
    val monthInput = PetBirthDateInput.Month(year.toString(), month.toString())
    val day = if (part == PetBirthDatePart.DAY) selected else input.component(PetBirthDatePart.DAY)
    if (day == null || day !in petBirthDateOptions(monthInput, PetBirthDatePart.DAY, today)) return monthInput
    return PetBirthDateInput.Day(year.toString(), month.toString(), day.toString())
}
