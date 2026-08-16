package com.example.huaweimisync.measurements

import java.math.BigDecimal

data class MeasurementFieldValidation(
    val parsedValue: Double? = null,
    val error: String? = null,
) {
    val isValid: Boolean
        get() = error == null && parsedValue != null
}

class MeasurementEditorDraft private constructor(
    private val inputs: Map<MeasurementField, String>,
) {
    operator fun get(field: MeasurementField): String = inputs[field].orEmpty()

    fun withValue(field: MeasurementField, value: String): MeasurementEditorDraft =
        MeasurementEditorDraft(inputs + (field to value))

    fun validation(field: MeasurementField): MeasurementFieldValidation =
        field.validateInput(get(field))

    val isValid: Boolean
        get() = inputs.keys.all { validation(it).isValid }

    fun parsedValuesOrNull(): MeasurementUiValues? {
        if (!isValid) return null

        fun value(field: MeasurementField): Double? =
            inputs[field]?.let { validation(field).parsedValue }

        return MeasurementUiValues(
            weightKg = value(MeasurementField.WEIGHT_KG)!!,
            impedanceOhm = value(MeasurementField.IMPEDANCE_OHM)?.toInt(),
            bmi = value(MeasurementField.BMI),
            bodyFatPercent = value(MeasurementField.BODY_FAT_PERCENT),
            bodyFatMassKg = value(MeasurementField.BODY_FAT_MASS_KG),
            waterPercent = value(MeasurementField.WATER_PERCENT),
            waterMassKg = value(MeasurementField.WATER_MASS_KG),
            muscleMassKg = value(MeasurementField.MUSCLE_MASS_KG),
            skeletalMuscleMassKg = value(MeasurementField.SKELETAL_MUSCLE_MASS_KG),
            boneMassKg = value(MeasurementField.BONE_MASS_KG),
            proteinPercent = value(MeasurementField.PROTEIN_PERCENT),
            proteinMassKg = value(MeasurementField.PROTEIN_MASS_KG),
            visceralFatLevel = value(MeasurementField.VISCERAL_FAT_LEVEL),
            basalMetabolicRateKcal = value(MeasurementField.BASAL_METABOLIC_RATE_KCAL),
            metabolicAge = value(MeasurementField.METABOLIC_AGE)?.toInt(),
            leanBodyMassKg = value(MeasurementField.LEAN_BODY_MASS_KG),
        )
    }

    override fun equals(other: Any?): Boolean =
        other is MeasurementEditorDraft && inputs == other.inputs

    override fun hashCode(): Int = inputs.hashCode()

    override fun toString(): String = "MeasurementEditorDraft(inputs=$inputs)"

    companion object {
        fun from(values: MeasurementUiValues): MeasurementEditorDraft = MeasurementEditorDraft(
            MeasurementField.entries.associateWith { field ->
                values[field]?.let(field::editorText).orEmpty()
            },
        )

        fun fromWeight(weightKg: Double): MeasurementEditorDraft = MeasurementEditorDraft(
            mapOf(MeasurementField.WEIGHT_KG to MeasurementField.WEIGHT_KG.editorText(weightKg)),
        )

        fun fromInputs(inputs: Map<MeasurementField, String>): MeasurementEditorDraft =
            MeasurementEditorDraft(
                MeasurementField.entries.associateWith { field -> inputs[field].orEmpty() },
            )
    }
}

fun MeasurementField.validateInput(input: String): MeasurementFieldValidation {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return MeasurementFieldValidation(error = "Обязательное поле")

    val normalized = trimmed.replace(',', '.')
    val parsed = if (wholeNumber) {
        val integer = normalized.toIntOrNull()
            ?: return MeasurementFieldValidation(error = "Введите целое число")
        integer.toDouble()
    } else {
        normalized.toDoubleOrNull()
            ?: return MeasurementFieldValidation(error = "Введите число")
    }

    if (!parsed.isFinite()) return MeasurementFieldValidation(error = "Введите конечное число")
    if (parsed < 0.0) return MeasurementFieldValidation(error = "Значение не может быть отрицательным")
    if (maximum != null && parsed > maximum) {
        return MeasurementFieldValidation(error = "Допустимый диапазон: 0–${maximum.toInt()}")
    }
    return MeasurementFieldValidation(parsedValue = parsed)
}

private fun MeasurementField.editorText(value: Double): String = if (wholeNumber) {
    value.toInt().toString()
} else {
    BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
}
