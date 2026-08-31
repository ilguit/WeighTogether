package com.palixander.scalesync

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.palixander.scalesync.charts.ChartsScreen
import com.palixander.scalesync.changelog.ChangelogScreen
import com.palixander.scalesync.backup.BackupImportMode
import com.palixander.scalesync.core.Sex
import com.palixander.scalesync.measurements.MeasurementsCallbacks
import com.palixander.scalesync.measurements.MeasurementsDestination
import com.palixander.scalesync.measurements.MeasurementsScreen
import com.palixander.scalesync.measurements.MeasurementsUiState
import com.palixander.scalesync.measurements.MeasurementsUiEvent
import com.palixander.scalesync.ui.components.HuaweiIconButton
import com.palixander.scalesync.ui.accounts.AccountManagementCallbacks
import com.palixander.scalesync.ui.routing.MeasurementResolverCallbacks
import com.palixander.scalesync.ui.routing.MeasurementResolverDialog
import com.palixander.scalesync.ui.routing.PendingResolverForegroundFallback
import com.palixander.scalesync.ui.routing.PendingResolverReturnDestination
import com.palixander.scalesync.ui.routing.UnsavedMeasurementPreviewDialog
import com.palixander.scalesync.ui.routing.UnsavedPreviewCallbacks
import com.palixander.scalesync.ui.components.HuaweiSystemBarBackgrounds
import com.palixander.scalesync.ui.icons.HuaweiIcons
import com.palixander.scalesync.ui.theme.HuaweiDimensions
import com.palixander.scalesync.ui.theme.ScaleSyncTheme
import com.palixander.scalesync.ui.profiles.PetProfileScreen
import com.palixander.scalesync.ui.profiles.PetHistoryCallbacks
import com.palixander.scalesync.ui.profiles.PetHistoryUiState
import com.palixander.scalesync.ui.profiles.ProfileDestination
import com.palixander.scalesync.ui.profiles.ProfileKey
import com.palixander.scalesync.ui.profiles.ProfileNavigationState
import com.palixander.scalesync.ui.profiles.ProfileSelectionUiState
import com.palixander.scalesync.ui.profiles.ProfileSelector
import com.palixander.scalesync.ui.profiles.buildProfilePresentations
import com.palixander.scalesync.ui.profiles.reconcileProfileNavigation
import com.palixander.scalesync.ui.profiles.reconcileProfileSelection
import kotlinx.coroutines.flow.Flow

internal enum class AppSection(
    val title: String,
    val icon: ImageVector,
) {
    MEASUREMENTS("Измерения", HuaweiIcons.Scale),
    CHARTS("Графики", HuaweiIcons.Charts),
    SETTINGS("Настройки", HuaweiIcons.Settings),
}

internal val defaultAppSection = AppSection.MEASUREMENTS

internal enum class AppDestination {
    ROOT,
    CHANGELOG,
}

internal data class MeasurementsChrome(
    val showTopBar: Boolean,
    val showBottomNavigation: Boolean,
    val contentUsesSafeDrawingInsets: Boolean,
)

internal fun measurementsChromeFor(destination: MeasurementsDestination): MeasurementsChrome =
    when (destination) {
        MeasurementsDestination.SUMMARY -> MeasurementsChrome(
            showTopBar = true,
            showBottomNavigation = true,
            contentUsesSafeDrawingInsets = false,
        )

        MeasurementsDestination.PENDING_QUEUE,
        MeasurementsDestination.HISTORY,
        MeasurementsDestination.EDITOR,
        -> MeasurementsChrome(
            showTopBar = false,
            showBottomNavigation = false,
            contentUsesSafeDrawingInsets = true,
        )
    }

