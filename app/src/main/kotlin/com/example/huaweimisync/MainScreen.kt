package com.example.huaweimisync

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.huaweimisync.charts.ChartsScreen
import com.example.huaweimisync.core.Sex
import com.example.huaweimisync.measurements.MeasurementsScreen
import com.example.huaweimisync.measurements.MeasurementsUiEvent

internal enum class AppSection(
    val title: String,
    val icon: String,
) {
    MEASUREMENTS("Измерения", "≡"),
    CHARTS("Графики", "⌁"),
    SETTINGS("Настройки", "⚙"),
}

internal val defaultAppSection = AppSection.MEASUREMENTS

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HuaweiMiSyncApp(
    viewModel: MainViewModel,
    measurementsViewModel: MeasurementsViewModel,
    chartsViewModel: ChartsViewModel,
    requestHealthConnectPermissions: () -> Unit,
    openBatterySettings: () -> Unit,
    openApplicationSettings: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val measurementsState by measurementsViewModel.uiState.collectAsStateWithLifecycle()
    val chartsState by chartsViewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var currentSection by rememberSaveable { mutableStateOf(defaultAppSection) }
    val editorOpen = currentSection == AppSection.MEASUREMENTS && measurementsState.editor != null

    LaunchedEffect(measurementsViewModel) {
        measurementsViewModel.events.collect { event ->
            when (event) {
                is MeasurementsUiEvent.ShowSnackbar -> snackbarHostState.showSnackbar(event.message)
            }
        }
    }
    BackHandler(enabled = editorOpen) {
        measurementsViewModel.callbacks.onEditorDismissed()
    }

    MaterialTheme {
        Scaffold(
            topBar = {
                if (!editorOpen) {
                    TopAppBar(title = { Text(currentSection.title) })
                }
            },
            bottomBar = {
                if (!editorOpen) {
                    NavigationBar {
                        AppSection.entries.forEach { section ->
                            NavigationBarItem(
                                selected = currentSection == section,
                                onClick = { currentSection = section },
                                icon = { Text(section.icon) },
                                label = { Text(section.title) },
                            )
                        }
                    }
                }
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { padding ->
            when (currentSection) {
                AppSection.SETTINGS -> SettingsScreen(
                    state = state,
                    huaweiConfigured = viewModel.huaweiConfigured,
                    huaweiAvailableInBuild = viewModel.huaweiAvailableInBuild,
                    healthConnectAvailable = viewModel.healthConnectAvailable,
                    onSaveProfile = viewModel::saveProfile,
                    onHuaweiAuthorization = viewModel::authorizeHuawei,
                    onHealthConnectAuthorization = requestHealthConnectPermissions,
                    onManualTest = viewModel::sendManualTest,
                    onManualScan = viewModel::toggleManualScan,
                    onReliabilityMode = viewModel::setReliabilityMode,
                    openBatterySettings = openBatterySettings,
                    openApplicationSettings = openApplicationSettings,
                    modifier = Modifier.padding(padding),
                )

                AppSection.MEASUREMENTS -> MeasurementsScreen(
                    state = measurementsState,
                    callbacks = measurementsViewModel.callbacks,
                    modifier = Modifier.padding(padding),
                )

                AppSection.CHARTS -> ChartsScreen(
                    state = chartsState,
                    onDateRangeChange = chartsViewModel::setDateRange,
                    onMetricSelectionChange = chartsViewModel::setMetricSelected,
                    onSelectAll = chartsViewModel::selectAll,
                    onClearSelection = chartsViewModel::clearSelection,
                    modifier = Modifier.padding(padding),
                )
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    state: MainUiState,
    huaweiConfigured: Boolean,
    huaweiAvailableInBuild: Boolean,
    healthConnectAvailable: Boolean,
    onSaveProfile: (String, String, Sex) -> Unit,
    onHuaweiAuthorization: () -> Unit,
    onHealthConnectAuthorization: () -> Unit,
    onManualTest: (String, String) -> Unit,
    onManualScan: () -> Unit,
    onReliabilityMode: (Boolean) -> Unit,
    openBatterySettings: () -> Unit,
    openApplicationSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Spacer(Modifier.height(1.dp)) }
        state.message?.let { message -> item { StatusCard(message) } }
        item { ProfileCard(state, onSaveProfile) }
        item {
            IntegrationCard(
                huaweiConfigured,
                huaweiAvailableInBuild,
                healthConnectAvailable,
                onHuaweiAuthorization,
                onHealthConnectAuthorization,
            )
        }
        item { ManualPrototypeCard(onManualTest) }
        item {
            BleCard(
                state,
                onManualScan,
                onReliabilityMode,
                openBatterySettings,
                openApplicationSettings,
            )
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun StatusCard(message: String) = Card(Modifier.fillMaxWidth()) {
    Text(message, Modifier.padding(14.dp), style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun ProfileCard(state: MainUiState, onSave: (String, String, Sex) -> Unit) {
    val current = state.settings.profile
    var height by remember(current) { mutableStateOf(current?.heightCm?.toString() ?: "175") }
    var birthDate by remember(current) { mutableStateOf(current?.birthDate?.toString() ?: "1990-01-01") }
    var sex by remember(current) { mutableStateOf(current?.sex ?: Sex.MALE) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Профиль расчёта", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = height,
                onValueChange = { height = it },
                label = { Text("Рост, см") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = birthDate,
                onValueChange = { birthDate = it },
                label = { Text("Дата рождения, ГГГГ-ММ-ДД") },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Sex.entries.forEach { option ->
                    OutlinedButton(onClick = { sex = option }) {
                        Text((if (option == Sex.MALE) "Мужской" else "Женский") + if (sex == option) " ✓" else "")
                    }
                }
            }
            Button(onClick = { onSave(height, birthDate, sex) }) { Text("Сохранить профиль") }
        }
    }
}

@Composable
private fun IntegrationCard(
    huaweiConfigured: Boolean,
    huaweiAvailableInBuild: Boolean,
    healthConnectAvailable: Boolean,
    onHuaweiAuthorization: () -> Unit,
    onHealthConnectAuthorization: () -> Unit,
) = Card(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Интеграции", style = MaterialTheme.typography.titleMedium)
        if (huaweiAvailableInBuild) {
            Text(if (huaweiConfigured) "Huawei appId настроен" else "Huawei: нужен enterprise appId и write-scope")
            Button(onClick = onHuaweiAuthorization) { Text("Разрешить Huawei Health") }
        } else {
            Text("Huawei direct отключён: расширенная запись недоступна индивидуальным разработчикам")
        }
        Text(if (healthConnectAvailable) "Health Connect доступен" else "Health Connect недоступен")
        OutlinedButton(onClick = onHealthConnectAuthorization) { Text("Разрешить запись в Health Connect") }
    }
}

@Composable
private fun ManualPrototypeCard(onSend: (String, String) -> Unit) {
    var weight by remember { mutableStateOf("70.0") }
    var impedance by remember { mutableStateOf("500") }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Ручное тестовое измерение", style = MaterialTheme.typography.titleMedium)
            Text("Создаёт полный состав тела без Bluetooth и отправляет его в Health Connect.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = weight,
                    onValueChange = { weight = it },
                    label = { Text("Вес, кг") },
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                OutlinedTextField(
                    value = impedance,
                    onValueChange = { impedance = it },
                    label = { Text("Импеданс") },
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
            Button(onClick = { onSend(weight, impedance) }) { Text("Отправить тест") }
        }
    }
}

@Composable
private fun BleCard(
    state: MainUiState,
    onManualScan: () -> Unit,
    onReliabilityMode: (Boolean) -> Unit,
    openBatterySettings: () -> Unit,
    openApplicationSettings: () -> Unit,
) = Card(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Весы XMTZC05HM", style = MaterialTheme.typography.titleMedium)
        Text("Выбранные весы: ${state.settings.scaleName ?: "не найдены"} ${state.settings.scaleAddress.orEmpty()}")
        Button(onClick = onManualScan) {
            Text(if (state.scanning) "Остановить поиск" else "Найти весы")
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Повышенная надёжность")
            Switch(
                checked = state.settings.reliabilityMode,
                onCheckedChange = onReliabilityMode,
            )
        }
        Text("На vivo разрешите автозапуск и фоновую работу для этого приложения. Для режима повышенной надёжности нужны уведомления.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = openBatterySettings) { Text("Батарея") }
            OutlinedButton(onClick = openApplicationSettings) { Text("Настройки приложения") }
        }
    }
}
