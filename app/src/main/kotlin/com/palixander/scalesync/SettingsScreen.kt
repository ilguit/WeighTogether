package com.palixander.scalesync

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.palixander.scalesync.data.AppSettings
import com.palixander.scalesync.core.Sex
import com.palixander.scalesync.core.UserProfile
import com.palixander.scalesync.backup.BackupImportMode
import com.palixander.scalesync.domain.Pet
import com.palixander.scalesync.domain.PetId
import com.palixander.scalesync.domain.PetWithLatestWeight
import com.palixander.scalesync.ui.components.HuaweiFilterButton
import com.palixander.scalesync.ui.appLocale
import com.palixander.scalesync.ui.accounts.AccountManagementCallbacks
import com.palixander.scalesync.ui.accounts.AccountManagementSection
import com.palixander.scalesync.ui.accounts.AccountEditorScreen
import com.palixander.scalesync.ui.accounts.WeightRecognitionSetting
import com.palixander.scalesync.ui.components.HuaweiIconButton
import com.palixander.scalesync.ui.components.HuaweiSettingRow
import com.palixander.scalesync.ui.components.HuaweiSurface
import com.palixander.scalesync.ui.icons.HuaweiIcons
import com.palixander.scalesync.ui.settings.SettingsGroup
import com.palixander.scalesync.ui.settings.SettingsGroupDivider
import com.palixander.scalesync.ui.settings.SettingsGroupRow
import com.palixander.scalesync.ui.settings.SettingsStatusMark
import com.palixander.scalesync.ui.theme.HuaweiColors
import com.palixander.scalesync.ui.theme.HuaweiDimensions
import com.palixander.scalesync.ui.text.UiText
import com.palixander.scalesync.ui.text.resolve
import com.palixander.scalesync.ui.text.uiText
import com.palixander.scalesync.ui.text.pluralUiText
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal data class SettingsCallbacks(
    val onOpenProfile: () -> Unit = {},
    val onOpenChangelog: () -> Unit = {},
    val onHealthConnectAuthorization: () -> Unit,
    val onHealthConnectAccessManagement: () -> Unit,
    val onManualScan: () -> Unit,
    val onReliabilityMode: (Boolean) -> Unit,
    val openBatterySettings: () -> Unit,
    val openApplicationSettings: () -> Unit,
    val accountManagement: AccountManagementCallbacks = AccountManagementCallbacks.None,
    val onWeightDeltaStateChanged: (com.palixander.scalesync.ui.accounts.WeightDeltaEditorState) -> Unit = {},
    val onWeightDeltaSave: (Double) -> Unit = {},
    val onIgnoreUnknownMeasurementsChanged: (Boolean) -> Unit = {},
    val onCreatePet: () -> Unit = {},
    val onEditPet: (Pet) -> Unit = {},
    val onPetProfileAction: (PetProfileAction) -> Unit = {},
    val onSavePet: () -> Unit = {},
    val onRequestDeletePet: (PetId) -> Unit = {},
    val onConfirmDeletePet: () -> Unit = {},
    val onDismissPetManagement: () -> Unit = {},
    val onExportBackup: () -> Unit = {},
    val onImportBackup: (BackupImportMode) -> Unit = {},
    val onConfirmBackupImport: () -> Unit = {},
    val onDismissBackupImport: () -> Unit = {},
    val onDisableHealthConnect: () -> Unit = {},
    val onForgetScale: () -> Unit = {},
)

internal enum class SettingsSectionExpansion {
    Collapsed,
    Expanded,
    ;

    fun toggled(): SettingsSectionExpansion = when (this) {
        Collapsed -> Expanded
        Expanded -> Collapsed
    }
}

internal enum class SettingsSectionKey(val titleRes: Int) {
    ACCOUNTS(R.string.settings_profiles),
    INTEGRATIONS(R.string.settings_integrations),
    SCALE(R.string.settings_scales),
    BACKUP(R.string.settings_backup),
    ADDITIONAL(R.string.settings_additional),
    ABOUT(R.string.settings_about),
}

internal enum class SettingsDestination(val titleRes: Int) {
    ROOT(R.string.nav_settings),
    PROFILES(R.string.settings_profiles),
    SCALE(R.string.settings_scales),
    HEALTH_CONNECT(R.string.settings_health_connect),
    BACKUP(R.string.settings_backup),
    DIAGNOSTICS(R.string.settings_diagnostics),
}

internal fun settingsRootDestinations(): List<SettingsDestination> = buildList {
    add(SettingsDestination.PROFILES)
    add(SettingsDestination.SCALE)
    add(SettingsDestination.HEALTH_CONNECT)
    add(SettingsDestination.BACKUP)
    add(SettingsDestination.DIAGNOSTICS)
}

internal fun settingsRootIcon(destination: SettingsDestination) = when (destination) {
    SettingsDestination.PROFILES -> HuaweiIcons.Users
    SettingsDestination.SCALE -> HuaweiIcons.Bluetooth
    SettingsDestination.HEALTH_CONNECT -> HuaweiIcons.HealthConnect
    SettingsDestination.BACKUP -> HuaweiIcons.Archive
    SettingsDestination.DIAGNOSTICS -> HuaweiIcons.Stethoscope
    SettingsDestination.ROOT -> HuaweiIcons.Settings
}

internal fun scaleRootStatusSuccessful(presentation: ScaleSettingsPresentation): Boolean =
    presentation.status == ScaleSettingsStatus.READY

internal fun healthRootStatusSuccessful(
    state: HealthConnectPermissionsUiState,
    locallyEnabled: Boolean,
): Boolean = state.availability == HealthConnectAvailability.AVAILABLE &&
    state.isConnected && locallyEnabled

internal fun settingsDetailDestructiveAction(
    destination: SettingsDestination,
    state: MainUiState,
): DestructiveSettingsAction? = when (destination) {
    SettingsDestination.SCALE -> DestructiveSettingsAction.SCALE.takeIf {
        scalePresentation(state).allowForget
    }
    SettingsDestination.HEALTH_CONNECT -> DestructiveSettingsAction.HEALTH_CONNECT.takeIf {
        state.settings.healthConnectSyncEnabled && state.healthConnect.isConnected
    }
    else -> null
}

internal data class IntegrationPresentation(
    val supportingText: UiText,
    val actionLabel: UiText? = null,
    val actionEnabled: Boolean = true,
    val actionOpensManagement: Boolean = false,
    val actionRetriesCheck: Boolean = false,
)

