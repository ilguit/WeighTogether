package com.palixander.scalesync.measurements

import com.palixander.scalesync.R
import com.palixander.scalesync.ui.text.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MeasurementEditorValidationTest {
    @Test
    fun fieldsHaveFixedSixteenItemOrder() {
        assertEquals(16, MeasurementField.entries.size)
        assertEquals(MeasurementField.WEIGHT_KG, MeasurementField.entries.first())
        assertEquals(MeasurementField.LEAN_BODY_MASS_KG, MeasurementField.entries.last())
    }

    @Test
    fun editorGroupsContainAllSixteenFieldsExactlyOnce() {
        assertEquals(
            listOf(
                UiText.Resource(R.string.measurement_group_main),
                UiText.Resource(R.string.measurement_group_body_composition),
                UiText.Resource(R.string.measurement_group_muscles_bones),
                UiText.Resource(R.string.measurement_group_metabolism),
            ),
            measurementEditorSections.map(MeasurementEditorSection::title),
        )
        assertEquals(listOf(3, 7, 3, 3), measurementEditorSections.map { it.fields.size })
        assertEquals(
            MeasurementField.entries.toSet(),
            measurementEditorSections.flatMap(MeasurementEditorSection::fields).toSet(),
        )
        assertEquals(
            16,
            measurementEditorSections.flatMap(MeasurementEditorSection::fields).size,
        )
    }

    @Test
    fun decimalFieldsAcceptCommaAndDot() {
        assertEquals(72.35, MeasurementField.WEIGHT_KG.validateInput("72,35").parsedValue!!, 0.0)
        assertEquals(72.35, MeasurementField.WEIGHT_KG.validateInput("72.35").parsedValue!!, 0.0)
    }

    @Test
    fun validationRejectsBlankNegativeNonFiniteAndMalformedValues() {
        assertEquals(UiText.Resource(R.string.validation_required_field), MeasurementField.WEIGHT_KG.validateInput(" ").error)
        assertNotNull(MeasurementField.WEIGHT_KG.validateInput("-1").error)
        assertNotNull(MeasurementField.WEIGHT_KG.validateInput("NaN").error)
        assertNotNull(MeasurementField.WEIGHT_KG.validateInput("1,2.3").error)
    }

    @Test
    fun percentageFieldsAreLimitedToOneHundred() {
        assertTrue(MeasurementField.BODY_FAT_PERCENT.validateInput("100").isValid)
        assertNotNull(MeasurementField.BODY_FAT_PERCENT.validateInput("100,01").error)
        assertNotNull(MeasurementField.WATER_PERCENT.validateInput("101").error)
        assertNotNull(MeasurementField.PROTEIN_PERCENT.validateInput("101").error)
    }

    @Test
    fun impedanceAndMetabolicAgeRequireIntegers() {
        assertTrue(MeasurementField.IMPEDANCE_OHM.validateInput("500").isValid)
        assertFalse(MeasurementField.IMPEDANCE_OHM.validateInput("500.0").isValid)
        assertTrue(MeasurementField.METABOLIC_AGE.validateInput("42").isValid)
        assertFalse(MeasurementField.METABOLIC_AGE.validateInput("42,5").isValid)
    }

    @Test
    fun editingCreatesNewDraftAndKeepsOriginalUnchanged() {
        val original = MeasurementEditorDraft.from(sampleValues())
        val changed = original.withValue(MeasurementField.WEIGHT_KG, "71,25")

        assertEquals("70", original[MeasurementField.WEIGHT_KG])
        assertEquals("71,25", changed[MeasurementField.WEIGHT_KG])
        assertEquals(71.25, changed.parsedValuesOrNull()!!.weightKg, 0.0)
    }

    @Test
    fun invalidDraftCannotProduceSubmission() {
        val invalid = MeasurementEditorDraft.from(sampleValues())
            .withValue(MeasurementField.METABOLIC_AGE, "forty")
        val editor = MeasurementEditorState(
            measurementId = "measurement-1",
            measuredAtEpochSecond = 0L,
            draft = invalid,
        )

        assertFalse(invalid.isValid)
        assertNull(invalid.parsedValuesOrNull())
        assertFalse(editor.canSave)
        assertTrue(editor.copy(draft = MeasurementEditorDraft.from(sampleValues())).canSave)
        assertFalse(
            editor.copy(
                draft = MeasurementEditorDraft.from(sampleValues()),
                isSaving = true,
            ).canSave,
        )
    }

    @Test
    fun weightOnlyDraftValidatesAndSubmitsOnlyWeight() {
        val draft = MeasurementEditorDraft.fromWeight(70.0)
        val editor = MeasurementEditorState(
            measurementId = "weight-only",
            measuredAtEpochSecond = 0L,
            draft = draft,
            type = MeasurementUiType.WEIGHT_ONLY,
        )

        assertTrue(draft.isValid)
        assertTrue(editor.canSave)
        assertTrue(editor.isWeightOnly)
        assertEquals(listOf(MeasurementField.WEIGHT_KG), editor.sections.flatMap { it.fields })
        assertEquals(70.0, draft.parsedValuesOrNull()!!.weightKg, 0.0)
        assertNull(draft.parsedValuesOrNull()!!.bodyFatPercent)
        assertNull(draft.parsedValuesOrNull()!!.impedanceOhm)

        val invalid = draft.withValue(MeasurementField.WEIGHT_KG, "")
        assertFalse(invalid.isValid)
        assertNull(invalid.parsedValuesOrNull())
    }

    @Test
    fun retryIsHiddenForLocalOnlyRecords() {
        val item = sampleItem(healthConnectStatus = "LOCAL_ONLY")

        assertTrue(item.isLocalOnly)
        assertFalse(item.canRetry)
    }

    @Test
    fun failedRecordCanBeRetriedButFullySyncedRecordCannot() {
        assertTrue(sampleItem(healthConnectStatus = "FAILED").canRetry)
        assertFalse(sampleItem(healthConnectStatus = "SYNCED").canRetry)
    }

    private fun sampleItem(
        healthConnectStatus: String,
    ) = MeasurementUiItem(
        id = "measurement-1",
        measuredAtEpochSecond = 0L,
        values = sampleValues(),
        sync = measurementSyncPresentation(
            healthConnectStatus = healthConnectStatus,
            healthConnectError = null,
        ),
    )

    private fun sampleValues() = MeasurementUiValues(
        weightKg = 70.0,
        impedanceOhm = 500,
        bmi = 22.9,
        bodyFatPercent = 18.5,
        bodyFatMassKg = 12.95,
        waterPercent = 58.0,
        waterMassKg = 40.6,
        muscleMassKg = 54.0,
        skeletalMuscleMassKg = 29.0,
        boneMassKg = 3.0,
        proteinPercent = 18.0,
        proteinMassKg = 12.6,
        visceralFatLevel = 7.0,
        basalMetabolicRateKcal = 1_650.0,
        metabolicAge = 35,
        leanBodyMassKg = 57.05,
    )
}
