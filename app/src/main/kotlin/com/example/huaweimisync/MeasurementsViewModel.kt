package com.example.huaweimisync

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.huaweimisync.data.MeasurementEntity
import com.example.huaweimisync.data.MeasurementMutationResult
import com.example.huaweimisync.data.MeasurementType
import com.example.huaweimisync.data.MeasurementValues
import com.example.huaweimisync.domain.Account
import com.example.huaweimisync.domain.PendingMeasurement
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
import com.example.huaweimisync.measurements.PendingMeasurementUiItem
import com.example.huaweimisync.measurements.MeasurementsUiEvent
import com.example.huaweimisync.measurements.MeasurementsUiState
import com.example.huaweimisync.measurements.buildMeasurementSummary
import com.example.huaweimisync.measurements.buildHomeKgChartUiState
import com.example.huaweimisync.measurements.currentLocalDates
import com.example.huaweimisync.measurements.homeChartRefreshInputs
import com.example.huaweimisync.measurements.measurementSyncPresentation
import com.example.huaweimisync.measurements.toPendingMeasurementUiItem
import com.example.huaweimisync.measurements.toPreliminaryMeasurementUiItem
import com.example.huaweimisync.measurements.toggleHomeKgChartSeriesKey
import com.example.huaweimisync.domain.AccountId
import com.example.huaweimisync.domain.PendingMeasurementId
import com.example.huaweimisync.domain.PendingMeasurementReadinessSnapshot
import com.example.huaweimisync.domain.withPendingMeasurementReadiness
import com.example.huaweimisync.ui.accounts.AccountSelectorUiState
import com.example.huaweimisync.ui.accounts.reconcileAccountSelection
import com.example.huaweimisync.ui.routing.PendingResolverReturnDestination
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalCoroutinesApi::class)
class MeasurementsViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as MiSyncApplication).container
    private val repository = container.repository
    private val profileStore = container.profileStore
    private val homeChartZoneId = ZoneId.systemDefault()
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
    private val measurements = accountSelectionScopedLoad(
        selections = accountSelector,
        accountId = AccountSelectorUiState::selectedAccountId,
        emptyValue = AccountMeasurementPresentationSource(),
        observe = { accountId, selector ->
                combine(
                    repository.observeAllEntities(accountId),
                    repository.observePreliminary(accountId),
                ) { finalized, preliminary ->
                    AccountMeasurementPresentationSource(
                        finalized = finalized,
                        preliminary = preliminary,
                        account = selector.accounts.firstOrNull { it.id == accountId },
                    )
                }
        },
    ).stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(),
        AccountSelectionScopedLoad(
            selection = AccountSelectorUiState(
                accounts = emptyList(),
                selectedAccountId = null,
                primaryAccountId = null,
                isLoading = true,
            ),
            load = AccountScopedLoad.Loading,
        ),
    )
    private val pending = repository.observeUnassignedPending()
        .withPendingMeasurementReadiness()
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(),
            PendingMeasurementReadinessSnapshot(emptyList(), Instant.EPOCH),
        )
    private val interaction = MutableStateFlow(MeasurementsInteractionState())
    private val nextOperationToken = AtomicLong()
    private val eventChannel = Channel<MeasurementsUiEvent>(Channel.BUFFERED)

    val events = eventChannel.receiveAsFlow()

    private val measurementsWithChartRefresh = homeChartRefreshInputs(
        measurements,
        profileStore.settings.map { it.homeKgChartSeriesKeys },
        currentLocalDates(
            zoneId = homeChartZoneId,
            clock = Clock.system(homeChartZoneId),
        ),
    )

    private val presentation = measurementsWithChartRefresh.mapLatest { refresh ->
        withContext(Dispatchers.Default) {
            val source = refresh.measurements.load.valuesOrEmpty()
            val items = buildMeasurementPresentationItems(
                source = source,
                protectedLatestId = repository.protectedLatestId(source.finalized),
                now = Instant.now(),
                preliminaryComposition = { preliminary, account ->
                    repository.preliminaryComposition(preliminary, account.profile)
                },
            )
            MeasurementsPresentation(
                loadState = refresh.measurements.load,
                accountSelector = refresh.measurements.selection,
                items = items,
                summary = buildMeasurementSummary(items),
                homeKgChart = buildHomeKgChartUiState(
                    measurements = items,
                    persistedActiveSeriesKeys = refresh.persistedActiveSeriesKeys,
                    zoneId = homeChartZoneId,
                    currentDate = refresh.currentDate,
                ),
            )
        }
    }
    private val presentationWithPending = combine(presentation, pending) { current, snapshot ->
        current.copy(
            pending = snapshot.measurements.map { value ->
                value.toPendingMeasurementUiItem(snapshot.observedAt)
            },
        )
    }

    val uiState = combine(
        presentationWithPending,
        interaction,
    ) { current, rawInteraction ->
        val currentInteraction = rawInteraction.normalizedFor(
            current.accountSelector.selectedAccountId,
        )
        val deletion = currentInteraction.deleteConfirmation
        val deletingId = deletion?.measurementId.takeIf { deletion?.isDeleting == true }
        val items = if (deletingId == null) current.items else current.items.map { item ->
            item.copy(isOperationInProgress = item.id == deletingId)
        }
        MeasurementsUiState(
            destination = currentInteraction.navigation.destination,
            editorOrigin = currentInteraction.navigation.editorOrigin,
            measurements = items,
            summary = current.summary,
            pendingMeasurements = current.pending,
            isLoading = current.loadState is AccountScopedLoad.Loading,
            editor = currentInteraction.editor,
            deleteConfirmation = deletion,
            homeKgChart = current.homeKgChart,
            accountSelector = current.accountSelector,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), MeasurementsUiState())

    val callbacks = MeasurementsCallbacks(
        onSummaryRequested = ::showSummary,
        onPendingQueueRequested = ::showPendingQueue,
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
        onHomeKgChartSeriesToggled = ::toggleHomeKgChartSeries,
    )

    private fun toggleHomeKgChartSeries(seriesKey: String) {
        val updatedKeys = toggleHomeKgChartSeriesKey(
            persistedKeys = profileStore.settings.value.homeKgChartSeriesKeys,
            toggledKey = seriesKey,
        ) ?: return
        profileStore.saveHomeKgChartSeriesKeys(updatedKeys)
    }

    private fun selectAccount(accountId: AccountId) {
        if (accountSelector.value.accounts.none { it.id == accountId }) return
        container.selectedAccountId.value = accountId
    }

    private fun currentInteraction(): MeasurementsInteractionState {
        val accountId = measurements.value.selection.selectedAccountId
        interaction.update { it.normalizedFor(accountId) }
        return interaction.value
    }

    private fun showSummary() {
        val current = currentInteraction()
        if (current.editor?.isSaving == true) return
        interaction.value = current.copy(
            navigation = current.navigation.showSummary(),
            editor = null,
        )
    }

    private fun showHistory() {
        val current = currentInteraction()
        if (current.editor?.isSaving == true) return
        interaction.value = current.copy(
            navigation = current.navigation.showHistory(),
            editor = null,
        )
    }

    private fun showPendingQueue() {
        val current = currentInteraction()
        if (current.editor?.isSaving == true) return
        interaction.value = current.copy(
            navigation = current.navigation.showPendingQueue(),
            editor = null,
        )
    }

    fun onPendingResolutionCompleted(
        returnDestination: PendingResolverReturnDestination,
    ) {
        val current = currentInteraction()
        interaction.value = current.copy(
            navigation = current.navigation.afterPendingResolution(returnDestination),
            editor = current.editor.takeUnless {
                returnDestination == PendingResolverReturnDestination.PENDING_QUEUE
            },
        )
    }

    private fun navigateBack() {
        val current = currentInteraction()
        if (current.navigation.destination == MeasurementsDestination.EDITOR &&
            current.editor?.isSaving == true
        ) return
        interaction.value = current.copy(
            navigation = current.navigation.back(),
            editor = current.editor.takeUnless {
                current.navigation.destination == MeasurementsDestination.EDITOR
            },
        )
    }

    private fun openEditor(id: String, origin: MeasurementEditorOrigin) {
        val current = currentInteraction()
        val value = measurements.value.load.valuesOrEmpty().finalized.firstOrNull { it.id == id } ?: run {
            showMessage("Измерение уже удалено")
            return
        }
        val nextEditor = MeasurementEditorState(
            measurementId = value.id,
            measuredAtEpochSecond = value.measuredAtEpochSecond,
            draft = if (value.measurementType == MeasurementType.WEIGHT_ONLY) {
                MeasurementEditorDraft.fromWeight(value.weightKg)
            } else {
                MeasurementEditorDraft.from(value.toUiValues())
            },
            type = value.measurementType.toUiType(),
        )
        interaction.value = current.copy(
            navigation = current.navigation.showEditor(origin),
            editor = nextEditor,
        )
    }

    private fun updateEditorField(field: MeasurementField, value: String) {
        val normalized = currentInteraction()
        interaction.update { current ->
            if (current.accountId != normalized.accountId) current else current.copy(
                editor = current.editor?.takeUnless { it.isSaving }
                    ?.copy(draft = current.editor.draft.withValue(field, value))
                    ?: current.editor,
            )
        }
    }

    private fun saveEditor(id: String, values: MeasurementUiValues) {
        val owner = currentInteraction()
        val current = owner.editor ?: return
        if (current.measurementId != id || current.isSaving) return
        val operation = operation(owner.accountId, id)
        interaction.value = owner.copy(
            editor = current.copy(isSaving = true),
            saveOperation = operation,
        )
        viewModelScope.launch {
            val result = if (current.isWeightOnly) {
                repository.updateWeightOnly(id, values.weightKg)
            } else {
                values.toDataValues()?.let { repository.update(id, it) }
                    ?: MeasurementMutationResult.Invalid
            }
            val message = when (result) {
                MeasurementMutationResult.Success -> "Локальное измерение изменено"
                MeasurementMutationResult.NotFound -> "Измерение уже удалено"
                MeasurementMutationResult.Invalid -> "Проверьте введённые значения"
                MeasurementMutationResult.ProtectedLatest -> "Не удалось изменить измерение"
            }
            if (interaction.acceptOperation(operation) { state ->
                    state.afterSaveCompletion(result)
                }
            ) {
                showMessage(message)
            }
        }
    }

    private fun dismissEditor() {
        if (currentInteraction().editor?.isSaving != true) navigateBack()
    }

    private fun requestDelete(id: String) {
        val owner = currentInteraction()
        val values = measurements.value.load.valuesOrEmpty().finalized
        if (values.none { it.id == id }) {
            showMessage("Измерение уже удалено")
            return
        }
        val operation = operation(owner.accountId, id)
        interaction.value = owner.copy(deleteRequestOperation = operation)
        viewModelScope.launch {
            when (
                val request = measurementDeleteRequest(
                    id = id,
                    measurements = values,
                    protectedLatestId = id.takeIf { repository.isProtectedLatest(it) },
                )
            ) {
                MeasurementDeleteRequest.NotFound -> completeDeleteRequest(
                    operation, null, "Измерение уже удалено",
                )
                MeasurementDeleteRequest.ProtectedLatest -> completeDeleteRequest(
                    operation, null, PROTECTED_LATEST_MESSAGE,
                )
                is MeasurementDeleteRequest.Confirm -> completeDeleteRequest(
                    operation, request.confirmation, null,
                )
            }
        }
    }

    private fun confirmDelete(id: String) {
        val owner = currentInteraction()
        val confirmation = owner.deleteConfirmation ?: return
        if (confirmation.measurementId != id || confirmation.isDeleting) return
        val operation = operation(owner.accountId, id)
        interaction.value = owner.copy(
            deleteConfirmation = confirmation.copy(isDeleting = true),
            deleteOperation = operation,
        )
        viewModelScope.launch {
            val message = measurementDeleteResultMessage(repository.delete(id))
            if (interaction.acceptOperation(operation) { current ->
                    current.copy(deleteConfirmation = null, deleteOperation = null)
                }
            ) {
                showMessage(message)
            }
        }
    }

    private fun dismissDelete() {
        val current = currentInteraction()
        if (current.deleteConfirmation?.isDeleting != true) {
            interaction.value = current.copy(deleteConfirmation = null)
        }
    }

    private fun retry(id: String) {
        currentInteraction()
        if (measurements.value.load.valuesOrEmpty().finalized.none { it.id == id }) return
        viewModelScope.launch {
            repository.retry(id)
            showMessage("Повторная отправка поставлена в очередь")
        }
    }

    private fun showMessage(message: String) {
        eventChannel.trySend(MeasurementsUiEvent.ShowSnackbar(message))
    }

    private fun operation(accountId: AccountId?, measurementId: String) =
        MeasurementOperationToken(accountId, measurementId, nextOperationToken.incrementAndGet())

    private fun completeDeleteRequest(
        operation: MeasurementOperationToken,
        confirmation: MeasurementDeleteConfirmation?,
        message: String?,
    ) {
        if (interaction.acceptOperation(operation) { current ->
                current.copy(
                    deleteConfirmation = confirmation,
                    deleteRequestOperation = null,
                )
            } && message != null
        ) {
            showMessage(message)
        }
    }

    private fun showProtectedLatestMessage() {
        showMessage(PROTECTED_LATEST_MESSAGE)
    }

    companion object {
        const val PROTECTED_LATEST_MESSAGE =
            "Последнее измерение хранится в памяти весов и будет добавлено снова, поэтому удалить его нельзя"
    }
}