internal object SettingsScreenTestTags {
    const val List = "settings-list"
    const val Detail = "settings-detail"
    const val ProfilesRow = "settings-profiles-row"
    const val ScaleRow = "settings-scale-row"
    const val BackupRow = "settings-backup-row"
    const val DiagnosticsRow = "settings-diagnostics-row"
    const val ProfileRow = "settings-profile-row"
    const val HealthConnectRow = "settings-health-connect-row"
    const val HealthConnectAction = "settings-health-connect-action"
    const val HealthConnectDetail = "settings-health-connect-detail"
    const val ScaleDetail = "settings-scale-detail"
    const val ScaleStatus = "settings-scale-status"
    const val ScaleAction = "settings-scale-action"
    const val ScaleProgress = "settings-scale-progress"
    const val AccountsSection = "settings-section-accounts"
    const val IntegrationsSection = "settings-section-integrations"
    const val ScaleSection = "settings-section-scale"
    const val BackupSection = "settings-section-backup"
    const val AdditionalSection = "settings-section-additional"
    const val AboutSection = "settings-section-about"
    const val AccountsContent = "settings-content-accounts"
    const val IntegrationsContent = "settings-content-integrations"
    const val ScaleContent = "settings-content-scale"
    const val BackupContent = "settings-content-backup"
    const val AboutContent = "settings-content-about"
    const val AdditionalToggle = AdditionalSection
    const val AdditionalContent = "settings-additional-content"
    const val ManualTestWeight = "settings-manual-test-weight"
    const val ManualTestImpedance = "settings-manual-test-impedance"
    const val ChangelogRow = "settings-changelog-row"
    const val LanguageRow = "settings-language-row"
    const val LanguageDialog = "settings-language-dialog"
    const val LanguageOptionPrefix = "settings-language-option-"
    const val ProfileEditor = "profile-editor"
    const val ProfileEditorError = "profile-editor-error"
    const val BackupExport = "settings-backup-export"
    const val BackupMerge = "settings-backup-merge"
    const val BackupReplace = "settings-backup-replace"
    const val BackupDialog = "settings-backup-dialog"
    const val BackupConfirm = "settings-backup-confirm"
    const val BackupDetail = "settings-backup-detail"
    const val DiagnosticsDetail = "settings-diagnostics-detail"
    const val PetsSection = "settings-pets-section"
    const val PetDeleteDialog = "settings-pet-delete-dialog"
    const val DestructiveSection = "settings-destructive-section"
    const val DisableHealthConnect = "settings-disable-health-connect"
    const val DisableHuawei = "settings-disable-huawei"
    const val ForgetScale = "settings-forget-scale"
    const val DestructiveDialog = "settings-destructive-dialog"
    const val DestructiveConfirm = "settings-destructive-confirm"
    const val IntegrationStatus = "settings-integration-status"
    const val DetailHero = "settings-detail-hero"
    const val DetailHeroIcon = "settings-detail-hero-icon"
    const val DetailPrimaryAction = "settings-detail-primary-action"
    const val DetailStatusGroup = "settings-detail-status-group"
    const val DetailStatusDivider = "settings-detail-status-divider"
    const val DetailDangerZone = "settings-detail-danger-zone"
    const val BackupSaveGroup = "settings-backup-save-group"
    const val BackupRestoreGroup = "settings-backup-restore-group"
    const val BackupRestoreDivider = "settings-backup-restore-divider"
    const val DiagnosticsMeasurementGroup = "settings-diagnostics-measurement-group"
    const val DiagnosticsBackgroundGroup = "settings-diagnostics-background-group"
    const val DiagnosticsBackgroundDivider = "settings-diagnostics-background-divider"
    const val DiagnosticsBackgroundSecondDivider = "settings-diagnostics-background-divider-second"
    const val ManualTestAction = "settings-manual-test-action"
    const val ProfilesGroup = "settings-group-profiles"
    const val ConnectionsGroup = "settings-group-connections"
    const val SupportGroup = "settings-group-support"
    const val ConnectionsDivider = "settings-group-connections-divider"
    const val SupportDivider = "settings-group-support-divider"
    const val SupportSecondDivider = "settings-group-support-divider-second"
    const val ConnectionsSecondDivider = "settings-group-connections-divider-second"
    const val RootDivider = "settings-root-divider"
    const val ConnectionsHeading = "settings-heading-connections"
    const val SupportHeading = "settings-heading-support"
    const val LeadingIconSuffix = "-leading-icon"
    const val TrailingChevronSuffix = "-trailing-chevron"
    const val ScaleStatusMark = "settings-scale-status-mark"
    const val HealthConnectStatusMark = "settings-health-connect-status-mark"
}

private val ProfileSummaryDateFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")

internal fun formatProfileSummary(profile: UserProfile?, locale: Locale = Locale.getDefault()): UiText {
    if (profile == null) return uiText(R.string.settings_profile_not_configured)
    val height = NumberFormat.getNumberInstance(locale).run {
        minimumFractionDigits = 0
        maximumFractionDigits = 1
        format(profile.heightCm)
    }
    val sex = if (profile.sex == Sex.MALE) R.string.settings_sex_male_lower else R.string.settings_sex_female_lower
    return uiText(
        R.string.settings_profile_summary,
        height,
        profile.birthDate.format(ProfileSummaryDateFormatter),
        uiText(sex),
    )
}

internal fun profilesRootSummary(peopleCount: Int, petCount: Int): UiText {
    require(peopleCount >= 0)
    require(petCount >= 0)
    if (peopleCount == 0 && petCount == 0) return uiText(R.string.settings_add_first_profile)
    return UiText.Joined(
        listOf(
            pluralUiText(R.plurals.settings_people_count, peopleCount, peopleCount),
            pluralUiText(R.plurals.settings_pet_count, petCount, petCount),
        ),
        separator = " · ",
    )
}

internal fun settingsRootGroupItemIndex(destination: SettingsDestination): Int? = when (destination) {
    SettingsDestination.PROFILES -> 0
    SettingsDestination.SCALE,
    SettingsDestination.HEALTH_CONNECT,
    -> 2
    SettingsDestination.BACKUP,
    SettingsDestination.DIAGNOSTICS,
    -> 4
    SettingsDestination.ROOT -> null
}

internal fun scaleDetailIdentity(settings: AppSettings): UiText = settings.scaleAddress?.let { address ->
    uiText(R.string.settings_raw_value, listOfNotNull(settings.scaleName, address).joinToString(" · "))
} ?: uiText(R.string.settings_device_not_selected)

internal fun formatLatestPetWeight(
    pet: PetWithLatestWeight,
    locale: Locale = Locale.getDefault(),
    now: Instant = Instant.now(),
    zoneId: ZoneId = ZoneId.systemDefault(),
): UiText = pet.latestPetWeightKg?.let {
    val formatted = NumberFormat.getNumberInstance(locale).run {
        minimumFractionDigits = 0
        maximumFractionDigits = 2
        format(it)
    }
    val measuredAt = requireNotNull(pet.latestMeasuredAt).atZone(zoneId)
    val today = now.atZone(zoneId).toLocalDate()
    val dateLabel = when (measuredAt.toLocalDate()) {
        today -> uiText(R.string.settings_today)
        today.minusDays(1) -> uiText(R.string.settings_yesterday)
        else -> uiText(R.string.settings_raw_value, measuredAt.format(DateTimeFormatter.ofPattern("dd.MM.yyyy", locale)))
    }
    val timeLabel = measuredAt.format(DateTimeFormatter.ofPattern("HH:mm", locale))
    uiText(R.string.settings_pet_weight_summary, formatted, dateLabel, timeLabel)
} ?: uiText(R.string.settings_no_value)

internal val BACKUP_REPLACE_WARNING = uiText(R.string.settings_backup_replace_warning)

internal fun healthConnectPresentation(
    state: HealthConnectPermissionsUiState,
    locallyEnabled: Boolean = true,
): IntegrationPresentation = when (state.availability) {
    HealthConnectAvailability.CHECKING -> IntegrationPresentation(
        supportingText = uiText(R.string.settings_hc_checking),
        actionLabel = uiText(R.string.settings_connect),
        actionEnabled = false,
    )
    HealthConnectAvailability.UNAVAILABLE -> IntegrationPresentation(
        supportingText = uiText(R.string.settings_hc_unsupported),
    )
    HealthConnectAvailability.PROVIDER_UPDATE_REQUIRED -> IntegrationPresentation(
        supportingText = uiText(R.string.settings_hc_update_required),
    )
    HealthConnectAvailability.CHECK_FAILED -> IntegrationPresentation(
        supportingText = uiText(R.string.settings_hc_check_failed),
        actionLabel = uiText(R.string.settings_connect),
    )
    HealthConnectAvailability.AVAILABLE -> if (!locallyEnabled) {
        IntegrationPresentation(
            supportingText = uiText(R.string.settings_hc_disabled),
            actionLabel = uiText(R.string.settings_connect_again),
        )
    } else if (state.isConnected) {
        IntegrationPresentation(
            supportingText = uiText(R.string.settings_hc_connected),
            actionLabel = uiText(R.string.settings_open),
            actionOpensManagement = true,
        )
    } else {
        val grantedCount = state.permissionStates.count { it.value }
        IntegrationPresentation(
            supportingText = uiText(R.string.settings_hc_permissions_count, grantedCount, state.requiredPermissions.size),
            actionLabel = uiText(R.string.settings_connect),
        )
    }
}

/** Keeps permission management system-owned while explaining the manual fallback when unavailable. */
internal fun IntegrationPresentation.withHealthConnectManagementFallback(
    systemManagementAvailable: Boolean,
): IntegrationPresentation = if (actionOpensManagement && !systemManagementAvailable) {
    copy(
        supportingText = uiText(R.string.settings_hc_manual_fallback, supportingText),
        actionLabel = null,
    )
} else {
    this
}

