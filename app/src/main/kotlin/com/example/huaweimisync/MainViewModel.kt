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
import com.example.huaweimisync.ui.routing.ResolverQueueState
import com.example.huaweimisync.ui.routing.UnsavedMeasurementPreviewState
import com.example.huaweimisync.ui.routing.buildResolverAccountOptions
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
    private val resolverRequested = MutableStateFlow(false)
    private val notificationPermissionGranted = MutableStateFlow(
        container.pendingMeasurementNotifications.areNotificationsAllowed(),
    )
    private val resolverOperationInProgress = MutableStateFlow(false)
    private val pendingDecision = MutableStateFlow<PendingDecisionSnapshot?>(null)
    private val pendingForNewAccount = MutableStateFlow<PendingMeasurementId?>(null)
    private val unsavedPreview = MutableStateFlow<UnsavedMeasurementPreviewState?>(null)

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
        resolverRequested,
        notificationPermissionGranted,
    ) { pendingValues, requested, notificationsGranted ->
        ResolverQueueState.from(
            pending = pendingValues,
            isResolverVisible = requested,
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
        val current = queue.current
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
                if (head == null) {
                    resolverRequested.value = false
                    pendingDecision.value = null
                }
                unsavedPreview.value = unsavedPreview.value?.takeIf { preview ->
                    values.any { it.id == preview.pending.id }
                }
                val createPendingId = pendingForNewAccount.value
                if (createPendingId != null && values.none { it.id == createPendingId }) {
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
            combine(pending, accountsSnapshot) { pendingValues, snapshot ->
                pendingValues.firstOrNull()?.id to snapshot
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
        val pendingId = pendingForNewAccount.value
        if (pendingId == null) {
            val created = container.accounts.createAccount(account)
            if (accountsSnapshot.value.settings.primaryAccountId == null) {
                container.selectedAccountId.value = created.id
            }
            finishAccountOperation("Аккаунт «${created.displayName}» создан")
            return@runAccountOperation
        }
        when (val result = container.repository.createAccountAndAssignPending(pendingId, account)) {
            is CreateAccountAndAssignResult.Created -> {
                pendingForNewAccount.value = null
                container.selectedAccountId.value = result.account.id
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
        resolverRequested.value = true
    }

    fun resolveLater() {
        resolverRequested.value = false
    }

    fun choosePendingAccount(pendingId: PendingMeasurementId, accountId: AccountId) =
        viewModelScope.launch {
            if (pending.value.firstOrNull()?.id != pendingId || resolverOperationInProgress.value) {
                return@launch
            }
            resolverOperationInProgress.value = true
            try {
                when (container.repository.finalizePending(pendingId, accountId)) {
                    is FinalizePendingResult.Finalized ->
                        showMessage("Измерение назначено аккаунту")
                    is FinalizePendingResult.AlreadyFinalized ->
                        showMessage("Измерение уже назначено")
                    FinalizePendingResult.ProfileIncomplete ->
                        showMessage("Сначала заполните профиль выбранного аккаунта")
                    FinalizePendingResult.AccountNotFound -> showMessage("Аккаунт уже удалён")
                    FinalizePendingResult.PendingNotFound -> showMessage("Измерение уже обработано")
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
        if (pending.value.firstOrNull()?.id != pendingId) return
        pendingForNewAccount.value = pendingId
        resolverRequested.value = false
        onAccountManagementAction(AccountManagementAction.AddRequested)
    }

    fun showPendingWithoutSaving(pendingId: PendingMeasurementId) {
        val pendingValue = pending.value.firstOrNull { it.id == pendingId } ?: return
        resolverRequested.value = false
        unsavedPreview.value = UnsavedMeasurementPreviewState(pendingValue)
    }

    fun updateUnsavedPreview(state: UnsavedMeasurementPreviewState) {
        if (unsavedPreview.value?.pending?.id == state.pending.id) {
            unsavedPreview.value = state
        }
    }

    fun closeUnsavedPreviewAndDiscard(pendingId: PendingMeasurementId) = viewModelScope.launch {
        if (unsavedPreview.value?.pending?.id != pendingId) return@launch
        if (container.repository.discardPending(pendingId)) {
            unsavedPreview.value = null
            resolverRequested.value = true
            showMessage("Измерение удалено без сохранения")
        } else {
            unsavedPreview.value = null
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
                resolverRequested.value = true
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
        if (!parsed.isFinal) return

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
                    if (pending.value.firstOrNull()?.id == result.pending.id) {
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
            if (pending.value.firstOrNull()?.id == pendingId) pendingDecision.value = null
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
