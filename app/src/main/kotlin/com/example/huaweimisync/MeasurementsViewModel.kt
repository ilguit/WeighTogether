package com.example.huaweimisync

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.huaweimisync.data.MeasurementEntity
import com.example.huaweimisync.data.MeasurementMutationResult
import com.example.huaweimisync.data.MeasurementValues
import com.example.huaweimisync.measurements.MeasurementDeleteConfirmation
import com.example.huaweimisync.measurements.MeasurementEditorDraft
import com.example.huaweimisync.measurements.MeasurementEditorOrigin
import com.example.huaweimisync.measurements.MeasurementEditorState
import com.example.huaweimisync.measurements.MeasurementField
import com.example.huaweimisync.measurements.MeasurementUiItem
import com.example.huaweimisync.measurements.MeasurementUiValues
import com.example.huaweimisync.measurements.MeasurementsCallbacks
import com.example.huaweimisync.measurements.MeasurementsDestination
import com.example.huaweimisync.measurements.MeasurementsNavigationState
import com.example.huaweimisync.measurements.MeasurementsUiEvent
import com.example.huaweimisync.measurements.MeasurementsUiState
import com.example.huaweimisync.measurements.buildMeasurementSummary
import com.example.huaweimisync.measurements.measurementSyncPresentation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MeasurementsViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = (application as MiSyncApplication).container.repository
    private val measurements = repository.observeAll()
        .map<List<MeasurementEntity>, MeasurementsLoadState>(MeasurementsLoadState::Loaded)
        .stateIn(viewModelScope, SharingStarted.Eagerly, MeasurementsLoadState.Loading)
    private val navigation = MutableStateFlow(MeasurementsNavigationState())
    private val editor = MutableStateFlow<MeasurementEditorState?>(null)
    private val deleteConfirmation = MutableStateFlow<MeasurementDeleteConfirmation?>(null)
    private val eventChannel = Channel<MeasurementsUiEvent>(Channel.BUFFERED)

    val events = eventChannel.receiveAsFlow()

    val uiState = combine(
        measurements,
        navigation,
        editor,
        deleteConfirmation,
    ) { loadState, currentNavigation, currentEditor, deletion ->
        val values = loadState.valuesOrEmpty()
            .sortedByDescending(MeasurementEntity::measuredAtEpochMillis)
        val protectedLatestId = repository.protectedLatestId(values)
        val items = values.map { value ->
            value.toUiItem(
                isDeleteProtected = value.id == protectedLatestId,
                isOperationInProgress = deletion?.isDeleting == true &&
                    deletion.measurementId == value.id,
            )
        }
        MeasurementsUiState(
            destination = currentNavigation.destination,
            editorOrigin = currentNavigation.editorOrigin,
            measurements = items,
            summary = buildMeasurementSummary(items),
            isLoading = loadState is MeasurementsLoadState.Loading,
            editor = currentEditor,
            deleteConfirmation = deletion,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MeasurementsUiState())

    val callbacks = MeasurementsCallbacks(
        onSummaryRequested = ::showSummary,
        onHistoryRequested = ::showHistory,
        onBackRequested = ::navigateBack,
        onEditRequested = ::openEditor,
        onEditorFieldChanged = ::updateEditorField,
        onEditorSaveRequested = ::saveEditor,
        onEditorDismissed = ::dismissEditor,
        onDeleteRequested = ::requestDelete,
        onDeleteConfirmed = ::confirmDelete,
        onDeleteDismissed = ::dismissDelete,
        onRetryRequested = ::retry,
    )

    private fun showSummary() {
        if (editor.value?.isSaving == true) return
        navigation.update(MeasurementsNavigationState::showSummary)
        editor.value = null
    }

    private fun showHistory() {
        if (editor.value?.isSaving == true) return
        navigation.update(MeasurementsNavigationState::showHistory)
        editor.value = null
    }

    private fun navigateBack() {
        if (navigation.value.destination == MeasurementsDestination.EDITOR) {
            if (editor.value?.isSaving == true) return
            navigation.update(MeasurementsNavigationState::back)
            editor.value = null
            return
        }
        navigation.update(MeasurementsNavigationState::back)
    }

    private fun openEditor(id: String, origin: MeasurementEditorOrigin) {
        val value = measurements.value.valuesOrEmpty().firstOrNull { it.id == id } ?: run {
            showMessage("Измерение уже удалено")
            return
        }
        editor.value = MeasurementEditorState(
            measurementId = value.id,
            measuredAtEpochMillis = value.measuredAtEpochMillis,
            draft = MeasurementEditorDraft.from(value.values.toUiValues()),
        )
        navigation.update { it.showEditor(origin) }
    }

    private fun updateEditorField(field: MeasurementField, value: String) {
        editor.update { current ->
            current?.takeUnless { it.isSaving }?.copy(draft = current.draft.withValue(field, value))
                ?: current
        }
    }

    private fun saveEditor(id: String, values: MeasurementUiValues) {
        val current = editor.value ?: return
        if (current.measurementId != id || current.isSaving) return
        editor.value = current.copy(isSaving = true)
        viewModelScope.launch {
            when (repository.update(id, values.toDataValues())) {
                MeasurementMutationResult.Success -> {
                    closeEditor()
                    showMessage("Локальное измерение изменено")
                }

                MeasurementMutationResult.NotFound -> {
                    closeEditor()
                    showMessage("Измерение уже удалено")
                }

                MeasurementMutationResult.Invalid -> {
                    editor.update { it?.copy(isSaving = false) }
                    showMessage("Проверьте введённые значения")
                }

                MeasurementMutationResult.ProtectedLatest -> {
                    editor.update { it?.copy(isSaving = false) }
                    showMessage("Не удалось изменить измерение")
                }
            }
        }
    }

    private fun dismissEditor() {
        if (editor.value?.isSaving != true) navigateBack()
    }

    private fun requestDelete(id: String) {
        val values = measurements.value.valuesOrEmpty()
        when (
            val request = measurementDeleteRequest(
                id = id,
                measurements = values,
                protectedLatestId = repository.protectedLatestId(values),
            )
        ) {
            MeasurementDeleteRequest.NotFound -> showMessage("Измерение уже удалено")
            MeasurementDeleteRequest.ProtectedLatest -> showProtectedLatestMessage()
            is MeasurementDeleteRequest.Confirm -> deleteConfirmation.value = request.confirmation
        }
    }

    private fun confirmDelete(id: String) {
        val confirmation = deleteConfirmation.value ?: return
        if (confirmation.measurementId != id || confirmation.isDeleting) return
        deleteConfirmation.value = confirmation.copy(isDeleting = true)
        viewModelScope.launch {
            showMessage(measurementDeleteResultMessage(repository.delete(id)))
            deleteConfirmation.value = null
        }
    }

    private fun dismissDelete() {
        if (deleteConfirmation.value?.isDeleting != true) deleteConfirmation.value = null
    }

    private fun retry(id: String) = viewModelScope.launch {
        repository.retry(id)
        showMessage("Повторная отправка поставлена в очередь")
    }

    private fun showMessage(message: String) {
        eventChannel.trySend(MeasurementsUiEvent.ShowSnackbar(message))
    }

    private fun closeEditor() {
        navigation.update(MeasurementsNavigationState::back)
        editor.value = null
    }

    private fun showProtectedLatestMessage() {
        showMessage(PROTECTED_LATEST_MESSAGE)
    }

    companion object {
        const val PROTECTED_LATEST_MESSAGE =
            "Последнее измерение хранится в памяти весов и будет добавлено снова, поэтому удалить его нельзя"
    }
}