@Composable
internal fun SettingsScreen(
    state: MainUiState,
    callbacks: SettingsCallbacks,
    contentPadding: PaddingValues,
    destination: SettingsDestination = SettingsDestination.ROOT,
    onDestinationChanged: (SettingsDestination) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var returnFocusDestination by rememberSaveable { mutableStateOf<SettingsDestination?>(null) }
    val rootListState = rememberLazyListState()
    val rootFocusRequesters = remember {
        settingsRootDestinations()
            .associateWith { FocusRequester() }
    }
    LaunchedEffect(destination) {
        if (destination == SettingsDestination.ROOT) {
            returnFocusDestination?.let { returnedFrom ->
                settingsRootGroupItemIndex(returnedFrom)?.let { groupItemIndex ->
                    rootListState.scrollToItem(groupItemIndex)
                    withFrameNanos { }
                    rootFocusRequesters[returnedFrom]?.requestFocus()
                }
            }
            returnFocusDestination = null
        }
    }
    val openDestination: (SettingsDestination) -> Unit = {
        returnFocusDestination = it
        onDestinationChanged(it)
    }
    when (destination) {
        SettingsDestination.ROOT -> SettingsRootScreen(
            state, callbacks, contentPadding, openDestination, rootFocusRequesters, rootListState,
            modifier,
        )
        SettingsDestination.PROFILES -> SettingsProfilesContent(state, callbacks, contentPadding, modifier)
        SettingsDestination.SCALE -> SettingsScaleDetail(state, callbacks, contentPadding, modifier)
        SettingsDestination.HEALTH_CONNECT -> SettingsHealthConnectDetail(state, callbacks, contentPadding, modifier)
        SettingsDestination.BACKUP -> SettingsBackupDetail(state, callbacks, contentPadding, modifier)
        SettingsDestination.DIAGNOSTICS -> SettingsDiagnosticsDetail(
            state = state,
            callbacks = callbacks,
            contentPadding = contentPadding,
            modifier = modifier,
        )
    }
}

@Composable
private fun SettingsBackupDetail(
    state: MainUiState,
    callbacks: SettingsCallbacks,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    SettingsSimpleDetail(contentPadding, SettingsScreenTestTags.BackupDetail, modifier) {
        SettingsBackupDetailContent(state.backup, callbacks)
    }
    BackupImportDialog(state.backup, callbacks)
}

@Composable
private fun SettingsDiagnosticsDetail(
    state: MainUiState,
    callbacks: SettingsCallbacks,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    SettingsSimpleDetail(contentPadding, SettingsScreenTestTags.DiagnosticsDetail, modifier) {
        SettingsDiagnosticsContent(
            state = state,
            callbacks = callbacks,
        )
    }
}

@Composable
private fun SettingsSimpleDetail(
    contentPadding: PaddingValues,
    testTag: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier.fillMaxSize().padding(contentPadding).testTag(SettingsScreenTestTags.Detail),
        contentAlignment = Alignment.TopCenter,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().widthIn(max = 720.dp).testTag(testTag),
            contentPadding = PaddingValues(
                start = HuaweiDimensions.ContentPadding,
                end = HuaweiDimensions.ContentPadding,
                top = 4.dp,
                bottom = 28.dp,
            ),
        ) { item { content() } }
    }
}

@Composable
private fun SettingsScaleDetail(
    state: MainUiState,
    callbacks: SettingsCallbacks,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val resources = LocalContext.current.resources
    SettingsActionDetail(
        state = state,
        contentPadding = contentPadding,
        testTag = SettingsScreenTestTags.ScaleDetail,
        destructiveAction = settingsDetailDestructiveAction(SettingsDestination.SCALE, state),
        callbacks = callbacks,
        modifier = modifier,
    ) {
        val presentation = scalePresentation(state)
        ConnectionDetailContent(
            title = stringResource(R.string.settings_scales_title),
            icon = HuaweiIcons.Bluetooth,
            status = presentation.supportingText.resolve(resources),
            identityLabel = stringResource(R.string.settings_device),
            identity = scaleDetailIdentity(state.settings).resolve(resources),
            actionLabel = presentation.actionLabel?.resolve(resources),
            actionEnabled = presentation.actionEnabled,
            actionTag = SettingsScreenTestTags.ScaleAction,
            progress = presentation.showProgress,
            statusTag = SettingsScreenTestTags.ScaleStatus,
            onAction = when (presentation.action) {
                ScaleSettingsAction.OPEN_APP_SETTINGS -> callbacks.openApplicationSettings
                ScaleSettingsAction.SEARCH, ScaleSettingsAction.RETRY -> callbacks.onManualScan
                null -> ({})
            },
        )
    }
}

@Composable
private fun SettingsHealthConnectDetail(
    state: MainUiState,
    callbacks: SettingsCallbacks,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val resources = LocalContext.current.resources
    val presentation = healthConnectPresentation(
        state.healthConnect,
        state.settings.healthConnectSyncEnabled,
    ).withHealthConnectManagementFallback(state.healthConnectSystemManagementAvailable)
    SettingsActionDetail(
        state = state,
        contentPadding = contentPadding,
        testTag = SettingsScreenTestTags.HealthConnectDetail,
        destructiveAction = settingsDetailDestructiveAction(SettingsDestination.HEALTH_CONNECT, state),
        callbacks = callbacks,
        modifier = modifier,
    ) {
        ConnectionDetailContent(
            title = stringResource(R.string.settings_health_connect),
            icon = HuaweiIcons.HealthConnect,
            status = presentation.supportingText.resolve(resources),
            identityLabel = stringResource(R.string.settings_device),
            identity = stringResource(R.string.settings_system_integration),
            actionLabel = presentation.actionLabel?.resolve(resources),
            actionEnabled = presentation.actionEnabled,
            actionTag = SettingsScreenTestTags.HealthConnectAction,
            actionContentDescription = if (presentation.actionOpensManagement) {
                stringResource(R.string.settings_hc_open_cd)
            } else {
                stringResource(R.string.settings_hc_connect_cd)
            },
            onAction = if (presentation.actionOpensManagement) {
                callbacks.onHealthConnectAccessManagement
            } else {
                callbacks.onHealthConnectAuthorization
            },
        )
    }
}

@Composable
private fun SettingsActionDetail(
    state: MainUiState,
    contentPadding: PaddingValues,
    testTag: String,
    destructiveAction: DestructiveSettingsAction?,
    callbacks: SettingsCallbacks,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var confirmation by rememberSaveable { mutableStateOf<DestructiveSettingsAction?>(null) }
    var submitted by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.destructiveActionInProgress) {
        if (submitted && state.destructiveActionInProgress == null) {
            confirmation = null
            submitted = false
        }
    }
    Box(
        modifier = modifier.fillMaxSize().padding(contentPadding).testTag(SettingsScreenTestTags.Detail),
        contentAlignment = Alignment.TopCenter,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().widthIn(max = 720.dp).testTag(testTag),
            contentPadding = PaddingValues(
                start = HuaweiDimensions.ContentPadding,
                end = HuaweiDimensions.ContentPadding,
                top = 4.dp,
                bottom = 28.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(HuaweiDimensions.ItemSpacing),
        ) {
            item { content() }
            destructiveAction?.let { action ->
                item {
                    DetailDestructiveAction(
                        action = action,
                        enabled = state.destructiveActionInProgress == null,
                        onClick = { confirmation = action },
                    )
                }
            }
        }
    }
    confirmation?.let { action ->
        DestructiveConfirmationDialog(
            action = action,
            busy = state.destructiveActionInProgress != null,
            onDismiss = {
                if (state.destructiveActionInProgress == null) {
                    confirmation = null
                    submitted = false
                }
            },
            onConfirm = {
                submitted = true
                when (action) {
                    DestructiveSettingsAction.HEALTH_CONNECT -> callbacks.onDisableHealthConnect()
                    DestructiveSettingsAction.SCALE -> callbacks.onForgetScale()
                }
            },
        )
    }
}

@Composable
private fun ConnectionDetailContent(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    status: String,
    identityLabel: String,
    identity: String,
    actionLabel: String?,
    actionEnabled: Boolean,
    actionTag: String,
    onAction: () -> Unit,
    actionContentDescription: String? = null,
    progress: Boolean = false,
    statusTag: String = SettingsScreenTestTags.IntegrationStatus,
) {
    Column(verticalArrangement = Arrangement.spacedBy(HuaweiDimensions.ItemSpacing)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp)
                .testTag(SettingsScreenTestTags.DetailHero),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Surface(
                modifier = Modifier.size(52.dp).testTag(SettingsScreenTestTags.DetailHeroIcon),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.primary,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, modifier = Modifier.size(30.dp))
                }
            }
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(
                status,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        actionLabel?.let { label ->
            Box(Modifier.fillMaxWidth().testTag(SettingsScreenTestTags.DetailPrimaryAction)) {
                Button(
                    onClick = onAction,
                    enabled = actionEnabled,
                    modifier = Modifier.fillMaxWidth().heightIn(min = HuaweiDimensions.TouchTarget)
                        .testTag(actionTag).then(
                            if (actionContentDescription == null) Modifier else Modifier.semantics {
                                contentDescription = actionContentDescription
                            },
                        ),
                ) {
                    if (progress) CircularProgressIndicator(Modifier.size(20.dp).testTag(SettingsScreenTestTags.ScaleProgress))
                    Text(label, modifier = if (progress) Modifier.padding(start = 8.dp) else Modifier)
                }
            }
        }
        DetailSectionTitle(stringResource(R.string.settings_state))
        SettingsGroup(Modifier.testTag(SettingsScreenTestTags.DetailStatusGroup)) {
            DetailInfoRow(identityLabel, identity)
            SettingsGroupDivider(Modifier.testTag(SettingsScreenTestTags.DetailStatusDivider))
            DetailInfoRow(
                label = stringResource(R.string.settings_status),
                value = status,
                modifier = Modifier.testTag(statusTag)
                    .semantics { stateDescription = status },
            )
        }
    }
}

