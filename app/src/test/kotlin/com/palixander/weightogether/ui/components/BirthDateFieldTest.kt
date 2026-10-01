package com.palixander.weightogether.ui.components

import androidx.compose.material3.ExperimentalMaterial3Api
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalMaterial3Api::class)
class BirthDateFieldTest {
    @Test
    fun `formats birth date as day month year`() {
        assertEquals("Apr 9, 1993", formatBirthDate(LocalDate.of(1993, 4, 9)))
    }

    @Test
    fun `leap day survives picker conversion and formatting`() {
        val leapDay = LocalDate.of(2000, 2, 29)

        val restored = birthDateFromPickerMillis(leapDay.toBirthDatePickerMillis())

        assertEquals(leapDay, restored)
        assertEquals("Feb 29, 2000", formatBirthDate(restored))
    }

    @Test
    fun `empty value initially displays a date thirty years ago`() {
        val today = LocalDate.of(2026, 8, 20)

        assertEquals(
            LocalDate.of(1996, 8, 20),
            initialBirthDatePickerDate(value = null, today = today),
        )
    }

    @Test
    fun `saved value determines the initially displayed date`() {
        val savedBirthDate = LocalDate.of(1988, 2, 29)

        assertEquals(
            savedBirthDate,
            initialBirthDatePickerDate(
                value = savedBirthDate,
                today = LocalDate.of(2026, 8, 20),
            ),
        )
    }

    @Test
    fun `account policy includes today and rejects tomorrow`() {
        val today = LocalDate.of(2026, 8, 20)
        val policy = BirthDateSelectionPolicy.forAccount(today)

        assertTrue(policy.allows(today))
        assertFalse(policy.allows(today.plusDays(1)))
        assertEquals(today, policy.maxDateInclusive)
    }

    @Test
    fun `unsaved preview policy uses measurement date instead of current date`() {
        val measurementDate = LocalDate.of(2024, 2, 29)
        val policy = BirthDateSelectionPolicy.forUnsavedPreview(measurementDate)

        assertTrue(policy.allows(measurementDate))
        assertFalse(policy.allows(LocalDate.of(2024, 3, 1)))
        assertEquals(measurementDate, policy.maxDateInclusive)
    }

    @Test
    fun `picker disables days and years after inclusive upper bound`() {
        val maxDate = LocalDate.of(2026, 8, 20)
        val selectableDates = birthDateSelectableDates(
            BirthDateSelectionPolicy.forAccount(maxDate),
        )

        assertTrue(selectableDates.isSelectableDate(maxDate.toBirthDatePickerMillis()))
        assertFalse(selectableDates.isSelectableDate(maxDate.plusDays(1).toBirthDatePickerMillis()))
        assertTrue(selectableDates.isSelectableYear(2026))
        assertFalse(selectableDates.isSelectableYear(2027))
    }

    @Test
    fun `confirmation revalidates picker selection against upper bound`() {
        val maxDate = LocalDate.of(2024, 2, 29)
        val policy = BirthDateSelectionPolicy.forUnsavedPreview(maxDate)

        assertEquals(
            maxDate,
            confirmedBirthDate(maxDate.toBirthDatePickerMillis(), policy),
        )
        assertNull(confirmedBirthDate(maxDate.plusDays(1).toBirthDatePickerMillis(), policy))
        assertNull(confirmedBirthDate(selectedDateMillis = null, selectionPolicy = policy))
    }
}