internal sealed interface MeasurementDeleteRequest {
    data object NotFound : MeasurementDeleteRequest
    data object ProtectedLatest : MeasurementDeleteRequest
    data class Confirm(
        val confirmation: MeasurementDeleteConfirmation,
    ) : MeasurementDeleteRequest
}

internal fun measurementDeleteRequest(
    id: String,
    measurements: List<MeasurementEntity>,
    protectedLatestId: String?,
): MeasurementDeleteRequest {
    val value = measurements.firstOrNull { it.id == id } ?: return MeasurementDeleteRequest.NotFound
    if (value.id == protectedLatestId) return MeasurementDeleteRequest.ProtectedLatest
    return MeasurementDeleteRequest.Confirm(
        MeasurementDeleteConfirmation(
            measurementId = value.id,
            measuredAtEpochMillis = value.measuredAtEpochMillis,
            weightKg = value.weightKg,
        ),
    )
}

internal fun measurementDeleteResultMessage(result: MeasurementMutationResult): String = when (result) {
    MeasurementMutationResult.Success -> "Локальное измерение удалено"
    MeasurementMutationResult.NotFound -> "Измерение уже удалено"
    MeasurementMutationResult.Invalid -> "Не удалось удалить измерение"
    MeasurementMutationResult.ProtectedLatest -> MeasurementsViewModel.PROTECTED_LATEST_MESSAGE
}