@Composable
private fun DetailInfoRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = 58.dp)
            .padding(horizontal = HuaweiDimensions.ContentPadding, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(HuaweiDimensions.ItemSpacing),
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1.4f))
    }
}

@Composable
private fun DetailSectionTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.secondary,
        modifier = Modifier.padding(start = 10.dp, top = 6.dp).semantics { heading() },
    )
}

@Composable
private fun SettingsBackupDetailContent(state: BackupUiState, callbacks: SettingsCallbacks) {
    Column(verticalArrangement = Arrangement.spacedBy(HuaweiDimensions.ItemSpacing)) {
        DetailSectionTitle(stringResource(R.string.settings_save_data))
        SettingsGroup(Modifier.testTag(SettingsScreenTestTags.BackupSaveGroup)) {
            DetailActionRow(
                icon = HuaweiIcons.Archive,
                title = stringResource(R.string.settings_export_backup),
                supportingText = stringResource(R.string.settings_backup_contents),
                enabled = !state.inProgress,
                tag = SettingsScreenTestTags.BackupExport,
                onClick = callbacks.onExportBackup,
            )
        }
        if (state.inProgress) Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(horizontal = 8.dp),
        ) {
            CircularProgressIndicator(Modifier.size(24.dp))
            Text(stringResource(R.string.settings_backup_processing))
        }
        DetailSectionTitle(stringResource(R.string.settings_restore_data))
        SettingsGroup(Modifier.testTag(SettingsScreenTestTags.BackupRestoreGroup)) {
            DetailActionRow(
                icon = HuaweiIcons.Refresh,
                title = stringResource(R.string.settings_import_merge),
                supportingText = stringResource(R.string.settings_keep_existing_data),
                enabled = !state.inProgress,
                tag = SettingsScreenTestTags.BackupMerge,
                onClick = { callbacks.onImportBackup(BackupImportMode.MERGE) },
            )
            SettingsGroupDivider(Modifier.testTag(SettingsScreenTestTags.BackupRestoreDivider))
            DetailActionRow(
                icon = HuaweiIcons.Warning,
                title = stringResource(R.string.settings_replace_all_data),
                supportingText = stringResource(R.string.settings_replace_data_supporting),
                enabled = !state.inProgress,
                tag = SettingsScreenTestTags.BackupReplace,
                onClick = { callbacks.onImportBackup(BackupImportMode.REPLACE) },
            )
        }
    }
}

@Composable
private fun DetailActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    supportingText: String,
    enabled: Boolean = true,
    tag: String,
    onClick: () -> Unit,
) {
    SettingsGroupRow(
        leadingIcon = icon,
        title = title,
        supportingText = supportingText,
        enabled = enabled,
        modifier = Modifier.testTag(tag),
        onClick = onClick,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsDiagnosticsContent(
    state: MainUiState,
    callbacks: SettingsCallbacks,
) {
    Column(verticalArrangement = Arrangement.spacedBy(HuaweiDimensions.ItemSpacing)) {
        DetailSectionTitle(stringResource(R.string.settings_background_work))
        SettingsGroup(Modifier.testTag(SettingsScreenTestTags.DiagnosticsBackgroundGroup)) {
            DiagnosticsSwitchRow(state, callbacks)
            SettingsGroupDivider(Modifier.testTag(SettingsScreenTestTags.DiagnosticsBackgroundDivider))
            DetailActionRow(
                icon = HuaweiIcons.Settings,
                title = stringResource(R.string.settings_battery_settings),
                supportingText = stringResource(R.string.settings_allow_background),
                tag = "settings-diagnostics-battery",
                onClick = callbacks.openBatterySettings,
            )
            SettingsGroupDivider(Modifier.testTag(SettingsScreenTestTags.DiagnosticsBackgroundSecondDivider))
            DetailActionRow(
                icon = HuaweiIcons.Tune,
                title = stringResource(R.string.settings_system_app_settings),
                supportingText = stringResource(R.string.settings_permissions_notifications),
                tag = "settings-diagnostics-application",
                onClick = callbacks.openApplicationSettings,
            )
        }
    }
}

@Composable
private fun DiagnosticsSwitchRow(state: MainUiState, callbacks: SettingsCallbacks) {
    val resources = LocalContext.current.resources
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 68.dp)
            .clickable(role = Role.Switch) {
                callbacks.onReliabilityMode(!state.settings.reliabilityMode)
            }.padding(horizontal = HuaweiDimensions.ContentPadding, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.settings_reliability_mode), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.settings_reliability_mode_supporting),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Switch(
            checked = state.settings.reliabilityMode,
            onCheckedChange = callbacks.onReliabilityMode,
            modifier = Modifier.semantics { contentDescription = resources.getString(R.string.settings_reliability_mode) },
        )
    }
}

@Composable
private fun DetailDestructiveAction(
    action: DestructiveSettingsAction,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val (label, tag) = when (action) {
        DestructiveSettingsAction.HEALTH_CONNECT -> stringResource(R.string.settings_disconnect_hc) to SettingsScreenTestTags.DisableHealthConnect
        DestructiveSettingsAction.SCALE -> stringResource(R.string.settings_forget_scale) to SettingsScreenTestTags.ForgetScale
    }
    Column(Modifier.testTag(SettingsScreenTestTags.DetailDangerZone)) {
        DetailSectionTitle(stringResource(R.string.settings_management))
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.fillMaxWidth().heightIn(min = HuaweiDimensions.TouchTarget).testTag(tag),
        ) { Text(label) }
    }
}

@Composable
private fun SettingsProfilesContent(
    state: MainUiState,
    callbacks: SettingsCallbacks,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val resources = LocalContext.current.resources
    state.accountManagement.editor?.let { draft ->
        AccountEditorScreen(
            draft = draft,
            accounts = state.accountManagement.accounts,
            operationInProgress = state.accountManagement.operationInProgress,
            error = state.accountManagement.operationError,
            onDraftChanged = { callbacks.accountManagement.onAction(
                com.palixander.scalesync.ui.accounts.AccountManagementAction.EditorChanged(it),
            ) },
            onCreate = callbacks.accountManagement.onCreate,
            onUpdate = callbacks.accountManagement.onUpdate,
            onDismiss = { callbacks.accountManagement.onAction(
                com.palixander.scalesync.ui.accounts.AccountManagementAction.DialogDismissed,
            ) },
            modifier = modifier.padding(contentPadding),
        )
        return
    }
    Box(
        modifier = modifier.fillMaxSize().padding(contentPadding).testTag(SettingsScreenTestTags.Detail),
        contentAlignment = Alignment.TopCenter,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().widthIn(max = 720.dp),
            contentPadding = PaddingValues(
                start = HuaweiDimensions.ContentPadding,
                end = HuaweiDimensions.ContentPadding,
                top = 4.dp,
                bottom = 28.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(HuaweiDimensions.ItemSpacing),
        ) {
            item {
                AccountManagementSection(
                    state = state.accountManagement,
                    callbacks = callbacks.accountManagement,
                    pets = state.pets,
                    onAddPet = callbacks.onCreatePet,
                    onEditPet = { callbacks.onEditPet(it.pet) },
                    onDeletePet = callbacks.onRequestDeletePet,
                    petSpeciesLabel = { petSpeciesLabel(it.pet.species).resolve(resources) },
                    petWeightLabel = { formatLatestPetWeight(it, resources.appLocale).resolve(resources) },
                )
            }
            item {
                WeightRecognitionSetting(
                    state = state.weightDeltaEditor,
                    onStateChanged = callbacks.onWeightDeltaStateChanged,
                    onSave = callbacks.onWeightDeltaSave,
                    ignoreUnknownMeasurements = state.accountSettings.ignoreUnknownMeasurements,
                    onIgnoreUnknownMeasurementsChanged = callbacks.onIgnoreUnknownMeasurementsChanged,
                )
            }
        }
    }
    PetDeletionDialog(state, callbacks)
}

