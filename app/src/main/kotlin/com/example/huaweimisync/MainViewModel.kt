package com.example.huaweimisync

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.le.ScanResult
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.huaweimisync.ble.BackgroundScanRegistrar
import com.example.huaweimisync.ble.BleSupport
import com.example.huaweimisync.ble.ManualScaleScanner
import com.example.huaweimisync.ble.ReliabilityScanService
import com.example.huaweimisync.ble.ScanWorkScheduler
import com.example.huaweimisync.data.AppSettings
import com.example.huaweimisync.data.AccountNameConflictException
import com.example.huaweimisync.data.MeasurementIngestionResult
import com.example.huaweimisync.domain.Account
import com.example.huaweimisync.domain.AccountId
import com.example.huaweimisync.domain.AccountSettings
import com.example.huaweimisync.domain.AccountUpdate
import com.example.huaweimisync.domain.CreateAccountAndAssignResult
import com.example.huaweimisync.domain.DiscardPendingAndUpdateIgnorePolicyResult
import com.example.huaweimisync.domain.DiscardPendingResult
import com.example.huaweimisync.domain.FinalizePendingResult
import com.example.huaweimisync.domain.NewAccount
import com.example.huaweimisync.domain.PendingMeasurement
import com.example.huaweimisync.domain.PendingMeasurementId
import com.example.huaweimisync.domain.isAwaitingDecisionAt
import com.example.huaweimisync.domain.PrimaryHistorySyncMode
import com.example.huaweimisync.domain.RoutingCandidate
import com.example.huaweimisync.domain.RoutingDecision
import com.example.huaweimisync.ui.accounts.AccountDeletionRequest
import com.example.huaweimisync.ui.accounts.AccountManagementAction
import com.example.huaweimisync.ui.accounts.AccountManagementUiState
import com.example.huaweimisync.ui.accounts.WeightDeltaEditorState
import com.example.huaweimisync.ui.accounts.reconcileAccountManagement
import com.example.huaweimisync.ui.accounts.reduceAccountManagement
import com.example.huaweimisync.ui.routing.MeasurementResolverUiState
import com.example.huaweimisync.ui.routing.PendingResolverCompletion
import com.example.huaweimisync.ui.routing.PendingResolverSession
import com.example.huaweimisync.ui.routing.PendingResolverSource
import com.example.huaweimisync.ui.routing.ResolverQueueState
import com.example.huaweimisync.ui.routing.UnsavedPreviewMemoryState
import com.example.huaweimisync.ui.routing.UnsavedMeasurementPreviewState
import com.example.huaweimisync.ui.routing.UnsavedPreviewSessionCoordinator
import com.example.huaweimisync.ui.routing.activeCompletionFor
import com.example.huaweimisync.ui.routing.buildResolverAccountOptions
import com.example.huaweimisync.ui.routing.isActivePendingResolverTarget
import com.example.huaweimisync.ui.routing.oldestPendingResolverTarget
import com.example.huaweimisync.worker.ExternalSyncPauseTransition
import com.example.huaweimisync.worker.MeasurementWorkSweep
import com.example.huaweimisync.worker.PendingDecisionFallback
import com.example.huaweimisync.sync.SyncResult
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class MainUiState(
    val settings: AppSettings = AppSettings(),
    val scanning: Boolean = false,
    val isExternalSyncPaused: Boolean = false,
    val healthConnect: HealthConnectPermissionsUiState = HealthConnectPermissionsUiState(),
    val healthConnectSystemManagementAvailable: Boolean = false,
    val profileEditor: ProfileEditorUiState = ProfileEditorUiState(),
    val huawei: HuaweiIntegrationUiState = HuaweiIntegrationUiState(),
    val accounts: List<Account> = emptyList(),
    val accountSettings: AccountSettings = AccountSettings(),
    val accountManagement: AccountManagementUiState = AccountManagementUiState(),
    val weightDeltaEditor: WeightDeltaEditorState = WeightDeltaEditorState(),
    val resolverQueue: ResolverQueueState = ResolverQueueState(),
    val resolver: MeasurementResolverUiState? = null,
    val unsavedPreview: UnsavedMeasurementPreviewState? = null,
) {
    val primaryAccount: Account?
        get() = accounts.firstOrNull { it.id == accountSettings.primaryAccountId }

    val canUseExternalIntegrations: Boolean
        get() = primaryAccount?.profile is com.example.huaweimisync.domain.AccountProfile.Complete

    internal val healthConnectCapabilities: HealthConnectIntegrationCapabilities
        get() = healthConnectIntegrationCapabilities(
            permissions = healthConnect,
            managementIntentAvailable = healthConnectSystemManagementAvailable,
            selectedAccountSyncEligible = canUseExternalIntegrations,
        )
}

private data class AccountsSnapshot(
    val accounts: List<Account>,
    val settings: AccountSettings,
)

private data class PendingDecisionSnapshot(
    val pendingId: PendingMeasurementId,
    val decision: RoutingDecision,
    val ignoreUnknownMeasurements: Boolean? = null,
)

private data class MainCoreState(
    val settings: AppSettings,
    val scanning: Boolean,
    val isExternalSyncPaused: Boolean,
    val healthConnect: HealthConnectPermissionsUiState,
    val huawei: HuaweiIntegrationUiState,
)

