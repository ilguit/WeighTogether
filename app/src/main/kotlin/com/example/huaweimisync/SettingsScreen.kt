package com.example.huaweimisync

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
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
import com.example.huaweimisync.core.Sex
import com.example.huaweimisync.core.UserProfile
import com.example.huaweimisync.backup.BackupImportMode
import com.example.huaweimisync.domain.Pet
import com.example.huaweimisync.domain.PetId
import com.example.huaweimisync.domain.PetSpecies
import com.example.huaweimisync.domain.normalizePetName
import com.example.huaweimisync.ui.components.HuaweiFilterButton
import com.example.huaweimisync.ui.accounts.AccountManagementCallbacks
import com.example.huaweimisync.ui.accounts.AccountManagementSection
import com.example.huaweimisync.ui.accounts.WeightRecognitionSetting
import com.example.huaweimisync.ui.components.HuaweiIconButton
import com.example.huaweimisync.ui.components.HuaweiSettingRow
import com.example.huaweimisync.ui.components.HuaweiSurface
import com.example.huaweimisync.ui.icons.HuaweiIcons
import com.example.huaweimisync.ui.theme.HuaweiDimensions
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
    val onManualTest: (String, String) -> Unit,
    val onManualScan: () -> Unit,
    val onReliabilityMode: (Boolean) -> Unit,
    val openBatterySettings: () -> Unit,
    val openApplicationSettings: () -> Unit,
    val accountManagement: AccountManagementCallbacks = AccountManagementCallbacks.None,
    val onWeightDeltaStateChanged: (com.example.huaweimisync.ui.accounts.WeightDeltaEditorState) -> Unit = {},
    val onWeightDeltaSave: (Double) -> Unit = {},
    val onIgnoreUnknownMeasurementsChanged: (Boolean) -> Unit = {},
    val onCreatePet: () -> Unit = {},
    val onEditPet: (Pet) -> Unit = {},
    val onSavePet: (String, PetSpecies) -> Unit = { _, _ -> },
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
    const val PetEditor = "settings-pet-editor"
    const val PetDeleteDialog = "settings-pet-delete-dialog"
    const val DestructiveSection = "settings-destructive-section"
    const val DisableHealthConnect = "settings-disable-health-connect"
    const val DisableHuawei = "settings-disable-huawei"
    const val ForgetScale = "settings-forget-scale"
    const val DestructiveDialog = "settings-destructive-dialog"
    const val DestructiveConfirm = "settings-destructive-confirm"
    const val IntegrationStatus = "settings-integration-status"
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
    var manualTestWeight by rememberSaveable { mutableStateOf("70.0") }
    var manualTestImpedance by rememberSaveable { mutableStateOf("500") }
    var returnFocusDestination by rememberSaveable { mutableStateOf<SettingsDestination?>(null) }
    val rootListState = rememberLazyListState()
    val rootFocusRequesters = remember {
        settingsRootDestinations(BuildConfig.HUAWEI_EXTENDED_ENABLED)
            .associateWith { FocusRequester() }
    }
    LaunchedEffect(destination) {
        if (destination == SettingsDestination.ROOT) {
            returnFocusDestination?.let { returnedFrom ->
                val destinationIndex = settingsRootDestinations(
                    BuildConfig.HUAWEI_EXTENDED_ENABLED,
                ).indexOf(returnedFrom)
                if (destinationIndex >= 0) {
                    rootListState.scrollToItem(destinationIndex + if (destinationIndex == 0) 0 else 1)
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
            weight = manualTestWeight,
            onWeightChanged = { manualTestWeight = it },
            impedance = manualTestImpedance,
            onImpedanceChanged = { manualTestImpedance = it },
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
        SettingsBackupContent(state.backup, callbacks)
    }
    BackupImportDialog(state.backup, callbacks)
}

@Composable
private fun SettingsDiagnosticsDetail(
    state: MainUiState,
    callbacks: SettingsCallbacks,
    weight: String,
    onWeightChanged: (String) -> Unit,
    impedance: String,
    onImpedanceChanged: (String) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    SettingsSimpleDetail(contentPadding, SettingsScreenTestTags.DiagnosticsDetail, modifier) {
        HuaweiSurface(contentPadding = PaddingValues(0.dp)) {
            AdditionalContent(
                state = state,
                callbacks = callbacks,
                weight = weight,
                onWeightChanged = onWeightChanged,
                impedance = impedance,
                onImpedanceChanged = onImpedanceChanged,
            )
        }
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
        destructiveAction = DestructiveSettingsAction.SCALE.takeIf { scalePresentation(state).allowForget },
        callbacks = callbacks,
        modifier = modifier,
    ) {
        SettingsScaleContent(state, callbacks)
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
        destructiveAction = DestructiveSettingsAction.HEALTH_CONNECT.takeIf {
            state.settings.healthConnectSyncEnabled && state.healthConnect.isConnected
        },
        callbacks = callbacks,
        modifier = modifier,
    ) {
        IntegrationDetailCard(
            title = "Health Connect",
            presentation = presentation,
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
        destructiveAction = DestructiveSettingsAction.HUAWEI.takeIf {
            state.settings.huaweiSyncEnabled && state.huawei.status == HuaweiIntegrationStatus.AUTHORIZED
        },
        callbacks = callbacks,
        modifier = modifier,
    ) {
        IntegrationDetailCard(
            title = "Huawei Health",
            presentation = presentation,
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
private fun IntegrationDetailCard(
    title: String,
    presentation: IntegrationPresentation,
    actionTag: String,
    onAction: () -> Unit,
    actionContentDescription: String? = null,
) {
    HuaweiSurface(contentPadding = PaddingValues(HuaweiDimensions.ContentPadding)) {
        Column(verticalArrangement = Arrangement.spacedBy(HuaweiDimensions.CompactItemSpacing)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                presentation.supportingText,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .testTag(SettingsScreenTestTags.IntegrationStatus)
                    .semantics { stateDescription = presentation.supportingText },
            )
            presentation.actionLabel?.let { label ->
                Button(
                    onClick = onAction,
                    enabled = presentation.actionEnabled,
                    modifier = Modifier.fillMaxWidth().testTag(actionTag).then(
                        if (actionContentDescription == null) Modifier else Modifier.semantics {
                            contentDescription = actionContentDescription
                        },
                    ),
                ) { Text(label) }
            }
        }
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
    Column {
        SettingsDivider()
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().testTag(tag),
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
    PetManagementDialogs(state, callbacks)
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
                start = HuaweiDimensions.ContentPadding,
                end = HuaweiDimensions.ContentPadding,
                top = 4.dp,
                bottom = 28.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(HuaweiDimensions.ItemSpacing),
        ) {
            item {
                SettingsNavigationRow(
                    SettingsDestination.PROFILES,
                    profilesRootSummary(state.accountManagement.accounts.size, state.pets.size),
                    SettingsScreenTestTags.ProfilesRow,
                    onDestinationChanged,
                    focusRequesters[SettingsDestination.PROFILES],
                )
            }
            item {
                Text(
                    "Весы и синхронизация",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier
                        .padding(start = HuaweiDimensions.CompactContentPadding)
                        .semantics { heading() },
                )
            }
            item { SettingsNavigationRow(SettingsDestination.SCALE, scaleStatus(state), SettingsScreenTestTags.ScaleRow, onDestinationChanged, focusRequesters[SettingsDestination.SCALE]) }
            item { SettingsNavigationRow(SettingsDestination.HEALTH_CONNECT, healthPresentation.supportingText, SettingsScreenTestTags.HealthConnectRow, onDestinationChanged, focusRequesters[SettingsDestination.HEALTH_CONNECT]) }
            if (BuildConfig.HUAWEI_EXTENDED_ENABLED) item {
                SettingsNavigationRow(SettingsDestination.HUAWEI_HEALTH, huaweiPresentation.supportingText, SettingsScreenTestTags.HuaweiHealthRow, onDestinationChanged, focusRequesters[SettingsDestination.HUAWEI_HEALTH])
            }
            item { SettingsNavigationRow(SettingsDestination.BACKUP, "Экспорт и импорт данных", SettingsScreenTestTags.BackupRow, onDestinationChanged, focusRequesters[SettingsDestination.BACKUP]) }
            item { SettingsNavigationRow(SettingsDestination.DIAGNOSTICS, "Проверка и системные настройки", SettingsScreenTestTags.DiagnosticsRow, onDestinationChanged, focusRequesters[SettingsDestination.DIAGNOSTICS]) }
            item {
                HuaweiSurface(contentPadding = PaddingValues(0.dp)) {
                    HuaweiSettingRow(
                        icon = HuaweiIcons.Calendar,
                        title = "История версий",
                        supportingText = "Что нового в приложении",
                        modifier = Modifier.testTag(SettingsScreenTestTags.ChangelogRow),
                        onClick = callbacks.onOpenChangelog,
                        titleMaxLines = Int.MAX_VALUE,
                        supportingTextMaxLines = Int.MAX_VALUE,
                    ) { SettingsNavigationChevron() }
                }
            }
        }
    }
}

private fun scaleStatus(state: MainUiState): String = scalePresentation(state).supportingText

@Composable
private fun SettingsNavigationRow(
    destination: SettingsDestination,
    supportingText: String,
    testTag: String,
    onDestinationChanged: (SettingsDestination) -> Unit,
    focusRequester: FocusRequester?,
) {
    HuaweiSurface(contentPadding = PaddingValues(0.dp)) {
        HuaweiSettingRow(
            icon = HuaweiIcons.ChevronRight,
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
            titleMaxLines = Int.MAX_VALUE,
            supportingTextMaxLines = Int.MAX_VALUE,
        ) { SettingsNavigationChevron() }
    }
}

@Composable
private fun SettingsNavigationChevron() {
    Box(
        modifier = Modifier.size(HuaweiDimensions.TouchTarget),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = HuaweiIcons.ChevronRight,
            contentDescription = null,
            modifier = Modifier.size(HuaweiDimensions.Icon),
        )
    }
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
    var manualTestWeight by rememberSaveable { mutableStateOf("70.0") }
    var manualTestImpedance by rememberSaveable { mutableStateOf("500") }
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
                        weight = manualTestWeight,
                        onWeightChanged = { manualTestWeight = it },
                        impedance = manualTestImpedance,
                        onImpedanceChanged = { manualTestImpedance = it },
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
    PetManagementDialogs(state, callbacks)
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
private fun PetManagementDialogs(state: MainUiState, callbacks: SettingsCallbacks) {
    state.petManagement.editor?.let { mode ->
        val existing = (mode as? PetEditorMode.Edit)?.pet
        var name by rememberSaveable(existing?.id?.value) { mutableStateOf(existing?.displayName.orEmpty()) }
        var species by rememberSaveable(existing?.id?.value) {
            mutableStateOf(existing?.species?.takeUnless { it == PetSpecies.UNSPECIFIED })
        }
        var submitted by rememberSaveable(existing?.id?.value) { mutableStateOf(false) }
        val duplicate = state.pets.any {
            it.pet.id != existing?.id && normalizePetName(it.pet.displayName) == normalizePetName(name)
        }
        val valid = isPetEditorValid(name, species) && !duplicate
        AlertDialog(
            modifier = Modifier.testTag(SettingsScreenTestTags.PetEditor),
            onDismissRequest = { if (!state.petManagement.busy) callbacks.onDismissPetManagement() },
            title = { Text(if (existing == null) "Новый питомец" else "Изменить питомца") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it; submitted = false },
                        label = { Text("Имя питомца") },
                        singleLine = true,
                        isError = submitted && !valid,
                    )
                    PetSpeciesSelector(species) { species = it; submitted = false }
                    if (submitted && !valid) Text(
                        when {
                            name.trim().isEmpty() -> "Введите имя питомца"
                            duplicate -> "Питомец с таким именем уже есть"
                            species == null -> "Выберите вид питомца"
                            else -> "Имя должно содержать не больше 50 символов"
                        },
                        color = MaterialTheme.colorScheme.error,
                    )
                    state.petManagement.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !state.petManagement.busy,
                    onClick = {
                        submitted = true
                        if (valid) callbacks.onSavePet(name.trim(), requireNotNull(species))
                    },
                ) { Text("Сохранить") }
            },
            dismissButton = {
                TextButton(
                    enabled = !state.petManagement.busy,
                    onClick = callbacks.onDismissPetManagement,
                ) { Text("Отмена") }
            },
        )
    }
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
    weight: String,
    onWeightChanged: (String) -> Unit,
    impedance: String,
    onImpedanceChanged: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(HuaweiDimensions.ContentPadding),
        verticalArrangement = Arrangement.spacedBy(HuaweiDimensions.ItemSpacing),
    ) {
        Text("Ручное тестовое измерение", style = MaterialTheme.typography.titleSmall)
        Text(
            "Проходит тот же путь распознавания профиля, что и измерение с весов.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        ResponsiveTestFields(
            weight = weight,
            onWeightChanged = onWeightChanged,
            impedance = impedance,
            onImpedanceChanged = onImpedanceChanged,
        )
        OutlinedButton(
            onClick = { callbacks.onManualTest(weight, impedance) },
            modifier = Modifier.fillMaxWidth().heightIn(min = HuaweiDimensions.TouchTarget),
        ) {
            Text("Отправить тест")
        }
        HorizontalDivider()
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
private fun ResponsiveTestFields(
    weight: String,
    onWeightChanged: (String) -> Unit,
    impedance: String,
    onImpedanceChanged: (String) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val compact = maxWidth < 360.dp
        if (compact) {
            Column(verticalArrangement = Arrangement.spacedBy(HuaweiDimensions.CompactItemSpacing)) {
                TestField(
                    weight,
                    onWeightChanged,
                    "Вес, кг",
                    KeyboardType.Decimal,
                    Modifier.fillMaxWidth().testTag(SettingsScreenTestTags.ManualTestWeight),
                )
                TestField(
                    impedance,
                    onImpedanceChanged,
                    "Импеданс, Ом",
                    KeyboardType.Number,
                    Modifier.fillMaxWidth().testTag(SettingsScreenTestTags.ManualTestImpedance),
                )
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(HuaweiDimensions.CompactItemSpacing)) {
                TestField(
                    weight,
                    onWeightChanged,
                    "Вес, кг",
                    KeyboardType.Decimal,
                    Modifier.weight(1f).testTag(SettingsScreenTestTags.ManualTestWeight),
                )
                TestField(
                    impedance,
                    onImpedanceChanged,
                    "Импеданс, Ом",
                    KeyboardType.Number,
                    Modifier.weight(1f).testTag(SettingsScreenTestTags.ManualTestImpedance),
                )
            }
        }
    }
}

@Composable
private fun TestField(
    value: String,
    onValueChanged: (String) -> Unit,
    label: String,
    keyboardType: KeyboardType,
    modifier: Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChanged,
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        singleLine = true,
        modifier = modifier,
    )
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