@Composable
private fun SettingsRootScreen(
    state: MainUiState,
    callbacks: SettingsCallbacks,
    contentPadding: PaddingValues,
    onDestinationChanged: (SettingsDestination) -> Unit,
    focusRequesters: Map<SettingsDestination, FocusRequester>,
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    var languageDialogOpen by rememberSaveable { mutableStateOf(false) }
    val scalePresentation = scalePresentation(state)
    val healthPresentation = healthConnectPresentation(
        state.healthConnect,
        state.settings.healthConnectSyncEnabled,
    ).withHealthConnectManagementFallback(state.healthConnectSystemManagementAvailable)
    Box(
        modifier = modifier.fillMaxSize().padding(contentPadding),
        contentAlignment = Alignment.TopCenter,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().widthIn(max = 720.dp).testTag(SettingsScreenTestTags.List),
            state = listState,
            contentPadding = PaddingValues(
                start = HuaweiDimensions.CompactContentPadding,
                end = HuaweiDimensions.CompactContentPadding,
                top = 4.dp,
                bottom = 28.dp,
            ),
        ) {
            item {
                SettingsGroup(Modifier.testTag(SettingsScreenTestTags.ProfilesGroup)) {
                    SettingsNavigationRow(
                        SettingsDestination.PROFILES,
                        settingsRootIcon(SettingsDestination.PROFILES),
                        profilesRootSummary(state.accountManagement.accounts.size, state.pets.size),
                        SettingsScreenTestTags.ProfilesRow,
                        onDestinationChanged,
                        focusRequesters[SettingsDestination.PROFILES],
                    )
                }
            }
            item {
                Text(
                    stringResource(R.string.settings_connections_heading),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier
                        .padding(start = 10.dp, top = 18.dp, bottom = 8.dp)
                        .testTag(SettingsScreenTestTags.ConnectionsHeading)
                        .semantics { heading() },
                )
            }
            item {
                SettingsGroup(Modifier.testTag(SettingsScreenTestTags.ConnectionsGroup)) {
                    SettingsNavigationRow(
                        SettingsDestination.SCALE, settingsRootIcon(SettingsDestination.SCALE),
                        scaleRootSupportingText(scalePresentation, state.settings.scaleName),
                        SettingsScreenTestTags.ScaleRow,
                        onDestinationChanged, focusRequesters[SettingsDestination.SCALE],
                        status = {
                            SettingsRootStatusMark(
                                scaleRootStatusSuccessful(scalePresentation),
                                SettingsScreenTestTags.ScaleStatusMark,
                            )
                        },
                    )
                    SettingsRootDivider(SettingsScreenTestTags.ConnectionsDivider)
                    SettingsNavigationRow(
                        SettingsDestination.HEALTH_CONNECT,
                        settingsRootIcon(SettingsDestination.HEALTH_CONNECT),
                        healthPresentation.supportingText, SettingsScreenTestTags.HealthConnectRow,
                        onDestinationChanged, focusRequesters[SettingsDestination.HEALTH_CONNECT],
                        status = {
                            SettingsRootStatusMark(
                                healthRootStatusSuccessful(
                                    state.healthConnect,
                                    state.settings.healthConnectSyncEnabled,
                                ),
                                SettingsScreenTestTags.HealthConnectStatusMark,
                            )
                        },
                    )
                }
            }
            item {
                Text(
                    stringResource(R.string.settings_support_heading),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier
                        .padding(start = 10.dp, top = 18.dp, bottom = 8.dp)
                        .testTag(SettingsScreenTestTags.SupportHeading)
                        .semantics { heading() },
                )
            }
            item {
                SettingsGroup(Modifier.testTag(SettingsScreenTestTags.SupportGroup)) {
                    SettingsGroupRow(
                        leadingIcon = HuaweiIcons.Language,
                        title = stringResource(R.string.settings_language),
                        supportingText = stringResource(AppLanguageManager.current().labelRes),
                        modifier = Modifier.testTag(SettingsScreenTestTags.LanguageRow),
                        onClick = { languageDialogOpen = true },
                        leadingIconTag = SettingsScreenTestTags.LanguageRow + SettingsScreenTestTags.LeadingIconSuffix,
                        trailingTag = SettingsScreenTestTags.LanguageRow + SettingsScreenTestTags.TrailingChevronSuffix,
                    )
                    SettingsRootDivider(SettingsScreenTestTags.SupportDivider)
                    SettingsNavigationRow(
                        SettingsDestination.BACKUP,
                        settingsRootIcon(SettingsDestination.BACKUP),
                        uiText(R.string.settings_backup_root_supporting),
                        SettingsScreenTestTags.BackupRow,
                        onDestinationChanged,
                        focusRequesters[SettingsDestination.BACKUP],
                    )
                    SettingsRootDivider(SettingsScreenTestTags.SupportSecondDivider)
                    SettingsNavigationRow(
                        SettingsDestination.DIAGNOSTICS,
                        settingsRootIcon(SettingsDestination.DIAGNOSTICS),
                        uiText(R.string.settings_diagnostics_root_supporting),
                        SettingsScreenTestTags.DiagnosticsRow,
                        onDestinationChanged,
                        focusRequesters[SettingsDestination.DIAGNOSTICS],
                    )
                    SettingsRootDivider(SettingsScreenTestTags.ConnectionsSecondDivider)
                    SettingsGroupRow(
                        leadingIcon = HuaweiIcons.History,
                        title = stringResource(R.string.settings_version_history),
                        supportingText = stringResource(R.string.settings_version_history_supporting),
                        modifier = Modifier.testTag(SettingsScreenTestTags.ChangelogRow),
                        onClick = callbacks.onOpenChangelog,
                        leadingIconTag = SettingsScreenTestTags.ChangelogRow + SettingsScreenTestTags.LeadingIconSuffix,
                        trailingTag = SettingsScreenTestTags.ChangelogRow + SettingsScreenTestTags.TrailingChevronSuffix,
                    )
                }
            }
        }
    }
    if (languageDialogOpen) {
        AppLanguageDialog(onDismiss = { languageDialogOpen = false })
    }
}

@Composable
private fun AppLanguageDialog(onDismiss: () -> Unit) {
    val selected = AppLanguageManager.current()
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(SettingsScreenTestTags.LanguageDialog),
        title = { Text(stringResource(R.string.settings_language_dialog_title)) },
        text = {
            LazyColumn {
                items(AppLanguage.entries) { language ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(role = Role.RadioButton) {
                                onDismiss()
                                AppLanguageManager.select(language)
                            }
                            .testTag(SettingsScreenTestTags.LanguageOptionPrefix + language.languageTag)
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = language == selected, onClick = null)
                        Text(stringResource(language.labelRes), Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) }
        },
    )
}

@Composable
private fun SettingsNavigationRow(
    destination: SettingsDestination,
    leadingIcon: androidx.compose.ui.graphics.vector.ImageVector,
    supportingText: UiText,
    testTag: String,
    onDestinationChanged: (SettingsDestination) -> Unit,
    focusRequester: FocusRequester?,
    status: (@Composable () -> Unit)? = null,
) {
    val resources = LocalContext.current.resources
    val resolvedSupportingText = supportingText.resolve(resources)
    SettingsGroupRow(
        leadingIcon = leadingIcon,
        title = stringResource(destination.titleRes),
        supportingText = resolvedSupportingText,
        modifier = Modifier
            .testTag(testTag)
            .then(if (focusRequester == null) Modifier else Modifier.focusRequester(focusRequester))
            .semantics {
                stateDescription = resolvedSupportingText
                if (destination == SettingsDestination.HEALTH_CONNECT) {
                    contentDescription = resources.getString(R.string.settings_hc_row_cd)
                }
            },
        onClick = { onDestinationChanged(destination) },
        leadingIconTag = testTag + SettingsScreenTestTags.LeadingIconSuffix,
        trailingTag = testTag + SettingsScreenTestTags.TrailingChevronSuffix,
        status = status,
    )
}

@Composable
private fun SettingsRootDivider(tag: String) {
    Box(Modifier.testTag(tag)) {
        SettingsGroupDivider(Modifier.testTag(SettingsScreenTestTags.RootDivider))
    }
}

@Composable
private fun SettingsRootStatusMark(success: Boolean, tag: String) {
    SettingsStatusMark(
        icon = if (success) HuaweiIcons.Success else HuaweiIcons.Warning,
        tint = if (success) MaterialTheme.colorScheme.primary else HuaweiColors.Warning,
        modifier = Modifier.testTag(tag),
    )
}