private data class RoutingUiSnapshot(
    val queue: ResolverQueueState,
    val resolver: MeasurementResolverUiState?,
    val preview: UnsavedMeasurementPreviewState?,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as MiSyncApplication).container
    private val huaweiAuthorization = HuaweiAuthorizationController(
        gateway = container.huaweiHealth,
        retryPendingHuawei = container.repository::retryPendingHuawei,
    )
    private val scanner = ManualScaleScanner(application)
    private val eventEmitter = MainUiEventEmitter()
    private val pendingDiscardUndo = PendingDiscardUndoCoordinator(eventEmitter)
    private val pendingDiscardsInProgress = mutableSetOf<PendingMeasurementId>()
    private val scanning = MutableStateFlow(false)
    private val initialHealthConnectAvailability = container.healthConnect.availability()
    private val initialHealthConnectState = if (
        initialHealthConnectAvailability == HealthConnectAvailability.AVAILABLE
    ) {
        HealthConnectPermissionsUiState.checking(container.healthConnect.permissions)
    } else {
        HealthConnectPermissionsUiState(
            availability = initialHealthConnectAvailability,
            requiredPermissions = container.healthConnect.permissions,
            grantedPermissions = emptySet(),
        )
    }
    private val healthConnect = MutableStateFlow(initialHealthConnectState)
    private val initialHuaweiState = huaweiAuthorization.initialState
    private val huawei = MutableStateFlow(initialHuaweiState)
    private val accountsSnapshot = combine(
        container.accounts.observeAccounts(),
        container.accounts.observeSettings(),
    ) { accounts, settings -> AccountsSnapshot(accounts, settings) }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        AccountsSnapshot(emptyList(), AccountSettings()),
    )
    private val pending = container.repository.observePending().map { values ->
        val now = Instant.now()
        values.filter { it.isAwaitingDecisionAt(now) }
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        emptyList(),
    )
    private val accountManagementDialog = MutableStateFlow(AccountManagementUiState())
    private val weightDeltaEditor = MutableStateFlow(WeightDeltaEditorState())
    private val resolverSession = MutableStateFlow<PendingResolverSession?>(null)
    private val notificationPermissionGranted = MutableStateFlow(
        container.pendingMeasurementNotifications.areNotificationsAllowed(),
    )
    private val resolverOperationInProgress = MutableStateFlow(false)
    private val pendingDecision = MutableStateFlow<PendingDecisionSnapshot?>(null)
    private val pendingForNewAccount = MutableStateFlow<PendingResolverSession?>(null)
    private val unsavedPreviewSession = UnsavedPreviewSessionCoordinator()
    private val externalSyncPaused = container.profileStore.settings
        .externalSyncPausedState()
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            isExternalSyncPaused(
                container.profileStore.externalSyncPausedUntilEpochMillis,
                System.currentTimeMillis(),
            ),
        )

    val events = eventEmitter.events

    private val coreState = combine(
        container.profileStore.settings,
        scanning,
        externalSyncPaused,
        healthConnect,
        huawei,
    ) { settings, isScanning, isSyncPaused, healthConnectState, huaweiState ->
        MainCoreState(
            settings = settings,
            scanning = isScanning,
            isExternalSyncPaused = isSyncPaused,
            healthConnect = healthConnectState,
            huawei = huaweiState,
        )
    }
    private val accountManagement = combine(
        accountsSnapshot,
        accountManagementDialog,
    ) { snapshot, dialog ->
        reconcileAccountManagement(
            state = dialog,
            accounts = snapshot.accounts,
            primaryAccountId = snapshot.settings.primaryAccountId,
        )
    }
    private val resolverQueue = combine(
        pending,
        resolverSession,
        notificationPermissionGranted,
    ) { pendingValues, session, notificationsGranted ->
        ResolverQueueState.from(
            pending = pendingValues,
            selectedPendingId = session?.pendingId,
            notificationPermissionGranted = notificationsGranted,
        )
    }
    private val routingUi = combine(
        resolverQueue,
        accountsSnapshot,
        pendingDecision,
        resolverOperationInProgress,
        unsavedPreviewSession.active,
    ) { queue, accounts, decision, operation, previewSession ->
        val preview = previewSession?.state
        val current = queue.selected
        val resolver = if (
            queue.isResolverVisible && current != null && preview == null
        ) {
            val candidates = decision
                ?.takeIf { it.pendingId == current.id }
                ?.decision
                ?.routingCandidates()
                .orEmpty()
            MeasurementResolverUiState(
                pending = current,
                accountOptions = buildResolverAccountOptions(
                    accounts = accounts.accounts,
                    primaryAccountId = accounts.settings.primaryAccountId,
                    candidates = candidates,
                ),
                ignoreUnknownMeasurements = decision
                    ?.takeIf { it.pendingId == current.id }
                    ?.ignoreUnknownMeasurements,
                operationInProgress = operation,
            )
        } else {
            null
        }
        RoutingUiSnapshot(queue, resolver, preview)
    }

    val uiState: StateFlow<MainUiState> = combine(
        coreState,
        accountsSnapshot,
        accountManagement,
        weightDeltaEditor,
        routingUi,
    ) { core, accountSnapshot, management, deltaEditor, routing ->
        MainUiState(
            settings = core.settings,
            scanning = core.scanning,
            isExternalSyncPaused = core.isExternalSyncPaused,
            healthConnect = core.healthConnect,
            huawei = core.huawei,
            accounts = accountSnapshot.accounts,
            accountSettings = accountSnapshot.settings,
            accountManagement = management,
            weightDeltaEditor = deltaEditor,
            resolverQueue = routing.queue,
            resolver = routing.resolver,
            unsavedPreview = routing.preview,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        MainUiState(
            resolverQueue = ResolverQueueState(
                notificationPermissionGranted = notificationPermissionGranted.value,
            ),
        ),
    )

    val huaweiConfigured: Boolean get() = container.huaweiHealth.isConfigured
    val huaweiAvailableInBuild: Boolean get() = container.huaweiHealth.isAvailableInBuild
    val healthConnectAvailable: Boolean get() = container.healthConnect.isAvailable()
    val healthConnectPermissions: Set<String> get() = container.healthConnect.permissions

    init {
        viewModelScope.launch {
            accountsSnapshot.collectLatest { snapshot ->
                accountManagementDialog.value = reconcileAccountManagement(
                    state = accountManagementDialog.value,
                    accounts = snapshot.accounts,
                    primaryAccountId = snapshot.settings.primaryAccountId,
                )
            }
        }
        viewModelScope.launch {
            accountsSnapshot.collectLatest { snapshot ->
                if (!weightDeltaEditor.value.isSaving) {
                    weightDeltaEditor.value = WeightDeltaEditorState.from(
                        snapshot.settings.weightDeltaKg,
                    )
                }
            }
        }
        viewModelScope.launch {
            pending.collectLatest { values ->
                val head = values.firstOrNull()
                val selectedId = resolverSession.value?.pendingId
                if (selectedId != null && values.none { it.id == selectedId }) {
                    resolverSession.value = null
                }
                if (head == null) {
                    pendingDecision.value = null
                } else {
                    pendingDecision.value = pendingDecision.value?.takeIf { decision ->
                        values.any { it.id == decision.pendingId }
                    }
                }
                unsavedPreviewSession.retainAvailable(values.mapTo(mutableSetOf()) { it.id })
                val createPendingSession = pendingForNewAccount.value
                if (
                    createPendingSession != null &&
                    values.none { it.id == createPendingSession.pendingId }
                ) {
                    pendingForNewAccount.value = null
                    accountManagementDialog.value = accountManagementDialog.value.let { dialog ->
                        if (dialog.editor?.editingAccountId == null) {
                            dialog.copy(editor = null)
                        } else {
                            dialog
                        }
                    }
                }
            }
        }
        viewModelScope.launch {
            combine(
                pending,
                accountsSnapshot,
                resolverSession,
            ) { pendingValues, snapshot, session ->
                val routingTargetId = session?.pendingId?.takeIf { id ->
                    pendingValues.any { it.id == id }
                } ?: pendingValues.firstOrNull()?.id
                routingTargetId to snapshot
            }.collectLatest { (pendingId, _) ->
                if (pendingId == null) {
                    pendingDecision.value = null
                } else {
                    refreshRoutingDecision(pendingId)
                }
            }
        }
        viewModelScope.launch {
            container.pendingMeasurementNotifications.notificationDeniedFallback.collectLatest {
                if (it is PendingDecisionFallback.ShowOnForeground) {
                    notificationPermissionGranted.value = false
                }
            }
        }
        onForeground()
    }

    fun onAccountManagementAction(action: AccountManagementAction) {
        val current = reconcileAccountManagement(
            state = accountManagementDialog.value,
            accounts = accountsSnapshot.value.accounts,
            primaryAccountId = accountsSnapshot.value.settings.primaryAccountId,
        )
        accountManagementDialog.value = reduceAccountManagement(current, action)
        if (action == AccountManagementAction.DialogDismissed) {
            pendingForNewAccount.value = null
        }
    }

    fun createAccount(account: NewAccount) = runAccountOperation {
        val pendingSession = pendingForNewAccount.value
        if (pendingSession == null) {
            val created = container.accounts.createAccount(account)
            if (accountsSnapshot.value.settings.primaryAccountId == null) {
                container.selectedAccountId.value = created.id
            }
            finishAccountOperation("Аккаунт «${created.displayName}» создан")
            return@runAccountOperation
        }
        val completion = requireNotNull(
            pendingSession.completionFor(pendingSession.pendingId),
        )
        when (
            val result = container.repository.createAccountAndAssignPending(
                pendingSession.pendingId,
                account,
            )
        ) {
            is CreateAccountAndAssignResult.Created -> {
                pendingForNewAccount.value = null
                container.selectedAccountId.value = result.account.id
                completePendingResolution(completion)
                finishAccountOperation(
                    "Аккаунт «${result.account.displayName}» создан, измерение назначено",
                )
            }
            is CreateAccountAndAssignResult.NameConflict -> failAccountOperation(
                "Аккаунт с таким именем уже существует",
            )
            CreateAccountAndAssignResult.PendingNotFound,
            is CreateAccountAndAssignResult.AlreadyFinalized,
            -> {
                pendingForNewAccount.value = null
                completePendingResolution(completion)
                finishAccountOperation("Измерение уже обработано")
            }
        }
    }

    fun updateAccount(account: AccountUpdate) = runAccountOperation {
        val updated = container.accounts.updateAccount(account)
        finishAccountOperation("Аккаунт «${updated.displayName}» сохранён")
    }

    fun setPrimaryAccount(accountId: AccountId, mode: PrimaryHistorySyncMode) =
        runAccountOperation {
            container.accounts.setPrimaryAccount(accountId, mode)
            container.selectedAccountId.value = accountId
            finishAccountOperation("Основной аккаунт изменён")
        }

    fun deleteAccount(accountId: AccountId) = runAccountOperation {
        container.accounts.deleteAccount(accountId)
        if (container.selectedAccountId.value == accountId) {
            container.selectedAccountId.value = null
        }
        finishAccountOperation("Аккаунт и его локальная история удалены")
    }

    fun deletePrimaryAccount(request: AccountDeletionRequest) = runAccountOperation {
        container.accounts.deletePrimaryWithReplacement(
            primaryAccountId = request.accountId,
            replacementAccountId = request.replacementAccountId,
            historySyncMode = request.historySyncMode,
        )
        container.selectedAccountId.value = request.replacementAccountId
        finishAccountOperation("Основной аккаунт и его локальная история удалены")
    }

    fun updateWeightDeltaEditor(state: WeightDeltaEditorState) {
        if (!weightDeltaEditor.value.isSaving) weightDeltaEditor.value = state
    }

    fun saveWeightDelta(weightDeltaKg: Double) = viewModelScope.launch {
        if (!weightDeltaEditor.value.canSave) return@launch
        weightDeltaEditor.value = weightDeltaEditor.value.copy(isSaving = true)
        runCatching { container.accounts.updateWeightDeltaKg(weightDeltaKg) }
            .onSuccess {
                weightDeltaEditor.value = WeightDeltaEditorState.from(weightDeltaKg)
                showMessage("Дельта распознавания сохранена")
            }
            .onFailure {
                weightDeltaEditor.value = weightDeltaEditor.value.copy(isSaving = false)
                showMessage(it.userFacingMessage("Не удалось сохранить дельту"))
            }
    }

    fun setIgnoreUnknownMeasurements(enabled: Boolean) = viewModelScope.launch {
        try {
            container.accounts.updateIgnoreUnknownMeasurements(enabled)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            showMessage(error.userFacingMessage("Не удалось сохранить настройку"))
        }
    }

    fun openResolver() {
        val observedPending = pending.value
        if (observedPending.isNotEmpty()) {
            selectPendingForResolver(
                pendingId = ResolverQueueState.from(observedPending).pending.first().id,
                source = PendingResolverSource.EXTERNAL,
            )
            return
        }
        viewModelScope.launch {
            try {
                oldestPendingResolverTarget(
                    observedPending = observedPending,
                    durablePendingSnapshots = container.repository.observePending(),
                )?.let { pendingId ->
                    selectPendingForResolver(pendingId, PendingResolverSource.EXTERNAL)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                showMessage(error.userFacingMessage("Не удалось открыть ожидающее измерение"))
            }
        }
    }

    fun openResolverFromQueue(pendingId: PendingMeasurementId) {
        if (pending.value.any { it.id == pendingId }) {
            selectPendingForResolver(pendingId, PendingResolverSource.PENDING_QUEUE)
        }
    }

    fun resolveLater() {
        resolverSession.value = null
    }

    fun choosePendingAccount(pendingId: PendingMeasurementId, accountId: AccountId) =
        viewModelScope.launch {
            val session = resolverSession.value ?: return@launch
            val completion = session.completionFor(pendingId) ?: return@launch
            if (
                !isActivePendingResolverTarget(
                    pending = pending.value,
                    selectedPendingId = session.pendingId,
                    requestedPendingId = pendingId,
                ) || resolverOperationInProgress.value
            ) {
                return@launch
            }
            resolverOperationInProgress.value = true
            try {
                when (container.repository.finalizePending(pendingId, accountId)) {
                    is FinalizePendingResult.Finalized -> {
                        completePendingResolution(completion)
                        showMessage("Измерение назначено аккаунту")
                    }
                    is FinalizePendingResult.AlreadyFinalized -> {
                        completePendingResolution(completion)
                        showMessage("Измерение уже назначено")
                    }
                    FinalizePendingResult.ProfileIncomplete ->
                        showMessage("Сначала заполните профиль выбранного аккаунта")
                    FinalizePendingResult.AccountNotFound -> showMessage("Аккаунт уже удалён")
                    FinalizePendingResult.PendingNotFound -> {
                        completePendingResolution(completion)
                        showMessage("Измерение уже обработано")
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                showMessage(error.userFacingMessage("Не удалось назначить измерение"))
            } finally {
                resolverOperationInProgress.value = false
            }
        }

    fun startCreateAccountForPending(pendingId: PendingMeasurementId) {
        val session = resolverSession.value ?: return
        if (session.completionFor(pendingId) == null ||
            !isActivePendingResolverTarget(pending.value, session.pendingId, pendingId)
        ) {
            return
        }
        pendingForNewAccount.value = session
        resolverSession.value = null
        onAccountManagementAction(AccountManagementAction.AddRequested)
    }

    fun showPendingWithoutSaving(pendingId: PendingMeasurementId) {
        val session = resolverSession.value?.takeIf { it.pendingId == pendingId } ?: return
        showPendingWithoutSaving(pendingId, session)
    }

    fun showPendingWithoutSavingFromQueue(pendingId: PendingMeasurementId) {
        showPendingWithoutSaving(
            pendingId = pendingId,
            session = PendingResolverSession(pendingId, PendingResolverSource.PENDING_QUEUE),
        )
    }

    fun updateResolverIgnoreUnknownMeasurements(
        pendingId: PendingMeasurementId,
        enabled: Boolean,
    ) {
        val current = pendingDecision.value ?: return
        if (
            current.pendingId == pendingId &&
            current.decision === RoutingDecision.NoMatch &&
            current.ignoreUnknownMeasurements != null
        ) {
            pendingDecision.value = current.copy(ignoreUnknownMeasurements = enabled)
        }
    }

    fun deletePendingFromResolver(pendingId: PendingMeasurementId) = viewModelScope.launch {
        val completion = resolverSession.value?.activeCompletionFor(
            pending = pending.value,
            requestedPendingId = pendingId,
        ) ?: return@launch
        if (resolverOperationInProgress.value) return@launch

        resolverOperationInProgress.value = true
        try {
            val ignoreUnknownMeasurements = pendingDecision.value?.takeIf { decision ->
                decision.pendingId == pendingId && decision.decision === RoutingDecision.NoMatch
            }?.ignoreUnknownMeasurements
            if (ignoreUnknownMeasurements == null) {
                discardPending(completion.pendingId, completion)
            } else {
                discardPendingAndUpdateIgnorePolicy(
                    pendingId = completion.pendingId,
                    ignoreUnknownMeasurements = ignoreUnknownMeasurements,
                    completion = completion,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } finally {
            resolverOperationInProgress.value = false
        }
    }

    fun deletePendingFromQueue(pendingId: PendingMeasurementId) = viewModelScope.launch {
        if (pending.value.none { it.id == pendingId }) return@launch
        discardPending(pendingId)
    }

    internal fun onPendingDiscardSnackbarResult(snackbarId: Long, undoRequested: Boolean) {
        val undoToken = pendingDiscardUndo.finish(snackbarId, undoRequested) ?: return
        viewModelScope.launch {
            showMessage(
                restorePendingForUndo(
                    undoToken = undoToken,
                    restorePending = container.repository::restorePending,
                ),
            )
        }
    }

    fun updateUnsavedPreview(state: UnsavedMeasurementPreviewState) {
        unsavedPreviewSession.update(state)
    }

    fun closeUnsavedPreviewAndDiscard(pendingId: PendingMeasurementId) = viewModelScope.launch {
        val completion = unsavedPreviewSession.takeClose(pendingId) ?: return@launch
        discardPending(pendingId, completion)
    }

    fun registerBackgroundScan() {
        if (container.profileStore.settings.value.scaleAddress == null) return
        showMessage(BackgroundScanRegistrar.register(getApplication()).fold(
            onSuccess = { "Фоновое BLE-сканирование включено" },
            onFailure = { it.message ?: "Не удалось включить сканирование" },
        ))
    }

    fun toggleManualScan() {
        if (scanning.value) {
            scanner.stop()
            scanning.value = false
            restoreAutomaticScanning()
            showMessage("Ручное сканирование остановлено")
            return
        }
        BackgroundScanRegistrar.unregister(getApplication())
        ReliabilityScanService.setEnabled(getApplication(), false)
        val started = scanner.start(
            onResult = ::onScanResult,
            onError = { error ->
                scanner.stop()
                scanning.value = false
                restoreAutomaticScanning()
                showMessage(error)
            },
        )
        started.onSuccess {
            scanning.value = true
            showMessage("Встаньте на весы и дождитесь финального измерения")
        }.onFailure {
            restoreAutomaticScanning()
            showMessage(it.message ?: "Не удалось запустить сканирование")
        }
    }

    fun toggleExternalSyncPause() = viewModelScope.launch {
        showMessage(container.externalSyncPause.toggle().snackbarMessage())
    }

    fun setReliabilityMode(enabled: Boolean) {
        if (!BleSupport.hasScanPermission(getApplication())) {
            showMessage("Сначала разрешите Bluetooth-сканирование")
            return
        }
        if (enabled && container.profileStore.settings.value.scaleAddress == null) {
            showMessage("Сначала выберите весы")
            return
        }
        container.profileStore.setReliabilityMode(enabled)
        ReliabilityScanService.setEnabled(getApplication(), enabled)
        showMessage(if (enabled) "Режим повышенной надёжности включён" else "Режим выключен")
    }

    fun authorizeHuawei() = viewModelScope.launch {
        val attempt = huaweiAuthorization.authorize { huawei.value = it }
        showMessage(huaweiAuthorizationMessage(attempt))
    }

    fun sendManualTest(weight: String, impedance: String) = viewModelScope.launch {
        val weightKg = weight.replace(',', '.').toDoubleOrNull()
        val impedanceOhm = impedance.toIntOrNull()
        if (weightKg == null || impedanceOhm == null ||
            weightKg !in 10.0..300.0 || impedanceOhm !in 80..3_000
        ) {
            showMessage("Проверьте вес и импеданс")
            return@launch
        }
        when (val result = container.repository.ingestTestMeasurement(weightKg, impedanceOhm)) {
            is MeasurementIngestionResult.CreatedAggregate ->
                showMessage("Тестовое измерение ожидает завершения")
            is MeasurementIngestionResult.UpdatedAggregate ->
                showMessage("Окно тестового измерения продлено")
            MeasurementIngestionResult.SuppressedFinal,
            MeasurementIngestionResult.SuppressedTombstone,
            -> showMessage("Такое тестовое измерение уже существует")
            is MeasurementIngestionResult.Assigned -> {
                val accountName = container.accounts.getAccount(result.measurement.accountId)
                    ?.displayName
                    .orEmpty()
                showMessage(
                    if (result.wasAlreadyFinalized) {
                        "Такое тестовое измерение уже обработано"
                    } else {
                        "Тестовое измерение назначено аккаунту «$accountName»"
                    },
                )
            }
            is MeasurementIngestionResult.AwaitingDecision -> {
                selectPendingForResolver(result.pending.id, PendingResolverSource.EXTERNAL)
                pendingDecision.value = PendingDecisionSnapshot(result.pending.id, result.decision)
                showMessage("Тестовое измерение ожидает выбора аккаунта")
            }
            MeasurementIngestionResult.Tombstoned,
            MeasurementIngestionResult.LegacyDuplicate,
            -> showMessage("Такое тестовое измерение уже существует")
            MeasurementIngestionResult.AutomaticallyIgnoredUnknown -> Unit
            MeasurementIngestionResult.PendingMissing -> showMessage("Измерение уже обработано")
            MeasurementIngestionResult.IgnoredNotFinal -> showMessage("Измерение ещё не завершено")
            MeasurementIngestionResult.LegacyProfileMissing ->
                showMessage("Не удалось обработать тестовое измерение")
        }
    }

    fun retry(id: String) = viewModelScope.launch {
        container.repository.retry(id)
        showMessage("Повторная отправка поставлена в очередь")
    }

    fun setMessage(text: String) {
        showMessage(text)
    }

    fun onBluetoothPermissionsReady() {
        registerBackgroundScan()
        if (container.profileStore.settings.value.reliabilityMode) {
            runCatching { ReliabilityScanService.setEnabled(getApplication(), true) }
        }
    }

    fun setNotificationPermissionGranted(granted: Boolean) {
        notificationPermissionGranted.value = granted &&
            container.pendingMeasurementNotifications.areNotificationsAllowed()
        viewModelScope.launch { container.repository.refreshPendingPresentation() }
    }

    fun onHealthConnectPermissionsChanged(allGranted: Boolean) = viewModelScope.launch {
        updateHealthConnectPermissions(
            notifyResult = true,
            grantedHint = if (allGranted) healthConnectPermissions else null,
        )
    }

    fun onHealthConnectPermissionsChanged(grantedPermissions: Set<String>) = viewModelScope.launch {
        updateHealthConnectPermissions(
            notifyResult = true,
            grantedHint = grantedPermissions,
        )
    }

    fun refreshHealthConnectQueue() = viewModelScope.launch {
        val allGranted = runCatching { container.healthConnect.hasPermissions() }.getOrDefault(false)
        if (allGranted) container.repository.retryPendingHealthConnect()
    }

    /** Re-checks Health Connect and Huawei permissions after returning to the foreground. */
    fun refreshIntegrations() = viewModelScope.launch {
        updateHealthConnectPermissions(notifyResult = false)
        huaweiAuthorization.refresh { huawei.value = it }
    }

    /** Foreground repair closes Room→WorkManager gaps and restores pending presentation. */
    fun onForeground() = viewModelScope.launch {
        notificationPermissionGranted.value =
            container.pendingMeasurementNotifications.areNotificationsAllowed()
        runCatching { MeasurementWorkSweep(container.repository).run() }
            .onFailure { showMessage("Не удалось проверить ожидающие измерения") }
        runCatching { container.repository.refreshPendingPresentation() }
        updateHealthConnectPermissions(notifyResult = false)
        huaweiAuthorization.refresh { huawei.value = it }
    }

    fun refreshHuaweiAuthorization() = viewModelScope.launch {
        huaweiAuthorization.refresh { huawei.value = it }
    }

    override fun onCleared() {
        scanner.stop()
        super.onCleared()
    }

    @SuppressLint("MissingPermission")
    private fun onScanResult(result: ScanResult) {
        if (!BleSupport.hasConnectPermission(getApplication())) return
        val payload = BleSupport.serviceData(result) ?: return
        val address = runCatching { result.device.address }.getOrNull() ?: return
        val parsed = container.packetParser.parse(payload, address) ?: return
        if (!parsed.isStableWeight) return

        val name = runCatching { result.device.name }.getOrNull()
        container.profileStore.saveScale(address, name)
        scanner.stop()
        scanning.value = false
        ScanWorkScheduler.enqueue(getApplication(), result)
        restoreAutomaticScanning()
        showMessage("Весы выбраны: ${name ?: address}. Измерение принято")
    }

    private fun restoreAutomaticScanning() {
        if (container.profileStore.settings.value.scaleAddress == null) return
        BackgroundScanRegistrar.register(getApplication())
        if (container.profileStore.settings.value.reliabilityMode) {
            runCatching { ReliabilityScanService.setEnabled(getApplication(), true) }
        }
    }

    private suspend fun updateHealthConnectPermissions(
        notifyResult: Boolean,
        grantedHint: Set<String>? = null,
    ) {
        val required = healthConnectPermissions
        val availability = container.healthConnect.availability()
        if (availability != HealthConnectAvailability.AVAILABLE) {
            healthConnect.value = HealthConnectPermissionsUiState(
                availability = availability,
                requiredPermissions = required,
                grantedPermissions = emptySet(),
            )
            if (notifyResult) showMessage("Health Connect недоступен на этом устройстве")
            return
        }

        healthConnect.value = HealthConnectPermissionsUiState.checking(
            requiredPermissions = required,
            grantedPermissions = healthConnect.value.grantedPermissions,
        )
        val granted = grantedHint ?: runCatching {
            container.healthConnect.getGrantedPermissions()
        }.getOrElse {
            healthConnect.value = HealthConnectPermissionsUiState.checkFailed(
                requiredPermissions = required,
                grantedPermissions = healthConnect.value.grantedPermissions,
            )
            if (notifyResult) showMessage("Не удалось проверить разрешения Health Connect")
            return
        }
        val snapshot = HealthConnectPermissionsUiState.snapshot(
            isAvailable = true,
            requiredPermissions = required,
            grantedPermissions = granted,
        )
        healthConnect.value = snapshot
        if (snapshot.isConnected) {
            container.repository.retryPendingHealthConnect()
            if (notifyResult) {
                showMessage("Health Connect: разрешения выданы, очередь перезапущена")
            }
        } else if (notifyResult) {
            showMessage("Health Connect: разрешены не все показатели")
        }
    }

    private fun showMessage(message: String) {
        eventEmitter.showSnackbar(message)
    }

    private suspend fun discardPending(
        pendingId: PendingMeasurementId,
        completion: PendingResolverCompletion? = null,
    ) {
        if (!pendingDiscardsInProgress.add(pendingId)) return
        try {
            when (val result = container.repository.discardPending(pendingId)) {
                is DiscardPendingResult.Discarded -> {
                    completion?.let(::completePendingResolution)
                    pendingDiscardUndo.show(result.undoToken)
                }
                is DiscardPendingResult.AlreadyFinalized,
                DiscardPendingResult.PendingNotFound,
                -> {
                    completion?.let(::completePendingResolution)
                    showMessage("Измерение уже обработано")
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            showMessage(error.userFacingMessage("Не удалось удалить измерение"))
        } finally {
            pendingDiscardsInProgress.remove(pendingId)
        }
    }

    private suspend fun discardPendingAndUpdateIgnorePolicy(
        pendingId: PendingMeasurementId,
        ignoreUnknownMeasurements: Boolean,
        completion: PendingResolverCompletion,
    ) {
        if (!pendingDiscardsInProgress.add(pendingId)) return
        try {
            when (
                val result = container.repository.discardPendingAndUpdateIgnorePolicy(
                    pendingId,
                    ignoreUnknownMeasurements,
                )
            ) {
                is DiscardPendingAndUpdateIgnorePolicyResult.Discarded -> {
                    completePendingResolution(completion)
                    result.undoToken?.let(pendingDiscardUndo::show)
                }
                is DiscardPendingAndUpdateIgnorePolicyResult.AlreadyFinalized,
                DiscardPendingAndUpdateIgnorePolicyResult.PendingNotFound,
                -> {
                    completePendingResolution(completion)
                    showMessage("Измерение уже обработано")
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            showMessage(error.userFacingMessage("Не удалось удалить измерение"))
        } finally {
            pendingDiscardsInProgress.remove(pendingId)
        }
    }

    private fun selectPendingForResolver(
        pendingId: PendingMeasurementId,
        source: PendingResolverSource,
    ) {
        resolverSession.value = PendingResolverSession(pendingId, source)
    }

    private fun showPendingWithoutSaving(
        pendingId: PendingMeasurementId,
        session: PendingResolverSession,
    ) {
        val pendingValue = pending.value.firstOrNull { it.id == pendingId } ?: return
        if (session.pendingId != pendingId) return
        resolverSession.value = null
        unsavedPreviewSession.show(
            state = UnsavedMeasurementPreviewState(pendingValue),
            resolverSession = session,
        )
    }

    private fun completePendingResolution(completion: PendingResolverCompletion) {
        if (resolverSession.value?.pendingId == completion.pendingId) {
            resolverSession.value = null
        }
        eventEmitter.pendingResolutionCompleted(
            pendingId = completion.pendingId,
            returnDestination = completion.returnDestination,
        )
    }

    private fun runAccountOperation(block: suspend () -> Unit) {
        if (accountManagementDialog.value.operationInProgress) return
        accountManagementDialog.value = accountManagementDialog.value.copy(
            operationInProgress = true,
        )
        viewModelScope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                failAccountOperation(error.userFacingMessage("Не удалось изменить аккаунт"))
            } finally {
                accountManagementDialog.value = accountManagementDialog.value.copy(
                    operationInProgress = false,
                )
            }
        }
    }

    private fun finishAccountOperation(message: String) {
        accountManagementDialog.value = AccountManagementUiState()
        showMessage(message)
    }

    private fun failAccountOperation(message: String) {
        accountManagementDialog.value = accountManagementDialog.value.copy(
            operationInProgress = false,
        )
        showMessage(message)
    }

    private suspend fun refreshRoutingDecision(pendingId: PendingMeasurementId) {
        try {
            when (val result = container.repository.routePending(pendingId)) {
                is MeasurementIngestionResult.AwaitingDecision -> {
                    if (
                        isActivePendingResolverTarget(
                            pending = pending.value,
                            selectedPendingId = resolverSession.value?.pendingId,
                            requestedPendingId = result.pending.id,
                        )
                    ) {
                        pendingDecision.value = PendingDecisionSnapshot(
                            result.pending.id,
                            result.decision,
                            ignoreUnknownMeasurements = resolverIgnoreUnknownPolicySelection(
                                decision = result.decision,
                                savedPolicy = accountsSnapshot.value.settings
                                    .ignoreUnknownMeasurements,
                            ),
                        )
                    }
                }
                is MeasurementIngestionResult.Assigned -> pendingDecision.value = null
                is MeasurementIngestionResult.CreatedAggregate,
                is MeasurementIngestionResult.UpdatedAggregate,
                MeasurementIngestionResult.SuppressedFinal,
                MeasurementIngestionResult.SuppressedTombstone,
                MeasurementIngestionResult.PendingMissing,
                MeasurementIngestionResult.AutomaticallyIgnoredUnknown,
                MeasurementIngestionResult.Tombstoned,
                MeasurementIngestionResult.IgnoredNotFinal,
                MeasurementIngestionResult.LegacyDuplicate,
                MeasurementIngestionResult.LegacyProfileMissing,
                -> pendingDecision.value = null
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            if (
                isActivePendingResolverTarget(
                    pending = pending.value,
                    selectedPendingId = resolverSession.value?.pendingId,
                    requestedPendingId = pendingId,
                )
            ) {
                pendingDecision.value = null
            }
        }
    }

    private fun huaweiAuthorizationMessage(attempt: HuaweiAuthorizationAttempt): String {
        if (attempt.confirmedState.status == HuaweiIntegrationStatus.AUTHORIZED) {
            return "Huawei Health: разрешение подтверждено, очередь перезапущена"
        }
        return when (val request = attempt.requestResult) {
            SyncResult.Success -> when (attempt.confirmedState.status) {
                HuaweiIntegrationStatus.CHECK_FAILED ->
                    "Huawei Health: не удалось подтвердить разрешение"
                else -> "Huawei Health: разрешение не выдано"
            }
            is SyncResult.Disabled -> request.message
            is SyncResult.Blocked -> request.message
            is SyncResult.Retryable -> request.message
        }
    }
}

internal const val EXTERNAL_SYNC_PAUSED_MESSAGE =
    "Внешняя синхронизация приостановлена на 5 минут"
internal const val EXTERNAL_SYNC_RESUMED_MESSAGE = "Внешняя синхронизация возобновлена"

internal fun ExternalSyncPauseTransition.snackbarMessage(): String = when (this) {
    is ExternalSyncPauseTransition.Paused -> EXTERNAL_SYNC_PAUSED_MESSAGE
    ExternalSyncPauseTransition.Resumed -> EXTERNAL_SYNC_RESUMED_MESSAGE
}

internal fun isExternalSyncPaused(pausedUntilEpochMillis: Long, nowEpochMillis: Long): Boolean =
    pausedUntilEpochMillis > nowEpochMillis

/** Emits again at the persisted deadline and rechecks wall time after every wake-up. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun Flow<AppSettings>.externalSyncPausedState(
    nowEpochMillis: () -> Long = System::currentTimeMillis,
): Flow<Boolean> = flatMapLatest { settings ->
    flow {
        val deadline = settings.externalSyncPausedUntilEpochMillis
        var paused = isExternalSyncPaused(deadline, nowEpochMillis())
        emit(paused)
        while (paused) {
            delay((deadline - nowEpochMillis()).coerceAtLeast(1L))
            paused = isExternalSyncPaused(deadline, nowEpochMillis())
        }
        if (deadline > 0L) emit(false)
    }
}.distinctUntilChanged()

internal fun resolverIgnoreUnknownPolicySelection(
    decision: RoutingDecision,
    savedPolicy: Boolean,
): Boolean? = savedPolicy.takeIf { decision === RoutingDecision.NoMatch }

private fun RoutingDecision.routingCandidates(): List<RoutingCandidate> = when (this) {
    is RoutingDecision.ChooseAccount -> candidates
    is RoutingDecision.AssignSingle -> listOf(candidate)
    is RoutingDecision.AssignPrimary -> if (differenceKg != null && medianWeightKg != null) {
        listOf(
            RoutingCandidate(
                accountId = accountId,
                differenceKg = differenceKg,
                medianWeightKg = medianWeightKg,
                stableOrder = 0,
            ),
        )
    } else {
        emptyList()
    }
    RoutingDecision.NoMatch -> emptyList()
}

private fun Throwable.userFacingMessage(fallback: String): String = when (this) {
    is AccountNameConflictException -> "Аккаунт с таким именем уже существует"
    else -> message?.takeIf(String::isNotBlank) ?: fallback
}
