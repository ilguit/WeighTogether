package com.example.huaweimisync.measurements

import com.example.huaweimisync.domain.AccountId
import com.example.huaweimisync.ui.accounts.AccountSelectorUiState

/** State-based destinations owned by the measurements feature. */
enum class MeasurementsDestination {
    SUMMARY,
    PENDING_QUEUE,
    HISTORY,
    EDITOR,
}

enum class MeasurementEditorOrigin(
    val destination: MeasurementsDestination,
) {
    SUMMARY(MeasurementsDestination.SUMMARY),
    HISTORY(MeasurementsDestination.HISTORY),
}

data class MeasurementsNavigationState(
    val destination: MeasurementsDestination = MeasurementsDestination.SUMMARY,
    val editorOrigin: MeasurementEditorOrigin = MeasurementEditorOrigin.SUMMARY,
) {
    fun showSummary(): MeasurementsNavigationState = copy(
        destination = MeasurementsDestination.SUMMARY,
    )

    fun showHistory(): MeasurementsNavigationState = copy(
        destination = MeasurementsDestination.HISTORY,
    )

    fun showPendingQueue(): MeasurementsNavigationState = copy(
        destination = MeasurementsDestination.PENDING_QUEUE,
    )

    fun showEditor(origin: MeasurementEditorOrigin): MeasurementsNavigationState = copy(
        destination = MeasurementsDestination.EDITOR,
        editorOrigin = origin,
    )

    fun back(): MeasurementsNavigationState = when (destination) {
        MeasurementsDestination.EDITOR -> copy(destination = editorOrigin.destination)
        MeasurementsDestination.PENDING_QUEUE,
        MeasurementsDestination.HISTORY -> copy(destination = MeasurementsDestination.SUMMARY)
        MeasurementsDestination.SUMMARY -> this
    }
}

enum class MeasurementEditorGroup(
    val title: String,
) {
    MAIN("Основное"),
    BODY_COMPOSITION("Состав тела"),
    MUSCLES_AND_BONES("Мышцы и кости"),
    METABOLISM("Метаболизм"),
}

/**
 * UI-owned field order. It intentionally mirrors the data layer's MeasurementMetric order, while
 * keeping this package independent from Room and repository types.
 */
enum class MeasurementField(
    val label: String,
    val unit: String,
    val decimalPlaces: Int,
    val editorGroup: MeasurementEditorGroup,
    internal val wholeNumber: Boolean = false,
    internal val maximum: Double? = null,
) {
    WEIGHT_KG("Вес", "кг", 2, MeasurementEditorGroup.MAIN),
    IMPEDANCE_OHM("Импеданс", "Ом", 0, MeasurementEditorGroup.MAIN, wholeNumber = true),
    BMI("Индекс массы тела", "", 1, MeasurementEditorGroup.MAIN),
    BODY_FAT_PERCENT("Жир", "%", 1, MeasurementEditorGroup.BODY_COMPOSITION, maximum = 100.0),
    BODY_FAT_MASS_KG("Масса жира", "кг", 2, MeasurementEditorGroup.BODY_COMPOSITION),
    WATER_PERCENT("Вода", "%", 1, MeasurementEditorGroup.BODY_COMPOSITION, maximum = 100.0),
    WATER_MASS_KG("Масса воды", "кг", 2, MeasurementEditorGroup.BODY_COMPOSITION),
    MUSCLE_MASS_KG("Мышечная масса", "кг", 2, MeasurementEditorGroup.MUSCLES_AND_BONES),
    SKELETAL_MUSCLE_MASS_KG(
        "Скелетная мышечная масса",
        "кг",
        2,
        MeasurementEditorGroup.MUSCLES_AND_BONES,
    ),
    BONE_MASS_KG("Костная масса", "кг", 2, MeasurementEditorGroup.MUSCLES_AND_BONES),
    PROTEIN_PERCENT("Белок", "%", 1, MeasurementEditorGroup.BODY_COMPOSITION, maximum = 100.0),
    PROTEIN_MASS_KG("Масса белка", "кг", 2, MeasurementEditorGroup.BODY_COMPOSITION),
    VISCERAL_FAT_LEVEL("Уровень висцерального жира", "", 1, MeasurementEditorGroup.METABOLISM),
    BASAL_METABOLIC_RATE_KCAL("Основной обмен", "ккал", 0, MeasurementEditorGroup.METABOLISM),
    METABOLIC_AGE(
        "Метаболический возраст",
        "лет",
        0,
        MeasurementEditorGroup.METABOLISM,
        wholeNumber = true,
    ),
    LEAN_BODY_MASS_KG("Безжировая масса", "кг", 2, MeasurementEditorGroup.BODY_COMPOSITION),
    ;

    val inputLabel: String
        get() = if (unit.isBlank()) label else "$label, $unit"
}

