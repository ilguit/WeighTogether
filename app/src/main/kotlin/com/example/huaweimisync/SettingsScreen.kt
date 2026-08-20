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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.huaweimisync.core.Sex
import com.example.huaweimisync.core.UserProfile
import com.example.huaweimisync.ui.components.HuaweiFilterButton
import com.example.huaweimisync.ui.accounts.AccountManagementCallbacks
import com.example.huaweimisync.ui.accounts.AccountManagementSection
import com.example.huaweimisync.ui.accounts.WeightRecognitionSetting
import com.example.huaweimisync.ui.components.HuaweiIconButton
import com.example.huaweimisync.ui.components.HuaweiSectionTitle
import com.example.huaweimisync.ui.components.HuaweiSettingRow
import com.example.huaweimisync.ui.components.HuaweiSurface
import com.example.huaweimisync.ui.icons.HuaweiIcons
import com.example.huaweimisync.ui.theme.HuaweiDimensions
import java.time.format.DateTimeFormatter
import java.util.Locale

internal data class SettingsCallbacks(
    val onOpenProfile: () -> Unit = {},
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
)

internal enum class AdditionalExpansion {
    Collapsed,
    Expanded,
    ;

    fun toggled(): AdditionalExpansion = when (this) {
        Collapsed -> Expanded
        Expanded -> Collapsed
    }
}

internal data class IntegrationPresentation(
    val supportingText: String,
    val actionLabel: String? = null,
    val actionEnabled: Boolean = true,
    val actionOpensManagement: Boolean = false,
    val actionRetriesCheck: Boolean = false,
)

