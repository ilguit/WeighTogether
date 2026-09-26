package com.palixander.scalesync.measurements

import com.palixander.scalesync.R
import com.palixander.scalesync.domain.MeasurementOrigin

import com.palixander.scalesync.domain.AccountId
import com.palixander.scalesync.domain.PendingMeasurement
import com.palixander.scalesync.domain.PendingMeasurementId
import com.palixander.scalesync.domain.PreliminaryDecisionReadiness
import com.palixander.scalesync.domain.lifecycleAt
import com.palixander.scalesync.ui.reference.ReferenceMetricPresentation
import com.palixander.scalesync.ui.accounts.AccountSelectorUiState
import com.palixander.scalesync.ui.routing.PendingResolverReturnDestination
import com.palixander.scalesync.ui.text.UiText
import java.time.Instant

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

    fun afterPendingResolution(
        returnDestination: PendingResolverReturnDestination,
    ): MeasurementsNavigationState = when (returnDestination) {
        PendingResolverReturnDestination.PENDING_QUEUE -> showPendingQueue()
        PendingResolverReturnDestination.PRESERVE_CURRENT -> this
    }

    fun afterAccountSelectionChanged(): MeasurementsNavigationState = when (destination) {
        MeasurementsDestination.EDITOR -> back()
        MeasurementsDestination.SUMMARY,
        MeasurementsDestination.PENDING_QUEUE,
        MeasurementsDestination.HISTORY -> this
    }
}

enum class MeasurementEditorGroup(
    val title: String,
) {
    MAIN("Main"),
    BODY_COMPOSITION("Body composition"),
    MUSCLES_AND_BONES("Muscles and bones"),
    METABOLISM("Metabolism"),
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
    WEIGHT_KG("Weight", "kg", 2, MeasurementEditorGroup.MAIN),
    IMPEDANCE_OHM("Impedance", "Ω", 0, MeasurementEditorGroup.MAIN, wholeNumber = true),
    BMI("Body mass index", "", 1, MeasurementEditorGroup.MAIN),
    BODY_FAT_PERCENT("Body fat", "%", 1, MeasurementEditorGroup.BODY_COMPOSITION, maximum = 100.0),
    BODY_FAT_MASS_KG("Fat mass", "kg", 2, MeasurementEditorGroup.BODY_COMPOSITION),
    WATER_PERCENT("Water", "%", 1, MeasurementEditorGroup.BODY_COMPOSITION, maximum = 100.0),
    WATER_MASS_KG("Water mass", "kg", 2, MeasurementEditorGroup.BODY_COMPOSITION),
    MUSCLE_MASS_KG("Muscle mass", "kg", 2, MeasurementEditorGroup.MUSCLES_AND_BONES),
    SKELETAL_MUSCLE_MASS_KG(
        "Skeletal muscle mass",
        "kg",
        2,
        MeasurementEditorGroup.MUSCLES_AND_BONES,
    ),
    BONE_MASS_KG("Bone mass", "kg", 2, MeasurementEditorGroup.MUSCLES_AND_BONES),
    PROTEIN_PERCENT("Protein", "%", 1, MeasurementEditorGroup.BODY_COMPOSITION, maximum = 100.0),
    PROTEIN_MASS_KG("Protein mass", "kg", 2, MeasurementEditorGroup.BODY_COMPOSITION),
    VISCERAL_FAT_LEVEL("Visceral fat level", "", 1, MeasurementEditorGroup.METABOLISM),
    BASAL_METABOLIC_RATE_KCAL("Basal metabolic rate", "kcal", 0, MeasurementEditorGroup.METABOLISM),
    METABOLIC_AGE(
        "Metabolic age",
        "years",
        0,
        MeasurementEditorGroup.METABOLISM,
        wholeNumber = true,
    ),
    LEAN_BODY_MASS_KG("Lean body mass", "kg", 2, MeasurementEditorGroup.BODY_COMPOSITION),
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
    val label: UiText,
) {
    HEALTH_CONNECT(UiText.Resource(R.string.health_connect_title)),
}

