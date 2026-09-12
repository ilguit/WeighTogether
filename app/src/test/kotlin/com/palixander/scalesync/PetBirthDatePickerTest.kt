package com.palixander.scalesync

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PetBirthDatePickerTest {
    private val today = LocalDate.of(2026, 9, 12)

    @Test
    fun selectionAddsOnlyExplicitPartsAndCanReducePrecision() {
        val year = select(PetBirthDateInput.Empty, PetBirthDatePart.YEAR, 2024)
        assertEquals(PetBirthDateInput.Year("2024"), year)
        val month = select(year, PetBirthDatePart.MONTH, 2)
        assertEquals(PetBirthDateInput.Month("2024", "2"), month)
        val day = select(month, PetBirthDatePart.DAY, 29)
        assertEquals(PetBirthDateInput.Day("2024", "2", "29"), day)
        assertEquals(month, select(day, PetBirthDatePart.DAY, null))
        assertEquals(year, select(day, PetBirthDatePart.MONTH, null))
        assertEquals(PetBirthDateInput.Empty, select(day, PetBirthDatePart.YEAR, null))
    }

    @Test
    fun leapYearAndShortMonthDropOnlyImpossibleDay() {
        assertEquals(29, options(PetBirthDateInput.Month("2024", "2"), PetBirthDatePart.DAY).last())
        assertEquals(28, options(PetBirthDateInput.Month("2025", "2"), PetBirthDatePart.DAY).last())
        assertEquals(PetBirthDateInput.Month("2025", "2"), select(
            PetBirthDateInput.Day("2024", "2", "29"), PetBirthDatePart.YEAR, 2025,
        ))
        assertEquals(PetBirthDateInput.Month("2024", "4"), select(
            PetBirthDateInput.Day("2024", "3", "31"), PetBirthDatePart.MONTH, 4,
        ))
    }

    @Test
    fun currentYearAndMonthExcludeFuturePartsAndClearIncompatibleSelection() {
        assertEquals(2026, options(PetBirthDateInput.Empty, PetBirthDatePart.YEAR).first())
        assertEquals(9, options(PetBirthDateInput.Year("2026"), PetBirthDatePart.MONTH).last())
        assertEquals(12, options(PetBirthDateInput.Month("2026", "9"), PetBirthDatePart.DAY).last())
        assertEquals(PetBirthDateInput.Year("2026"), select(
            PetBirthDateInput.Day("2025", "12", "31"), PetBirthDatePart.YEAR, 2026,
        ))
        assertEquals(PetBirthDateInput.Month("2026", "9"), select(
            PetBirthDateInput.Day("2026", "8", "31"), PetBirthDatePart.MONTH, 9,
        ))
        assertEquals(PetBirthDateInput.Empty, select(PetBirthDateInput.Empty, PetBirthDatePart.YEAR, 2027))
    }

    @Test
    fun restoredPaddedDateRetainsCompatiblePartsAndAllSupportedYearsRemainAvailable() {
        val restored = PetBirthDateInput.Day("2020", "02", "09")
        assertEquals(PetBirthDateInput.Day("2021", "2", "9"), select(restored, PetBirthDatePart.YEAR, 2021))
        assertTrue(1 in options(restored, PetBirthDatePart.YEAR))
        assertEquals(2, restored.component(PetBirthDatePart.MONTH))
        assertTrue(options(PetBirthDateInput.Empty, PetBirthDatePart.MONTH).isEmpty())
        assertTrue(options(PetBirthDateInput.Year("2020"), PetBirthDatePart.DAY).isEmpty())
    }

    private fun select(input: PetBirthDateInput, part: PetBirthDatePart, value: Int?) =
        selectPetBirthDatePart(input, part, value, today)

    private fun options(input: PetBirthDateInput, part: PetBirthDatePart) =
        petBirthDateOptions(input, part, today)
}