data class MeasurementEditorSection(
    val group: MeasurementEditorGroup,
    val fields: List<MeasurementField>,
) {
    val title: String
        get() = group.title
}

val measurementEditorSections: List<MeasurementEditorSection> = MeasurementEditorGroup.entries.map { group ->
    MeasurementEditorSection(
        group = group,
        fields = MeasurementField.entries.filter { it.editorGroup == group },
    )
}

val weightOnlyEditorSections: List<MeasurementEditorSection> = listOf(
    MeasurementEditorSection(
        group = MeasurementEditorGroup.MAIN,
        fields = listOf(MeasurementField.WEIGHT_KG),
    ),
)

enum class MeasurementUiType {
    FULL,
    WEIGHT_ONLY,
}

data class MeasurementUiValues(
    val weightKg: Double,
    val impedanceOhm: Int?,
    val bmi: Double?,
    val bodyFatPercent: Double?,
    val bodyFatMassKg: Double?,
    val waterPercent: Double?,
    val waterMassKg: Double?,
    val muscleMassKg: Double?,
    val skeletalMuscleMassKg: Double?,
    val boneMassKg: Double?,
    val proteinPercent: Double?,
    val proteinMassKg: Double?,
    val visceralFatLevel: Double?,
    val basalMetabolicRateKcal: Double?,
    val metabolicAge: Int?,
    val leanBodyMassKg: Double?,
) {
    operator fun get(field: MeasurementField): Double? = when (field) {
        MeasurementField.WEIGHT_KG -> weightKg
        MeasurementField.IMPEDANCE_OHM -> impedanceOhm?.toDouble()
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
        MeasurementField.METABOLIC_AGE -> metabolicAge?.toDouble()
        MeasurementField.LEAN_BODY_MASS_KG -> leanBodyMassKg
    }
}

enum class MeasurementSyncDirection(
    val label: String,
) {
    HEALTH_CONNECT("Health Connect"),
    HUAWEI_HEALTH("Huawei Health"),
}

enum class MeasurementSyncPresentationState(
    val label: String,
) {
    LOCAL_ONLY("Только локально"),
    ERROR("Ошибка синхронизации"),
    PENDING("Ожидает отправки"),
    SYNCED("Синхронизировано"),
}

data class MeasurementSyncDirectionPresentation(
    val direction: MeasurementSyncDirection,
    val state: MeasurementSyncPresentationState,
    val message: String,
    val canRetry: Boolean,
) {
    val label: String
        get() = direction.label
}

data class MeasurementSyncPresentation(
    val state: MeasurementSyncPresentationState,
    val directions: List<MeasurementSyncDirectionPresentation>,
    val canRetry: Boolean,
) {
    val label: String
        get() = state.label
}

data class MeasurementUiItem(
    val id: String,
    val measuredAtEpochMillis: Long,
    val values: MeasurementUiValues,
    val sync: MeasurementSyncPresentation,
    val type: MeasurementUiType = MeasurementUiType.FULL,
    val isDeleteProtected: Boolean = false,
    val isOperationInProgress: Boolean = false,
) {
    val isWeightOnly: Boolean
        get() = type == MeasurementUiType.WEIGHT_ONLY

    val isLocalOnly: Boolean
        get() = sync.state == MeasurementSyncPresentationState.LOCAL_ONLY

    val canRetry: Boolean
        get() = sync.canRetry
}

