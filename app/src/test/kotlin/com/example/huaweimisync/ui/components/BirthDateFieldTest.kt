package com.example.huaweimisync.ui.components

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class BirthDateFieldTest {
    @Test
    fun `formats birth date as day month year`() {
        assertEquals("09.04.1993", formatBirthDate(LocalDate.of(1993, 4, 9)))
    }

    @Test
    fun `leap day survives picker conversion and formatting`() {
        val leapDay = LocalDate.of(2000, 2, 29)

        val restored = birthDateFromPickerMillis(leapDay.toBirthDatePickerMillis())

        assertEquals(leapDay, restored)
        assertEquals("29.02.2000", formatBirthDate(restored))
    }
}