enum class MeasurementSyncPresentationState(
    val label: UiText,
) {
    LOCAL_ONLY(UiText.Resource(R.string.sync_state_local_only)),
    ERROR(UiText.Resource(R.string.sync_state_error)),
    PENDING(UiText.Resource(R.string.sync_state_pending)),
    SYNCED(UiText.Resource(R.string.sync_state_synced)),
}

data class MeasurementSyncDirectionPresentation(
    val direction: MeasurementSyncDirection,
    val state: MeasurementSyncPresentationState,
    val message: UiText?,
    val canRetry: Boolean,
) {
    val label: UiText
        get() = direction.label
}

data class MeasurementSyncPresentation(
    val state: MeasurementSyncPresentationState,
    val directions: List<MeasurementSyncDirectionPresentation>,
    val canRetry: Boolean,
) {
    val label: UiText
        get() = state.label
}

data class MeasurementUiItem(
    val id: String,
    val measuredAtEpochSecond: Long,
    val values: MeasurementUiValues,
    val sync: MeasurementSyncPresentation,
    val type: MeasurementUiType = MeasurementUiType.FULL,
    val isManuallyEdited: Boolean = false,
    val hasProfileSyncMismatch: Boolean = false,
    val isOperationInProgress: Boolean = false,
    /** Stable across pending replacement by a finalized Room row. */
    val presentationKey: String = id,
    /** Room row id used by edit/delete/retry/sync; absent while the item is preliminary. */
    val finalMeasurementId: String? = id,
    val sourcePendingId: PendingMeasurementId? = null,
    val isPreliminary: Boolean = false,
    val preliminaryDecisionReadiness: PreliminaryDecisionReadiness? = null,
    /** Finalized-only ScaleSync 1 presentation. Preliminary rows intentionally keep this empty. */
    val referenceMetrics: List<ReferenceMetricPresentation> = emptyList(),
    val ratingHeightCm: Double? = null,
    val referenceAge: Int? = null,
    val hasRestoredRatingHeight: Boolean = false,
    val origin: MeasurementOrigin = MeasurementOrigin.LEGACY,
) {
    init {
        require(presentationKey.isNotBlank()) { "Presentation key must not be blank" }
        if (isPreliminary) {
            require(sourcePendingId != null) { "Preliminary measurement must retain its pending id" }
            require(finalMeasurementId == null) { "Preliminary measurement cannot address a final row" }
            require(preliminaryDecisionReadiness != null) {
                "Preliminary measurement must expose decision readiness"
            }
        } else {
            require(finalMeasurementId != null) { "Finalized measurement must address its Room row" }
            require(preliminaryDecisionReadiness == null) {
                "Finalized measurement cannot expose preliminary readiness"
            }
        }
    }

    val isWeightOnly: Boolean
        get() = type == MeasurementUiType.WEIGHT_ONLY

    val isLocalOnly: Boolean
        get() = sync.state == MeasurementSyncPresentationState.LOCAL_ONLY

    val canRetry: Boolean
        get() = hasFinalActions && sync.canRetry

    val mutationId: String?
        get() = finalMeasurementId.takeIf { hasFinalActions }

    val isReadyForDecision: Boolean
        get() = preliminaryDecisionReadiness == PreliminaryDecisionReadiness.READY_FOR_DECISION

    val hasFinalActions: Boolean
        get() = !isPreliminary && finalMeasurementId != null

    val canEdit: Boolean
        get() = hasFinalActions && !isOperationInProgress

    val canDelete: Boolean
        get() = hasFinalActions && !isOperationInProgress

    val canSync: Boolean
        get() = hasFinalActions && !isOperationInProgress

    val hasSyncPresentation: Boolean
        get() = hasFinalActions && !isLocalOnly

    val measuredAt: Instant
        get() = Instant.ofEpochSecond(measuredAtEpochSecond)
}

