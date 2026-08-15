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
import com.example.huaweimisync.core.Sex
import com.example.huaweimisync.data.AppSettings
import com.example.huaweimisync.data.StoreResult
import com.example.huaweimisync.sync.SyncResult
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
    private val profileEditor = ProfileEditorController(
        saveProfile = container.profileStore::saveProfile,
        eventEmitter = eventEmitter,
    )

    val events = eventEmitter.events

    val uiState: StateFlow<MainUiState> = combine(
        container.profileStore.settings,
        scanning,
        healthConnect,
        profileEditor.state,
        huawei,
    ) { settings, isScanning, healthConnectState, profileEditorState, huaweiState ->
        MainUiState(
            settings = settings,
            scanning = isScanning,
            healthConnect = healthConnectState,
            profileEditor = profileEditorState,
            huawei = huaweiState,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        MainUiState(
            settings = container.profileStore.settings.value,
            healthConnect = initialHealthConnectState,
            huawei = initialHuaweiState,
        ),
    )

    val huaweiConfigured: Boolean get() = container.huaweiHealth.isConfigured
    val huaweiAvailableInBuild: Boolean get() = container.huaweiHealth.isAvailableInBuild
    val healthConnectAvailable: Boolean get() = container.healthConnect.isAvailable()
    val healthConnectPermissions: Set<String> get() = container.healthConnect.permissions

    init {
        refreshIntegrations()
    }

    fun openProfileEditor() {
        profileEditor.open(container.profileStore.settings.value.profile)
    }

    fun closeProfileEditor() {
        profileEditor.close()
    }

    fun updateProfileHeight(value: String) {
        profileEditor.updateHeight(value)
    }

    fun updateProfileBirthDate(value: String) {
        profileEditor.updateBirthDate(value)
    }

    fun updateProfileSex(value: Sex) {
        profileEditor.updateSex(value)
    }

    fun saveProfile() {
        profileEditor.save()
    }

    fun saveProfile(height: String, birthDate: String, sex: Sex) {
        if (!profileEditor.state.value.isOpen) openProfileEditor()
        profileEditor.updateHeight(height)
        profileEditor.updateBirthDate(birthDate)
        profileEditor.updateSex(sex)
        profileEditor.save()
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
        showMessage(when (container.repository.insertManual(weightKg, impedanceOhm)) {
            is StoreResult.Inserted -> "Тестовая запись создана и поставлена в очередь"
            StoreResult.Duplicate -> "Такая тестовая запись уже существует"
            StoreResult.ProfileMissing -> "Сначала сохраните профиль"
        })
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
