package com.palixander.scalesync

import android.annotation.SuppressLint
import android.app.Application
import android.net.Uri
import android.os.SystemClock
import androidx.work.WorkManager
import com.palixander.scalesync.backup.BackupImportMode
import com.palixander.scalesync.backup.BackupImportPreview
import android.bluetooth.le.ScanResult
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.palixander.scalesync.ble.BackgroundScanRegistrar
import com.palixander.scalesync.ble.BleSupport
import com.palixander.scalesync.ble.ManualScaleScanner
import com.palixander.scalesync.ble.ReliabilityScanService
import com.palixander.scalesync.ble.ScanWorkScheduler
import com.palixander.scalesync.ble.ForgetScaleCoordinator
import com.palixander.scalesync.data.AppSettings
import com.palixander.scalesync.data.AccountNameConflictException
import com.palixander.scalesync.data.ExternalSyncDestination
import com.palixander.scalesync.data.MeasurementIngestionResult
import com.palixander.scalesync.domain.Account
import com.palixander.scalesync.domain.AccountId
import com.palixander.scalesync.domain.AccountSettings
import com.palixander.scalesync.domain.AccountUpdate
import com.palixander.scalesync.domain.CreateAccountAndAssignResult
import com.palixander.scalesync.domain.DiscardPendingAndUpdateIgnorePolicyResult
import com.palixander.scalesync.domain.DiscardPendingResult
import com.palixander.scalesync.domain.FinalizePendingResult
import com.palixander.scalesync.domain.NewAccount
import com.palixander.scalesync.domain.PetSpecies
import com.palixander.scalesync.domain.PetId
import com.palixander.scalesync.domain.PetUpdate
import com.palixander.scalesync.domain.PetWithLatestWeight
import com.palixander.scalesync.domain.PendingMeasurement
import com.palixander.scalesync.domain.PendingMeasurementId
import com.palixander.scalesync.domain.isAwaitingDecisionAt
import com.palixander.scalesync.domain.withPendingMeasurementReadiness
import com.palixander.scalesync.domain.PrimaryHistorySyncMode
import com.palixander.scalesync.domain.ProfileHistoryUpdateMode
import com.palixander.scalesync.domain.ProfileUpdateAttemptResult
import com.palixander.scalesync.domain.RoutingCandidate
import com.palixander.scalesync.domain.RoutingDecision
import com.palixander.scalesync.ui.accounts.AccountDeletionRequest
import com.palixander.scalesync.ui.accounts.AccountManagementAction
import com.palixander.scalesync.ui.accounts.AccountManagementUiState
import com.palixander.scalesync.ui.accounts.WeightDeltaEditorState
import com.palixander.scalesync.ui.accounts.reconcileAccountManagement
import com.palixander.scalesync.ui.accounts.reduceAccountManagement
import com.palixander.scalesync.ui.text.UiText
import com.palixander.scalesync.ui.text.uiText
import com.palixander.scalesync.ui.routing.MeasurementResolverUiState
import com.palixander.scalesync.ui.routing.PendingResolverCompletion
import com.palixander.scalesync.ui.routing.PendingResolverSession
import com.palixander.scalesync.ui.routing.PendingResolverSource
import com.palixander.scalesync.ui.routing.ResolverQueueState
import com.palixander.scalesync.ui.routing.UnsavedPreviewMemoryState
import com.palixander.scalesync.ui.routing.UnsavedMeasurementPreviewState
import com.palixander.scalesync.ui.routing.UnsavedPreviewSessionCoordinator
import com.palixander.scalesync.ui.routing.calculateUnsavedPreview as calculateUnsavedPreviewResult
import com.palixander.scalesync.ui.routing.activeCompletionFor
import com.palixander.scalesync.ui.routing.buildResolverAccountOptions
import com.palixander.scalesync.ui.routing.isActivePendingResolverTarget
import com.palixander.scalesync.ui.profiles.PetHistoryStateOwner
import com.palixander.scalesync.ui.profiles.PetHistoryReferenceDependencies
import com.palixander.scalesync.ui.profiles.PetHistoryReferenceLoader
import com.palixander.scalesync.ui.routing.oldestPendingResolverTarget
import com.palixander.scalesync.ui.routing.pendingForResolverLifecycle
import com.palixander.scalesync.worker.ExternalSyncPauseTransition
import com.palixander.scalesync.worker.MeasurementWorkSweep
import com.palixander.scalesync.worker.PendingDecisionFallback
import com.palixander.scalesync.sync.SyncResult
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
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

internal const val SCALE_REFRESH_TIMEOUT_MILLIS = 7_000L
internal const val SCALE_REFRESH_UNAVAILABLE_MESSAGE = "Весы недоступны"

data class MainUiState(
    val settings: AppSettings = AppSettings(),
    val scanning: Boolean = false,
    val isRefreshing: Boolean = false,
    val scaleAvailability: ScaleAvailability = ScaleAvailability.AVAILABLE,
    val scaleScanError: String? = null,
    val isExternalSyncPaused: Boolean = false,
    val healthConnect: HealthConnectPermissionsUiState = HealthConnectPermissionsUiState(),
    val healthConnectSystemManagementAvailable: Boolean = false,
    val profileEditor: ProfileEditorUiState = ProfileEditorUiState(),
    val profilesLoaded: Boolean = false,
    val accounts: List<Account> = emptyList(),
    val accountSettings: AccountSettings = AccountSettings(),
    val accountManagement: AccountManagementUiState = AccountManagementUiState(),
    val weightDeltaEditor: WeightDeltaEditorState = WeightDeltaEditorState(),
    val resolverQueue: ResolverQueueState = ResolverQueueState(),
    val resolver: MeasurementResolverUiState? = null,
    val unsavedPreview: UnsavedMeasurementPreviewState? = null,
    val backup: BackupUiState = BackupUiState(),
    val pets: List<PetWithLatestWeight> = emptyList(),
    val petMeasurement: PetMeasurementUiState = PetMeasurementUiState.Idle,
    val petManagement: PetManagementUiState = PetManagementUiState(),
    val destructiveActionInProgress: DestructiveSettingsAction? = null,
) {
    val primaryAccount: Account?
        get() = accounts.firstOrNull { it.id == accountSettings.primaryAccountId }

    val canUseExternalIntegrations: Boolean
        get() = primaryAccount?.profile is com.palixander.scalesync.domain.AccountProfile.Complete

    internal val healthConnectCapabilities: HealthConnectIntegrationCapabilities
        get() = healthConnectIntegrationCapabilities(
            permissions = healthConnect,
            managementIntentAvailable = healthConnectSystemManagementAvailable,
            selectedAccountSyncEligible = canUseExternalIntegrations,
        )
}