internal object MainScreenTestTags {
    const val TopBar = "main-top-bar"
    const val TopBarTitle = "main-top-bar-title"
    const val SettingsBack = "settings-detail-back"
    const val PendingQueueAction = "measurements-pending-queue-action"
    const val PendingQueueBadge = "measurements-pending-queue-badge"
    const val HistoryAction = "measurements-history-action"
    const val PetMeasurementAction = "measurements-pet-measurement-action"
    const val ExternalSyncAction = "measurements-external-sync-action"
    const val PullToRefresh = "measurements-pull-to-refresh"
    const val PullToRefreshIndicator = "measurements-pull-to-refresh-indicator"
    const val BottomNavigation = "main-bottom-navigation"
    const val SnackbarHost = "main-snackbar-host"
}

internal val ExternalSyncPausedSemanticsKey = SemanticsPropertyKey<Boolean>("ExternalSyncPaused")
internal var SemanticsPropertyReceiver.externalSyncPaused by ExternalSyncPausedSemanticsKey

@Composable
internal fun MainUiEventHandler(
    events: Flow<MainUiEvent>,
    snackbarHostState: SnackbarHostState,
    onPendingResolutionCompleted: (MainUiEvent.PendingResolutionCompleted) -> Unit,
    onPendingDiscardSnackbarResult: (snackbarId: Long, undoRequested: Boolean) -> Unit,
) {
    val currentResolutionHandler by rememberUpdatedState(onPendingResolutionCompleted)
    val currentDiscardResultHandler by rememberUpdatedState(onPendingDiscardSnackbarResult)
    LaunchedEffect(events, snackbarHostState) {
        events.collect { event ->
            when (event) {
                is MainUiEvent.ShowSnackbar -> snackbarHostState.showSnackbar(event.message)
                is MainUiEvent.ShowPendingDiscardUndo -> {
                    var resultReported = false
                    try {
                        val result = snackbarHostState.showSnackbar(
                            message = event.message,
                            actionLabel = event.actionLabel,
                            withDismissAction = true,
                            duration = SnackbarDuration.Long,
                        )
                        currentDiscardResultHandler(
                            event.snackbarId,
                            result == SnackbarResult.ActionPerformed,
                        )
                        resultReported = true
                    } finally {
                        if (!resultReported) {
                            currentDiscardResultHandler(event.snackbarId, false)
                        }
                    }
                }
                is MainUiEvent.PendingResolutionCompleted ->
                    currentResolutionHandler(event)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScaleSyncApp(
    viewModel: MainViewModel,
    measurementsViewModel: MeasurementsViewModel,
    chartsViewModel: ChartsViewModel,
    healthConnectSystemManagementAvailable: Boolean,
    requestHealthConnectPermissions: () -> Unit,
    openHealthConnectAccessManagement: () -> Unit,
    openBatterySettings: () -> Unit,
    openApplicationSettings: () -> Unit,
    createBackup: () -> Unit,
    openBackup: (BackupImportMode) -> Unit,
) {
    var currentSection by rememberSaveable { mutableStateOf(defaultAppSection) }
    var currentDestination by rememberSaveable { mutableStateOf(AppDestination.ROOT) }
    var settingsDestination by rememberSaveable { mutableStateOf(SettingsDestination.ROOT) }
    var profileNavigation by rememberSaveable(stateSaver = ProfileNavigationState.Saver) {
        mutableStateOf(ProfileNavigationState())
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val measurementsState = if (currentSection == AppSection.MEASUREMENTS) {
        val activeState by measurementsViewModel.uiState.collectAsStateWithLifecycle()
        activeState
    } else {
        MeasurementsUiState()
    }
    val chartsState = if (currentSection == AppSection.CHARTS) {
        val activeState by chartsViewModel.uiState.collectAsStateWithLifecycle()
        activeState
    } else {
        chartsViewModel.initialUiState
    }
    val snackbarHostState = remember { SnackbarHostState() }
    val profiles = buildProfilePresentations(state.accounts, state.pets)
    val requestedProfileKey = profileNavigation.selectedKey ?: measurementsState.accountSelector
        .selectedAccountId?.let(ProfileKey::Human)
    val profileSelection = if (state.profilesLoaded) {
        reconcileProfileSelection(
            profiles = profiles,
            requestedKey = requestedProfileKey,
            primaryAccountId = state.accountSettings.primaryAccountId,
        )
    } else {
        null
    }
    LaunchedEffect(profileSelection?.selectedKey, profileSelection?.fallback) {
        profileSelection?.let {
            profileNavigation = reconcileProfileNavigation(
                state = profileNavigation,
                selection = it,
                profilesLoaded = state.profilesLoaded,
            )
        }
    }
    LaunchedEffect(measurementsViewModel) {
        measurementsViewModel.events.collect { event ->
            when (event) {
                is MeasurementsUiEvent.ShowSnackbar -> snackbarHostState.showSnackbar(event.message)
            }
        }
    }
    val selectedPetId = (profileNavigation.destination as? ProfileDestination.PetShell)?.petId
    val petHistoryOwner = selectedPetId?.let { petId ->
        remember(petId) { viewModel.petHistoryStateOwner(petId) }
    }
    DisposableEffect(petHistoryOwner) {
        onDispose { petHistoryOwner?.close() }
    }
    val petHistoryState = petHistoryOwner?.uiState?.collectAsStateWithLifecycle()?.value
    MainUiEventHandler(
        events = viewModel.events,
        snackbarHostState = snackbarHostState,
        onPendingDiscardSnackbarResult = viewModel::onPendingDiscardSnackbarResult,
        onPendingResolutionCompleted = { event ->
            measurementsViewModel.onPendingResolutionCompleted(event.returnDestination)
            if (
                event.returnDestination ==
                PendingResolverReturnDestination.PENDING_QUEUE
            ) {
                currentSection = AppSection.MEASUREMENTS
            }
        },
    )
    ScaleSyncScaffold(
        state = state.copy(
            healthConnectSystemManagementAvailable =
                healthConnectSystemManagementAvailable,
        ),
        currentSection = currentSection,
        currentDestination = currentDestination,
        settingsDestination = settingsDestination,
        profileSelection = profileSelection,
        profileDestination = profileNavigation.destination,
        petHistoryState = petHistoryState,
        petHistoryCallbacks = petHistoryOwner?.callbacks,
        measurementsDestination = measurementsState.destination,
        measurementsCallbacks = measurementsViewModel.callbacks,
        petMeasurementCallbacks = PetMeasurementCallbacks(
            onOpen = viewModel::openPetMeasurement,
            onShowCreate = viewModel::showCreatePet,
            onCreateAndStart = viewModel::createPetAndStartMeasurement,
            onStart = viewModel::startPetMeasurement,
            onCancel = viewModel::cancelPetMeasurement,
        ),
        snackbarHostState = snackbarHostState,
        onSectionSelected = {
            currentSection = it
            currentDestination = AppDestination.ROOT
        },
        onDestinationChanged = { currentDestination = it },
        onSettingsDestinationChanged = { settingsDestination = it },
        onProfileSelected = { key ->
            profileNavigation = profileNavigation.select(key)
            if (key is ProfileKey.Human) measurementsViewModel.callbacks.onAccountSelected(key.accountId)
        },
        onPetBack = { profileNavigation = profileNavigation.back() },
        onRefreshFromScale = viewModel::refreshFromScale,
        onToggleExternalSyncPause = viewModel::toggleExternalSyncPause,
        onCloseProfile = {},
        onSaveProfile = {},
        onProfileHeightChanged = {},
        onProfileBirthDateChanged = {},
        onProfileSexChanged = {},
        settingsCallbacks = SettingsCallbacks(
            onOpenChangelog = { currentDestination = AppDestination.CHANGELOG },
            onHuaweiAuthorization = viewModel::authorizeHuawei,
            onHuaweiPermissionRefresh = viewModel::refreshHuaweiAuthorization,
            onHealthConnectAuthorization = requestHealthConnectPermissions,
            onHealthConnectAccessManagement = openHealthConnectAccessManagement,
            onManualTest = viewModel::sendManualTest,
            onManualScan = viewModel::toggleManualScan,
            onReliabilityMode = viewModel::setReliabilityMode,
            openBatterySettings = openBatterySettings,
            openApplicationSettings = openApplicationSettings,
            onExportBackup = createBackup,
            onImportBackup = openBackup,
            onConfirmBackupImport = viewModel::requestBackupImport,
            onDismissBackupImport = viewModel::dismissBackupPreview,
            accountManagement = AccountManagementCallbacks(
                onAction = viewModel::onAccountManagementAction,
                onCreate = viewModel::createAccount,
                onUpdate = viewModel::updateAccount,
                onConfirmProfileUpdate = viewModel::confirmProfileUpdate,
                onSetPrimary = viewModel::setPrimaryAccount,
                onDelete = viewModel::deleteAccount,
                onDeletePrimary = viewModel::deletePrimaryAccount,
            ),
            onWeightDeltaStateChanged = viewModel::updateWeightDeltaEditor,
            onWeightDeltaSave = viewModel::saveWeightDelta,
            onIgnoreUnknownMeasurementsChanged = viewModel::setIgnoreUnknownMeasurements,
            onCreatePet = viewModel::showCreatePetManagement,
            onEditPet = viewModel::showEditPetManagement,
            onSavePet = viewModel::savePetManagement,
            onRequestDeletePet = viewModel::requestDeletePet,
            onConfirmDeletePet = viewModel::confirmDeletePet,
            onDismissPetManagement = viewModel::dismissPetManagement,
            onDisableHealthConnect = viewModel::disableHealthConnect,
            onDisableHuawei = viewModel::disableHuawei,
            onForgetScale = viewModel::forgetScale,
        ),
        resolverCallbacks = MeasurementResolverCallbacks(
            onAccountSelected = viewModel::choosePendingAccount,
            onCreateAccount = { pendingId ->
                viewModel.startCreateAccountForPending(pendingId)
                currentSection = AppSection.SETTINGS
            },
            onShowWithoutSaving = viewModel::showPendingWithoutSaving,
            onIgnoreUnknownMeasurementsChanged =
                viewModel::updateResolverIgnoreUnknownMeasurements,
            onDelete = viewModel::deletePendingFromResolver,
            onLater = viewModel::resolveLater,
        ),
        unsavedPreviewCallbacks = UnsavedPreviewCallbacks(
            onStateChange = viewModel::updateUnsavedPreview,
            onCalculate = viewModel::calculateUnsavedPreview,
            onCloseAndDiscard = viewModel::closeUnsavedPreviewAndDiscard,
        ),
        onOpenResolver = viewModel::openResolver,
        measurementsContent = { padding ->
            MeasurementsScreen(
                state = measurementsState,
                callbacks = measurementsViewModel.callbacks.copy(
                    onPendingAssignRequested = viewModel::openResolverFromQueue,
                    onPendingPreviewRequested = viewModel::showPendingWithoutSavingFromQueue,
                    onPendingDeleteRequested = viewModel::deletePendingFromQueue,
                ),
                showAccountSelector = false,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .consumeWindowInsets(padding),
            )
        },
        chartsContent = { padding ->
            ChartsScreen(
                state = chartsState,
                callbacks = chartsViewModel.callbacks,
                showAccountSelector = false,
                modifier = Modifier.fillMaxSize().padding(padding),
            )
        },
    )
}

/**
 * State-only shell used by production and Compose tests. Keeping Android integrations in
 * [ScaleSyncApp] lets navigation and profile validation be tested with deterministic fakes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScaleSyncScaffold(
    state: MainUiState,
    currentSection: AppSection,
    currentDestination: AppDestination = AppDestination.ROOT,
    settingsDestination: SettingsDestination = SettingsDestination.ROOT,
    profileSelection: ProfileSelectionUiState? = null,
    profileDestination: ProfileDestination = ProfileDestination.HumanShell,
    petHistoryState: PetHistoryUiState? = null,
    petHistoryCallbacks: PetHistoryCallbacks? = null,
    measurementsDestination: MeasurementsDestination,
    measurementsCallbacks: MeasurementsCallbacks,
    petMeasurementCallbacks: PetMeasurementCallbacks = PetMeasurementCallbacks.None,
    snackbarHostState: SnackbarHostState,
    onSectionSelected: (AppSection) -> Unit,
    onDestinationChanged: (AppDestination) -> Unit = {},
    onSettingsDestinationChanged: (SettingsDestination) -> Unit = {},
    onProfileSelected: (ProfileKey) -> Unit = {},
    onPetBack: () -> Unit = {},
    onCloseProfile: () -> Unit,
    onSaveProfile: () -> Unit,
    onProfileHeightChanged: (String) -> Unit,
    onProfileBirthDateChanged: (String) -> Unit,
    onProfileSexChanged: (Sex) -> Unit,
    settingsCallbacks: SettingsCallbacks,
    onRefreshFromScale: () -> Unit = {},
    onToggleExternalSyncPause: () -> Unit = {},
    resolverCallbacks: MeasurementResolverCallbacks = MeasurementResolverCallbacks.None,
    unsavedPreviewCallbacks: UnsavedPreviewCallbacks = UnsavedPreviewCallbacks.None,
    onOpenResolver: () -> Unit = {},
    measurementsContent: @Composable (PaddingValues) -> Unit,
    chartsContent: @Composable (PaddingValues) -> Unit,
) {
    val petDestination = profileDestination as? ProfileDestination.PetShell
    val petProfile = petDestination?.let { destination ->
        state.pets.firstOrNull { it.pet.id == destination.petId }
    }
    val profileEditorOpen = state.profileEditor.isOpen
    val changelogOpen = !profileEditorOpen && currentDestination == AppDestination.CHANGELOG
    val settingsDetailOpen = currentSection == AppSection.SETTINGS &&
        settingsDestination != SettingsDestination.ROOT && !changelogOpen
    val settingsBackFocusRequester = remember { FocusRequester() }
    LaunchedEffect(settingsDetailOpen, settingsDestination) {
        if (settingsDetailOpen) settingsBackFocusRequester.requestFocus()
    }
    val measurementsChrome = measurementsChromeFor(measurementsDestination)
    val showTopBar = when {
        profileEditorOpen -> true
        currentSection == AppSection.MEASUREMENTS -> measurementsChrome.showTopBar
        else -> true
    }
    val showBottomNavigation = petDestination == null && !profileEditorOpen && !changelogOpen &&
        !settingsDetailOpen && when (currentSection) {
        AppSection.MEASUREMENTS -> measurementsChrome.showBottomNavigation
        AppSection.CHARTS, AppSection.SETTINGS -> true
    }
    val contentWindowInsets = if (
        !profileEditorOpen &&
        currentSection == AppSection.MEASUREMENTS &&
        measurementsChrome.contentUsesSafeDrawingInsets
    ) {
        WindowInsets.safeDrawing
    } else {
        WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)
    }

    BackHandler(
        enabled = changelogOpen,
        onBack = { onDestinationChanged(AppDestination.ROOT) },
    )
    BackHandler(
        enabled = settingsDetailOpen,
        onBack = { onSettingsDestinationChanged(SettingsDestination.ROOT) },
    )
    BackHandler(enabled = petDestination != null, onBack = onPetBack)
    BackHandler(
        enabled = !profileEditorOpen &&
            currentSection == AppSection.MEASUREMENTS &&
            measurementsDestination != MeasurementsDestination.SUMMARY,
        onBack = measurementsCallbacks.onBackRequested,
    )
    BackHandler(enabled = profileEditorOpen, onBack = onCloseProfile)

    ScaleSyncTheme {
        Box(Modifier.fillMaxSize()) {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.onBackground,
                contentWindowInsets = contentWindowInsets,
                topBar = {
                    if (showTopBar) {
                        HuaweiTopBar(
                            title = when {
                                profileEditorOpen -> "Профиль"
                                changelogOpen -> "История изменений"
                                settingsDetailOpen -> settingsDestination.title
                                petDestination != null -> petHistoryState?.pet?.displayName
                                    ?: petProfile?.pet?.displayName
                                    ?: "Питомец"
                                else -> currentSection.title
                            },
                            showBack = profileEditorOpen || changelogOpen || petDestination != null || settingsDetailOpen,
                            onBack = if (petDestination != null) {
                                onPetBack
                            } else if (changelogOpen) {
                                { onDestinationChanged(AppDestination.ROOT) }
                            } else if (settingsDetailOpen) {
                                { onSettingsDestinationChanged(SettingsDestination.ROOT) }
                            } else {
                                onCloseProfile
                            },
                            backContentDescription = mainBackContentDescription(
                                changelogOpen = changelogOpen,
                                petProfileOpen = petDestination != null,
                                settingsDetailOpen = settingsDetailOpen,
                            ),
                            backModifier = if (settingsDetailOpen) {
                                Modifier
                                    .testTag(MainScreenTestTags.SettingsBack)
                                    .focusRequester(settingsBackFocusRequester)
                            } else {
                                Modifier
                            },
                            showMeasurementActions = petDestination == null && !profileEditorOpen &&
                                currentSection == AppSection.MEASUREMENTS &&
                                measurementsDestination == MeasurementsDestination.SUMMARY,
                            pendingCount = state.resolverQueue.pendingCount,
                            onPendingQueueRequested =
                                measurementsCallbacks.onPendingQueueRequested,
                            onHistoryRequested = measurementsCallbacks.onHistoryRequested,
                            petMeasurementEnabled = !state.scanning && !state.isRefreshing &&
                                state.petMeasurement == PetMeasurementUiState.Idle,
                            onPetMeasurementRequested = petMeasurementCallbacks.onOpen,
                            isExternalSyncPaused = state.isExternalSyncPaused,
                            onToggleExternalSyncPause = onToggleExternalSyncPause,
                        )
                    }
                },
                bottomBar = {
                    when {
                        profileEditorOpen -> ProfileEditorSaveBar(
                            onSave = onSaveProfile,
                        )
                        showBottomNavigation -> HuaweiBottomNavigation(
                            selectedSection = currentSection,
                            onSectionSelected = onSectionSelected,
                        )
                    }
                },
                snackbarHost = {
                    SnackbarHost(
                        hostState = snackbarHostState,
                        modifier = Modifier.testTag(MainScreenTestTags.SnackbarHost),
                    )
                },
            ) { padding ->
                when {
                    petDestination != null -> PetProfileScreen(
                        state = petHistoryState ?: PetHistoryUiState.initial(petDestination.petId),
                        callbacks = petHistoryCallbacks ?: PetHistoryCallbacks({}, { _, _ -> }),
                        contentPadding = padding,
                        onStartMeasurement = { petMeasurementCallbacks.onStart(petDestination.petId) },
                    )

                    profileEditorOpen -> ProfileEditorScreen(
                        state = state.profileEditor,
                        onHeightChanged = onProfileHeightChanged,
                        onBirthDateChanged = onProfileBirthDateChanged,
                        onSexChanged = onProfileSexChanged,
                        contentPadding = padding,
                    )

                    changelogOpen -> ChangelogScreen(contentPadding = padding)

                    currentSection == AppSection.SETTINGS -> Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .consumeWindowInsets(padding),
                    ) {
                        SettingsScreen(
                            state = state,
                            callbacks = settingsCallbacks,
                            contentPadding = PaddingValues(),
                            destination = settingsDestination,
                            onDestinationChanged = onSettingsDestinationChanged,
                        )
                    }

                    currentSection == AppSection.MEASUREMENTS &&
                        measurementsDestination == MeasurementsDestination.SUMMARY -> {
                        val pullToRefreshState = rememberPullToRefreshState()
                        PullToRefreshBox(
                            isRefreshing = state.isRefreshing,
                            onRefresh = onRefreshFromScale,
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag(MainScreenTestTags.PullToRefresh),
                            state = pullToRefreshState,
                            indicator = {
                                PullToRefreshDefaults.Indicator(
                                    state = pullToRefreshState,
                                    isRefreshing = state.isRefreshing,
                                    modifier = Modifier
                                        .align(Alignment.TopCenter)
                                        .padding(top = padding.calculateTopPadding())
                                        .testTag(MainScreenTestTags.PullToRefreshIndicator),
                                )
                            },
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(padding)
                                    .consumeWindowInsets(padding),
                            ) {
                                profileSelection?.let { selection ->
                                    ProfileSelector(
                                        state = selection,
                                        onProfileSelected = onProfileSelected,
                                        modifier = Modifier.padding(
                                            horizontal = HuaweiDimensions.ContentPadding,
                                            vertical = HuaweiDimensions.CompactContentPadding,
                                        ),
                                    )
                                }
                                Box(Modifier.weight(1f)) { measurementsContent(PaddingValues()) }
                            }
                        }
                    }

                    currentSection == AppSection.MEASUREMENTS -> measurementsContent(padding)

                    else -> Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .consumeWindowInsets(padding),
                    ) {
                        profileSelection?.let { selection ->
                            ProfileSelector(
                                state = selection,
                                onProfileSelected = onProfileSelected,
                                modifier = Modifier.padding(
                                    horizontal = HuaweiDimensions.ContentPadding,
                                    vertical = HuaweiDimensions.CompactContentPadding,
                                ),
                            )
                        }
                        Box(Modifier.weight(1f)) { chartsContent(PaddingValues()) }
                    }
                }
            }
            PendingResolverForegroundFallback(
                state = state.resolverQueue,
                onOpen = onOpenResolver,
                modifier = Modifier
                    .padding(horizontal = HuaweiDimensions.ContentPadding)
                    .padding(top = HuaweiDimensions.ContentPadding),
            )
            state.resolver?.let { resolver ->
                MeasurementResolverDialog(
                    state = resolver,
                    callbacks = resolverCallbacks,
                )
            }
            state.unsavedPreview?.let { preview ->
                UnsavedMeasurementPreviewDialog(
                    state = preview,
                    callbacks = unsavedPreviewCallbacks,
                    snackbarHostState = snackbarHostState,
                )
            }
            PetMeasurementDialog(
                state = state.petMeasurement,
                pets = state.pets,
                callbacks = petMeasurementCallbacks,
            )
            HuaweiSystemBarBackgrounds()
        }
    }
}

internal fun mainBackContentDescription(
    changelogOpen: Boolean,
    petProfileOpen: Boolean,
    settingsDetailOpen: Boolean = false,
): String = when {
    changelogOpen -> "Вернуться к настройкам"
    settingsDetailOpen -> "Вернуться к настройкам"
    petProfileOpen -> "Вернуться к профилям"
    else -> "Закрыть редактор профиля"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HuaweiTopBar(
    title: String,
    showBack: Boolean,
    onBack: () -> Unit,
    backContentDescription: String,
    backModifier: Modifier,
    showMeasurementActions: Boolean,
    pendingCount: Int,
    onPendingQueueRequested: () -> Unit,
    onHistoryRequested: () -> Unit,
    petMeasurementEnabled: Boolean,
    onPetMeasurementRequested: () -> Unit,
    isExternalSyncPaused: Boolean,
    onToggleExternalSyncPause: () -> Unit,
) {
    TopAppBar(
        modifier = Modifier.testTag(MainScreenTestTags.TopBar),
        title = {
            Text(
                text = title,
                modifier = Modifier.testTag(MainScreenTestTags.TopBarTitle),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        navigationIcon = {
            if (showBack) {
                HuaweiIconButton(
                    icon = HuaweiIcons.Back,
                    contentDescription = backContentDescription,
                    onClick = onBack,
                    modifier = backModifier,
                )
            }
        },
        actions = {
            if (showMeasurementActions) {
                PendingQueueAction(
                    pendingCount = pendingCount,
                    onClick = onPendingQueueRequested,
                )
                HuaweiIconButton(
                    icon = HuaweiIcons.Calendar,
                    contentDescription = "Открыть историю измерений",
                    onClick = onHistoryRequested,
                    modifier = Modifier.testTag(MainScreenTestTags.HistoryAction),
                )
                HuaweiIconButton(
                    icon = HuaweiIcons.Dog,
                    contentDescription = "Взвесить питомца",
                    onClick = onPetMeasurementRequested,
                    modifier = Modifier.testTag(MainScreenTestTags.PetMeasurementAction),
                    enabled = petMeasurementEnabled,
                )
                HuaweiIconButton(
                    icon = if (isExternalSyncPaused) HuaweiIcons.Play else HuaweiIcons.Pause,
                    contentDescription = if (isExternalSyncPaused) {
                        "Возобновить внешнюю синхронизацию"
                    } else {
                        "Приостановить внешнюю синхронизацию"
                    },
                    onClick = onToggleExternalSyncPause,
                    modifier = Modifier
                        .testTag(MainScreenTestTags.ExternalSyncAction)
                        .semantics { externalSyncPaused = isExternalSyncPaused },
                    colors = if (isExternalSyncPaused) {
                        IconButtonDefaults.iconButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        )
                    } else {
                        IconButtonDefaults.iconButtonColors()
                    },
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            titleContentColor = MaterialTheme.colorScheme.onBackground,
        ),
    )
}

@Composable
private fun PendingQueueAction(
    pendingCount: Int,
    onClick: () -> Unit,
) {
    Box {
        HuaweiIconButton(
            icon = HuaweiIcons.Pending,
            contentDescription = if (pendingCount == 0) {
                "Открыть неназначенные измерения. Очередь пуста"
            } else {
                "Открыть неназначенные измерения. Ожидают назначения: $pendingCount"
            },
            onClick = onClick,
            modifier = Modifier.testTag(MainScreenTestTags.PendingQueueAction),
        )
        if (pendingCount > 0) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 6.dp, end = 6.dp)
                    .size(if (pendingCount <= 9) 16.dp else 8.dp)
                    .testTag(MainScreenTestTags.PendingQueueBadge),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ) {
                if (pendingCount <= 9) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = pendingCount.toString(),
                            modifier = Modifier.clearAndSetSemantics { },
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HuaweiBottomNavigation(
    selectedSection: AppSection,
    onSectionSelected: (AppSection) -> Unit,
) {
    NavigationBar(
        modifier = Modifier.testTag(MainScreenTestTags.BottomNavigation),
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        tonalElevation = 0.dp,
    ) {
        AppSection.entries.forEach { section ->
            NavigationBarItem(
                selected = selectedSection == section,
                onClick = { onSectionSelected(section) },
                modifier = Modifier.heightIn(min = HuaweiDimensions.BottomNavigationItemHeight),
                icon = {
                    Icon(
                        imageVector = section.icon,
                        contentDescription = section.title,
                    )
                },
                label = { Text(section.title) },
            )
        }
    }
}
