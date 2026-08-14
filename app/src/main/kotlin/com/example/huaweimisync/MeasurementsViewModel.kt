package com.example.huaweimisync

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.huaweimisync.data.MeasurementEntity
import com.example.huaweimisync.data.MeasurementMutationResult
import com.example.huaweimisync.data.MeasurementValues
import com.example.huaweimisync.measurements.MeasurementDeleteConfirmation
import com.example.huaweimisync.measurements.MeasurementEditorDraft
import com.example.huaweimisync.measurements.MeasurementEditorState
import com.example.huaweimisync.measurements.MeasurementField
import com.example.huaweimisync.measurements.MeasurementUiItem
import com.example.huaweimisync.measurements.MeasurementUiValues
import com.example.huaweimisync.measurements.MeasurementsCallbacks
import com.example.huaweimisync.measurements.MeasurementsUiEvent
import com.example.huaweimisync.measurements.MeasurementsUiState
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MeasurementsViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = (application as MiSyncApplication).container.repository
    private val measurements = repository.observeAll()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val editor = MutableStateFlow<MeasurementEditorState?>(null)
    private val deleteConfirmation = MutableStateFlow<MeasurementDeleteConfirmation?>(null)
    private val eventChannel = Channel<MeasurementsUiEvent>(Channel.BUFFERED)

    val events = eventChannel.receiveAsFlow()

    val uiState = combine(measurements, editor, deleteConfirmation) { values, currentEditor, deletion ->
        MeasurementsUiState(
            measurements = values.map { value ->
                value.toUiItem(
                    isOperationInProgress = deletion?.isDeleting == true &&
                        deletion.measurementId == value.id,
                )
            },
            editor = currentEditor,
            deleteConfirmation = deletion,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MeasurementsUiState())

    val callbacks = MeasurementsCallbacks(
        onEditRequested = ::openEditor,
        onEditorFieldChanged = ::updateEditorField,
        onEditorSaveRequested = ::saveEditor,
        onEditorDismissed = ::dismissEditor,
        onDeleteRequested = ::requestDelete,
        onDeleteConfirmed = ::confirmDelete,
        onDeleteDismissed = ::dismissDelete,
        onRetryRequested = ::retry,
    )

    private fun openEditor(id: String) {
        val value = measurements.value.firstOrNull { it.id == id } ?: run {
            showMessage("Измерение уже удалено")
            return
        }
        editor.value = MeasurementEditorState(
            measurementId = value.id,
            measuredAtEpochMillis = value.measuredAtEpochMillis,
            draft = MeasurementEditorDraft.from(value.values.toUiValues()),
        )
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
                    editor.value = null
                    showMessage("Локальное измерение изменено")
                }

                MeasurementMutationResult.NotFound -> {
                    editor.value = null
                    showMessage("Измерение уже удалено")
                }

                MeasurementMutationResult.Invalid -> {
                    editor.update { it?.copy(isSaving = false) }
                    showMessage("Проверьте введённые значения")
                }
            }
        }
    }

    private fun dismissEditor() {
        if (editor.value?.isSaving != true) editor.value = null
    }

    private fun requestDelete(id: String) {
        val value = measurements.value.firstOrNull { it.id == id } ?: run {
            showMessage("Измерение уже удалено")
            return
        }
        deleteConfirmation.value = MeasurementDeleteConfirmation(
            measurementId = value.id,
            measuredAtEpochMillis = value.measuredAtEpochMillis,
            weightKg = value.weightKg,
        )
    }

    private fun confirmDelete(id: String) {
        val confirmation = deleteConfirmation.value ?: return
        if (confirmation.measurementId != id || confirmation.isDeleting) return
        deleteConfirmation.value = confirmation.copy(isDeleting = true)
        viewModelScope.launch {
            when (repository.delete(id)) {
                MeasurementMutationResult.Success -> showMessage("Локальное измерение удалено")
                MeasurementMutationResult.NotFound -> showMessage("Измерение уже удалено")
                MeasurementMutationResult.Invalid -> showMessage("Не удалось удалить измерение")
            }
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
}

private fun MeasurementEntity.toUiItem(isOperationInProgress: Boolean): MeasurementUiItem =
    MeasurementUiItem(
        id = id,
        measuredAtEpochMillis = measuredAtEpochMillis,
        values = values.toUiValues(),
        huaweiStatus = huaweiStatus,
        healthConnectStatus = healthConnectStatus,
        huaweiError = huaweiError,
        healthConnectError = healthConnectError,
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