enum class DestructiveSettingsAction { HEALTH_CONNECT, SCALE }

private data class AccountsSnapshot(
    val accounts: List<Account>,
    val settings: AccountSettings,
    val loaded: Boolean,
)

private data class PetsSnapshot(
    val pets: List<PetWithLatestWeight>,
    val loaded: Boolean,
)

data class BackupUiState(
    val inProgress: Boolean = false,
    val preview: BackupImportPreview? = null,
    val replaceConfirmationRequested: Boolean = false,
)

private data class PendingDecisionSnapshot(
    val pendingId: PendingMeasurementId,
    val decision: RoutingDecision,
    val ignoreUnknownMeasurements: Boolean? = null,
)

private data class MainCoreState(
    val settings: AppSettings,
    val scanning: Boolean,
    val isRefreshing: Boolean,
    val scaleAvailability: ScaleAvailability,
    val scaleScanError: String?,
    val isExternalSyncPaused: Boolean,
    val healthConnect: HealthConnectPermissionsUiState,
    val destructiveActionInProgress: DestructiveSettingsAction?,
)

private data class ScaleScanningState(
    val settings: AppSettings,
    val scanning: Boolean,
    val isRefreshing: Boolean,
    val availability: ScaleAvailability,
    val error: String?,
)

private data class RoutingUiSnapshot(
    val queue: ResolverQueueState,
    val resolver: MeasurementResolverUiState?,
    val preview: UnsavedMeasurementPreviewState?,
)