internal object SettingsScreenTestTags {
    const val ProfileRow = "settings-profile-row"
    const val AdditionalToggle = "settings-additional-toggle"
    const val AdditionalContent = "settings-additional-content"
    const val ProfileEditor = "profile-editor"
    const val ProfileEditorError = "profile-editor-error"
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

internal fun healthConnectPresentation(
    state: HealthConnectPermissionsUiState,
): IntegrationPresentation = when (state.availability) {
    HealthConnectAvailability.CHECKING -> IntegrationPresentation(
        supportingText = "Проверка разрешений…",
        actionLabel = "Подключить",
        actionEnabled = false,
    )
    HealthConnectAvailability.UNAVAILABLE -> IntegrationPresentation(
        supportingText = "Недоступно на этом устройстве",
    )
    HealthConnectAvailability.CHECK_FAILED -> IntegrationPresentation(
        supportingText = "Не удалось проверить разрешения",
        actionLabel = "Подключить",
    )
    HealthConnectAvailability.AVAILABLE -> if (state.isConnected) {
        IntegrationPresentation(
            supportingText = "Подключено · все разрешения выданы",
            actionLabel = "Отключить",
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

internal fun huaweiIntegrationPresentation(
    state: HuaweiIntegrationUiState,
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
    HuaweiIntegrationStatus.AUTHORIZED -> IntegrationPresentation(
        supportingText = "Подключено",
    )
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
    modifier: Modifier = Modifier,
) {
    var additionalExpansion by rememberSaveable { mutableStateOf(AdditionalExpansion.Collapsed) }

    Box(
        modifier = modifier.fillMaxSize().padding(contentPadding),
        contentAlignment = Alignment.TopCenter,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxHeight().fillMaxWidth().widthIn(max = 720.dp),
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
                )
            }
            item {
                WeightRecognitionSetting(
                    state = state.weightDeltaEditor,
                    onStateChanged = callbacks.onWeightDeltaStateChanged,
                    onSave = callbacks.onWeightDeltaSave,
                )
            }
            item { SettingsIntegrationsSection(state, callbacks) }
            item { SettingsScaleSection(state, callbacks.onManualScan) }
            item {
                SettingsAdditionalSection(
                    state = state,
                    expansion = additionalExpansion,
                    onToggle = { additionalExpansion = additionalExpansion.toggled() },
                    callbacks = callbacks,
                )
            }
        }
    }
}

@Composable
private fun SettingsIntegrationsSection(
    state: MainUiState,
    callbacks: SettingsCallbacks,
) {
    val primary = state.primaryAccount
    val healthConnectCapabilities = state.healthConnectCapabilities
    val primaryStatus = when {
        primary == null -> "Основной аккаунт не выбран"
        !state.canUseExternalIntegrations -> "${primary.displayName} · заполните профиль"
        else -> "Основной: ${primary.displayName}"
    }
    val healthConnect = healthConnectPresentation(state.healthConnect).forPrimaryAccount(
        primaryStatus,
        healthConnectCapabilities.selectedAccountSyncEligible,
    )
    val huawei = huaweiIntegrationPresentation(state.huawei).forPrimaryAccount(
        primaryStatus,
        state.canUseExternalIntegrations,
    )
    SettingsSection(title = "Интеграции") {
        HuaweiSurface(contentPadding = PaddingValues(0.dp)) {
            Column {
                HuaweiSettingRow(
                    icon = HuaweiIcons.Health,
                    title = "Health Connect",
                    supportingText = healthConnect.supportingText,
                    onClick = callbacks.onHealthConnectAccessManagement.takeIf {
                        healthConnectCapabilities.systemManagementAvailable
                    },
                ) {
                    healthConnect.actionLabel?.let { label ->
                        TextButton(
                            onClick = if (healthConnect.actionOpensManagement) {
                                callbacks.onHealthConnectAccessManagement
                            } else {
                                callbacks.onHealthConnectAuthorization
                            },
                            enabled = healthConnect.actionEnabled,
                        ) { Text(label) }
                    }
                }
                SettingsDivider()
                HuaweiSettingRow(
                    icon = HuaweiIcons.Link,
                    title = "Huawei Health",
                    supportingText = huawei.supportingText,
                ) {
                    huawei.actionLabel?.let { label ->
                        TextButton(
                            onClick = if (huawei.actionRetriesCheck) {
                                callbacks.onHuaweiPermissionRefresh
                            } else {
                                callbacks.onHuaweiAuthorization
                            },
                            enabled = huawei.actionEnabled,
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

@Composable
private fun SettingsScaleSection(
    state: MainUiState,
    onManualScan: () -> Unit,
) {
    val selectedScale = if (state.settings.scaleAddress == null) {
        "Весы ещё не выбраны"
    } else {
        "${state.settings.scaleName ?: "XMTZC05HM"} · ${state.settings.scaleAddress}"
    }
    SettingsSection(title = "Весы") {
        HuaweiSurface(contentPadding = PaddingValues(0.dp)) {
            HuaweiSettingRow(
                icon = HuaweiIcons.Bluetooth,
                title = "Mi Body Composition Scale 2",
                supportingText = selectedScale,
            ) {
                TextButton(onClick = onManualScan) {
                    Text(if (state.scanning) "Стоп" else "Найти")
                }
            }
        }
    }
}

@Composable
private fun SettingsAdditionalSection(
    state: MainUiState,
    expansion: AdditionalExpansion,
    onToggle: () -> Unit,
    callbacks: SettingsCallbacks,
) {
    val expanded = expansion == AdditionalExpansion.Expanded
    SettingsSection(title = "Дополнительно") {
        HuaweiSurface(contentPadding = PaddingValues(0.dp)) {
            Column {
                HuaweiSettingRow(
                    icon = HuaweiIcons.Lab,
                    title = "Тестирование и фон",
                    supportingText = "Тестовое измерение и надёжность BLE",
                    modifier = Modifier
                        .testTag(SettingsScreenTestTags.AdditionalToggle)
                        .semantics {
                            role = Role.Button
                            stateDescription = if (expanded) "Развёрнуто" else "Свёрнуто"
                        },
                    onClick = onToggle,
                ) {
                    HuaweiIconButton(
                        icon = HuaweiIcons.ChevronRight,
                        contentDescription = if (expanded) {
                            "Свернуть дополнительные настройки"
                        } else {
                            "Развернуть дополнительные настройки"
                        },
                        onClick = onToggle,
                        modifier = Modifier.graphicsLayer { rotationZ = if (expanded) 90f else 0f },
                    )
                }
                if (expanded) {
                    SettingsDivider()
                    AdditionalContent(
                        state = state,
                        callbacks = callbacks,
                        modifier = Modifier.testTag(SettingsScreenTestTags.AdditionalContent),
                    )
                }
            }
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
    var weight by rememberSaveable { mutableStateOf("70.0") }
    var impedance by rememberSaveable { mutableStateOf("500") }

    Column(
        modifier = modifier.fillMaxWidth().padding(HuaweiDimensions.ContentPadding),
        verticalArrangement = Arrangement.spacedBy(HuaweiDimensions.ItemSpacing),
    ) {
        Text("Ручное тестовое измерение", style = MaterialTheme.typography.titleSmall)
        Text(
            "Проходит тот же путь распознавания аккаунта, что и измерение с весов.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        ResponsiveTestFields(
            weight = weight,
            onWeightChanged = { weight = it },
            impedance = impedance,
            onImpedanceChanged = { impedance = it },
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
                    Modifier.fillMaxWidth(),
                )
                TestField(
                    impedance,
                    onImpedanceChanged,
                    "Импеданс, Ом",
                    KeyboardType.Number,
                    Modifier.fillMaxWidth(),
                )
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(HuaweiDimensions.CompactItemSpacing)) {
                TestField(
                    weight,
                    onWeightChanged,
                    "Вес, кг",
                    KeyboardType.Decimal,
                    Modifier.weight(1f),
                )
                TestField(
                    impedance,
                    onImpedanceChanged,
                    "Импеданс, Ом",
                    KeyboardType.Number,
                    Modifier.weight(1f),
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
private fun SettingsSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(HuaweiDimensions.CompactItemSpacing)) {
        HuaweiSectionTitle(title)
        content()
    }
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 68.dp),
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