internal data class MeasurementsInteractionState(
    val accountId: AccountId? = null,
    val navigation: MeasurementsNavigationState = MeasurementsNavigationState(),
    val editor: MeasurementEditorState? = null,
    val deleteConfirmation: MeasurementDeleteConfirmation? = null,
    val saveOperation: MeasurementOperationToken? = null,
    val deleteRequestOperation: MeasurementOperationToken? = null,
    val deleteOperation: MeasurementOperationToken? = null,
) {
    fun normalizedFor(accountId: AccountId?): MeasurementsInteractionState =
        if (this.accountId == accountId) this else copy(
            accountId = accountId,
            navigation = navigation.afterAccountSelectionChanged(),
            editor = null,
            deleteConfirmation = null,
            saveOperation = null,
            deleteRequestOperation = null,
            deleteOperation = null,
        )

    fun withDeleteConfirmation(
        ownerAccountId: AccountId?,
        confirmation: MeasurementDeleteConfirmation,
    ): MeasurementsInteractionState = if (accountId == ownerAccountId) {
        copy(deleteConfirmation = confirmation)
    } else {
        this
    }
}

internal data class MeasurementOperationToken(
    val accountId: AccountId?,
    val measurementId: String,
    val sequence: Long,
)

private fun MeasurementsInteractionState.owns(operation: MeasurementOperationToken): Boolean =
    accountId == operation.accountId && when (operation) {
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
    MeasurementMutationResult.ProtectedLatest,
    -> copy(editor = editor?.copy(isSaving = false), saveOperation = null)
}