internal const val MANUALLY_EDITED_HISTORY_MESSAGE =
    "This measurement was edited manually. Changes are stored only on this device " +
        "and are not sent to external services."

internal const val PROFILE_SYNC_MISMATCH_HISTORY_MESSAGE =
    "Local metrics were recalculated for the updated profile. " +
        "Previously synced data in external services was not changed."

data class PendingMeasurementUiItem(
    val id: PendingMeasurementId,
    val measuredAtEpochSecond: Long,
    val weightKg: Double,
    val impedanceOhm: Int?,
    val decisionReadiness: PreliminaryDecisionReadiness =
        PreliminaryDecisionReadiness.READY_FOR_DECISION,
)

val PendingMeasurementUiItem.isProcessing: Boolean
    get() = decisionReadiness == PreliminaryDecisionReadiness.AGGREGATING

val PendingMeasurementUiItem.canAssign: Boolean
    get() = !isProcessing

val PendingMeasurementUiItem.canPreview: Boolean
    get() = !isProcessing

val PendingMeasurementUiItem.canDelete: Boolean
    get() = !isProcessing

val PendingMeasurementUiItem.measuredAt: Instant
    get() = Instant.ofEpochSecond(measuredAtEpochSecond)

internal fun PendingMeasurement.toPendingMeasurementUiItem(
    now: Instant = Instant.now(),
): PendingMeasurementUiItem =
    PendingMeasurementUiItem(
        id = id,
        measuredAtEpochSecond = measuredAt.epochSecond,
        weightKg = weightKg,
        impedanceOhm = impedanceOhm.takeIf { hasImpedance },
        decisionReadiness = lifecycleAt(now).decisionReadiness,
    )

/**
 * Summary/chart projection for the short-lived aggregate. Calculated metrics, when available, are
 * presentation-only and it carries no final-row identity, so consumers cannot offer mutations or
 * sync.
 */
internal fun PendingMeasurement.toPreliminaryMeasurementUiItem(
    now: Instant,
    composition: com.palixander.scalesync.core.BodyComposition? = null,
): MeasurementUiItem {
    val lifecycle = lifecycleAt(now)
    return MeasurementUiItem(
        id = id.value,
        presentationKey = lifecycle.presentationKey.value,
        finalMeasurementId = null,
        sourcePendingId = id,
        isPreliminary = true,
        preliminaryDecisionReadiness = lifecycle.decisionReadiness,
        measuredAtEpochSecond = measuredAt.epochSecond,
        values = composition?.let {
            MeasurementUiValues(
                weightKg = it.weightKg,
                impedanceOhm = it.impedanceOhm,
                bmi = it.bmi,
                bodyFatPercent = it.bodyFatPercent,
                bodyFatMassKg = it.bodyFatMassKg,
                waterPercent = it.waterPercent,
                waterMassKg = it.waterMassKg,
                muscleMassKg = it.muscleMassKg,
                skeletalMuscleMassKg = it.skeletalMuscleMassKg,
                boneMassKg = it.boneMassKg,
                proteinPercent = it.proteinPercent,
                proteinMassKg = it.proteinMassKg,
                visceralFatLevel = it.visceralFatLevel,
                basalMetabolicRateKcal = it.basalMetabolicRateKcal,
                metabolicAge = it.metabolicAge,
                leanBodyMassKg = it.leanBodyMassKg,
            )
        } ?: MeasurementUiValues(
            weightKg = weightKg,
            impedanceOhm = impedanceOhm.takeIf { hasImpedance },
            bmi = null,
            bodyFatPercent = null,
            bodyFatMassKg = null,
            waterPercent = null,
            waterMassKg = null,
            muscleMassKg = null,
            skeletalMuscleMassKg = null,
            boneMassKg = null,
            proteinPercent = null,
            proteinMassKg = null,
            visceralFatLevel = null,
            basalMetabolicRateKcal = null,
            metabolicAge = null,
            leanBodyMassKg = null,
        ),
        sync = measurementSyncPresentation(
            healthConnectStatus = "LOCAL_ONLY",
            healthConnectError = null,
        ),
        type = if (composition == null) MeasurementUiType.WEIGHT_ONLY else MeasurementUiType.FULL,
    )
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
    val ordered = measurements.sortedByDescending(MeasurementUiItem::measuredAtEpochSecond)
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
    val measuredAtEpochSecond: Long,
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

    val measuredAt: Instant
        get() = Instant.ofEpochSecond(measuredAtEpochSecond)
}

