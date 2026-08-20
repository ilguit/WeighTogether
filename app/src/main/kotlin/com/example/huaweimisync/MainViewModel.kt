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
import com.example.huaweimisync.domain.FinalizePendingResult
import com.example.huaweimisync.domain.NewAccount
import com.example.huaweimisync.domain.PendingMeasurement
import com.example.huaweimisync.domain.PendingMeasurementId
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
import com.example.huaweimisync.ui.routing.UnsavedMeasurementPreviewState
import com.example.huaweimisync.ui.routing.buildResolverAccountOptions
import com.example.huaweimisync.ui.routing.isActivePendingResolverTarget
import com.example.huaweimisync.ui.routing.oldestPendingResolverTarget
import com.example.huaweimisync.worker.MeasurementWorkSweep
import com.example.huaweimisync.worker.PendingDecisionFallback
import com.example.huaweimisync.sync.SyncResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class MainUiState(
    val settings: AppSettings = AppSettings(),
    val scanning: Boolean = false,
    val healthConnect: HealthConnectPermissionsUiState = HealthConnectPermissionsUiState(),
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
}

private data class AccountsSnapshot(
    val accounts: List<Account>,
    val settings: AccountSettings,
)

private data class PendingDecisionSnapshot(
    val pendingId: PendingMeasurementId,
    val decision: RoutingDecision,
)

private data class MainCoreState(
    val settings: AppSettings,
    val scanning: Boolean,
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
    private val scanning = MutableStateFlow(false)
    private val initialHealthConnectState = if (container.healthConnect.isAvailable()) {
        HealthConnectPermissionsUiState.checking(container.healthConnect.permissions)
    } else {
        HealthConnectPermissionsUiState.snapshot(
            isAvailable = false,
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
    private val pending = container.repository.observePending().stateIn(
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
    private val unsavedPreview = MutableStateFlow<UnsavedMeasurementPreviewState?>(null)
    private val unsavedPreviewSession = MutableStateFlow<PendingResolverSession?>(null)

    val events = eventEmitter.events

    private val coreState = combine(
        container.profileStore.settings,
        scanning,
        healthConnect,
        huawei,
    ) { settings, isScanning, healthConnectState, huaweiState ->
        MainCoreState(
            settings = settings,
            scanning = isScanning,
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
        unsavedPreview,
    ) { queue, accounts, decision, operation, preview ->
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
                unsavedPreview.value = unsavedPreview.value?.takeIf { preview ->
                    values.any { it.id == preview.pending.id }
                }
                if (unsavedPreview.value == null) unsavedPreviewSession.value = null
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

    fun updateUnsavedPreview(state: UnsavedMeasurementPreviewState) {
        if (unsavedPreview.value?.pending?.id == state.pending.id) {
            unsavedPreview.value = state
        }
    }

    fun closeUnsavedPreviewAndDiscard(pendingId: PendingMeasurementId) = viewModelScope.launch {
        if (unsavedPreview.value?.pending?.id != pendingId) return@launch
        val completion = unsavedPreviewSession.value?.completionFor(pendingId)
        if (container.repository.discardPending(pendingId)) {
            unsavedPreview.value = null
            unsavedPreviewSession.value = null
            completion?.let(::completePendingResolution)
            showMessage("Измерение удалено без сохранения")
        } else {
            unsavedPreview.value = null
            unsavedPreviewSession.value = null
            completion?.let(::completePendingResolution)
            showMessage("Измерение уже обработано")
        }
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
        val isAvailable = container.healthConnect.isAvailable()
        if (!isAvailable) {
            healthConnect.value = HealthConnectPermissionsUiState.snapshot(
                isAvailable = false,
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
        unsavedPreviewSession.value = session
        unsavedPreview.value = UnsavedMeasurementPreviewState(pendingValue)
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
                        )
                    }
                }
                is MeasurementIngestionResult.Assigned -> pendingDecision.value = null
                MeasurementIngestionResult.PendingMissing,
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