@Composable
private fun SettingsDetailPlaceholder(
    destination: SettingsDestination,
    contentPadding: PaddingValues,
    modifier: Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize().padding(contentPadding).testTag(SettingsScreenTestTags.Detail),
        contentAlignment = Alignment.Center,
    ) {
        Text(stringResource(destination.titleRes), style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun LegacySettingsScreen(
    state: MainUiState,
    callbacks: SettingsCallbacks,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val resources = LocalContext.current.resources
    state.accountManagement.editor?.let { draft ->
        AccountEditorScreen(
            draft = draft,
            accounts = state.accountManagement.accounts,
            operationInProgress = state.accountManagement.operationInProgress,
            error = state.accountManagement.operationError,
            onDraftChanged = { callbacks.accountManagement.onAction(
                com.palixander.scalesync.ui.accounts.AccountManagementAction.EditorChanged(it),
            ) },
            onCreate = callbacks.accountManagement.onCreate,
            onUpdate = callbacks.accountManagement.onUpdate,
            onDismiss = { callbacks.accountManagement.onAction(
                com.palixander.scalesync.ui.accounts.AccountManagementAction.DialogDismissed,
            ) },
            modifier = modifier.padding(contentPadding),
        )
        return
    }
    var accountsExpansion by rememberSaveable { mutableStateOf(SettingsSectionExpansion.Collapsed) }
    var integrationsExpansion by rememberSaveable { mutableStateOf(SettingsSectionExpansion.Collapsed) }
    var scaleExpansion by rememberSaveable { mutableStateOf(SettingsSectionExpansion.Collapsed) }
    var backupExpansion by rememberSaveable { mutableStateOf(SettingsSectionExpansion.Collapsed) }
    var additionalExpansion by rememberSaveable { mutableStateOf(SettingsSectionExpansion.Collapsed) }
    var aboutExpansion by rememberSaveable { mutableStateOf(SettingsSectionExpansion.Collapsed) }
    var destructiveConfirmation by rememberSaveable { mutableStateOf<DestructiveSettingsAction?>(null) }
    var destructiveSubmitted by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.destructiveActionInProgress) {
        if (destructiveSubmitted && state.destructiveActionInProgress == null) {
            destructiveConfirmation = null
            destructiveSubmitted = false
        }
    }

    Box(
        modifier = modifier.fillMaxSize().padding(contentPadding),
        contentAlignment = Alignment.TopCenter,
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth()
                .widthIn(max = 720.dp)
                .testTag(SettingsScreenTestTags.List),
            contentPadding = PaddingValues(
                start = HuaweiDimensions.ContentPadding,
                end = HuaweiDimensions.ContentPadding,
                top = 4.dp,
                bottom = 28.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(HuaweiDimensions.ItemSpacing),
        ) {
            item {
                CollapsibleSettingsSection(
                    title = stringResource(R.string.settings_profiles),
                    expansion = accountsExpansion,
                    testTag = SettingsScreenTestTags.AccountsSection,
                    contentTestTag = SettingsScreenTestTags.AccountsContent,
                    onToggle = { accountsExpansion = accountsExpansion.toggled() },
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(HuaweiDimensions.ItemSpacing)) {
                        AccountManagementSection(
                            state = state.accountManagement,
                            callbacks = callbacks.accountManagement,
                            pets = state.pets,
                            onAddPet = callbacks.onCreatePet,
                            onEditPet = { callbacks.onEditPet(it.pet) },
                            onDeletePet = callbacks.onRequestDeletePet,
                            petSpeciesLabel = { petSpeciesLabel(it.pet.species).resolve(resources) },
                            petWeightLabel = { formatLatestPetWeight(it, resources.appLocale).resolve(resources) },
                        )
                        WeightRecognitionSetting(
                            state = state.weightDeltaEditor,
                            onStateChanged = callbacks.onWeightDeltaStateChanged,
                            onSave = callbacks.onWeightDeltaSave,
                            ignoreUnknownMeasurements = state.accountSettings.ignoreUnknownMeasurements,
                            onIgnoreUnknownMeasurementsChanged = callbacks.onIgnoreUnknownMeasurementsChanged,
                        )
                    }
                }
            }
            item {
                CollapsibleSettingsSection(
                    title = stringResource(R.string.settings_integrations),
                    expansion = integrationsExpansion,
                    testTag = SettingsScreenTestTags.IntegrationsSection,
                    contentTestTag = SettingsScreenTestTags.IntegrationsContent,
                    onToggle = { integrationsExpansion = integrationsExpansion.toggled() },
                ) {
                    SettingsIntegrationsContent(state, callbacks)
                    IntegrationDestructiveActions(state) { destructiveConfirmation = it }
                }
            }
            item {
                CollapsibleSettingsSection(
                    title = stringResource(R.string.settings_scales),
                    expansion = scaleExpansion,
                    testTag = SettingsScreenTestTags.ScaleSection,
                    contentTestTag = SettingsScreenTestTags.ScaleContent,
                    onToggle = { scaleExpansion = scaleExpansion.toggled() },
                ) {
                    SettingsScaleContent(state, callbacks)
                    ScaleDestructiveAction(state) { destructiveConfirmation = it }
                }
            }
            item {
                CollapsibleSettingsSection(
                    title = stringResource(R.string.settings_backup),
                    expansion = backupExpansion,
                    testTag = SettingsScreenTestTags.BackupSection,
                    contentTestTag = SettingsScreenTestTags.BackupContent,
                    onToggle = { backupExpansion = backupExpansion.toggled() },
                ) { SettingsBackupContent(state.backup, callbacks) }
            }
            item {
                CollapsibleSettingsSection(
                    title = stringResource(R.string.settings_additional),
                    expansion = additionalExpansion,
                    testTag = SettingsScreenTestTags.AdditionalSection,
                    contentTestTag = SettingsScreenTestTags.AdditionalContent,
                    onToggle = { additionalExpansion = additionalExpansion.toggled() },
                ) {
                    AdditionalContent(
                        state = state,
                        callbacks = callbacks,
                    )
                }
            }
            item {
                CollapsibleSettingsSection(
                    title = stringResource(R.string.settings_about),
                    expansion = aboutExpansion,
                    testTag = SettingsScreenTestTags.AboutSection,
                    contentTestTag = SettingsScreenTestTags.AboutContent,
                    onToggle = { aboutExpansion = aboutExpansion.toggled() },
                ) {
                    HuaweiSurface(contentPadding = PaddingValues(0.dp)) {
                        HuaweiSettingRow(
                            icon = HuaweiIcons.Calendar,
                            title = stringResource(R.string.settings_changelog),
                            supportingText = stringResource(R.string.settings_changelog_supporting),
                            modifier = Modifier.testTag(SettingsScreenTestTags.ChangelogRow),
                            onClick = callbacks.onOpenChangelog,
                        ) {
                            HuaweiIconButton(
                                icon = HuaweiIcons.ChevronRight,
                                contentDescription = stringResource(R.string.settings_open_changelog),
                                onClick = callbacks.onOpenChangelog,
                            )
                        }
                    }
                }
            }
        }
    }
    state.backup.preview?.let { preview ->
        val counts = preview.counts
        val replaceWarning = state.backup.replaceConfirmationRequested
        AlertDialog(
            modifier = Modifier.testTag(SettingsScreenTestTags.BackupDialog),
            onDismissRequest = callbacks.onDismissBackupImport,
            title = { Text(stringResource(if (replaceWarning) R.string.settings_confirm_replace else R.string.settings_import_preview)) },
            text = {
                Text(
                    if (replaceWarning) {
                        BACKUP_REPLACE_WARNING.resolve(LocalContext.current.resources)
                    } else {
                        stringResource(R.string.settings_import_counts, counts.accountsAdded, counts.accountsSkipped, counts.accountsReplaced, counts.measurementsAdded, counts.measurementsSkipped, counts.measurementsReplaced, counts.petsAdded, counts.petsSkipped, counts.petsReplaced, counts.petMeasurementsAdded, counts.petMeasurementsSkipped, counts.petMeasurementsReplaced)
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = callbacks.onConfirmBackupImport,
                    enabled = !state.backup.inProgress,
                    modifier = Modifier.testTag(SettingsScreenTestTags.BackupConfirm),
                ) { Text(stringResource(if (replaceWarning) R.string.settings_replace_data else R.string.settings_import)) }
            },
            dismissButton = {
                TextButton(onClick = callbacks.onDismissBackupImport) { Text(stringResource(R.string.settings_cancel)) }
            },
        )
    }
    PetDeletionDialog(state, callbacks)
    destructiveConfirmation?.let { action ->
        DestructiveConfirmationDialog(
            action = action,
            busy = state.destructiveActionInProgress != null,
            onDismiss = {
                if (state.destructiveActionInProgress == null) {
                    destructiveConfirmation = null
                    destructiveSubmitted = false
                }
            },
            onConfirm = {
                destructiveSubmitted = true
                when (action) {
                    DestructiveSettingsAction.HEALTH_CONNECT -> callbacks.onDisableHealthConnect()
                    DestructiveSettingsAction.SCALE -> callbacks.onForgetScale()
                }
            },
        )
    }
}

@Composable
private fun IntegrationDestructiveActions(
    state: MainUiState,
    onRequest: (DestructiveSettingsAction) -> Unit,
) {
    val busy = state.destructiveActionInProgress != null
    val healthEnabled = state.settings.healthConnectSyncEnabled && state.healthConnect.isConnected
    if (!healthEnabled) return
    Column {
        SettingsDivider()
        HuaweiSurface(
            modifier = Modifier.testTag(SettingsScreenTestTags.DestructiveSection),
            contentPadding = PaddingValues(HuaweiDimensions.ContentPadding),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(HuaweiDimensions.CompactItemSpacing)) {
                Text(
                    stringResource(R.string.settings_danger_explanation),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                if (healthEnabled) OutlinedButton(
                    onClick = { onRequest(DestructiveSettingsAction.HEALTH_CONNECT) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().testTag(SettingsScreenTestTags.DisableHealthConnect),
                ) { Text(stringResource(R.string.settings_disconnect_hc)) }
            }
        }
    }
}

@Composable
private fun ScaleDestructiveAction(
    state: MainUiState,
    onRequest: (DestructiveSettingsAction) -> Unit,
) {
    if (state.settings.scaleAddress == null) return
    SettingsDivider()
    OutlinedButton(
        onClick = { onRequest(DestructiveSettingsAction.SCALE) },
        enabled = state.destructiveActionInProgress == null,
        modifier = Modifier
            .fillMaxWidth()
            .padding(HuaweiDimensions.ContentPadding)
            .testTag(SettingsScreenTestTags.ForgetScale),
    ) { Text(stringResource(R.string.settings_forget_scale)) }
}

@Composable
private fun DestructiveConfirmationDialog(
    action: DestructiveSettingsAction,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val (title, warning) = when (action) {
        DestructiveSettingsAction.HEALTH_CONNECT -> stringResource(R.string.settings_disconnect_hc_question) to
            stringResource(R.string.settings_disconnect_hc_explanation)
        DestructiveSettingsAction.SCALE -> stringResource(R.string.settings_forget_scale_question) to
            stringResource(R.string.settings_forget_scale_explanation)
    }
    AlertDialog(
        modifier = Modifier.testTag(SettingsScreenTestTags.DestructiveDialog),
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(warning)
                if (busy) Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.settings_in_progress))
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = !busy,
                modifier = Modifier.testTag(SettingsScreenTestTags.DestructiveConfirm),
            ) { Text(stringResource(R.string.settings_confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.settings_cancel)) } },
    )
}