class MainViewModel @JvmOverloads constructor(
    application: Application,
    private val currentDate: () -> LocalDate = { LocalDate.now() },
) : AndroidViewModel(application) {
    private val container = (application as ScaleSyncApplication).container

    private val petHistoryReferenceLoader = PetHistoryReferenceLoader(viewModelScope, Dispatchers.IO) {
        PetHistoryReferenceDependencies.bundled()
    }
    private val petHistoryOwners = mutableMapOf<PetId, PetHistoryStateOwner>()
    fun petHistoryStateOwner(petId: PetId): PetHistoryStateOwner = petHistoryOwners.getOrPut(petId) {
        PetHistoryStateOwner(
            initialPetId = petId,
            repository = container.pets,
            parentScope = viewModelScope,
            referenceDependencies = petHistoryReferenceLoader::load,
        )
    }
    private val scanner = ManualScaleScanner(application)
    private val refreshScanner = ManualScaleScanner(application)
    private val petScanner = ManualScaleScanner(application)
    private val eventEmitter = MainUiEventEmitter()
    private val pendingDiscardUndo = PendingDiscardUndoCoordinator(eventEmitter)
    private val pendingDiscardsInProgress = mutableSetOf<PendingMeasurementId>()
    private val scanning = MutableStateFlow(false)
    private val scaleAvailability = MutableStateFlow(currentScaleAvailability())
    private val scaleScanError = MutableStateFlow<String?>(null)
    private val refreshing = MutableStateFlow(false)
    private val petMeasurement = MutableStateFlow<PetMeasurementUiState>(PetMeasurementUiState.Idle)
    private val petManagement = MutableStateFlow(PetManagementUiState())
    private val petManagementSessionIds = AtomicLong(0L)
    private val petBreedCatalog = PetBreedCatalog()
    private val petMeasurementStartup = PetMeasurementStartupGuard()
    private val petMeasurementCreation = PetMeasurementCreationGuard()
    private var petMeasurementStartupJob: Job? = null
    private val backup = MutableStateFlow(BackupUiState())
    private val scaleRefresh = ScaleRefreshCoordinator(
        setRefreshing = { refreshing.value = it },
        stopScanner = refreshScanner::stop,
        restoreAutomaticScanning = ::restoreAutomaticScanning,
        showMessage = ::showMessage,
    )
    private val petMeasurementCoordinator = PetMeasurementCoordinator(
        setState = { petMeasurement.value = it },
        stopScanner = petScanner::stop,
        restoreAutomaticScanning = ::restoreAutomaticScanning,
        showMessage = ::showMessage,
        acquirePetSessionGate = { selectedAddress ->
            acquirePetIngestionSession(
                selectedAddress = selectedAddress,
                activateGate = {
                    val lease = container.petMeasurementIngestionGate.activate()
                    PetIngestionSession(
                        registerPetPacket = lease::registerPetPacket,
                        release = lease::release,
                        protectPetPacket = lease::protectPetPacket,
                        protectPetReading = lease::protectPetReading,
                    )
                },
                lookupBaseline = { address ->
                    container.repository.latestAcceptedStableMeasurement(address)?.let {
                        PetStableReadingBaseline(
                            address = it.deviceAddress,
                            weightKg = it.weightKg,
                            rawIdentity = petReadingRawIdentity(it.rawPayload),
                        )
                    }
                },
            )
        },
        monotonicNowNanos = SystemClock::elapsedRealtimeNanos,
    )
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
    private val accountsSnapshot = combine(
        container.accounts.observeAccounts(),
        container.accounts.observeSettings(),
    ) { accounts, settings -> AccountsSnapshot(accounts, settings, loaded = true) }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        AccountsSnapshot(emptyList(), AccountSettings(), loaded = false),
    )
    private val pets = container.pets.observePets().map { pets ->
        PetsSnapshot(pets, loaded = true)
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        PetsSnapshot(emptyList(), loaded = false),
    )
    private val pending = container.repository.observeUnassignedPending()
        .withPendingMeasurementReadiness()
        .map { snapshot ->
            snapshot.measurements.filter { it.isAwaitingDecisionAt(snapshot.observedAt) }
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
    private val destructiveActionInProgress = MutableStateFlow<DestructiveSettingsAction?>(null)
    private val pendingDecision = MutableStateFlow<PendingDecisionSnapshot?>(null)
    private val pendingForNewAccount = MutableStateFlow<PendingResolverSession?>(null)
    private val unsavedPreviewSession = UnsavedPreviewSessionCoordinator()
    private val unsavedPreviewCalculationIds = AtomicLong(0L)
    private val externalSyncPaused = container.profileStore.settings
        .map { it.externalSyncPaused }
        .distinctUntilChanged()
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            container.profileStore.externalSyncPaused,
        )

    val events = eventEmitter.events

    private val scaleScanningState = combine(
        container.profileStore.settings,
        scanning,
        refreshing,
        scaleAvailability,
        scaleScanError,
    ) { settings, isScanning, isRefreshing, availability, error ->
        ScaleScanningState(settings, isScanning, isRefreshing, availability, error)
    }
    private val coreState = combine(
        scaleScanningState,
        externalSyncPaused,
        healthConnect,
        destructiveActionInProgress,
    ) { scanState, isSyncPaused, healthConnectState, destructiveAction ->
        MainCoreState(
            settings = scanState.settings,
            scanning = scanState.scanning,
            isRefreshing = scanState.isRefreshing,
            scaleAvailability = scanState.availability,
            scaleScanError = scanState.error,
            isExternalSyncPaused = isSyncPaused,
            healthConnect = healthConnectState,
            destructiveActionInProgress = destructiveAction,
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
            pending = pendingForResolverLifecycle(pendingValues, session),
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

    private val contentState = combine(
        coreState,
        accountsSnapshot,
        accountManagement,
        weightDeltaEditor,
        routingUi,
    ) { core, accountSnapshot, management, deltaEditor, routing ->
        MainUiState(
            settings = core.settings,
            scanning = core.scanning,
            isRefreshing = core.isRefreshing,
            scaleAvailability = core.scaleAvailability,
            scaleScanError = core.scaleScanError,
            isExternalSyncPaused = core.isExternalSyncPaused,
            healthConnect = core.healthConnect,
            destructiveActionInProgress = core.destructiveActionInProgress,
            profilesLoaded = accountSnapshot.loaded,
            accounts = accountSnapshot.accounts,
            accountSettings = accountSnapshot.settings,
            accountManagement = management,
            weightDeltaEditor = deltaEditor,
            resolverQueue = routing.queue,
            resolver = routing.resolver,
            unsavedPreview = routing.preview,
        )
    }
    val uiState: StateFlow<MainUiState> = combine(
        contentState,
        backup,
        pets,
        petMeasurement,
        petManagement,
    ) { state, backupState, petValues, petState, petManagementState ->
        state.copy(
            backup = backupState,
            profilesLoaded = state.profilesLoaded && petValues.loaded,
            pets = petValues.pets,
            petMeasurement = petState,
            petManagement = petManagementState,
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

    val healthConnectAvailable: Boolean get() = container.healthConnect.isAvailable()
    val healthConnectPermissions: Set<String> get() = container.healthConnect.permissions

    fun exportBackup(uri: Uri?) {
        if (uri == null || backup.value.inProgress) return
        viewModelScope.launch(Dispatchers.IO) {
            backup.value = BackupUiState(inProgress = true)
            try {
                getApplication<Application>().contentResolver.openOutputStream(uri)?.use {
                    container.backupExport.writeTo(it)
                } ?: error(localized(R.string.error_open_selected_file))
                showMessage(localized(R.string.message_backup_saved))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                showMessage(error.userFacingMessage(localized(R.string.error_backup_create)))
            } finally {
                backup.value = BackupUiState()
            }
        }
    }

    fun previewBackup(uri: Uri?, mode: BackupImportMode) {
        if (uri == null || backup.value.inProgress) return
        viewModelScope.launch(Dispatchers.IO) {
            backup.value = BackupUiState(inProgress = true)
            try {
                val document = getApplication<Application>().contentResolver.openInputStream(uri)?.use {
                    container.backupImport.read(it)
                } ?: error(localized(R.string.error_open_selected_file))
                val preview = container.backupImport.preview(
                    document,
                    container.backupSnapshotSource.readSnapshot(),
                    container.profileStore.versionedPortableSnapshot(),
                    mode,
                )
                backup.value = BackupUiState(preview = preview)
            } catch (cancelled: CancellationException) {
                backup.value = BackupUiState()
                throw cancelled
            } catch (error: Exception) {
                backup.value = BackupUiState()
                showMessage(error.userFacingMessage(localized(R.string.error_backup_read)))
            }
        }
    }

    fun dismissBackupPreview() { backup.value = BackupUiState() }

    fun requestBackupImport() {
        val preview = backup.value.preview ?: return
        if (preview.mode == BackupImportMode.REPLACE && !backup.value.replaceConfirmationRequested) {
            backup.value = backup.value.copy(replaceConfirmationRequested = true)
        } else {
            applyBackupImport(preview)
        }
    }

    private fun applyBackupImport(preview: BackupImportPreview) {
        if (backup.value.inProgress) return
        viewModelScope.launch(Dispatchers.IO) {
            backup.value = backup.value.copy(inProgress = true)
            try {
                val pendingBefore = container.measurementPersistence.pendingSnapshot()
                val result = container.backupImportApplier.apply(preview)
                if (result is com.palixander.scalesync.backup.BackupImportApplyResult.CompletedPendingRecovery) {
                    backup.value = BackupUiState()
                    showMessage(localized(R.string.message_backup_import_pending_recovery))
                    return@launch
                }
                if (preview.mode == BackupImportMode.REPLACE) {
                    val workManager = WorkManager.getInstance(getApplication())
                    pendingBefore.forEach { workManager.cancelUniqueWork("finalize-${it.id.value}") }
                } else {
                    container.measurementPersistence.pendingSnapshot()
                        .forEach(container.finalizationScheduler::enqueueIfAbsent)
                }
                backup.value = BackupUiState()
                showMessage(
                    localized(
                        R.string.message_backup_import_completed,
                        result.counts.accountsAdded,
                        result.counts.measurementsAdded,
                        result.counts.petsAdded,
                        result.counts.petMeasurementsAdded,
                    ),
                )
            } catch (cancelled: CancellationException) {
                backup.value = BackupUiState()
                throw cancelled
            } catch (stale: com.palixander.scalesync.backup.BackupPreviewStale) {
                backup.value = BackupUiState(preview = stale.refreshedPreview)
                showMessage(localized(R.string.message_backup_preview_stale))
            } catch (error: Exception) {
                backup.value = backup.value.copy(inProgress = false)
                showMessage(error.userFacingMessage(localized(R.string.error_backup_import)))
            }
        }
    }

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
                resolverSession.value?.let { session ->
                    values.firstOrNull { it.id == session.pendingId }?.let { current ->
                        if (session.pendingSnapshot != current) {
                            resolverSession.value = session.copy(pendingSnapshot = current)
                        }
                    }
                }
                val selectedId = resolverSession.value?.pendingId
                pendingDecision.value = pendingDecision.value?.takeIf { decision ->
                    decision.pendingId == selectedId || values.any { it.id == decision.pendingId }
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
                val routingTargetId = session?.pendingId ?: pendingValues.firstOrNull()?.id
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
                container.accountSelection.select(created.id)
            }
            finishAccountOperation(uiText(R.string.message_account_created, created.displayName))
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
                container.accountSelection.select(result.account.id)
                completePendingResolution(completion)
                finishAccountOperation(
                    uiText(
                        R.string.message_account_created_assigned,
                        result.account.displayName,
                    ),
                )
            }
            is CreateAccountAndAssignResult.NameConflict -> failAccountOperation(
                uiText(R.string.message_account_name_conflict),
            )
            CreateAccountAndAssignResult.PendingNotFound,
            is CreateAccountAndAssignResult.AlreadyFinalized,
            -> {
                pendingForNewAccount.value = null
                completePendingResolution(completion)
                finishAccountOperation(uiText(R.string.message_measurement_already_processed))
            }
        }
    }

    fun updateAccount(account: AccountUpdate) = runAccountOperation {
        when (val result = container.accounts.attemptProfileUpdate(account)) {
            is ProfileUpdateAttemptResult.Saved ->
                finishAccountOperation(
                    uiText(R.string.message_account_saved, result.account.displayName),
                )
            ProfileUpdateAttemptResult.ConfirmationRequired -> {
                val draft = accountManagementDialog.value.editor ?: return@runAccountOperation
                accountManagementDialog.value = reduceAccountManagement(
                    accountManagementDialog.value.copy(operationInProgress = false),
                    AccountManagementAction.ProfileUpdateConfirmationRequested(account, draft),
                )
            }
        }
    }

    fun confirmProfileUpdate(mode: ProfileHistoryUpdateMode) = runAccountOperation {
        val update = accountManagementDialog.value.profileUpdateConfirmation?.update
            ?: return@runAccountOperation
        saveAccountUpdate(update, mode)
    }

    private suspend fun saveAccountUpdate(account: AccountUpdate, mode: ProfileHistoryUpdateMode) {
        val updated = container.accounts.updateAccount(account, mode)
        finishAccountOperation(uiText(R.string.message_account_saved, updated.displayName))
    }

    fun setPrimaryAccount(accountId: AccountId, mode: PrimaryHistorySyncMode) =
        runAccountOperation {
            container.accounts.setPrimaryAccount(accountId, mode)
            container.accountSelection.select(accountId)
            finishAccountOperation(uiText(R.string.message_primary_account_changed))
        }

    fun deleteAccount(accountId: AccountId) = runAccountOperation {
        container.accounts.deleteAccount(accountId)
        val selection = container.accountSelection.selection.value
        if (selection.accountId == accountId) {
            container.accountSelection.selectIfCurrent(selection, null)
        }
        finishAccountOperation(uiText(R.string.message_account_deleted))
    }

    fun deletePrimaryAccount(request: AccountDeletionRequest) = runAccountOperation {
        container.accounts.deletePrimaryWithReplacement(
            primaryAccountId = request.accountId,
            replacementAccountId = request.replacementAccountId,
            historySyncMode = request.historySyncMode,
        )
        container.accountSelection.select(request.replacementAccountId)
        finishAccountOperation(uiText(R.string.message_primary_account_deleted))
    }

    fun updateWeightDeltaEditor(state: WeightDeltaEditorState) {
        if (!weightDeltaEditor.value.isSaving) weightDeltaEditor.value = state
    }

    fun saveWeightDelta(weightDeltaKg: Double) = viewModelScope.launch(Dispatchers.Default) {
        if (!weightDeltaEditor.value.canSave) return@launch
        weightDeltaEditor.value = weightDeltaEditor.value.copy(isSaving = true)
        runCatching { container.accounts.updateWeightDeltaKg(weightDeltaKg) }
            .onSuccess {
                weightDeltaEditor.value = WeightDeltaEditorState.from(weightDeltaKg)
                showMessage(uiText(R.string.message_weight_delta_saved))
            }
            .onFailure {
                weightDeltaEditor.value = weightDeltaEditor.value.copy(isSaving = false)
                showMessage(
                    it.message?.takeIf(String::isNotBlank)?.let(UiText::Raw)
                        ?: uiText(R.string.error_save_weight_delta),
                )
            }
    }

    fun setIgnoreUnknownMeasurements(enabled: Boolean) = viewModelScope.launch {
        try {
            container.accounts.updateIgnoreUnknownMeasurements(enabled)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            showMessage(error.userFacingMessage(localized(R.string.error_save_setting)))
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
                    durablePendingSnapshots = container.repository.observeUnassignedPending(),
                )?.let { pendingId ->
                    selectPendingForResolver(pendingId, PendingResolverSource.EXTERNAL)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                showMessage(error.userFacingMessage(localized(R.string.error_open_pending_measurement)))
            }
        }
    }

    fun openResolverFromQueue(pendingId: PendingMeasurementId) {
        if (pending.value.any { it.id == pendingId }) {
            selectPendingForResolver(pendingId, PendingResolverSource.PENDING_QUEUE)
        }
    }

    fun resolveLater() {
        clearResolverSession()
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
                    }
                    is FinalizePendingResult.AlreadyFinalized -> {
                        completePendingResolution(completion)
                        showMessage(uiText(R.string.message_measurement_already_assigned))
                    }
                    FinalizePendingResult.ProfileIncomplete ->
                        showMessage(uiText(R.string.message_complete_selected_profile))
                    FinalizePendingResult.AccountNotFound ->
                        showMessage(uiText(R.string.message_account_already_deleted))
                    FinalizePendingResult.PendingNotFound -> {
                        completePendingResolution(completion)
                        showMessage(uiText(R.string.message_measurement_already_processed))
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                showMessage(error.userFacingMessage(localized(R.string.error_assign_measurement)))
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
        clearResolverSession(session.pendingId)
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

    fun calculateUnsavedPreview(pendingId: PendingMeasurementId) {
        val zoneId = ZoneId.systemDefault()
        val request = unsavedPreviewSession.startCalculation(
            pendingId = pendingId,
            requestId = unsavedPreviewCalculationIds.incrementAndGet(),
            zoneId = zoneId,
        ) ?: return
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val result = calculateUnsavedPreviewResult(
                    pending = request.pending,
                    draft = request.profileDraft,
                    zoneId = zoneId,
                )
                if (result == null) {
                    unsavedPreviewSession.failCalculation(pendingId, request.requestId)
                } else {
                    unsavedPreviewSession.completeCalculation(pendingId, request.requestId, result)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                unsavedPreviewSession.failCalculation(pendingId, request.requestId)
            }
        }
    }

    fun closeUnsavedPreviewAndDiscard(pendingId: PendingMeasurementId) = viewModelScope.launch {
        val completion = unsavedPreviewSession.takeClose(pendingId) ?: return@launch
        discardPending(pendingId, completion)
    }

    fun registerBackgroundScan() {
        if (container.profileStore.settings.value.scaleAddress == null) return
        showMessage(BackgroundScanRegistrar.register(getApplication()).fold(
            onSuccess = { "Фоновое BLE-сканирование включено" },
            onFailure = { it.message ?: localized(R.string.error_enable_scanning) },
        ))
    }

    fun toggleManualScan() {
        if (petMeasurementCoordinator.isActive) {
            showMessage("Сначала завершите взвешивание питомца")
            return
        }
        if (scanning.value) {
            scanner.stop()
            scanning.value = false
            restoreAutomaticScanning()
            showMessage("Ручное сканирование остановлено")
            return
        }
        scaleAvailability.value = currentScaleAvailability()
        scaleScanError.value = null
        if (scaleAvailability.value != ScaleAvailability.AVAILABLE) return
        BackgroundScanRegistrar.unregister(getApplication())
        ReliabilityScanService.setEnabled(getApplication(), false)
        val started = scanner.start(
            onResult = ::onScanResult,
            onError = { error ->
                scanner.stop()
                scanning.value = false
                restoreAutomaticScanning()
                scaleScanError.value = error
                showMessage(error)
            },
        )
        started.onSuccess {
            scanning.value = true
            showMessage("Встаньте на весы и дождитесь финального измерения")
        }.onFailure {
            restoreAutomaticScanning()
            scaleAvailability.value = currentScaleAvailability()
            val message = it.message ?: localized(R.string.error_start_scanning)
            if (scaleAvailability.value == ScaleAvailability.AVAILABLE) scaleScanError.value = message
            showMessage(message)
        }
    }

    /** Starts one direct BLE request for pull-to-refresh; concurrent gestures are ignored. */
    fun refreshFromScale() {
        if (petMeasurementCoordinator.isActive) {
            showMessage("Сначала завершите взвешивание питомца")
            return
        }
        val refresh = beginScaleRefresh(
            address = container.profileStore.settings.value.scaleAddress,
            coordinator = scaleRefresh,
            showMessage = ::showMessage,
        ) ?: return
        val operation = refresh.operation
        val started = runCatching {
            BackgroundScanRegistrar.unregister(getApplication())
            ReliabilityScanService.setEnabled(getApplication(), false)
        }.fold(
            onSuccess = {
                refreshScanner.start(
                    address = refresh.address,
                    onResult = { result ->
                        onRefreshScanResult(operation, refresh.address, result)
                    },
                    onError = { error -> scaleRefresh.fail(operation, error) },
                )
            },
            onFailure = { Result.failure(it) },
        )
        started.onFailure { error ->
            scaleRefresh.fail(operation, error.message ?: localized(R.string.error_start_scanning))
        }.onSuccess {
            val timeoutJob = viewModelScope.launch {
                delay(SCALE_REFRESH_TIMEOUT_MILLIS)
                scaleRefresh.timeout(operation)
            }
            scaleRefresh.attachTimeout(operation, timeoutJob::cancel)
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

    /** Stops every BLE producer before durably removing the selected scale. */
    fun forgetScale() = runDestructiveAction(DestructiveSettingsAction.SCALE) {
        ForgetScaleCoordinator(
            stopBleSessions = {
                invalidatePetMeasurementStartup()
                scanner.stop()
                scanning.value = false
                scaleRefresh.clear()
                petMeasurementCoordinator.clear()
                refreshScanner.stop()
                petScanner.stop()
            },
            unregisterPendingIntentScan = {
                BackgroundScanRegistrar.unregister(getApplication())
            },
            stopReliabilityService = {
                ReliabilityScanService.setEnabled(getApplication(), false)
            },
            cancelBleWork = {
                WorkManager.getInstance(getApplication())
                    .cancelAllWorkByTag(ScanWorkScheduler.BLE_PROCESSING_WORK_TAG)
            },
            packetGate = container.scalePacketProcessingGate,
            clearSettings = container.profileStore::forgetScale,
        ).forget()
        showMessage("Весы забыты")
    }

    fun disableHealthConnect() = disableExternalIntegration(
        DestructiveSettingsAction.HEALTH_CONNECT,
        ExternalSyncDestination.HEALTH_CONNECT,
        "Health Connect отключён в приложении. Разрешения можно отозвать в системных настройках.",
    )

    private fun disableExternalIntegration(
        action: DestructiveSettingsAction,
        destination: ExternalSyncDestination,
        successMessage: String,
    ) = runDestructiveAction(action) {
        container.profileStore.setExternalSyncEnabled(destination, false)
        showMessage(successMessage)
    }

    private fun runDestructiveAction(
        action: DestructiveSettingsAction,
        block: suspend () -> Unit,
    ) {
        if (!destructiveActionInProgress.compareAndSet(null, action)) return
        viewModelScope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                showMessage(error.userFacingMessage(localized(R.string.error_perform_action)))
            } finally {
                destructiveActionInProgress.compareAndSet(action, null)
            }
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
        scaleAvailability.value = currentScaleAvailability()
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
            explicitAuthorization = true,
        )
    }

    fun onHealthConnectPermissionsChanged(grantedPermissions: Set<String>) = viewModelScope.launch {
        updateHealthConnectPermissions(
            notifyResult = true,
            grantedHint = grantedPermissions,
            explicitAuthorization = true,
        )
    }

    fun refreshHealthConnectQueue() = viewModelScope.launch {
        val allGranted = runCatching { container.healthConnect.hasPermissions() }.getOrDefault(false)
        if (allGranted) container.repository.retryPendingHealthConnect()
    }

    /** Re-checks Health Connect permissions after returning to the foreground. */
    fun refreshIntegrations() = viewModelScope.launch {
        updateHealthConnectPermissions(notifyResult = false)
    }

    /** Foreground repair closes Room→WorkManager gaps and restores pending presentation. */
    fun onForeground() = viewModelScope.launch {
        scaleAvailability.value = currentScaleAvailability()
        notificationPermissionGranted.value =
            container.pendingMeasurementNotifications.areNotificationsAllowed()
        runCatching { MeasurementWorkSweep(container.repository).run() }
            .onFailure { showMessage("Не удалось проверить ожидающие измерения") }
        runCatching { container.repository.refreshPendingPresentation() }
        updateHealthConnectPermissions(notifyResult = false)
    }

    override fun onCleared() {
        petHistoryOwners.values.forEach { it.close() }
        invalidatePetMeasurementStartup()
        petMeasurementCoordinator.clear()
        scaleRefresh.clear()
        scanner.stop()
        super.onCleared()
    }

    fun openPetMeasurement() {
        invalidatePetMeasurementStartup()
        if (scanning.value || refreshing.value || petMeasurementCoordinator.isActive) {
            showMessage("Дождитесь завершения текущего BLE-сканирования")
            return
        }
        petMeasurementCoordinator.showSelection()
    }

    fun showCreatePet() {
        petMeasurementCoordinator.showCreating()
    }

    fun createPetAndStartMeasurement(
        displayName: String,
        species: PetSpecies,
        onCreated: (com.palixander.scalesync.domain.Pet) -> Unit,
    ) {
        if (petMeasurementCoordinator.isActive) return
        val creationToken = petMeasurementCreation.begin() ?: return
        viewModelScope.launch {
            val pet = try {
                container.pets.createPet(newPetForQuickMeasurement(displayName, species))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (!petMeasurementCreation.complete(creationToken)) return@launch
                petMeasurement.value = PetMeasurementUiState.Error(
                    error.message?.let(UiText::Raw) ?: uiText(R.string.error_create_pet),
                )
                return@launch
            }
            if (!petMeasurementCreation.complete(creationToken)) return@launch
            onCreated(pet)
        }
    }

    fun showCreatePetManagement() {
        if (petManagement.value.busy) return
        petManagement.value = PetManagementController.showCreate(
            state = petManagement.value,
            editorSessionId = petManagementSessionIds.incrementAndGet(),
        )
    }

    fun showEditPetManagement(pet: com.palixander.scalesync.domain.Pet) {
        if (petManagement.value.busy) return
        petManagement.value = PetManagementController.showEdit(
            state = petManagement.value,
            pet = pet,
            editorSessionId = petManagementSessionIds.incrementAndGet(),
            breedCatalog = petBreedCatalog,
        )
    }

    fun onPetProfileAction(action: PetProfileAction) {
        petManagement.value = PetManagementController.onAction(petManagement.value, action)
    }

    fun savePetManagement() {
        val preparation = PetManagementController.prepareSave(
            state = petManagement.value,
            today = currentDate(),
            existingPets = pets.value.pets.map { it.pet },
        )
        petManagement.value = preparation.state
        val request = (preparation as? PetProfileSavePreparation.Ready)?.request ?: return
        viewModelScope.launch {
            try {
                when (val profile = request.profile) {
                    is ValidatedPetProfile.Create -> container.pets.createPet(profile.pet)
                    is ValidatedPetProfile.Edit -> container.pets.updatePet(profile.pet)
                }
                petManagement.value = PetManagementController.finishSave(
                    state = petManagement.value,
                    request = request,
                    result = PetProfilePersistenceResult.Success,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                petManagement.value = PetManagementController.finishSave(
                    state = petManagement.value,
                    request = request,
                    result = PetProfilePersistenceResult.Failure(
                        error.message ?: getApplication<Application>().getString(R.string.error_save_pet),
                    ),
                )
            }
        }
    }

    fun requestDeletePet(petId: PetId) {
        if (petManagement.value.busy) return
        petManagement.value = PetManagementUiState(busy = true)
        viewModelScope.launch {
            runCatching { container.pets.previewPetDeletion(petId) }
                .onSuccess { petManagement.value = PetManagementUiState(deletion = it) }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    petManagement.value = PetManagementUiState(error = error.message)
                    showMessage(uiText(R.string.error_prepare_delete_pet))
                }
        }
    }

    fun confirmDeletePet() {
        val snapshot = petManagement.value
        val preview = snapshot.deletion ?: return
        if (snapshot.busy) return
        petManagement.value = snapshot.copy(busy = true, error = null)
        viewModelScope.launch {
            runCatching { container.pets.deletePet(preview.pet.id) }
                .onSuccess { petManagement.value = PetManagementUiState() }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    petManagement.value = snapshot.copy(
                        busy = false,
                        error = error.message ?: getApplication<Application>().getString(R.string.error_delete_pet),
                    )
                }
        }
    }

    fun dismissPetManagement() {
        petManagement.value = PetManagementController.dismiss(petManagement.value)
    }

    fun startPetMeasurement(petId: PetId) {
        invalidatePetMeasurementStartup()
        val startupToken = petMeasurementStartup.begin()
        petMeasurementStartupJob = viewModelScope.launch {
            if (scanning.value || petMeasurementCoordinator.isActive) {
                showMessage(uiText(R.string.pet_measurement_wait_for_ble_scan))
                return@launch
            }
            if (refreshing.value) scaleRefresh.clear()
            val address = when (
                val preflight = scaleRefreshPreflight(
                    container.profileStore.settings.value.scaleAddress,
                )
            ) {
                is ScaleRefreshPreflightResult.Ready -> preflight.address
                is ScaleRefreshPreflightResult.Rejected -> {
                    petMeasurement.value = PetMeasurementUiState.Error(PET_SCALE_REQUIRED_MESSAGE)
                    showMessage(PET_SCALE_REQUIRED_MESSAGE)
                    return@launch
                }
            }
            if (!BleSupport.hasScanPermission(getApplication()) ||
                !BleSupport.hasConnectPermission(getApplication())
            ) {
                petMeasurement.value = PetMeasurementUiState.Error(PET_BLUETOOTH_PERMISSION_MESSAGE)
                showMessage(PET_BLUETOOTH_PERMISSION_MESSAGE)
                return@launch
            }
            val pet = petMeasurementStartup.resolve(startupToken) {
                container.pets.getPet(petId)
            } ?: run {
                if (!petMeasurementStartup.isCurrent(startupToken)) return@launch
                petMeasurement.value = PetMeasurementUiState.Error(uiText(R.string.pet_measurement_pet_not_found))
                return@launch
            }
            if (!petMeasurementStartup.isCurrent(startupToken)) return@launch
            if (scanning.value) {
                showMessage(uiText(R.string.pet_measurement_wait_for_ble_scan))
                return@launch
            }
            if (refreshing.value) scaleRefresh.clear()
            val previousPetWeightKg = pets.value.pets
                .firstOrNull { it.pet.id == pet.id }
                ?.latestMeasurement
                ?.petWeightKg
            val token = petMeasurementCoordinator.start(pet, address, previousPetWeightKg) ?: return@launch
            if (!petMeasurementStartup.isCurrent(startupToken)) {
                petMeasurementCoordinator.cancel()
                return@launch
            }
            val started = runCatching {
                BackgroundScanRegistrar.unregister(getApplication())
                ReliabilityScanService.setEnabled(getApplication(), false)
            }.fold(
                onSuccess = {
                    if (!petMeasurementStartup.isCurrent(startupToken)) {
                        return@fold Result.failure(
                            CancellationException("Pet measurement start invalidated"),
                        )
                    }
                    petScanner.start(
                        address = address,
                        onResult = { onPetScanResult(token, address, it) },
                        onError = { petMeasurementCoordinator.pause(token, UiText.Raw(it)) },
                    )
                },
                onFailure = { Result.failure(it) },
            )
            started.onFailure {
                petMeasurementCoordinator.fail(
                    token,
                    it.message?.let(UiText::Raw) ?: uiText(R.string.pet_measurement_start_failed),
                )
            }.onSuccess {
                attachPetMeasurementTimeout(token)
            }
        }
    }

    fun cancelPetMeasurement() {
        invalidatePetMeasurementStartup()
        petMeasurementCoordinator.cancel()
    }

    fun completePetMeasurement() {
        val request = petMeasurementCoordinator.beginSave() ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val measurement = container.pets.recordCompletedMeasurement(
                    petId = request.petId,
                    measuredAt = request.measuredAt,
                    firstWeightKg = request.firstWeightKg,
                    secondWeightKg = request.secondWeightKg,
                )
                petMeasurementCoordinator.saved(request.token, measurement)
            } catch (cancelled: CancellationException) {
                petMeasurementCoordinator.cancel(request.token)
                throw cancelled
            } catch (error: Exception) {
                petMeasurementCoordinator.fail(
                    request.token,
                    error.message?.let(UiText::Raw) ?: uiText(R.string.pet_measurement_save_failed),
                )
            }
        }
    }

    fun retryPetMeasurement() {
        val request = petMeasurementCoordinator.retry() ?: return
        val started = petScanner.start(
            address = request.selectedAddress,
            onResult = { onPetScanResult(request.token, request.selectedAddress, it) },
            onError = { petMeasurementCoordinator.pause(request.token, UiText.Raw(it)) },
        )
        started.onFailure {
            petMeasurementCoordinator.pause(
                request.token,
                it.message?.let(UiText::Raw) ?: uiText(R.string.pet_measurement_retry_failed),
            )
        }.onSuccess {
            attachPetMeasurementTimeout(request.token)
        }
    }

    private fun invalidatePetMeasurementStartup() {
        petMeasurementCreation.invalidate()
        petMeasurementStartup.invalidate()
        petMeasurementStartupJob?.cancel()
        petMeasurementStartupJob = null
    }

    private fun attachPetMeasurementTimeout(token: PetMeasurementCoordinator.OperationToken) {
        val timeoutJob = viewModelScope.launch {
            delay(PET_MEASUREMENT_TIMEOUT_MILLIS)
            petMeasurementCoordinator.timeout(token)
        }
        petMeasurementCoordinator.attachTimeout(token, timeoutJob::cancel)
    }

    @SuppressLint("MissingPermission")
    private fun onPetScanResult(
        token: PetMeasurementCoordinator.OperationToken,
        selectedAddress: String,
        result: ScanResult,
    ) {
        if (!BleSupport.hasConnectPermission(getApplication())) {
            petMeasurementCoordinator.fail(token, PET_BLUETOOTH_PERMISSION_MESSAGE)
            return
        }
        val payload = BleSupport.serviceData(result) ?: return
        val address = runCatching { result.device.address }.getOrNull() ?: return
        if (!isSelectedScaleAddress(selectedAddress, address)) return
        petMeasurementCoordinator.registerPetPacket(token, address, payload)
        val parsed = container.packetParser.parse(payload, address) ?: return
        val wasAwaitingFirst =
            petMeasurement.value is PetMeasurementUiState.AwaitingFirstWeight
        val request = petMeasurementCoordinator.accept(
            token,
            PetScaleReading(
                address = address,
                receivedAtNanos = result.timestampNanos,
                measuredAt = parsed.measuredAt,
                weightKg = parsed.weightKg,
                rawWeight = parsed.rawWeight,
                isStable = parsed.isStable,
                isStableWeight = parsed.isStableWeight,
                rawIdentity = petReadingRawIdentity(parsed.rawPayload),
            ),
        )
        if (request == null) {
            if (wasAwaitingFirst &&
                petMeasurement.value is PetMeasurementUiState.AwaitingSecondWeight
            ) {
                attachPetMeasurementTimeout(token)
            }
            return
        }
    }

    @SuppressLint("MissingPermission")
    private fun onScanResult(result: ScanResult) {
        if (!BleSupport.hasConnectPermission(getApplication())) return
        val payload = BleSupport.serviceData(result) ?: return
        val address = runCatching { result.device.address }.getOrNull() ?: return
        val parsed = container.packetParser.parse(payload, address) ?: return
        if (!shouldProcessForegroundScaleReading(
                raw = parsed,
                observeTransient = container.petMeasurementIngestionGate::observeTransientReading,
            )
        ) return

        val name = runCatching { result.device.name }.getOrNull()
        container.profileStore.saveScale(address, name)
        scanner.stop()
        scanning.value = false
        ScanWorkScheduler.processDirect(getApplication(), result)
        restoreAutomaticScanning()
    }

    @SuppressLint("MissingPermission")
    private fun onRefreshScanResult(
        operation: ScaleRefreshCoordinator.OperationToken,
        selectedAddress: String,
        result: ScanResult,
    ) {
        if (!BleSupport.hasConnectPermission(getApplication())) return
        val payload = BleSupport.serviceData(result) ?: return
        val address = runCatching { result.device.address }.getOrNull() ?: return
        if (!isSelectedScaleAddress(selectedAddress, address)) return
        val parsed = container.packetParser.parse(payload, address) ?: return
        if (!shouldProcessForegroundScaleReading(
                raw = parsed,
                selectedAddress = selectedAddress,
                observeTransient = container.petMeasurementIngestionGate::observeTransientReading,
            )
        ) return

        ScanWorkScheduler.processDirect(getApplication(), result)
        scaleRefresh.complete(operation)
    }

    private fun restoreAutomaticScanning() {
        if (scanning.value) return
        if (container.profileStore.settings.value.scaleAddress == null) return
        BackgroundScanRegistrar.register(getApplication())
        if (container.profileStore.settings.value.reliabilityMode) {
            runCatching { ReliabilityScanService.setEnabled(getApplication(), true) }
        }
    }

    private fun currentScaleAvailability(): ScaleAvailability = when {
        !BleSupport.hasScanPermission(getApplication()) -> ScaleAvailability.PERMISSION_REQUIRED
        BleSupport.scanner(getApplication()) == null -> ScaleAvailability.BLUETOOTH_DISABLED
        else -> ScaleAvailability.AVAILABLE
    }

    private suspend fun updateHealthConnectPermissions(
        notifyResult: Boolean,
        grantedHint: Set<String>? = null,
        explicitAuthorization: Boolean = false,
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
        if (shouldActivateHealthConnectAfterPermissionRefresh(
                isConnected = snapshot.isConnected,
                explicitAuthorization = explicitAuthorization,
            )
        ) {
            container.profileStore.setExternalSyncEnabled(
                ExternalSyncDestination.HEALTH_CONNECT,
                true,
            )
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

    private fun showMessage(message: UiText) {
        eventEmitter.showSnackbar(message)
    }

    private fun localized(id: Int, vararg arguments: Any): String =
        getApplication<Application>().getString(id, *arguments)

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
            showMessage(error.userFacingMessage(localized(R.string.error_delete_measurement)))
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
            showMessage(error.userFacingMessage(localized(R.string.error_delete_measurement)))
        } finally {
            pendingDiscardsInProgress.remove(pendingId)
        }
    }

    private fun selectPendingForResolver(
        pendingId: PendingMeasurementId,
        source: PendingResolverSource,
    ) {
        resolverSession.value = PendingResolverSession(
            pendingId = pendingId,
            source = source,
            pendingSnapshot = pending.value.firstOrNull { it.id == pendingId },
        )
    }

    private fun showPendingWithoutSaving(
        pendingId: PendingMeasurementId,
        session: PendingResolverSession,
    ) {
        val pendingValue = pending.value.firstOrNull { it.id == pendingId } ?: return
        if (session.pendingId != pendingId) return
        clearResolverSession(pendingId)
        unsavedPreviewSession.show(
            state = UnsavedMeasurementPreviewState(pendingValue),
            resolverSession = session,
        )
    }

    private fun completePendingResolution(completion: PendingResolverCompletion) {
        clearResolverSession(completion.pendingId)
        eventEmitter.pendingResolutionCompleted(
            pendingId = completion.pendingId,
            returnDestination = completion.returnDestination,
        )
    }

    private fun clearResolverSession(pendingId: PendingMeasurementId? = null) {
        if (pendingId == null || resolverSession.value?.pendingId == pendingId) {
            resolverSession.value = null
        }
    }

    private fun runAccountOperation(block: suspend () -> Unit) {
        if (accountManagementDialog.value.operationInProgress) return
        accountManagementDialog.value = accountManagementDialog.value.copy(
            operationInProgress = true,
            operationError = null,
        )
        viewModelScope.launch(Dispatchers.Default) {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                failAccountOperation(
                    when (error) {
                        is AccountNameConflictException ->
                            uiText(R.string.message_account_name_conflict)
                        else -> error.message?.takeIf(String::isNotBlank)?.let(UiText::Raw)
                            ?: uiText(R.string.error_change_profile)
                    },
                )
            } finally {
                accountManagementDialog.value = accountManagementDialog.value.copy(
                    operationInProgress = false,
                )
            }
        }
    }

    private fun finishAccountOperation(message: UiText) {
        accountManagementDialog.value = AccountManagementUiState()
        showMessage(message)
    }

    private fun failAccountOperation(message: UiText) {
        accountManagementDialog.value = accountManagementDialog.value.copy(
            operationInProgress = false,
            operationError = message,
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
                is MeasurementIngestionResult.Assigned -> {
                    clearResolverSession(pendingId)
                    pendingDecision.value = null
                }
                MeasurementIngestionResult.PendingMissing,
                MeasurementIngestionResult.AutomaticallyIgnoredUnknown,
                -> {
                    clearResolverSession(pendingId)
                    pendingDecision.value = null
                }
                is MeasurementIngestionResult.CreatedAggregate,
                is MeasurementIngestionResult.UpdatedAggregate,
                is MeasurementIngestionResult.UpgradedFinalized,
                MeasurementIngestionResult.SuppressedFinal,
                MeasurementIngestionResult.SuppressedTombstone,
                MeasurementIngestionResult.ExactReplay,
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

}

internal const val EXTERNAL_SYNC_PAUSED_MESSAGE =
    "Внешняя синхронизация приостановлена"
internal const val EXTERNAL_SYNC_RESUMED_MESSAGE = "Внешняя синхронизация возобновлена"

internal fun ExternalSyncPauseTransition.snackbarMessage(): String = when (this) {
    is ExternalSyncPauseTransition.Paused -> EXTERNAL_SYNC_PAUSED_MESSAGE
    ExternalSyncPauseTransition.Resumed -> EXTERNAL_SYNC_RESUMED_MESSAGE
}

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

private fun Throwable.userFacingMessage(fallback: String): String =
    message?.takeIf(String::isNotBlank) ?: fallback
