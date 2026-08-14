package com.example.huaweimisync.measurements

/**
 * UI-owned field order. It intentionally mirrors the data layer's MeasurementMetric order, while
 * keeping this package independent from Room and repository types.
 */
enum class MeasurementField(
    val label: String,
    val unit: String,
    val decimalPlaces: Int,
    internal val wholeNumber: Boolean = false,
    internal val maximum: Double? = null,
) {
    WEIGHT_KG("Вес", "кг", 2),
    IMPEDANCE_OHM("Импеданс", "Ом", 0, wholeNumber = true),
    BMI("Индекс массы тела", "", 1),
    BODY_FAT_PERCENT("Жир", "%", 1, maximum = 100.0),
    BODY_FAT_MASS_KG("Масса жира", "кг", 2),
    WATER_PERCENT("Вода", "%", 1, maximum = 100.0),
    WATER_MASS_KG("Масса воды", "кг", 2),
    MUSCLE_MASS_KG("Мышечная масса", "кг", 2),
    SKELETAL_MUSCLE_MASS_KG("Скелетная мышечная масса", "кг", 2),
    BONE_MASS_KG("Костная масса", "кг", 2),
    PROTEIN_PERCENT("Белок", "%", 1, maximum = 100.0),
    PROTEIN_MASS_KG("Масса белка", "кг", 2),
    VISCERAL_FAT_LEVEL("Уровень висцерального жира", "", 1),
    BASAL_METABOLIC_RATE_KCAL("Основной обмен", "ккал", 0),
    METABOLIC_AGE("Метаболический возраст", "лет", 0, wholeNumber = true),
    LEAN_BODY_MASS_KG("Безжировая масса", "кг", 2),
    ;

    val inputLabel: String
        get() = if (unit.isBlank()) label else "$label, $unit"
}

data class MeasurementUiValues(
    val weightKg: Double,
    val impedanceOhm: Int,
    val bmi: Double,
    val bodyFatPercent: Double,
    val bodyFatMassKg: Double,
    val waterPercent: Double,
    val waterMassKg: Double,
    val muscleMassKg: Double,
    val skeletalMuscleMassKg: Double,
    val boneMassKg: Double,
    val proteinPercent: Double,
    val proteinMassKg: Double,
    val visceralFatLevel: Double,
    val basalMetabolicRateKcal: Double,
    val metabolicAge: Int,
    val leanBodyMassKg: Double,
) {
    operator fun get(field: MeasurementField): Double = when (field) {
        MeasurementField.WEIGHT_KG -> weightKg
        MeasurementField.IMPEDANCE_OHM -> impedanceOhm.toDouble()
        MeasurementField.BMI -> bmi
        MeasurementField.BODY_FAT_PERCENT -> bodyFatPercent
        MeasurementField.BODY_FAT_MASS_KG -> bodyFatMassKg
        MeasurementField.WATER_PERCENT -> waterPercent
        MeasurementField.WATER_MASS_KG -> waterMassKg
        MeasurementField.MUSCLE_MASS_KG -> muscleMassKg
        MeasurementField.SKELETAL_MUSCLE_MASS_KG -> skeletalMuscleMassKg
        MeasurementField.BONE_MASS_KG -> boneMassKg
        MeasurementField.PROTEIN_PERCENT -> proteinPercent
        MeasurementField.PROTEIN_MASS_KG -> proteinMassKg
        MeasurementField.VISCERAL_FAT_LEVEL -> visceralFatLevel
        MeasurementField.BASAL_METABOLIC_RATE_KCAL -> basalMetabolicRateKcal
        MeasurementField.METABOLIC_AGE -> metabolicAge.toDouble()
        MeasurementField.LEAN_BODY_MASS_KG -> leanBodyMassKg
    }
}

data class MeasurementUiItem(
    val id: String,
    val measuredAtEpochMillis: Long,
    val values: MeasurementUiValues,
    val huaweiStatus: String,
    val healthConnectStatus: String,
    val huaweiError: String? = null,
    val healthConnectError: String? = null,
    val isOperationInProgress: Boolean = false,
) {
    val isLocalOnly: Boolean
        get() = huaweiStatus.isLocalOnlyStatus() || healthConnectStatus.isLocalOnlyStatus()

    val canRetry: Boolean
        get() = !isLocalOnly &&
            (huaweiStatus.isRetryableStatus() || healthConnectStatus.isRetryableStatus())
}

data class MeasurementEditorState(
    val measurementId: String,
    val measuredAtEpochMillis: Long,
    val draft: MeasurementEditorDraft,
    val isSaving: Boolean = false,
) {
    val canSave: Boolean
        get() = !isSaving && draft.isValid
}

data class MeasurementDeleteConfirmation(
    val measurementId: String,
    val measuredAtEpochMillis: Long,
    val weightKg: Double,
    val isDeleting: Boolean = false,
)

data class MeasurementsUiState(
    val measurements: List<MeasurementUiItem> = emptyList(),
    val isLoading: Boolean = false,
    val editor: MeasurementEditorState? = null,
    val deleteConfirmation: MeasurementDeleteConfirmation? = null,
)

/** One-shot effects that an integrating ViewModel can expose through a Channel/SharedFlow. */
sealed interface MeasurementsUiEvent {
    data class ShowSnackbar(
        val message: String,
    ) : MeasurementsUiEvent
}

data class MeasurementsCallbacks(
    val onEditRequested: (measurementId: String) -> Unit,
    val onEditorFieldChanged: (field: MeasurementField, value: String) -> Unit,
    val onEditorSaveRequested: (measurementId: String, values: MeasurementUiValues) -> Unit,
    val onEditorDismissed: () -> Unit,
    val onDeleteRequested: (measurementId: String) -> Unit,
    val onDeleteConfirmed: (measurementId: String) -> Unit,
    val onDeleteDismissed: () -> Unit,
    val onRetryRequested: (measurementId: String) -> Unit,
) {
    companion object {
        val None = MeasurementsCallbacks(
            onEditRequested = {},
            onEditorFieldChanged = { _, _ -> },
            onEditorSaveRequested = { _, _ -> },
            onEditorDismissed = {},
            onDeleteRequested = {},
            onDeleteConfirmed = {},
            onDeleteDismissed = {},
            onRetryRequested = {},
        )
    }
}

private fun String.isLocalOnlyStatus(): Boolean = equals("LOCAL_ONLY", ignoreCase = true)

private fun String.isRetryableStatus(): Boolean =
    !equals("SYNCED", ignoreCase = true) &&
        !equals("DISABLED", ignoreCase = true) &&
        !isLocalOnlyStatus()