data class MeasurementMetricPresentation(
    val field: MeasurementField,
    val value: Double?,
) {
    val label: String
        get() = this.field.label

    val unit: String
        get() = this.field.unit
}

data class MeasurementSummaryPresentation(
    val latest: MeasurementUiItem,
    val previous: MeasurementUiItem?,
    val weightDeltaKg: Double?,
    val keyMetrics: List<MeasurementMetricPresentation>,
    val additionalMetrics: List<MeasurementMetricPresentation>,
)

fun buildMeasurementSummary(
    measurements: List<MeasurementUiItem>,
): MeasurementSummaryPresentation? {
    val ordered = measurements.sortedByDescending(MeasurementUiItem::measuredAtEpochMillis)
    val latest = ordered.firstOrNull() ?: return null
    val previous = ordered.getOrNull(1)
    return MeasurementSummaryPresentation(
        latest = latest,
        previous = previous,
        weightDeltaKg = previous?.let { latest.values.weightKg - it.values.weightKg },
        keyMetrics = summaryKeyMetricFields.map { field ->
            MeasurementMetricPresentation(field, latest.values[field])
        },
        additionalMetrics = summaryAdditionalMetricFields.map { field ->
            MeasurementMetricPresentation(field, latest.values[field])
        },
    )
}

data class MeasurementEditorState(
    val measurementId: String,
    val measuredAtEpochMillis: Long,
    val draft: MeasurementEditorDraft,
    val type: MeasurementUiType = MeasurementUiType.FULL,
    val sections: List<MeasurementEditorSection> = when (type) {
        MeasurementUiType.FULL -> measurementEditorSections
        MeasurementUiType.WEIGHT_ONLY -> weightOnlyEditorSections
    },
    val isSaving: Boolean = false,
) {
    val isWeightOnly: Boolean
        get() = type == MeasurementUiType.WEIGHT_ONLY

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
    val destination: MeasurementsDestination = MeasurementsDestination.SUMMARY,
    val editorOrigin: MeasurementEditorOrigin = MeasurementEditorOrigin.SUMMARY,
    val measurements: List<MeasurementUiItem> = emptyList(),
    val summary: MeasurementSummaryPresentation? = null,
    val isLoading: Boolean = true,
    val editor: MeasurementEditorState? = null,
    val deleteConfirmation: MeasurementDeleteConfirmation? = null,
    val accountSelector: AccountSelectorUiState = AccountSelectorUiState(
        accounts = emptyList(),
        selectedAccountId = null,
        primaryAccountId = null,
    ),
) {
    val isHistoryEmpty: Boolean
        get() = !isLoading && measurements.isEmpty()

    val hasNoLatestMeasurement: Boolean
        get() = !isLoading && summary == null
}

/** One-shot effects that an integrating ViewModel can expose through a Channel/SharedFlow. */
sealed interface MeasurementsUiEvent {
    data class ShowSnackbar(
        val message: String,
    ) : MeasurementsUiEvent
}

data class MeasurementsCallbacks(
    val onSummaryRequested: () -> Unit,
    val onPendingQueueRequested: () -> Unit,
    val onHistoryRequested: () -> Unit,
    val onBackRequested: () -> Unit,
    val onEditRequested: (measurementId: String, origin: MeasurementEditorOrigin) -> Unit,
    val onEditorFieldChanged: (field: MeasurementField, value: String) -> Unit,
    val onEditorSaveRequested: (measurementId: String, values: MeasurementUiValues) -> Unit,
    val onEditorDismissed: () -> Unit,
    val onDeleteRequested: (measurementId: String) -> Unit,
    val onDeleteConfirmed: (measurementId: String) -> Unit,
    val onDeleteDismissed: () -> Unit,
    val onRetryRequested: (measurementId: String) -> Unit,
    val onAccountSelected: (AccountId) -> Unit = {},
) {
    companion object {
        val None = MeasurementsCallbacks(
            onSummaryRequested = {},
            onPendingQueueRequested = {},
            onHistoryRequested = {},
            onBackRequested = {},
            onEditRequested = { _, _ -> },
            onEditorFieldChanged = { _, _ -> },
            onEditorSaveRequested = { _, _ -> },
            onEditorDismissed = {},
            onDeleteRequested = {},
            onDeleteConfirmed = {},
            onDeleteDismissed = {},
            onRetryRequested = {},
            onAccountSelected = {},
        )
    }
}

