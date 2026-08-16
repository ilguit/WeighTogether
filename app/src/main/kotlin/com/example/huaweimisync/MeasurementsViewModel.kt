package com.example.huaweimisync

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.huaweimisync.data.MeasurementEntity
import com.example.huaweimisync.data.MeasurementMutationResult
import com.example.huaweimisync.data.MeasurementType
import com.example.huaweimisync.data.MeasurementValues
import com.example.huaweimisync.measurements.MeasurementDeleteConfirmation
import com.example.huaweimisync.measurements.MeasurementEditorDraft
import com.example.huaweimisync.measurements.MeasurementEditorOrigin
import com.example.huaweimisync.measurements.MeasurementEditorState
import com.example.huaweimisync.measurements.MeasurementField
import com.example.huaweimisync.measurements.MeasurementUiItem
import com.example.huaweimisync.measurements.MeasurementUiValues
import com.example.huaweimisync.measurements.MeasurementUiType
import com.example.huaweimisync.measurements.MeasurementsCallbacks
import com.example.huaweimisync.measurements.MeasurementsDestination
import com.example.huaweimisync.measurements.MeasurementsNavigationState
import com.example.huaweimisync.measurements.MeasurementsUiEvent
import com.example.huaweimisync.measurements.MeasurementsUiState
import com.example.huaweimisync.measurements.buildMeasurementSummary
import com.example.huaweimisync.measurements.measurementSyncPresentation
import com.example.huaweimisync.domain.AccountId
import com.example.huaweimisync.ui.accounts.AccountSelectionChangeTracker
import com.example.huaweimisync.ui.accounts.AccountSelectorUiState
import com.example.huaweimisync.ui.accounts.reconcileAccountSelection
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class MeasurementsViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as MiSyncApplication).container
    private val repository = container.repository
    private val accountSelector = combine(
        container.accounts.observeAccounts(),
        container.accounts.observeSettings(),
        container.selectedAccountId,
    ) { accounts, settings, selectedAccountId ->
        reconcileAccountSelection(accounts, selectedAccountId, settings.primaryAccountId)
    }.onEach { selector ->
        if (container.selectedAccountId.value != selector.selectedAccountId) {
            container.selectedAccountId.value = selector.selectedAccountId
        }
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        AccountSelectorUiState(
            accounts = emptyList(),
            selectedAccountId = null,
            primaryAccountId = null,
            isLoading = true,
        ),
    )
    private val measurements = accountSelector.flatMapLatest { selector ->
        selector.selectedAccountId?.let(repository::observeAllEntities) ?: flowOf(emptyList())
    }
        .map<List<MeasurementEntity>, MeasurementsLoadState>(MeasurementsLoadState::Loaded)
        .stateIn(viewModelScope, SharingStarted.Eagerly, MeasurementsLoadState.Loading)
    private val navigation = MutableStateFlow(MeasurementsNavigationState())
    private val editor = MutableStateFlow<MeasurementEditorState?>(null)
    private val deleteConfirmation = MutableStateFlow<MeasurementDeleteConfirmation?>(null)
    private val eventChannel = Channel<MeasurementsUiEvent>(Channel.BUFFERED)
    private val accountSelectionChanges = AccountSelectionChangeTracker()

    init {
        viewModelScope.launch {
            accountSelector.collectLatest { selector ->
                if (!selector.isLoading && accountSelectionChanges.update(selector.selectedAccountId)) {
                    resetAccountScopedUi()
                }
            }
        }
    }

    val events = eventChannel.receiveAsFlow()

    val uiState = combine(
        measurements,
        navigation,
        editor,
        deleteConfirmation,
        accountSelector,
    ) { loadState, currentNavigation, currentEditor, deletion, selector ->
        val values = loadState.valuesOrEmpty()
            .sortedByDescending(MeasurementEntity::measuredAtEpochMillis)
        val items = values.map { value ->
            value.toUiItem(
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
            accountSelector = selector,
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
        onAccountSelected = ::selectAccount,
    )

    private fun selectAccount(accountId: AccountId) {
        if (accountSelector.value.accounts.none { it.id == accountId }) return
        container.selectedAccountId.value = accountId
        resetAccountScopedUi()
    }

    private fun resetAccountScopedUi() {
        navigation.value = MeasurementsNavigationState()
        editor.value = null
        deleteConfirmation.value = null
    }

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
            draft = if (value.measurementType == MeasurementType.WEIGHT_ONLY) {
                MeasurementEditorDraft.fromWeight(value.weightKg)
            } else {
                MeasurementEditorDraft.from(value.toUiValues())
            },
            type = value.measurementType.toUiType(),
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
            val result = if (current.isWeightOnly) {
                repository.updateWeightOnly(id, values.weightKg)
            } else {
                values.toDataValues()?.let { repository.update(id, it) }
                    ?: MeasurementMutationResult.Invalid
            }
            when (result) {
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
            }
        }
    }

    private fun dismissEditor() {
        if (editor.value?.isSaving != true) navigateBack()
    }

    private fun requestDelete(id: String) {
        val value = measurements.value.valuesOrEmpty().firstOrNull { it.id == id } ?: run {
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

    private fun closeEditor() {
        navigation.update(MeasurementsNavigationState::back)
        editor.value = null
    }
}

private fun MeasurementEntity.toUiItem(isOperationInProgress: Boolean): MeasurementUiItem =
    MeasurementUiItem(
        id = id,
        measuredAtEpochMillis = measuredAtEpochMillis,
        values = toUiValues(),
        type = measurementType.toUiType(),
        sync = measurementSyncPresentation(
            healthConnectStatus = healthConnectStatus,
            healthConnectError = healthConnectError,
            huaweiStatus = huaweiStatus,
            huaweiError = huaweiError,
        ),
        isOperationInProgress = isOperationInProgress,
    )

private fun MeasurementEntity.toUiValues(): MeasurementUiValues = MeasurementUiValues(
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

private fun MeasurementUiValues.toDataValues(): MeasurementValues? = MeasurementValues(
    weightKg = weightKg,
    impedanceOhm = impedanceOhm ?: return null,
    bmi = bmi ?: return null,
    bodyFatPercent = bodyFatPercent ?: return null,
    bodyFatMassKg = bodyFatMassKg ?: return null,
    waterPercent = waterPercent ?: return null,
    waterMassKg = waterMassKg ?: return null,
    muscleMassKg = muscleMassKg ?: return null,
    skeletalMuscleMassKg = skeletalMuscleMassKg ?: return null,
    boneMassKg = boneMassKg ?: return null,
    proteinPercent = proteinPercent ?: return null,
    proteinMassKg = proteinMassKg ?: return null,
    visceralFatLevel = visceralFatLevel ?: return null,
    basalMetabolicRateKcal = basalMetabolicRateKcal ?: return null,
    metabolicAge = metabolicAge ?: return null,
    leanBodyMassKg = leanBodyMassKg ?: return null,
)

private fun MeasurementType.toUiType(): MeasurementUiType = when (this) {
    MeasurementType.FULL -> MeasurementUiType.FULL
    MeasurementType.WEIGHT_ONLY -> MeasurementUiType.WEIGHT_ONLY
}

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
