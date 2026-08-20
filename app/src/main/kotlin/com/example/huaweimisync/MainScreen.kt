package com.example.huaweimisync

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.huaweimisync.charts.ChartsScreen
import com.example.huaweimisync.core.Sex
import com.example.huaweimisync.measurements.MeasurementsCallbacks
import com.example.huaweimisync.measurements.MeasurementsDestination
import com.example.huaweimisync.measurements.MeasurementsScreen
import com.example.huaweimisync.measurements.MeasurementsUiEvent
import com.example.huaweimisync.measurements.toPendingMeasurementUiItem
import com.example.huaweimisync.ui.components.HuaweiIconButton
import com.example.huaweimisync.ui.accounts.AccountManagementCallbacks
import com.example.huaweimisync.ui.routing.MeasurementResolverCallbacks
import com.example.huaweimisync.ui.routing.MeasurementResolverDialog
import com.example.huaweimisync.ui.routing.PendingResolverForegroundFallback
import com.example.huaweimisync.ui.routing.PendingResolverReturnDestination
import com.example.huaweimisync.ui.routing.UnsavedMeasurementPreviewDialog
import com.example.huaweimisync.ui.routing.UnsavedPreviewCallbacks
import com.example.huaweimisync.ui.components.HuaweiSystemBarBackgrounds
import com.example.huaweimisync.ui.icons.HuaweiIcons
import com.example.huaweimisync.ui.theme.HuaweiDimensions
import com.example.huaweimisync.ui.theme.HuaweiMiSyncTheme

internal enum class AppSection(
    val title: String,
    val icon: ImageVector,
) {
    MEASUREMENTS("Измерения", HuaweiIcons.Scale),
    CHARTS("Графики", HuaweiIcons.Charts),
    SETTINGS("Настройки", HuaweiIcons.Settings),
}

internal val defaultAppSection = AppSection.MEASUREMENTS

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
    const val BottomNavigation = "main-bottom-navigation"
    const val SnackbarHost = "main-snackbar-host"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HuaweiMiSyncApp(
    viewModel: MainViewModel,
    measurementsViewModel: MeasurementsViewModel,
    chartsViewModel: ChartsViewModel,
    requestHealthConnectPermissions: () -> Unit,
    openHealthConnectAccessManagement: () -> Unit,
    openBatterySettings: () -> Unit,
    openApplicationSettings: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val measurementsState by measurementsViewModel.uiState.collectAsStateWithLifecycle()
    val chartsState by chartsViewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var currentSection by rememberSaveable { mutableStateOf(defaultAppSection) }
    LaunchedEffect(measurementsViewModel) {
        measurementsViewModel.events.collect { event ->
            when (event) {
                is MeasurementsUiEvent.ShowSnackbar -> snackbarHostState.showSnackbar(event.message)
            }
        }
    }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is MainUiEvent.ShowSnackbar -> snackbarHostState.showSnackbar(event.message)
                is MainUiEvent.PendingResolutionCompleted -> {
                    measurementsViewModel.onPendingResolutionCompleted(event.returnDestination)
                    if (
                        event.returnDestination ==
                        PendingResolverReturnDestination.PENDING_QUEUE
                    ) {
                        currentSection = AppSection.MEASUREMENTS
                    }
                }
            }
        }
    }
    HuaweiMiSyncScaffold(
        state = state,
        currentSection = currentSection,
        measurementsDestination = measurementsState.destination,
        measurementsCallbacks = measurementsViewModel.callbacks,
        snackbarHostState = snackbarHostState,
        onSectionSelected = { currentSection = it },
        onCloseProfile = {},
        onSaveProfile = {},
        onProfileHeightChanged = {},
        onProfileBirthDateChanged = {},
        onProfileSexChanged = {},
        settingsCallbacks = SettingsCallbacks(
            onHuaweiAuthorization = viewModel::authorizeHuawei,
            onHuaweiPermissionRefresh = viewModel::refreshHuaweiAuthorization,
            onHealthConnectAuthorization = requestHealthConnectPermissions,
            onHealthConnectAccessManagement = openHealthConnectAccessManagement,
            onManualTest = viewModel::sendManualTest,
            onManualScan = viewModel::toggleManualScan,
            onReliabilityMode = viewModel::setReliabilityMode,
            openBatterySettings = openBatterySettings,
            openApplicationSettings = openApplicationSettings,
            accountManagement = AccountManagementCallbacks(
                onAction = viewModel::onAccountManagementAction,
                onCreate = viewModel::createAccount,
                onUpdate = viewModel::updateAccount,
                onSetPrimary = viewModel::setPrimaryAccount,
                onDelete = viewModel::deleteAccount,
                onDeletePrimary = viewModel::deletePrimaryAccount,
            ),
            onWeightDeltaStateChanged = viewModel::updateWeightDeltaEditor,
            onWeightDeltaSave = viewModel::saveWeightDelta,
        ),
        resolverCallbacks = MeasurementResolverCallbacks(
            onAccountSelected = viewModel::choosePendingAccount,
            onCreateAccount = { pendingId ->
                viewModel.startCreateAccountForPending(pendingId)
                currentSection = AppSection.SETTINGS
            },
            onShowWithoutSaving = viewModel::showPendingWithoutSaving,
            onDelete = viewModel::deletePendingFromResolver,
            onLater = viewModel::resolveLater,
        ),
        unsavedPreviewCallbacks = UnsavedPreviewCallbacks(
            onStateChange = viewModel::updateUnsavedPreview,
            onCloseAndDiscard = viewModel::closeUnsavedPreviewAndDiscard,
        ),
        onOpenResolver = viewModel::openResolver,
        measurementsContent = { padding ->
            MeasurementsScreen(
                state = measurementsState.copy(
                    pendingCount = state.resolverQueue.pendingCount,
                    pendingMeasurements = state.resolverQueue.pending.map {
                        it.toPendingMeasurementUiItem()
                    },
                ),
                callbacks = measurementsViewModel.callbacks.copy(
                    onPendingAssignRequested = viewModel::openResolverFromQueue,
                    onPendingPreviewRequested = viewModel::showPendingWithoutSavingFromQueue,
                ),
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
                modifier = Modifier.fillMaxSize().padding(padding),
            )
        },
    )
}

