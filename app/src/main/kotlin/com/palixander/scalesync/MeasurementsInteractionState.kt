package com.palixander.scalesync

import com.palixander.scalesync.data.MeasurementMutationResult
import com.palixander.scalesync.domain.AccountId
import com.palixander.scalesync.measurements.MeasurementDeleteConfirmation
import com.palixander.scalesync.measurements.MeasurementEditorState
import com.palixander.scalesync.measurements.MeasurementsNavigationState
import com.palixander.scalesync.measurements.PendingClearConfirmation
import kotlinx.coroutines.flow.MutableStateFlow

internal data class MeasurementsInteractionState(
    val scrollToMeasurementId: String? = null,
    val selection: AccountSelection = AccountSelection(),
    val navigation: MeasurementsNavigationState = MeasurementsNavigationState(),
    val editor: MeasurementEditorState? = null,
    val deleteConfirmation: MeasurementDeleteConfirmation? = null,
    val pendingClearConfirmation: PendingClearConfirmation? = null,
    val saveOperation: MeasurementOperationToken? = null,
    val deleteRequestOperation: MeasurementOperationToken? = null,
    val deleteOperation: MeasurementOperationToken? = null,
) {
    val accountId: AccountId?
        get() = selection.accountId

    fun normalizedFor(selection: AccountSelection): MeasurementsInteractionState =
        if (this.selection == selection) this else copy(
            selection = selection,
            scrollToMeasurementId = null,
            navigation = navigation.afterAccountSelectionChanged(),
            editor = null,
            deleteConfirmation = null,
            pendingClearConfirmation = null,
            saveOperation = null,
            deleteRequestOperation = null,
            deleteOperation = null,
        )

    fun withDeleteConfirmation(
        ownerSelection: AccountSelection,
        confirmation: MeasurementDeleteConfirmation,
    ): MeasurementsInteractionState = if (selection == ownerSelection) {
        copy(deleteConfirmation = confirmation)
    } else {
        this
    }
}

internal data class MeasurementOperationToken(
    val selection: AccountSelection,
    val measurementId: String,
    val sequence: Long,
)

private fun MeasurementsInteractionState.owns(operation: MeasurementOperationToken): Boolean =
    selection == operation.selection && when (operation) {
        saveOperation -> editor?.measurementId == operation.measurementId
        deleteRequestOperation -> true
        deleteOperation -> deleteConfirmation?.measurementId == operation.measurementId
        else -> false
    }

internal fun MeasurementsInteractionState.afterSaveCompletion(
    result: MeasurementMutationResult,
): MeasurementsInteractionState = when (result) {
    MeasurementMutationResult.Success,
    MeasurementMutationResult.NotFound,
    -> copy(navigation = navigation.back(), editor = null, saveOperation = null)
    MeasurementMutationResult.Invalid,
    -> copy(editor = editor?.copy(isSaving = false), saveOperation = null)
}

internal inline fun MutableStateFlow<MeasurementsInteractionState>.acceptOperation(
    operation: MeasurementOperationToken,
    authoritativeSelection: AccountSelection,
    transform: (MeasurementsInteractionState) -> MeasurementsInteractionState,
): Boolean {
    while (true) {
        val current = value
        val normalized = current.normalizedFor(authoritativeSelection)
        if (!normalized.owns(operation)) {
            if (normalized === current || compareAndSet(current, normalized)) return false
        } else if (compareAndSet(current, transform(normalized))) {
            return true
        }
    }
}