private fun MeasurementEntity.toUiItem(
    isDeleteProtected: Boolean,
    isOperationInProgress: Boolean,
): MeasurementUiItem =
    MeasurementUiItem(
        id = id,
        measuredAtEpochMillis = measuredAtEpochMillis,
        values = values.toUiValues(),
        sync = measurementSyncPresentation(
            healthConnectStatus = healthConnectStatus,
            healthConnectError = healthConnectError,
            huaweiStatus = huaweiStatus,
            huaweiError = huaweiError,
        ),
        isDeleteProtected = isDeleteProtected,
        isOperationInProgress = isOperationInProgress,
    )

private fun MeasurementValues.toUiValues(): MeasurementUiValues = MeasurementUiValues(
    weightKg = weightKg,
    impedanceOhm = impedanceOhm,
    bmi = bmi,
    bodyFatPercent = bodyFatPercent,
    bodyFatMassKg = bodyFatMassKg,
    waterPercent = waterPercent,
    waterMassKg = waterMassKg,
    muscleMassKg = muscleMassKg,
    skeletalMuscleMassKg = skeletalMuscleMassKg,
    boneMassKg = boneMassKg,
    proteinPercent = proteinPercent,
    proteinMassKg = proteinMassKg,
    visceralFatLevel = visceralFatLevel,
    basalMetabolicRateKcal = basalMetabolicRateKcal,
    metabolicAge = metabolicAge,
    leanBodyMassKg = leanBodyMassKg,
)

private fun MeasurementUiValues.toDataValues(): MeasurementValues = MeasurementValues(
    weightKg = weightKg,
    impedanceOhm = impedanceOhm,
    bmi = bmi,
    bodyFatPercent = bodyFatPercent,
    bodyFatMassKg = bodyFatMassKg,
    waterPercent = waterPercent,
    waterMassKg = waterMassKg,
    muscleMassKg = muscleMassKg,
    skeletalMuscleMassKg = skeletalMuscleMassKg,
    boneMassKg = boneMassKg,
    proteinPercent = proteinPercent,
    proteinMassKg = proteinMassKg,
    visceralFatLevel = visceralFatLevel,
    basalMetabolicRateKcal = basalMetabolicRateKcal,
    metabolicAge = metabolicAge,
    leanBodyMassKg = leanBodyMassKg,
)

private sealed interface MeasurementsLoadState {
    data object Loading : MeasurementsLoadState

    data class Loaded(
        val values: List<MeasurementEntity>,
    ) : MeasurementsLoadState
}

private fun MeasurementsLoadState.valuesOrEmpty(): List<MeasurementEntity> = when (this) {
    MeasurementsLoadState.Loading -> emptyList()
    is MeasurementsLoadState.Loaded -> values
}