internal inline fun MutableStateFlow<MeasurementsInteractionState>.acceptOperation(
    operation: MeasurementOperationToken,
    transform: (MeasurementsInteractionState) -> MeasurementsInteractionState,
): Boolean {
    while (true) {
        val current = value
        if (!current.owns(operation)) return false
        if (compareAndSet(current, transform(current))) return true
    }
}

internal fun unassignedPendingMeasurements(
    values: List<PendingMeasurement>,
): List<PendingMeasurement> = values.filter { it.provisionalAccountId == null }

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
            measuredAtEpochSecond = value.measuredAtEpochSecond,
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

internal fun MeasurementEntity.toMeasurementUiItem(
    isDeleteProtected: Boolean,
    isOperationInProgress: Boolean,
): MeasurementUiItem =
    MeasurementUiItem(
        id = id,
        presentationKey = sourcePendingId ?: id,
        finalMeasurementId = id,
        sourcePendingId = sourcePendingId?.let(::PendingMeasurementId),
        measuredAtEpochSecond = measuredAtEpochSecond,
        values = toUiValues(),
        type = measurementType.toUiType(),
        sync = measurementSyncPresentation(
            healthConnectStatus = healthConnectStatus,
            healthConnectError = healthConnectError,
            huaweiStatus = huaweiStatus,
            huaweiError = huaweiError,
        ),
        isManuallyEdited = isManuallyEdited,
        hasProfileSyncMismatch = hasProfileSyncMismatch,
        isDeleteProtected = isDeleteProtected,
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

private data class MeasurementsPresentation(
    val loadState: AccountScopedLoad<AccountMeasurementPresentationSource>,
    val accountSelector: AccountSelectorUiState,
    val items: List<MeasurementUiItem>,
    val summary: com.example.huaweimisync.measurements.MeasurementSummaryPresentation?,
    val homeKgChart: com.example.huaweimisync.measurements.HomeKgChartUiState,
    val pending: List<PendingMeasurementUiItem> = emptyList(),
)

internal data class AccountMeasurementPresentationSource(
    val finalized: List<MeasurementEntity> = emptyList(),
    val preliminary: List<PendingMeasurement> = emptyList(),
    val account: Account? = null,
)

internal fun buildMeasurementPresentationItems(
    source: AccountMeasurementPresentationSource,
    protectedLatestId: String?,
    now: Instant,
    preliminaryComposition: (PendingMeasurement, Account) ->
        com.example.huaweimisync.core.BodyComposition?,
): List<MeasurementUiItem> {
    val finalizedPendingIds = source.finalized.mapNotNull(MeasurementEntity::sourcePendingId).toSet()
    val finalizedItems = source.finalized.map { value ->
        value.toMeasurementUiItem(
            isDeleteProtected = value.id == protectedLatestId,
            isOperationInProgress = false,
        )
    }
    val preliminaryItems = source.preliminary
        .filterNot { it.id.value in finalizedPendingIds }
        .map { pending ->
            pending.toPreliminaryMeasurementUiItem(
                now = now,
                composition = source.account?.let { preliminaryComposition(pending, it) },
            )
        }
    return (finalizedItems + preliminaryItems).sortedWith(
        compareByDescending<MeasurementUiItem> { it.measuredAtEpochSecond }
            .thenByDescending { it.presentationKey },
    )
}

private fun AccountScopedLoad<AccountMeasurementPresentationSource>.valuesOrEmpty():
    AccountMeasurementPresentationSource =
    when (this) {
        AccountScopedLoad.Loading -> AccountMeasurementPresentationSource()
        is AccountScopedLoad.Loaded -> value
    }
