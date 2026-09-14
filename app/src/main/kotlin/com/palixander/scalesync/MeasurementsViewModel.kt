package com.palixander.scalesync

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.palixander.scalesync.data.MeasurementEntity
import com.palixander.scalesync.data.MeasurementMutationResult
import com.palixander.scalesync.data.MeasurementType
import com.palixander.scalesync.data.MeasurementValues
import com.palixander.scalesync.data.RatingHeightOrigin
import com.palixander.scalesync.domain.Account
import com.palixander.scalesync.domain.PendingMeasurement
import com.palixander.scalesync.measurements.MeasurementDeleteConfirmation
import com.palixander.scalesync.measurements.MeasurementEditorDraft
import com.palixander.scalesync.measurements.MeasurementEditorOrigin
import com.palixander.scalesync.measurements.MeasurementEditorState
import com.palixander.scalesync.measurements.MeasurementField
import com.palixander.scalesync.measurements.MeasurementUiItem
import com.palixander.scalesync.measurements.MeasurementUiValues
import com.palixander.scalesync.measurements.MeasurementUiType
import com.palixander.scalesync.measurements.MeasurementsCallbacks
import com.palixander.scalesync.measurements.MeasurementsDestination
import com.palixander.scalesync.measurements.MeasurementsNavigationState
import com.palixander.scalesync.measurements.PendingMeasurementUiItem
import com.palixander.scalesync.measurements.PendingClearConfirmation
import com.palixander.scalesync.measurements.MeasurementsUiEvent
import com.palixander.scalesync.measurements.MeasurementsUiState
import com.palixander.scalesync.measurements.buildMeasurementSummary
import com.palixander.scalesync.measurements.buildHomeKgChartUiState
import com.palixander.scalesync.measurements.currentLocalDates
import com.palixander.scalesync.measurements.homeChartRefreshInputs
import com.palixander.scalesync.measurements.measurementSyncPresentation
import com.palixander.scalesync.measurements.toPendingMeasurementUiItem
import com.palixander.scalesync.measurements.toPreliminaryMeasurementUiItem
import com.palixander.scalesync.measurements.toggleHomeKgChartSeriesKey
import com.palixander.scalesync.domain.AccountId
import com.palixander.scalesync.domain.PendingMeasurementId
import com.palixander.scalesync.domain.PendingMeasurementReadinessSnapshot
import com.palixander.scalesync.domain.withPendingMeasurementReadiness
import com.palixander.scalesync.core.ReferenceClassifier
import com.palixander.scalesync.core.ReferenceContext
import com.palixander.scalesync.core.chronologicalAge
import com.palixander.scalesync.ui.accounts.AccountSelectorUiState
import com.palixander.scalesync.ui.accounts.reconcileAccountSelection
import com.palixander.scalesync.ui.routing.PendingResolverReturnDestination
import com.palixander.scalesync.ui.reference.ReferencePresentationFactory
import com.palixander.scalesync.ui.reference.toReferenceContext
import com.palixander.scalesync.ui.reference.toReferenceReadings
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalCoroutinesApi::class)
class MeasurementsViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as ScaleSyncApplication).container
    private val manualRepository = com.palixander.scalesync.data.ManualWeightRepository(
        container.database, container.syncScheduler::enqueueInitial,
    )
    val manualWeight = com.palixander.scalesync.ui.manualweight.ManualWeightStateOwner(
        viewModelScope, manualRepository::save,
        onSaved = { owner, result ->
            if (owner is com.palixander.scalesync.domain.ManualWeightOwner.Human) {
                container.accountSelection.select(owner.accountId)
                showHistory()
                interaction.update { it.copy(scrollToMeasurementId = result.measurementId) }
            }
            eventChannel.trySend(MeasurementsUiEvent.ManualWeightSaved(owner, result))
        },
    )
    private val repository = container.repository
    private val profileStore = container.profileStore
    private val homeChartZoneId = ZoneId.systemDefault()
    private val referenceClassifier = ReferenceClassifier()
    private val referencePresentationFactory = ReferencePresentationFactory(application.resources)
    private val interaction = MutableStateFlow(MeasurementsInteractionState())
    private var nextOperationSequence = 0L
    private val eventChannel = Channel<MeasurementsUiEvent>(Channel.BUFFERED)
    private val accountSelection = combine(
        container.accounts.observeAccounts(),
        container.accounts.observeSettings(),
        container.accountSelection.selection,
    ) { accounts, settings, selection ->
        selection to reconcileAccountSelection(accounts, selection.accountId, settings.primaryAccountId)
    }.mapNotNull { (sourceSelection, selector) ->
        val authoritative = container.accountSelection.selectIfCurrent(
            sourceSelection,
            selector.selectedAccountId,
        )
        if (authoritative.accountId != selector.selectedAccountId) return@mapNotNull null
        interaction.update { it.normalizedFor(authoritative) }
        MeasurementAccountSelection(authoritative, selector)
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        MeasurementAccountSelection(
            selection = AccountSelection(),
            selector = AccountSelectorUiState(
                accounts = emptyList(),
                selectedAccountId = null,
                primaryAccountId = null,
                isLoading = true,
            ),
        ),
    )
    private val measurements: StateFlow<
        AccountSelectionScopedLoad<MeasurementAccountSelection, AccountMeasurementPresentationSource>
    > = accountSelectionScopedLoad(
        selections = accountSelection,
        accountId = { it.selector.selectedAccountId },
        emptyValue = AccountMeasurementPresentationSource(),
        observe = { accountId, selection ->
                combine(
                    repository.observeAllEntities(accountId),
                    repository.observePreliminary(accountId),
                ) { finalized, preliminary ->
                    AccountMeasurementPresentationSource(
                        finalized = finalized,
                        preliminary = preliminary,
                        account = selection.selector.accounts.firstOrNull { it.id == accountId },
                    )
                }
        },
    ).stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(),
        AccountSelectionScopedLoad(
            selection = MeasurementAccountSelection(
                selection = AccountSelection(),
                selector = AccountSelectorUiState(
                    accounts = emptyList(),
                    selectedAccountId = null,
                    primaryAccountId = null,
                    isLoading = true,
                ),
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
    val events = eventChannel.receiveAsFlow()

    private val finalizedMeasurements = measurements
        .withoutPreliminaryUpdates()

    private val measurementsWithChartRefresh = homeChartRefreshInputs(
        finalizedMeasurements,
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
                now = Instant.now(),
                zoneId = homeChartZoneId,
                referenceClassifier = referenceClassifier,
                referencePresentationFactory = referencePresentationFactory,
                preliminaryComposition = { preliminary, account ->
                    repository.preliminaryComposition(preliminary, account.profile)
                },
            )
            MeasurementsPresentation(
                loadState = refresh.measurements.load,
                accountSelection = refresh.measurements.selection,
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
    private val presentationWithPreliminary = mergeMeasurementPresentationUpdates(
        presentations = presentation,
        measurements = measurements,
        now = Instant::now,
        preliminaryComposition = { preliminary, account ->
            repository.preliminaryComposition(preliminary, account.profile)
        },
    )
    private val presentationWithPending = combine(presentationWithPreliminary, pending) { current, snapshot ->
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
            container.accountSelection.selection.value,
        )
        val deletion = currentInteraction.deleteConfirmation
        val deletingId = deletion?.measurementId.takeIf { deletion?.isDeleting == true }
        val items = if (deletingId == null) current.items else current.items.map { item ->
            item.copy(isOperationInProgress = item.id == deletingId)
        }
        MeasurementsUiState(
            scrollToMeasurementId = currentInteraction.scrollToMeasurementId,
            destination = currentInteraction.navigation.destination,
            editorOrigin = currentInteraction.navigation.editorOrigin,
            measurements = items,
            summary = current.summary,
            pendingMeasurements = current.pending,
            isLoading = current.loadState is AccountScopedLoad.Loading,
            editor = currentInteraction.editor,
            deleteConfirmation = deletion,
            pendingClearConfirmation = currentInteraction.pendingClearConfirmation,
            homeKgChart = current.homeKgChart,
            accountSelector = current.accountSelection.selector,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), MeasurementsUiState())

    val callbacks = MeasurementsCallbacks(
        onAddWeightRequested = {
            val selector = accountSelection.value.selector
            selector.accounts.firstOrNull { it.id == selector.selectedAccountId }?.let { account ->
                manualWeight.open(com.palixander.scalesync.domain.ManualWeightOwner.Human(account.id), account.displayName)
            }
        },
        onScrollToMeasurementHandled = { interaction.update { it.copy(scrollToMeasurementId = null) } },
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
        onPendingClearRequested = ::requestPendingClear,
        onPendingClearConfirmed = ::confirmPendingClear,
        onPendingClearDismissed = ::dismissPendingClear,
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
        if (accountSelection.value.selector.accounts.none { it.id == accountId }) return
        val selection = container.accountSelection.select(accountId)
        interaction.update { it.normalizedFor(selection) }
    }

    private fun currentInteraction(): MeasurementsInteractionState {
        val selection = container.accountSelection.selection.value
        interaction.update { it.normalizedFor(selection) }
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
        val operation = operation(owner.selection, id)
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
            }
            if (interaction.acceptOperation(operation, container.accountSelection.selection.value) { state ->
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
        val operation = operation(owner.selection, id)
        interaction.value = owner.copy(deleteRequestOperation = operation)
        viewModelScope.launch {
            when (
                val request = measurementDeleteRequest(
                    id = id,
                    measurements = values,
                )
            ) {
                MeasurementDeleteRequest.NotFound -> completeDeleteRequest(
                    operation, null, "Измерение уже удалено",
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
        val operation = operation(owner.selection, id)
        interaction.value = owner.copy(
            deleteConfirmation = confirmation.copy(isDeleting = true),
            deleteOperation = operation,
        )
        viewModelScope.launch {
            val message = measurementDeleteResultMessage(repository.delete(id))
            if (interaction.acceptOperation(operation, container.accountSelection.selection.value) { current ->
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

    private fun requestPendingClear() {
        val current = currentInteraction()
        if (current.pendingClearConfirmation?.isClearing == true) return
        val count = pending.value.measurements.size
        if (count == 0) return
        interaction.value = current.copy(
            pendingClearConfirmation = PendingClearConfirmation(count = count),
        )
    }

    private fun confirmPendingClear() {
        val current = currentInteraction()
        val confirmation = current.pendingClearConfirmation ?: return
        if (confirmation.isClearing) return
        interaction.value = current.copy(
            pendingClearConfirmation = confirmation.copy(isClearing = true, errorMessage = null),
        )
        viewModelScope.launch {
            try {
                val cleared = repository.clearUnassignedPending()
                interaction.update { state -> state.copy(pendingClearConfirmation = null) }
                showMessage(
                    if (cleared == 0) "Неназначенных измерений уже нет"
                    else "Неназначенные измерения удалены",
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                interaction.update { state ->
                    state.copy(
                        pendingClearConfirmation = state.pendingClearConfirmation?.copy(
                            isClearing = false,
                            errorMessage = "Не удалось очистить измерения. Попробуйте ещё раз.",
                        ),
                    )
                }
            }
        }
    }

    private fun dismissPendingClear() {
        val current = currentInteraction()
        if (current.pendingClearConfirmation?.isClearing != true) {
            interaction.value = current.copy(pendingClearConfirmation = null)
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

    private fun operation(selection: AccountSelection, measurementId: String) =
        MeasurementOperationToken(
            selection = selection,
            measurementId = measurementId,
            sequence = ++nextOperationSequence,
        )

    private fun completeDeleteRequest(
        operation: MeasurementOperationToken,
        confirmation: MeasurementDeleteConfirmation?,
        message: String?,
    ) {
        if (interaction.acceptOperation(operation, container.accountSelection.selection.value) { current ->
                current.copy(
                    deleteConfirmation = confirmation,
                    deleteRequestOperation = null,
                )
            } && message != null
        ) {
            showMessage(message)
        }
    }

}

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

internal fun unassignedPendingMeasurements(
    values: List<PendingMeasurement>,
): List<PendingMeasurement> = values.filter { it.provisionalAccountId == null }

internal sealed interface MeasurementDeleteRequest {
    data object NotFound : MeasurementDeleteRequest
    data class Confirm(
        val confirmation: MeasurementDeleteConfirmation,
    ) : MeasurementDeleteRequest
}

internal fun measurementDeleteRequest(
    id: String,
    measurements: List<MeasurementEntity>,
): MeasurementDeleteRequest {
    val value = measurements.firstOrNull { it.id == id } ?: return MeasurementDeleteRequest.NotFound
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
}

internal fun MeasurementEntity.toMeasurementUiItem(
    isOperationInProgress: Boolean,
    account: Account? = null,
    zoneId: ZoneId = ZoneId.systemDefault(),
    referenceClassifier: ReferenceClassifier? = null,
    referencePresentationFactory: ReferencePresentationFactory? = null,
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
        ),
        isManuallyEdited = isManuallyEdited,
        origin = origin,
        hasProfileSyncMismatch = hasProfileSyncMismatch,
        isOperationInProgress = isOperationInProgress,
    ).withReferencePresentation(
        entity = this,
        account = account,
        zoneId = zoneId,
        classifier = referenceClassifier,
        factory = referencePresentationFactory,
    )

private fun MeasurementUiItem.withReferencePresentation(
    entity: MeasurementEntity,
    account: Account?,
    zoneId: ZoneId,
    classifier: ReferenceClassifier?,
    factory: ReferencePresentationFactory?,
): MeasurementUiItem {
    if (account == null || classifier == null || factory == null || isPreliminary) return this
    val referenceContext = measurementReferenceContext(
        values = values,
        measuredAt = measuredAt,
        ratingHeightCm = entity.ratingHeightCm,
        account = account,
        zoneId = zoneId,
    )
    val readings = values.toReferenceReadings()
    val metrics = factory.createAll(
        readings = readings,
        interpretations = classifier.classifyAll(readings, referenceContext.context),
    )
    return copy(
        referenceMetrics = metrics,
        ratingHeightCm = entity.ratingHeightCm,
        referenceAge = referenceContext.age,
        hasRestoredRatingHeight = entity.ratingHeightOrigin == RatingHeightOrigin.RESTORED_CURRENT_ACCOUNT,
    )
}

internal data class MeasurementReferenceContext(
    val context: ReferenceContext,
    val age: Int?,
)

internal fun measurementReferenceContext(
    values: MeasurementUiValues,
    measuredAt: Instant,
    ratingHeightCm: Double?,
    account: Account,
    zoneId: ZoneId,
): MeasurementReferenceContext {
    val measurementDate = measuredAt.atZone(zoneId).toLocalDate()
    val birthDate = account.profile.birthDate?.takeUnless { it.isAfter(measurementDate) }
    val sex = account.profile.sex
    return MeasurementReferenceContext(
        context = values.toReferenceContext(
            measurementDate = measurementDate,
            birthDate = birthDate,
            sex = sex,
            ratingHeightCm = ratingHeightCm,
        ),
        age = if (birthDate != null && sex != null) chronologicalAge(birthDate, measurementDate) else null,
    )
}

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

internal data class MeasurementAccountSelection(
    val selection: AccountSelection,
    val selector: AccountSelectorUiState,
)

internal data class MeasurementsPresentation(
    val loadState: AccountScopedLoad<AccountMeasurementPresentationSource>,
    val accountSelection: MeasurementAccountSelection,
    val items: List<MeasurementUiItem>,
    val summary: com.palixander.scalesync.measurements.MeasurementSummaryPresentation?,
    val homeKgChart: com.palixander.scalesync.measurements.HomeKgChartUiState,
    val pending: List<PendingMeasurementUiItem> = emptyList(),
)

/** Never combines a newly selected account with presentation derived for an older generation. */
internal fun MeasurementsPresentation.loadingFor(
    selection: MeasurementAccountSelection,
): MeasurementsPresentation = copy(
    loadState = AccountScopedLoad.Loading,
    accountSelection = selection,
    items = emptyList(),
    summary = null,
    homeKgChart = homeKgChart.copy(
        series = homeKgChart.series.map { series -> series.copy(points = emptyList()) },
    ),
)

internal fun mergeMeasurementPresentationUpdates(
    presentations: Flow<MeasurementsPresentation>,
    measurements: Flow<
        AccountSelectionScopedLoad<MeasurementAccountSelection, AccountMeasurementPresentationSource>
    >,
    now: () -> Instant,
    preliminaryComposition: (PendingMeasurement, Account) ->
        com.palixander.scalesync.core.BodyComposition?,
): Flow<MeasurementsPresentation> = combine(presentations, measurements) { current, latest ->
    if (current.accountSelection.selection != latest.selection.selection) {
        return@combine current.loadingFor(latest.selection)
    }
    val source = latest.load.valuesOrEmpty()
    val items = mergePreliminaryMeasurementPresentationItems(
        finalizedItems = current.items,
        preliminary = source.preliminary,
        account = source.account,
        now = now(),
        preliminaryComposition = preliminaryComposition,
    )
    current.copy(
        loadState = latest.load,
        accountSelection = latest.selection,
        items = items,
        summary = buildMeasurementSummary(items),
    )
}

internal data class AccountMeasurementPresentationSource(
    val finalized: List<MeasurementEntity> = emptyList(),
    val preliminary: List<PendingMeasurement> = emptyList(),
    val account: Account? = null,
)

/**
 * Preliminary rows can change several times during one BLE aggregation window. Keep those
 * updates out of the finalized history/chart pipeline so they cannot repeatedly rebuild it.
 */
internal fun <S> kotlinx.coroutines.flow.Flow<
    AccountSelectionScopedLoad<S, AccountMeasurementPresentationSource>,
>.withoutPreliminaryUpdates() = map { scoped ->
    scoped.copy(
        load = when (val load = scoped.load) {
            AccountScopedLoad.Loading -> AccountScopedLoad.Loading
            is AccountScopedLoad.Loaded -> AccountScopedLoad.Loaded(
                load.value.copy(preliminary = emptyList()),
            )
        },
    )
}.distinctUntilChanged()

internal fun buildMeasurementPresentationItems(
    source: AccountMeasurementPresentationSource,
    now: Instant,
    zoneId: ZoneId = ZoneId.systemDefault(),
    referenceClassifier: ReferenceClassifier? = null,
    referencePresentationFactory: ReferencePresentationFactory? = null,
    preliminaryComposition: (PendingMeasurement, Account) ->
        com.palixander.scalesync.core.BodyComposition?,
): List<MeasurementUiItem> {
    val finalizedPendingIds = source.finalized.mapNotNull(MeasurementEntity::sourcePendingId).toSet()
    val finalizedItems = source.finalized.map { value ->
        value.toMeasurementUiItem(
            isOperationInProgress = false,
            account = source.account,
            zoneId = zoneId,
            referenceClassifier = referenceClassifier,
            referencePresentationFactory = referencePresentationFactory,
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

internal fun mergePreliminaryMeasurementPresentationItems(
    finalizedItems: List<MeasurementUiItem>,
    preliminary: List<PendingMeasurement>,
    account: Account?,
    now: Instant,
    preliminaryComposition: (PendingMeasurement, Account) ->
        com.palixander.scalesync.core.BodyComposition?,
): List<MeasurementUiItem> {
    val finalizedPendingIds = finalizedItems.mapNotNull(MeasurementUiItem::sourcePendingId).toSet()
    val preliminaryItems = preliminary
        .filterNot { it.id in finalizedPendingIds }
        .map { pending ->
            pending.toPreliminaryMeasurementUiItem(
                now = now,
                composition = account?.let { preliminaryComposition(pending, it) },
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