/**
 * State-only shell used by production and Compose tests. Keeping Android integrations in
 * [HuaweiMiSyncApp] lets navigation and profile validation be tested with deterministic fakes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HuaweiMiSyncScaffold(
    state: MainUiState,
    currentSection: AppSection,
    measurementsDestination: MeasurementsDestination,
    measurementsCallbacks: MeasurementsCallbacks,
    snackbarHostState: SnackbarHostState,
    onSectionSelected: (AppSection) -> Unit,
    onCloseProfile: () -> Unit,
    onSaveProfile: () -> Unit,
    onProfileHeightChanged: (String) -> Unit,
    onProfileBirthDateChanged: (String) -> Unit,
    onProfileSexChanged: (Sex) -> Unit,
    settingsCallbacks: SettingsCallbacks,
    resolverCallbacks: MeasurementResolverCallbacks = MeasurementResolverCallbacks.None,
    unsavedPreviewCallbacks: UnsavedPreviewCallbacks = UnsavedPreviewCallbacks.None,
    onOpenResolver: () -> Unit = {},
    measurementsContent: @Composable (PaddingValues) -> Unit,
    chartsContent: @Composable (PaddingValues) -> Unit,
) {
    val profileEditorOpen = state.profileEditor.isOpen
    val measurementsChrome = measurementsChromeFor(measurementsDestination)
    val showTopBar = when {
        profileEditorOpen -> true
        currentSection == AppSection.MEASUREMENTS -> measurementsChrome.showTopBar
        else -> true
    }
    val showBottomNavigation = !profileEditorOpen && when (currentSection) {
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
        enabled = !profileEditorOpen &&
            currentSection == AppSection.MEASUREMENTS &&
            measurementsDestination != MeasurementsDestination.SUMMARY,
        onBack = measurementsCallbacks.onBackRequested,
    )
    BackHandler(enabled = profileEditorOpen, onBack = onCloseProfile)

    HuaweiMiSyncTheme {
        Box(Modifier.fillMaxSize()) {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.onBackground,
                contentWindowInsets = contentWindowInsets,
                topBar = {
                    if (showTopBar) {
                        HuaweiTopBar(
                            title = if (profileEditorOpen) "Профиль" else currentSection.title,
                            showBack = profileEditorOpen,
                            onBack = onCloseProfile,
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
                    profileEditorOpen -> ProfileEditorScreen(
                        state = state.profileEditor,
                        onHeightChanged = onProfileHeightChanged,
                        onBirthDateChanged = onProfileBirthDateChanged,
                        onSexChanged = onProfileSexChanged,
                        contentPadding = padding,
                    )

                    currentSection == AppSection.SETTINGS -> SettingsScreen(
                        state = state,
                        callbacks = settingsCallbacks,
                        contentPadding = padding,
                    )

                    currentSection == AppSection.MEASUREMENTS -> measurementsContent(padding)

                    else -> chartsContent(padding)
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
                )
            }
            HuaweiSystemBarBackgrounds()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HuaweiTopBar(
    title: String,
    showBack: Boolean,
    onBack: () -> Unit,
) {
    TopAppBar(
        modifier = Modifier.testTag(MainScreenTestTags.TopBar),
        title = { Text(title) },
        navigationIcon = {
            if (showBack) {
                HuaweiIconButton(
                    icon = HuaweiIcons.Back,
                    contentDescription = "Закрыть редактор профиля",
                    onClick = onBack,
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
