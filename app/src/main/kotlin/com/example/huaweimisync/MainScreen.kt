package com.example.huaweimisync

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
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
import com.example.huaweimisync.measurements.MeasurementsScreen
import com.example.huaweimisync.measurements.MeasurementsUiEvent
import com.example.huaweimisync.measurements.MeasurementsUiState
import com.example.huaweimisync.ui.components.HuaweiIconButton
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

/**
 * Integration seam for the Measurements redesign branch. Its destination contract can map state
 * to shell chrome without coupling this state-based app shell to a concrete nested destination.
 */
fun interface MeasurementsChromePolicy {
    fun resolve(state: MeasurementsUiState): MeasurementsChrome
}

data class MeasurementsChrome(
    val showTopBar: Boolean,
    val showBottomNavigation: Boolean,
)

internal val legacyMeasurementsChromePolicy = MeasurementsChromePolicy { state ->
    val isEditorOpen = state.editor != null
    MeasurementsChrome(
        showTopBar = !isEditorOpen,
        showBottomNavigation = !isEditorOpen,
    )
}

internal object MainScreenTestTags {
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
    measurementsChromePolicy: MeasurementsChromePolicy = legacyMeasurementsChromePolicy,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val measurementsState by measurementsViewModel.uiState.collectAsStateWithLifecycle()
    val chartsState by chartsViewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var currentSection by rememberSaveable { mutableStateOf(defaultAppSection) }
    val profileEditorOpen = state.profileEditor.isOpen
    val measurementsChrome = measurementsChromePolicy.resolve(measurementsState)

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
            }
        }
    }
    BackHandler(
        enabled = !profileEditorOpen &&
            currentSection == AppSection.MEASUREMENTS &&
            measurementsState.editor != null,
        onBack = measurementsViewModel.callbacks.onEditorDismissed,
    )

    HuaweiMiSyncScaffold(
        state = state,
        currentSection = currentSection,
        measurementsChrome = measurementsChrome,
        snackbarHostState = snackbarHostState,
        onSectionSelected = { currentSection = it },
        onCloseProfile = viewModel::closeProfileEditor,
        onSaveProfile = viewModel::saveProfile,
        onProfileHeightChanged = viewModel::updateProfileHeight,
        onProfileBirthDateChanged = viewModel::updateProfileBirthDate,
        onProfileSexChanged = viewModel::updateProfileSex,
        settingsCallbacks = SettingsCallbacks(
            onOpenProfile = viewModel::openProfileEditor,
            onHuaweiAuthorization = viewModel::authorizeHuawei,
            onHealthConnectAuthorization = requestHealthConnectPermissions,
            onHealthConnectAccessManagement = openHealthConnectAccessManagement,
            onManualTest = viewModel::sendManualTest,
            onManualScan = viewModel::toggleManualScan,
            onReliabilityMode = viewModel::setReliabilityMode,
            openBatterySettings = openBatterySettings,
            openApplicationSettings = openApplicationSettings,
        ),
        measurementsContent = { padding ->
            MeasurementsScreen(
                state = measurementsState,
                callbacks = measurementsViewModel.callbacks,
                modifier = Modifier.fillMaxSize().padding(padding),
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
    measurementsChrome: MeasurementsChrome,
    snackbarHostState: SnackbarHostState,
    onSectionSelected: (AppSection) -> Unit,
    onCloseProfile: () -> Unit,
    onSaveProfile: () -> Unit,
    onProfileHeightChanged: (String) -> Unit,
    onProfileBirthDateChanged: (String) -> Unit,
    onProfileSexChanged: (Sex) -> Unit,
    settingsCallbacks: SettingsCallbacks,
    measurementsContent: @Composable (PaddingValues) -> Unit,
    chartsContent: @Composable (PaddingValues) -> Unit,
) {
    val profileEditorOpen = state.profileEditor.isOpen
    val showTopBar = when {
        profileEditorOpen -> true
        currentSection == AppSection.MEASUREMENTS -> measurementsChrome.showTopBar
        else -> true
    }
    val showBottomNavigation = !profileEditorOpen && when (currentSection) {
        AppSection.MEASUREMENTS -> measurementsChrome.showBottomNavigation
        AppSection.CHARTS, AppSection.SETTINGS -> true
    }

    BackHandler(enabled = profileEditorOpen, onBack = onCloseProfile)

    HuaweiMiSyncTheme {
        Box(Modifier.fillMaxSize()) {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.onBackground,
                contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
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
