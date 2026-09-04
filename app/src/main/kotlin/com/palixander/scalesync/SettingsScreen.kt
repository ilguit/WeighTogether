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
import com.palixander.scalesync.ui.components.HuaweiFilterButton
import com.palixander.scalesync.ui.accounts.AccountManagementCallbacks
import com.palixander.scalesync.ui.accounts.AccountManagementSection
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
import java.text.NumberFormat
import java.time.format.DateTimeFormatter
import java.util.Locale

internal data class SettingsCallbacks(
    val onOpenProfile: () -> Unit = {},
    val onOpenChangelog: () -> Unit = {},
    val onHuaweiAuthorization: () -> Unit,
    val onHuaweiPermissionRefresh: () -> Unit,
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
    val onDisableHuawei: () -> Unit = {},
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

internal enum class SettingsSectionKey(val title: String) {
    ACCOUNTS("Профили"),
    INTEGRATIONS("Интеграции"),
    SCALE("Весы"),
    BACKUP("Резервная копия"),
    ADDITIONAL("Дополнительно"),
    ABOUT("О приложении"),
}

internal enum class SettingsDestination(val title: String) {
    ROOT("Настройки"),
    PROFILES("Профили"),
    SCALE("Весы"),
    HEALTH_CONNECT("Health Connect"),
    HUAWEI_HEALTH("Huawei Health"),
    BACKUP("Резервная копия"),
    DIAGNOSTICS("Диагностика"),
}

internal fun settingsRootDestinations(huaweiEnabled: Boolean): List<SettingsDestination> = buildList {
    add(SettingsDestination.PROFILES)
    add(SettingsDestination.SCALE)
    add(SettingsDestination.HEALTH_CONNECT)
    if (huaweiEnabled) add(SettingsDestination.HUAWEI_HEALTH)
    add(SettingsDestination.BACKUP)
    add(SettingsDestination.DIAGNOSTICS)
}

internal fun settingsRootIcon(destination: SettingsDestination) = when (destination) {
    SettingsDestination.PROFILES -> HuaweiIcons.Users
    SettingsDestination.SCALE -> HuaweiIcons.Bluetooth
    SettingsDestination.HEALTH_CONNECT -> HuaweiIcons.HealthConnect
    SettingsDestination.HUAWEI_HEALTH -> HuaweiIcons.HuaweiHealth
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

internal fun huaweiRootStatusSuccessful(
    state: HuaweiIntegrationUiState,
    locallyEnabled: Boolean,
): Boolean = state.status == HuaweiIntegrationStatus.AUTHORIZED && locallyEnabled

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
    SettingsDestination.HUAWEI_HEALTH -> DestructiveSettingsAction.HUAWEI.takeIf {
        BuildConfig.HUAWEI_EXTENDED_ENABLED && state.settings.huaweiSyncEnabled &&
            state.huawei.status == HuaweiIntegrationStatus.AUTHORIZED
    }
    else -> null
}

internal data class IntegrationPresentation(
    val supportingText: String,
    val actionLabel: String? = null,
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
    const val HuaweiHealthDivider = "settings-huawei-health-divider"
    const val HuaweiHealthRow = "settings-huawei-health-row"
    const val HuaweiHealthAction = "settings-huawei-health-action"
    const val HuaweiHealthDetail = "settings-huawei-health-detail"
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
    const val HuaweiHealthStatusMark = "settings-huawei-health-status-mark"
}

internal object SettingsScreenContentDescriptions {
    const val HealthConnectRow = "Настройки Health Connect"
    const val HealthConnectConnectAction = "Подключить Health Connect"
    const val HealthConnectOpenAction = "Открыть Health Connect"
}

private val ProfileSummaryDateFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")

internal fun formatProfileSummary(profile: UserProfile?): String {
    if (profile == null) return "Профиль не настроен"
    val height = if (profile.heightCm % 1.0 == 0.0) {
        profile.heightCm.toInt().toString()
    } else {
        String.format(Locale.US, "%.1f", profile.heightCm).replace('.', ',')
    }
    val sex = if (profile.sex == Sex.MALE) "мужской" else "женский"
    return "$height см · ${profile.birthDate.format(ProfileSummaryDateFormatter)} · $sex"
}

internal fun profilesRootSummary(peopleCount: Int, petCount: Int): String {
    require(peopleCount >= 0)
    require(petCount >= 0)
    if (peopleCount == 0 && petCount == 0) return "Добавьте первый профиль"
    return "${russianCount(peopleCount, "человек", "человека", "человек")} · " +
        russianCount(petCount, "питомец", "питомца", "питомцев")
}

internal fun settingsRootGroupItemIndex(destination: SettingsDestination): Int? = when (destination) {
    SettingsDestination.PROFILES -> 0
    SettingsDestination.SCALE,
    SettingsDestination.HEALTH_CONNECT,
    SettingsDestination.HUAWEI_HEALTH,
    -> 2
    SettingsDestination.BACKUP,
    SettingsDestination.DIAGNOSTICS,
    -> 4
    SettingsDestination.ROOT -> null
}

internal fun scaleDetailIdentity(settings: AppSettings): String {
    val address = settings.scaleAddress ?: return "Устройство не выбрано"
    return listOfNotNull(settings.scaleName, address).joinToString(" · ")
}

private fun russianCount(count: Int, one: String, few: String, many: String): String {
    val word = when {
        count % 100 in 11..14 -> many
        count % 10 == 1 -> one
        count % 10 in 2..4 -> few
        else -> many
    }
    return "$count $word"
}

internal fun formatLatestPetWeight(
    weightKg: Double?,
    locale: Locale = Locale.getDefault(),
): String = weightKg?.let {
    val formatted = NumberFormat.getNumberInstance(locale).run {
        minimumFractionDigits = 0
        maximumFractionDigits = 2
        format(it)
    }
    "Последний вес: $formatted кг"
} ?: "Измерений пока нет"

internal const val BACKUP_REPLACE_WARNING =
    "Все локальные профили, измерения людей, ожидающие измерения, питомцы и измерения питомцев " +
        "будут заменены. Это действие нельзя отменить."

internal fun healthConnectPresentation(
    state: HealthConnectPermissionsUiState,
    locallyEnabled: Boolean = true,
): IntegrationPresentation = when (state.availability) {
    HealthConnectAvailability.CHECKING -> IntegrationPresentation(
        supportingText = "Проверка разрешений…",
        actionLabel = "Подключить",
        actionEnabled = false,
    )
    HealthConnectAvailability.UNAVAILABLE -> IntegrationPresentation(
        supportingText = "Недоступно: устройство не поддерживает Health Connect",
    )
    HealthConnectAvailability.PROVIDER_UPDATE_REQUIRED -> IntegrationPresentation(
        supportingText = "Недоступно: установите или обновите Health Connect",
    )
    HealthConnectAvailability.CHECK_FAILED -> IntegrationPresentation(
        supportingText = "Не удалось проверить разрешения",
        actionLabel = "Подключить",
    )
    HealthConnectAvailability.AVAILABLE -> if (!locallyEnabled) {
        IntegrationPresentation(
            supportingText = "Отключено в приложении",
            actionLabel = "Подключить снова",
        )
    } else if (state.isConnected) {
        IntegrationPresentation(
            supportingText = "Подключено · все разрешения выданы",
            actionLabel = "Открыть",
            actionOpensManagement = true,
        )
    } else {
        val grantedCount = state.permissionStates.count { it.value }
        IntegrationPresentation(
            supportingText = "Разрешено $grantedCount из ${state.requiredPermissions.size}",
            actionLabel = "Подключить",
        )
    }
}

/** Keeps permission management system-owned while explaining the manual fallback when unavailable. */
internal fun IntegrationPresentation.withHealthConnectManagementFallback(
    systemManagementAvailable: Boolean,
): IntegrationPresentation = if (actionOpensManagement && !systemManagementAvailable) {
    copy(
        supportingText = "$supportingText · управляйте доступом вручную в Health Connect",
        actionLabel = null,
    )
} else {
    this
}

internal fun huaweiIntegrationPresentation(
    state: HuaweiIntegrationUiState,
    locallyEnabled: Boolean = true,
): IntegrationPresentation = when (state.status) {
    HuaweiIntegrationStatus.UNAVAILABLE_IN_BUILD -> IntegrationPresentation(
        supportingText = "Недоступно в personal-сборке",
    )
    HuaweiIntegrationStatus.CONFIGURATION_REQUIRED -> IntegrationPresentation(
        supportingText = "Нужны enterprise appId и write-scope",
    )
    HuaweiIntegrationStatus.CHECKING -> IntegrationPresentation(
        supportingText = "Проверка разрешения…",
        actionLabel = "Разрешить",
        actionEnabled = false,
    )
    HuaweiIntegrationStatus.AUTHORIZATION_REQUIRED -> IntegrationPresentation(
        supportingText = "Настроено · требуется авторизация",
        actionLabel = "Разрешить",
    )
    HuaweiIntegrationStatus.AUTHORIZED -> if (locallyEnabled) {
        IntegrationPresentation(supportingText = "Подключено")
    } else {
        IntegrationPresentation(
            supportingText = "Отключено в приложении",
            actionLabel = "Подключить снова",
        )
    }
    HuaweiIntegrationStatus.CHECK_FAILED -> IntegrationPresentation(
        supportingText = "Не удалось проверить разрешение",
        actionLabel = "Повторить",
        actionRetriesCheck = true,
    )
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
        settingsRootDestinations(BuildConfig.HUAWEI_EXTENDED_ENABLED)
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
        SettingsDestination.HUAWEI_HEALTH -> if (BuildConfig.HUAWEI_EXTENDED_ENABLED) {
            SettingsHuaweiHealthDetail(state, callbacks, contentPadding, modifier)
        } else {
            SettingsRootScreen(
                state, callbacks, contentPadding, openDestination, rootFocusRequesters,
                rootListState, modifier,
            )
        }
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
            title = "Весы",
            icon = HuaweiIcons.Bluetooth,
            status = presentation.supportingText,
            identityLabel = "Устройство",
            identity = scaleDetailIdentity(state.settings),
            actionLabel = presentation.actionLabel,
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
            title = "Health Connect",
            icon = HuaweiIcons.HealthConnect,
            status = presentation.supportingText,
            identityLabel = "Устройство",
            identity = "Системная интеграция",
            actionLabel = presentation.actionLabel,
            actionEnabled = presentation.actionEnabled,
            actionTag = SettingsScreenTestTags.HealthConnectAction,
            actionContentDescription = if (presentation.actionOpensManagement) {
                SettingsScreenContentDescriptions.HealthConnectOpenAction
            } else {
                SettingsScreenContentDescriptions.HealthConnectConnectAction
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
private fun SettingsHuaweiHealthDetail(
    state: MainUiState,
    callbacks: SettingsCallbacks,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val presentation = huaweiIntegrationPresentation(
        state.huawei,
        state.settings.huaweiSyncEnabled,
    )
    SettingsActionDetail(
        state = state,
        contentPadding = contentPadding,
        testTag = SettingsScreenTestTags.HuaweiHealthDetail,
        destructiveAction = settingsDetailDestructiveAction(SettingsDestination.HUAWEI_HEALTH, state),
        callbacks = callbacks,
        modifier = modifier,
    ) {
        ConnectionDetailContent(
            title = "Huawei Health",
            icon = HuaweiIcons.HuaweiHealth,
            status = presentation.supportingText,
            identityLabel = "Интеграция",
            identity = "Huawei Health Kit",
            actionLabel = presentation.actionLabel,
            actionEnabled = presentation.actionEnabled,
            actionTag = SettingsScreenTestTags.HuaweiHealthAction,
            onAction = if (presentation.actionRetriesCheck) {
                callbacks.onHuaweiPermissionRefresh
            } else {
                callbacks.onHuaweiAuthorization
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
                    DestructiveSettingsAction.HUAWEI -> callbacks.onDisableHuawei()
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
        DetailSectionTitle("Состояние")
        SettingsGroup(Modifier.testTag(SettingsScreenTestTags.DetailStatusGroup)) {
            DetailInfoRow(identityLabel, identity)
            SettingsGroupDivider(Modifier.testTag(SettingsScreenTestTags.DetailStatusDivider))
            DetailInfoRow(
                label = "Статус",
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
        DetailSectionTitle("Сохранить данные")
        SettingsGroup(Modifier.testTag(SettingsScreenTestTags.BackupSaveGroup)) {
            DetailActionRow(
                icon = HuaweiIcons.Archive,
                title = "Экспортировать резервную копию",
                supportingText = "Профили, питомцы, измерения и настройки",
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
            Text("Обработка резервной копии…")
        }
        DetailSectionTitle("Восстановить данные")
        SettingsGroup(Modifier.testTag(SettingsScreenTestTags.BackupRestoreGroup)) {
            DetailActionRow(
                icon = HuaweiIcons.Refresh,
                title = "Импортировать и объединить",
                supportingText = "Сохранить существующие данные",
                enabled = !state.inProgress,
                tag = SettingsScreenTestTags.BackupMerge,
                onClick = { callbacks.onImportBackup(BackupImportMode.MERGE) },
            )
            SettingsGroupDivider(Modifier.testTag(SettingsScreenTestTags.BackupRestoreDivider))
            DetailActionRow(
                icon = HuaweiIcons.Warning,
                title = "Полностью заменить данные",
                supportingText = "Текущие данные будут удалены после подтверждения",
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
        DetailSectionTitle("Работа в фоне")
        SettingsGroup(Modifier.testTag(SettingsScreenTestTags.DiagnosticsBackgroundGroup)) {
            DiagnosticsSwitchRow(state, callbacks)
            SettingsGroupDivider(Modifier.testTag(SettingsScreenTestTags.DiagnosticsBackgroundDivider))
            DetailActionRow(
                icon = HuaweiIcons.Settings,
                title = "Настройки батареи",
                supportingText = "Разрешить фоновую работу",
                tag = "settings-diagnostics-battery",
                onClick = callbacks.openBatterySettings,
            )
            SettingsGroupDivider(Modifier.testTag(SettingsScreenTestTags.DiagnosticsBackgroundSecondDivider))
            DetailActionRow(
                icon = HuaweiIcons.Tune,
                title = "Системные настройки приложения",
                supportingText = "Разрешения и уведомления",
                tag = "settings-diagnostics-application",
                onClick = callbacks.openApplicationSettings,
            )
        }
    }
}

@Composable
private fun DiagnosticsSwitchRow(state: MainUiState, callbacks: SettingsCallbacks) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 68.dp)
            .clickable(role = Role.Switch) {
                callbacks.onReliabilityMode(!state.settings.reliabilityMode)
            }.padding(horizontal = HuaweiDimensions.ContentPadding, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text("Режим надёжности", style = MaterialTheme.typography.titleSmall)
            Text(
                "Поддерживать поиск весов в фоне",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Switch(
            checked = state.settings.reliabilityMode,
            onCheckedChange = callbacks.onReliabilityMode,
            modifier = Modifier.semantics { contentDescription = "Режим надёжности" },
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
        DestructiveSettingsAction.HEALTH_CONNECT -> "Отключить Health Connect" to SettingsScreenTestTags.DisableHealthConnect
        DestructiveSettingsAction.HUAWEI -> "Отключить Huawei Health" to SettingsScreenTestTags.DisableHuawei
        DestructiveSettingsAction.SCALE -> "Забыть выбранные весы" to SettingsScreenTestTags.ForgetScale
    }
    Column(Modifier.testTag(SettingsScreenTestTags.DetailDangerZone)) {
        DetailSectionTitle("Управление")
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
                    petSpeciesLabel = { petSpeciesLabel(it.pet.species) },
                    petWeightLabel = { formatLatestPetWeight(it.latestPetWeightKg) },
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
    val scalePresentation = scalePresentation(state)
    val healthPresentation = healthConnectPresentation(
        state.healthConnect,
        state.settings.healthConnectSyncEnabled,
    ).withHealthConnectManagementFallback(state.healthConnectSystemManagementAvailable)
    val huaweiPresentation = huaweiIntegrationPresentation(
        state.huawei,
        state.settings.huaweiSyncEnabled,
    )
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
                    "Весы и синхронизация",
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
                    if (BuildConfig.HUAWEI_EXTENDED_ENABLED) {
                        SettingsRootDivider(SettingsScreenTestTags.ConnectionsSecondDivider)
                        SettingsNavigationRow(
                            SettingsDestination.HUAWEI_HEALTH,
                            settingsRootIcon(SettingsDestination.HUAWEI_HEALTH),
                            huaweiPresentation.supportingText, SettingsScreenTestTags.HuaweiHealthRow,
                            onDestinationChanged, focusRequesters[SettingsDestination.HUAWEI_HEALTH],
                            status = {
                                SettingsRootStatusMark(
                                    huaweiRootStatusSuccessful(
                                        state.huawei,
                                        state.settings.huaweiSyncEnabled,
                                    ),
                                    SettingsScreenTestTags.HuaweiHealthStatusMark,
                                )
                            },
                        )
                    }
                }
            }
            item {
                Text(
                    "Данные и приложение",
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
                    SettingsNavigationRow(
                        SettingsDestination.BACKUP,
                        settingsRootIcon(SettingsDestination.BACKUP),
                        "Экспорт и восстановление данных",
                        SettingsScreenTestTags.BackupRow,
                        onDestinationChanged,
                        focusRequesters[SettingsDestination.BACKUP],
                    )
                    SettingsRootDivider(SettingsScreenTestTags.SupportDivider)
                    SettingsNavigationRow(
                        SettingsDestination.DIAGNOSTICS,
                        settingsRootIcon(SettingsDestination.DIAGNOSTICS),
                        "Проверка и системные параметры",
                        SettingsScreenTestTags.DiagnosticsRow,
                        onDestinationChanged,
                        focusRequesters[SettingsDestination.DIAGNOSTICS],
                    )
                    SettingsRootDivider(SettingsScreenTestTags.SupportSecondDivider)
                    SettingsGroupRow(
                        leadingIcon = HuaweiIcons.History,
                        title = "История версий",
                        supportingText = "Что изменилось в приложении",
                        modifier = Modifier.testTag(SettingsScreenTestTags.ChangelogRow),
                        onClick = callbacks.onOpenChangelog,
                        leadingIconTag = SettingsScreenTestTags.ChangelogRow + SettingsScreenTestTags.LeadingIconSuffix,
                        trailingTag = SettingsScreenTestTags.ChangelogRow + SettingsScreenTestTags.TrailingChevronSuffix,
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsNavigationRow(
    destination: SettingsDestination,
    leadingIcon: androidx.compose.ui.graphics.vector.ImageVector,
    supportingText: String,
    testTag: String,
    onDestinationChanged: (SettingsDestination) -> Unit,
    focusRequester: FocusRequester?,
    status: (@Composable () -> Unit)? = null,
) {
    SettingsGroupRow(
        leadingIcon = leadingIcon,
        title = destination.title,
        supportingText = supportingText,
        modifier = Modifier
            .testTag(testTag)
            .then(if (focusRequester == null) Modifier else Modifier.focusRequester(focusRequester))
            .semantics {
                stateDescription = supportingText
                if (destination == SettingsDestination.HEALTH_CONNECT) {
                    contentDescription = SettingsScreenContentDescriptions.HealthConnectRow
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
        Text(destination.title, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun LegacySettingsScreen(
    state: MainUiState,
    callbacks: SettingsCallbacks,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
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
                    title = "Профили",
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
                            petSpeciesLabel = { petSpeciesLabel(it.pet.species) },
                            petWeightLabel = { formatLatestPetWeight(it.latestPetWeightKg) },
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
                    title = "Интеграции",
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
                    title = "Весы",
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
                    title = "Резервная копия",
                    expansion = backupExpansion,
                    testTag = SettingsScreenTestTags.BackupSection,
                    contentTestTag = SettingsScreenTestTags.BackupContent,
                    onToggle = { backupExpansion = backupExpansion.toggled() },
                ) { SettingsBackupContent(state.backup, callbacks) }
            }
            item {
                CollapsibleSettingsSection(
                    title = "Дополнительно",
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
                    title = "О приложении",
                    expansion = aboutExpansion,
                    testTag = SettingsScreenTestTags.AboutSection,
                    contentTestTag = SettingsScreenTestTags.AboutContent,
                    onToggle = { aboutExpansion = aboutExpansion.toggled() },
                ) {
                    HuaweiSurface(contentPadding = PaddingValues(0.dp)) {
                        HuaweiSettingRow(
                            icon = HuaweiIcons.Calendar,
                            title = "История изменений",
                            supportingText = "Что нового в версиях приложения",
                            modifier = Modifier.testTag(SettingsScreenTestTags.ChangelogRow),
                            onClick = callbacks.onOpenChangelog,
                        ) {
                            HuaweiIconButton(
                                icon = HuaweiIcons.ChevronRight,
                                contentDescription = "Открыть историю изменений",
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
            title = { Text(if (replaceWarning) "Подтвердите замену" else "Проверка импорта") },
            text = {
                Text(
                    if (replaceWarning) {
                        BACKUP_REPLACE_WARNING
                    } else {
                        "Профили: +${counts.accountsAdded}, пропущено ${counts.accountsSkipped}, заменено ${counts.accountsReplaced}. " +
                            "Измерения: +${counts.measurementsAdded}, пропущено ${counts.measurementsSkipped}, заменено ${counts.measurementsReplaced}. " +
                            "Питомцы: +${counts.petsAdded}, пропущено ${counts.petsSkipped}, заменено ${counts.petsReplaced}. " +
                            "Измерения питомцев: +${counts.petMeasurementsAdded}, пропущено ${counts.petMeasurementsSkipped}, заменено ${counts.petMeasurementsReplaced}."
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = callbacks.onConfirmBackupImport,
                    enabled = !state.backup.inProgress,
                    modifier = Modifier.testTag(SettingsScreenTestTags.BackupConfirm),
                ) { Text(if (replaceWarning) "Заменить данные" else "Импортировать") }
            },
            dismissButton = {
                TextButton(onClick = callbacks.onDismissBackupImport) { Text("Отмена") }
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
                    DestructiveSettingsAction.HUAWEI -> callbacks.onDisableHuawei()
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
    val huaweiEnabled = BuildConfig.HUAWEI_EXTENDED_ENABLED && state.settings.huaweiSyncEnabled &&
        state.huawei.status == HuaweiIntegrationStatus.AUTHORIZED
    if (!healthEnabled && !huaweiEnabled) return
    Column {
        SettingsDivider()
        HuaweiSurface(
            modifier = Modifier.testTag(SettingsScreenTestTags.DestructiveSection),
            contentPadding = PaddingValues(HuaweiDimensions.ContentPadding),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(HuaweiDimensions.CompactItemSpacing)) {
                Text(
                    "Эти действия прекращают будущую синхронизацию или удаляют привязку устройства.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                if (healthEnabled) OutlinedButton(
                    onClick = { onRequest(DestructiveSettingsAction.HEALTH_CONNECT) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().testTag(SettingsScreenTestTags.DisableHealthConnect),
                ) { Text("Отключить Health Connect") }
                if (huaweiEnabled) OutlinedButton(
                    onClick = { onRequest(DestructiveSettingsAction.HUAWEI) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().testTag(SettingsScreenTestTags.DisableHuawei),
                ) { Text("Отключить Huawei Health") }
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
    ) { Text("Забыть выбранные весы") }
}

@Composable
private fun DestructiveConfirmationDialog(
    action: DestructiveSettingsAction,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val (title, warning) = when (action) {
        DestructiveSettingsAction.HEALTH_CONNECT -> "Отключить Health Connect?" to
            "Новые измерения перестанут отправляться. Уже записанные данные не удалятся. Системные разрешения отзываются отдельно."
        DestructiveSettingsAction.HUAWEI -> "Отключить Huawei Health?" to
            "Новые измерения перестанут отправляться. Уже записанные данные не удалятся. Доступ отзывается отдельно в Huawei Health."
        DestructiveSettingsAction.SCALE -> "Забыть выбранные весы?" to
            "Фоновое сканирование будет остановлено, а привязку весов потребуется настроить заново. Измерения не удалятся."
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
                    Text("Выполняется…")
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = !busy,
                modifier = Modifier.testTag(SettingsScreenTestTags.DestructiveConfirm),
            ) { Text("Подтвердить") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Отмена") } },
    )
}

@Composable
private fun PetDeletionDialog(state: MainUiState, callbacks: SettingsCallbacks) {
    state.petManagement.deletion?.let { preview ->
        AlertDialog(
            modifier = Modifier.testTag(SettingsScreenTestTags.PetDeleteDialog),
            onDismissRequest = { if (!state.petManagement.busy) callbacks.onDismissPetManagement() },
            title = { Text("Удалить ${preview.pet.displayName}?") },
            text = { Text("Будет удалено измерений: ${preview.measurementCount}. Это действие нельзя отменить.") },
            confirmButton = {
                TextButton(
                    enabled = !state.petManagement.busy,
                    onClick = callbacks.onConfirmDeletePet,
                ) { Text("Удалить") }
            },
            dismissButton = {
                TextButton(
                    enabled = !state.petManagement.busy,
                    onClick = callbacks.onDismissPetManagement,
                ) { Text("Отмена") }
            },
        )
    }
}

@Composable
private fun SettingsBackupContent(state: BackupUiState, callbacks: SettingsCallbacks) {
        HuaweiSurface(contentPadding = PaddingValues(HuaweiDimensions.ContentPadding)) {
            Column(verticalArrangement = Arrangement.spacedBy(HuaweiDimensions.CompactItemSpacing)) {
                Text("Сохраните данные в JSON или импортируйте копию с предварительной проверкой.")
                if (state.inProgress) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator()
                        Text("Обработка резервной копии…")
                    }
                }
                Button(
                    onClick = callbacks.onExportBackup,
                    enabled = !state.inProgress,
                    modifier = Modifier.fillMaxWidth().testTag(SettingsScreenTestTags.BackupExport),
                ) { Text("Экспортировать") }
                OutlinedButton(
                    onClick = { callbacks.onImportBackup(BackupImportMode.MERGE) },
                    enabled = !state.inProgress,
                    modifier = Modifier.fillMaxWidth().testTag(SettingsScreenTestTags.BackupMerge),
                ) { Text("Импортировать и объединить") }
                OutlinedButton(
                    onClick = { callbacks.onImportBackup(BackupImportMode.REPLACE) },
                    enabled = !state.inProgress,
                    modifier = Modifier.fillMaxWidth().testTag(SettingsScreenTestTags.BackupReplace),
                ) { Text("Импортировать с заменой") }
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
            title = { Text(if (replaceWarning) "Подтвердите замену" else "Проверка импорта") },
            text = {
                Text(
                    if (replaceWarning) {
                        BACKUP_REPLACE_WARNING
                    } else {
                        "Профили: +${counts.accountsAdded}, пропущено ${counts.accountsSkipped}, заменено ${counts.accountsReplaced}. " +
                            "Измерения: +${counts.measurementsAdded}, пропущено ${counts.measurementsSkipped}, заменено ${counts.measurementsReplaced}. " +
                            "Питомцы: +${counts.petsAdded}, пропущено ${counts.petsSkipped}, заменено ${counts.petsReplaced}. " +
                            "Измерения питомцев: +${counts.petMeasurementsAdded}, пропущено ${counts.petMeasurementsSkipped}, заменено ${counts.petMeasurementsReplaced}."
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = callbacks.onConfirmBackupImport,
                    enabled = !state.inProgress,
                    modifier = Modifier.testTag(SettingsScreenTestTags.BackupConfirm),
                ) { Text(if (replaceWarning) "Заменить данные" else "Импортировать") }
            },
            dismissButton = {
                TextButton(onClick = callbacks.onDismissBackupImport) { Text("Отмена") }
            },
        )
    }
}

@Composable
private fun SettingsIntegrationsContent(
    state: MainUiState,
    callbacks: SettingsCallbacks,
) {
    val primary = state.primaryAccount
    val healthConnectCapabilities = state.healthConnectCapabilities
    val primaryStatus = when {
        primary == null -> "Основной профиль не выбран"
        !state.canUseExternalIntegrations -> "${primary.displayName} · заполните профиль"
        else -> "Основной: ${primary.displayName}"
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
    val huawei = if (BuildConfig.HUAWEI_EXTENDED_ENABLED) {
        huaweiIntegrationPresentation(
            state.huawei,
            locallyEnabled = state.settings.huaweiSyncEnabled,
        ).forPrimaryAccount(
            primaryStatus,
            state.canUseExternalIntegrations,
        )
    } else {
        null
    }
        HuaweiSurface(contentPadding = PaddingValues(0.dp)) {
            Column {
                HuaweiSettingRow(
                    icon = HuaweiIcons.Health,
                    title = "Health Connect",
                    supportingText = healthConnect.supportingText,
                    modifier = Modifier
                        .testTag(SettingsScreenTestTags.HealthConnectRow)
                        .semantics {
                            contentDescription = SettingsScreenContentDescriptions.HealthConnectRow
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
                                        SettingsScreenContentDescriptions.HealthConnectOpenAction
                                    } else {
                                        SettingsScreenContentDescriptions.HealthConnectConnectAction
                                    }
                                },
                        ) { Text(label) }
                    }
                }
                huawei?.let { presentation ->
                    SettingsDivider(
                        modifier = Modifier.testTag(SettingsScreenTestTags.HuaweiHealthDivider),
                    )
                    HuaweiSettingRow(
                        icon = HuaweiIcons.Link,
                        title = "Huawei Health",
                        supportingText = presentation.supportingText,
                        modifier = Modifier.testTag(SettingsScreenTestTags.HuaweiHealthRow),
                    ) {
                        presentation.actionLabel?.let { label ->
                            TextButton(
                                onClick = if (presentation.actionRetriesCheck) {
                                    callbacks.onHuaweiPermissionRefresh
                                } else {
                                    callbacks.onHuaweiAuthorization
                                },
                                enabled = presentation.actionEnabled,
                                modifier = Modifier.testTag(
                                    SettingsScreenTestTags.HuaweiHealthAction,
                                ),
                            ) { Text(label) }
                        }
                    }
                }
            }
        }
}

private fun IntegrationPresentation.forPrimaryAccount(
    primaryStatus: String,
    enabled: Boolean,
): IntegrationPresentation = copy(
    supportingText = "$primaryStatus · $supportingText",
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
    val presentation = scalePresentation(state)
    HuaweiSurface(contentPadding = PaddingValues(0.dp)) {
        HuaweiSettingRow(
            icon = HuaweiIcons.Bluetooth,
            title = "Mi Body Composition Scale 2",
            supportingText = presentation.supportingText,
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
                ) { Text(label) }
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
    Column(verticalArrangement = Arrangement.spacedBy(HuaweiDimensions.CompactItemSpacing)) {
        HuaweiSurface(contentPadding = PaddingValues(0.dp)) {
            HuaweiSettingRow(
                icon = HuaweiIcons.ChevronRight,
                title = title,
                supportingText = if (expanded) "Развёрнуто" else "Свёрнуто",
                modifier = Modifier
                    .testTag(testTag)
                    .semantics {
                        role = Role.Button
                        stateDescription = if (expanded) "Развёрнуто" else "Свёрнуто"
                    },
                onClick = onToggle,
            ) {
                HuaweiIconButton(
                    icon = HuaweiIcons.ChevronRight,
                    contentDescription = if (expanded) "Свернуть $title" else "Развернуть $title",
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
                Text("Повышенная надёжность", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Постоянное ожидание весов в фоне",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(
                checked = state.settings.reliabilityMode,
                onCheckedChange = callbacks.onReliabilityMode,
                modifier = Modifier.semantics { contentDescription = "Повышенная надёжность" },
            )
        }
        Text(
            "Для фоновой работы на некоторых устройствах разрешите автозапуск, работу от батареи и уведомления.",
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
            ) { Text("Батарея") }
            OutlinedButton(
                onClick = callbacks.openApplicationSettings,
                modifier = Modifier.heightIn(min = HuaweiDimensions.TouchTarget),
            ) { Text("Настройки приложения") }
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
                    "Данные используются для расчёта состава тела.",
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
                        Text(message, Modifier.padding(14.dp))
                    }
                }
            }
            item {
                HuaweiSurface {
                    Column(verticalArrangement = Arrangement.spacedBy(HuaweiDimensions.ItemSpacing)) {
                        OutlinedTextField(
                            value = state.height,
                            onValueChange = onHeightChanged,
                            label = { Text("Рост, см") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            isError = state.errorMessage != null,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = state.birthDate,
                            onValueChange = onBirthDateChanged,
                            label = { Text("Дата рождения, ГГГГ-ММ-ДД") },
                            singleLine = true,
                            isError = state.errorMessage != null,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text("Пол", style = MaterialTheme.typography.titleSmall)
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(
                                HuaweiDimensions.CompactItemSpacing,
                            ),
                        ) {
                            Sex.entries.forEach { option ->
                                HuaweiFilterButton(
                                    text = if (option == Sex.MALE) "Мужской" else "Женский",
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
            Text("Сохранить профиль")
        }
    }
}