@Composable
private fun PetDeletionDialog(state: MainUiState, callbacks: SettingsCallbacks) {
    state.petManagement.deletion?.let { preview ->
        AlertDialog(
            modifier = Modifier.testTag(SettingsScreenTestTags.PetDeleteDialog),
            onDismissRequest = { if (!state.petManagement.busy) callbacks.onDismissPetManagement() },
            title = { Text(stringResource(R.string.settings_delete_pet_question, preview.pet.displayName)) },
            text = { Text(stringResource(R.string.settings_delete_pet_explanation, preview.measurementCount)) },
            confirmButton = {
                TextButton(
                    enabled = !state.petManagement.busy,
                    onClick = callbacks.onConfirmDeletePet,
                ) { Text(stringResource(R.string.settings_delete)) }
            },
            dismissButton = {
                TextButton(
                    enabled = !state.petManagement.busy,
                    onClick = callbacks.onDismissPetManagement,
                ) { Text(stringResource(R.string.settings_cancel)) }
            },
        )
    }
}

@Composable
private fun SettingsBackupContent(state: BackupUiState, callbacks: SettingsCallbacks) {
        HuaweiSurface(contentPadding = PaddingValues(HuaweiDimensions.ContentPadding)) {
            Column(verticalArrangement = Arrangement.spacedBy(HuaweiDimensions.CompactItemSpacing)) {
                Text(stringResource(R.string.settings_backup_intro))
                if (state.inProgress) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator()
                        Text(stringResource(R.string.settings_backup_processing))
                    }
                }
                Button(
                    onClick = callbacks.onExportBackup,
                    enabled = !state.inProgress,
                    modifier = Modifier.fillMaxWidth().testTag(SettingsScreenTestTags.BackupExport),
                ) { Text(stringResource(R.string.settings_export)) }
                OutlinedButton(
                    onClick = { callbacks.onImportBackup(BackupImportMode.MERGE) },
                    enabled = !state.inProgress,
                    modifier = Modifier.fillMaxWidth().testTag(SettingsScreenTestTags.BackupMerge),
                ) { Text(stringResource(R.string.settings_import_merge)) }
                OutlinedButton(
                    onClick = { callbacks.onImportBackup(BackupImportMode.REPLACE) },
                    enabled = !state.inProgress,
                    modifier = Modifier.fillMaxWidth().testTag(SettingsScreenTestTags.BackupReplace),
                ) { Text(stringResource(R.string.settings_import_replace)) }
            }
        }
}