data class MeasurementDeleteConfirmation(
    val measurementId: String,
    val measuredAtEpochSecond: Long,
    val weightKg: Double,
    val isDeleting: Boolean = false,
)

data class PendingClearConfirmation(
    val count: Int,
    val isClearing: Boolean = false,
    val errorMessage: UiText? = null,
)

val MeasurementDeleteConfirmation.measuredAt: Instant
    get() = Instant.ofEpochSecond(measuredAtEpochSecond)

data class MeasurementsUiState(
    val scrollToMeasurementId: String? = null,
    val destination: MeasurementsDestination = MeasurementsDestination.SUMMARY,
    val editorOrigin: MeasurementEditorOrigin = MeasurementEditorOrigin.SUMMARY,
    val measurements: List<MeasurementUiItem> = emptyList(),
    val summary: MeasurementSummaryPresentation? = null,
    val pendingMeasurements: List<PendingMeasurementUiItem> = emptyList(),
    val isLoading: Boolean = true,
    val editor: MeasurementEditorState? = null,
    val deleteConfirmation: MeasurementDeleteConfirmation? = null,
    val pendingClearConfirmation: PendingClearConfirmation? = null,
    val homeKgChart: HomeKgChartUiState? = null,
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
    data class ManualWeightSaved(
        val owner: com.palixander.scalesync.domain.ManualWeightOwner,
        val result: com.palixander.scalesync.domain.ManualWeightResult.Saved,
    ) : MeasurementsUiEvent
    data class ShowSnackbar(
        val message: UiText,
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
    val onAddWeightRequested: () -> Unit = {},
    val onScrollToMeasurementHandled: () -> Unit = {},
    val onAccountSelected: (AccountId) -> Unit = {},
    val onPendingAssignRequested: (PendingMeasurementId) -> Unit = {},
    val onPendingPreviewRequested: (PendingMeasurementId) -> Unit = {},
    val onPendingDeleteRequested: (PendingMeasurementId) -> Unit = {},
    val onPendingClearRequested: () -> Unit = {},
    val onPendingClearConfirmed: () -> Unit = {},
    val onPendingClearDismissed: () -> Unit = {},
    val onHomeKgChartSeriesToggled: (seriesKey: String) -> Unit = {},
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
            onPendingAssignRequested = {},
            onPendingPreviewRequested = {},
            onPendingDeleteRequested = {},
            onPendingClearRequested = {},
            onPendingClearConfirmed = {},
            onPendingClearDismissed = {},
            onHomeKgChartSeriesToggled = {},
        )
    }
}

internal fun measurementSyncPresentation(
    healthConnectStatus: String,
    healthConnectError: String?,
): MeasurementSyncPresentation {
    val directions = listOfNotNull(
        syncDirectionPresentation(
            direction = MeasurementSyncDirection.HEALTH_CONNECT,
            rawStatus = healthConnectStatus,
            rawError = healthConnectError,
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
        MeasurementSyncPresentationState.LOCAL_ONLY -> null

        MeasurementSyncPresentationState.ERROR ->
            rawError?.takeIf(String::isNotBlank)?.let(UiText::Raw)
                ?: UiText.Resource(R.string.sync_message_send_failed)

        MeasurementSyncPresentationState.PENDING ->
            rawError?.takeIf(String::isNotBlank)?.let(UiText::Raw)
                ?: UiText.Resource(R.string.sync_message_pending)

        MeasurementSyncPresentationState.SYNCED -> UiText.Resource(R.string.sync_message_sent)
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