internal fun measurementSyncPresentation(
    healthConnectStatus: String,
    healthConnectError: String?,
    huaweiStatus: String,
    huaweiError: String?,
): MeasurementSyncPresentation {
    val directions = listOfNotNull(
        syncDirectionPresentation(
            direction = MeasurementSyncDirection.HEALTH_CONNECT,
            rawStatus = healthConnectStatus,
            rawError = healthConnectError,
        ),
        syncDirectionPresentation(
            direction = MeasurementSyncDirection.HUAWEI_HEALTH,
            rawStatus = huaweiStatus,
            rawError = huaweiError,
        ),
    )
    val availableDirections = directions.filterNot {
        it.state == MeasurementSyncPresentationState.LOCAL_ONLY
    }
    val aggregate = (availableDirections.ifEmpty { directions })
        .minByOrNull { it.state.priority }
        ?.state
        ?: MeasurementSyncPresentationState.SYNCED
    return MeasurementSyncPresentation(
        state = aggregate,
        directions = directions,
        canRetry = directions.any(MeasurementSyncDirectionPresentation::canRetry),
    )
}

private fun syncDirectionPresentation(
    direction: MeasurementSyncDirection,
    rawStatus: String,
    rawError: String?,
): MeasurementSyncDirectionPresentation? {
    val normalizedStatus = rawStatus.trim().uppercase()
    if (normalizedStatus == "DISABLED") return null

    val state = when (normalizedStatus) {
        "LOCAL_ONLY" -> MeasurementSyncPresentationState.LOCAL_ONLY
        "FAILED", "BLOCKED" -> MeasurementSyncPresentationState.ERROR
        "PENDING" -> MeasurementSyncPresentationState.PENDING
        "SYNCED" -> MeasurementSyncPresentationState.SYNCED
        else -> MeasurementSyncPresentationState.ERROR
    }
    val message = when (state) {
        MeasurementSyncPresentationState.LOCAL_ONLY ->
            "Запись хранится только на этом устройстве"

        MeasurementSyncPresentationState.ERROR ->
            rawError?.takeIf(String::isNotBlank) ?: "Не удалось отправить данные"

        MeasurementSyncPresentationState.PENDING ->
            rawError?.takeIf(String::isNotBlank) ?: "Отправка ожидает выполнения"

        MeasurementSyncPresentationState.SYNCED -> "Данные отправлены"
    }
    return MeasurementSyncDirectionPresentation(
        direction = direction,
        state = state,
        message = message,
        canRetry = state == MeasurementSyncPresentationState.ERROR ||
            state == MeasurementSyncPresentationState.PENDING,
    )
}

private val MeasurementSyncPresentationState.priority: Int
    get() = when (this) {
        MeasurementSyncPresentationState.LOCAL_ONLY -> 0
        MeasurementSyncPresentationState.ERROR -> 1
        MeasurementSyncPresentationState.PENDING -> 2
        MeasurementSyncPresentationState.SYNCED -> 3
    }

private val summaryKeyMetricFields = listOf(
    MeasurementField.BODY_FAT_PERCENT,
    MeasurementField.MUSCLE_MASS_KG,
    MeasurementField.WATER_PERCENT,
    MeasurementField.BMI,
)

private val summaryAdditionalMetricFields = listOf(
    MeasurementField.IMPEDANCE_OHM,
    MeasurementField.BODY_FAT_MASS_KG,
    MeasurementField.WATER_MASS_KG,
    MeasurementField.SKELETAL_MUSCLE_MASS_KG,
    MeasurementField.BONE_MASS_KG,
    MeasurementField.PROTEIN_PERCENT,
    MeasurementField.PROTEIN_MASS_KG,
    MeasurementField.VISCERAL_FAT_LEVEL,
    MeasurementField.BASAL_METABOLIC_RATE_KCAL,
    MeasurementField.METABOLIC_AGE,
    MeasurementField.LEAN_BODY_MASS_KG,
)