@Composable
private fun BackupImportDialog(state: BackupUiState, callbacks: SettingsCallbacks) {
    state.preview?.let { preview ->
        val counts = preview.counts
        val replaceWarning = state.replaceConfirmationRequested
        AlertDialog(
            modifier = Modifier.testTag(SettingsScreenTestTags.BackupDialog),
            onDismissRequest = callbacks.onDismissBackupImport,
            title = { Text(stringResource(if (replaceWarning) R.string.settings_confirm_replace else R.string.settings_import_preview)) },
            text = {
                Text(
                    if (replaceWarning) {
                        BACKUP_REPLACE_WARNING.resolve(LocalContext.current.resources)
                    } else {
                        stringResource(R.string.settings_import_counts, counts.accountsAdded, counts.accountsSkipped, counts.accountsReplaced, counts.measurementsAdded, counts.measurementsSkipped, counts.measurementsReplaced, counts.petsAdded, counts.petsSkipped, counts.petsReplaced, counts.petMeasurementsAdded, counts.petMeasurementsSkipped, counts.petMeasurementsReplaced)
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = callbacks.onConfirmBackupImport,
                    enabled = !state.inProgress,
                    modifier = Modifier.testTag(SettingsScreenTestTags.BackupConfirm),
                ) { Text(stringResource(if (replaceWarning) R.string.settings_replace_data else R.string.settings_import)) }
            },
            dismissButton = {
                TextButton(onClick = callbacks.onDismissBackupImport) { Text(stringResource(R.string.settings_cancel)) }
            },
        )
    }
}

@Composable
private fun SettingsIntegrationsContent(
    state: MainUiState,
    callbacks: SettingsCallbacks,
) {
    val resources = LocalContext.current.resources
    val primary = state.primaryAccount
    val healthConnectCapabilities = state.healthConnectCapabilities
    val primaryStatus = when {
        primary == null -> uiText(R.string.settings_primary_not_selected)
        !state.canUseExternalIntegrations -> uiText(R.string.settings_complete_profile, primary.displayName)
        else -> uiText(R.string.settings_primary_profile, primary.displayName)
    }
    val healthConnect = healthConnectPresentation(
        state.healthConnect,
        locallyEnabled = state.settings.healthConnectSyncEnabled,
    )
        .withHealthConnectManagementFallback(
            healthConnectCapabilities.systemManagementAvailable,
        )
        .forPrimaryAccount(
            primaryStatus,
            healthConnectCapabilities.selectedAccountSyncEligible,
        )
    HuaweiSurface(contentPadding = PaddingValues(0.dp)) {
            Column {
                HuaweiSettingRow(
                    icon = HuaweiIcons.Health,
                    title = stringResource(R.string.settings_health_connect),
                    supportingText = healthConnect.supportingText.resolve(resources),
                    modifier = Modifier
                        .testTag(SettingsScreenTestTags.HealthConnectRow)
                        .semantics {
                            contentDescription = resources.getString(R.string.settings_hc_row_cd)
                        },
                    onClick = callbacks.onHealthConnectAccessManagement.takeIf {
                        healthConnectCapabilities.systemManagementAvailable
                    },
                ) {
                    healthConnect.actionLabel?.takeUnless {
                        healthConnect.actionOpensManagement &&
                            !healthConnectCapabilities.systemManagementAvailable
                    }?.let { label ->
                        TextButton(
                            onClick = if (healthConnect.actionOpensManagement) {
                                callbacks.onHealthConnectAccessManagement
                            } else {
                                callbacks.onHealthConnectAuthorization
                            },
                            enabled = healthConnect.actionEnabled,
                            modifier = Modifier
                                .testTag(SettingsScreenTestTags.HealthConnectAction)
                                .semantics {
                                    contentDescription = if (healthConnect.actionOpensManagement) {
                                        resources.getString(R.string.settings_hc_open_cd)
                                    } else {
                                        resources.getString(R.string.settings_hc_connect_cd)
                                    }
                                },
                        ) { Text(label.resolve(resources)) }
                    }
                }
            }
        }
}

private fun IntegrationPresentation.forPrimaryAccount(
    primaryStatus: UiText,
    enabled: Boolean,
): IntegrationPresentation = copy(
    supportingText = uiText(R.string.settings_combined_status, primaryStatus, supportingText),
    actionEnabled = actionEnabled && enabled,
)

private fun scalePresentation(state: MainUiState) = scaleSettingsPresentation(
    selectedAddress = state.settings.scaleAddress,
    selectedName = state.settings.scaleName,
    scanning = state.scanning,
    availability = state.scaleAvailability,
    scanError = state.scaleScanError,
)

@Composable
private fun SettingsScaleContent(state: MainUiState, callbacks: SettingsCallbacks) {
    val resources = LocalContext.current.resources
    val presentation = scalePresentation(state)
    HuaweiSurface(contentPadding = PaddingValues(0.dp)) {
        HuaweiSettingRow(
            icon = HuaweiIcons.Bluetooth,
            title = stringResource(R.string.settings_default_scale_name),
            supportingText = presentation.supportingText.resolve(resources),
            modifier = Modifier.testTag(SettingsScreenTestTags.ScaleStatus),
        ) {
            if (presentation.showProgress) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp).testTag(SettingsScreenTestTags.ScaleProgress),
                )
            }
            presentation.actionLabel?.let { label ->
                TextButton(
                    onClick = when (presentation.action) {
                        ScaleSettingsAction.OPEN_APP_SETTINGS -> callbacks.openApplicationSettings
                        ScaleSettingsAction.SEARCH, ScaleSettingsAction.RETRY -> callbacks.onManualScan
                        null -> ({})
                    },
                    enabled = presentation.actionEnabled,
                    modifier = Modifier.testTag(SettingsScreenTestTags.ScaleAction),
                ) { Text(label.resolve(resources)) }
            }
        }
    }
}

@Composable
private fun CollapsibleSettingsSection(
    title: String,
    expansion: SettingsSectionExpansion,
    testTag: String,
    contentTestTag: String,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    val expanded = expansion == SettingsSectionExpansion.Expanded
    val expansionDescription = stringResource(if (expanded) R.string.settings_expanded else R.string.settings_collapsed)
    Column(verticalArrangement = Arrangement.spacedBy(HuaweiDimensions.CompactItemSpacing)) {
        HuaweiSurface(contentPadding = PaddingValues(0.dp)) {
            HuaweiSettingRow(
                icon = HuaweiIcons.ChevronRight,
                title = title,
                supportingText = expansionDescription,
                modifier = Modifier
                    .testTag(testTag)
                    .semantics {
                        role = Role.Button
                        stateDescription = expansionDescription
                    },
                onClick = onToggle,
            ) {
                HuaweiIconButton(
                    icon = HuaweiIcons.ChevronRight,
                    contentDescription = stringResource(if (expanded) R.string.settings_collapse_section else R.string.settings_expand_section, title),
                    onClick = onToggle,
                    modifier = Modifier.graphicsLayer { rotationZ = if (expanded) 90f else 0f },
                )
            }
        }
        if (expanded) {
            Column(
                modifier = Modifier.fillMaxWidth().testTag(contentTestTag),
                verticalArrangement = Arrangement.spacedBy(HuaweiDimensions.ItemSpacing),
            ) { content() }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AdditionalContent(
    state: MainUiState,
    callbacks: SettingsCallbacks,
    modifier: Modifier = Modifier,
) {
    val resources = LocalContext.current.resources
    Column(
        modifier = modifier.fillMaxWidth().padding(HuaweiDimensions.ContentPadding),
        verticalArrangement = Arrangement.spacedBy(HuaweiDimensions.ItemSpacing),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    role = Role.Switch,
                    onClick = { callbacks.onReliabilityMode(!state.settings.reliabilityMode) },
                )
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_enhanced_reliability), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(R.string.settings_enhanced_reliability_supporting),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(
                checked = state.settings.reliabilityMode,
                onCheckedChange = callbacks.onReliabilityMode,
                modifier = Modifier.semantics { contentDescription = resources.getString(R.string.settings_enhanced_reliability) },
            )
        }
        Text(
            stringResource(R.string.settings_background_help),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(HuaweiDimensions.CompactItemSpacing),
            verticalArrangement = Arrangement.spacedBy(HuaweiDimensions.CompactItemSpacing),
        ) {
            OutlinedButton(
                onClick = callbacks.openBatterySettings,
                modifier = Modifier.heightIn(min = HuaweiDimensions.TouchTarget),
            ) { Text(stringResource(R.string.settings_battery)) }
            OutlinedButton(
                onClick = callbacks.openApplicationSettings,
                modifier = Modifier.heightIn(min = HuaweiDimensions.TouchTarget),
            ) { Text(stringResource(R.string.settings_app_settings)) }
        }
    }
}

@Composable
private fun SettingsDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier.padding(start = 68.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

@Composable
internal fun ProfileEditorScreen(
    state: ProfileEditorUiState,
    onHeightChanged: (String) -> Unit,
    onBirthDateChanged: (String) -> Unit,
    onSexChanged: (Sex) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize().padding(contentPadding),
        contentAlignment = Alignment.TopCenter,
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth()
                .widthIn(max = 720.dp)
                .testTag(SettingsScreenTestTags.ProfileEditor),
            contentPadding = PaddingValues(
                horizontal = HuaweiDimensions.ContentPadding,
                vertical = HuaweiDimensions.CompactItemSpacing,
            ),
            verticalArrangement = Arrangement.spacedBy(HuaweiDimensions.ItemSpacing),
        ) {
            item {
                Text(
                    stringResource(R.string.settings_profile_editor_intro),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            state.errorMessage?.let { message ->
                item {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(SettingsScreenTestTags.ProfileEditorError),
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ) {
                        Text(message.resolve(LocalContext.current.resources), Modifier.padding(14.dp))
                    }
                }
            }
            item {
                HuaweiSurface {
                    Column(verticalArrangement = Arrangement.spacedBy(HuaweiDimensions.ItemSpacing)) {
                        OutlinedTextField(
                            value = state.height,
                            onValueChange = onHeightChanged,
                            label = { Text(stringResource(R.string.settings_height_label)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            isError = state.errorMessage != null,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = state.birthDate,
                            onValueChange = onBirthDateChanged,
                            label = { Text(stringResource(R.string.settings_birth_date_label)) },
                            singleLine = true,
                            isError = state.errorMessage != null,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(stringResource(R.string.settings_sex), style = MaterialTheme.typography.titleSmall)
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(
                                HuaweiDimensions.CompactItemSpacing,
                            ),
                        ) {
                            Sex.entries.forEach { option ->
                                HuaweiFilterButton(
                                    text = stringResource(if (option == Sex.MALE) R.string.settings_sex_male else R.string.settings_sex_female),
                                    onClick = { onSexChanged(option) },
                                    selected = state.sex == option,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(HuaweiDimensions.ItemSpacing)) }
        }
    }
}

@Composable
internal fun ProfileEditorSaveBar(
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars),
        color = MaterialTheme.colorScheme.background,
        shadowElevation = 2.dp,
    ) {
        Button(
            onClick = onSave,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = HuaweiDimensions.ContentPadding, vertical = 12.dp)
                .heightIn(min = HuaweiDimensions.TouchTarget),
            shape = MaterialTheme.shapes.medium,
        ) {
            Text(stringResource(R.string.settings_save_profile))
        }
    }
}
