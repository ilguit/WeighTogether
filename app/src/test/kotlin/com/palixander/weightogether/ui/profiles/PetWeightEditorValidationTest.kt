package com.palixander.weightogether.ui.profiles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PetWeightEditorValidationTest {
    @Test fun `formatter rounds half even to three decimals and removes trailing zeroes`() {
        assertEquals("4.12", canonicalPetWeight(4.1200000000000045))
        assertEquals("4.124", canonicalPetWeight(4.1245))
        assertEquals("4.126", canonicalPetWeight(4.1255))
    }

    @Test fun `parser accepts decimal separators and rejects invalid precision and non-positive values`() {
        assertEquals(0.001, parsePetWeightInput("0,001")!!, 0.0)
        assertEquals(4.125, parsePetWeightInput("4.125")!!, 0.0)
        assertEquals(4.0, parsePetWeightInput("4")!!, 0.0)
        listOf("", "0", "-1", "weight", "4.1234").forEach { assertNull(parsePetWeightInput(it)) }
    }

    @Test fun `canonical equivalents are no op and a changed valid value can save`() {
        val editor = PetWeightEditorState("id", "Луна", "date", 4.12, "4,120")
        assertFalse(editor.canSave)
        assertTrue(editor.copy(weightInput = "4,121").canSave)
        assertFalse(editor.copy(weightInput = "4,121", isSaving = true).canSave)
        assertFalse(editor.copy(weightInput = "4,121", isUnavailable = true).canSave)
    }
}
