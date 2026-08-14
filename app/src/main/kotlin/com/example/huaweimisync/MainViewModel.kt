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
import com.example.huaweimisync.core.UserProfile
import com.example.huaweimisync.data.AppSettings
import com.example.huaweimisync.data.MeasurementEntity
import com.example.huaweimisync.data.StoreResult
import com.example.huaweimisync.sync.SyncResult
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class MainUiState(
    val settings: AppSettings = AppSettings(),
    val measurements: List<MeasurementEntity> = emptyList(),
    val message: String? = null,
    val scanning: Boolean = false,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as MiSyncApplication).container
    private val scanner = ManualScaleScanner(application)
    private val message = MutableStateFlow<String?>(null)
    private val scanning = MutableStateFlow(false)

    val uiState: StateFlow<MainUiState> = combine(
        container.profileStore.settings,
        container.repository.observeRecent(),
        message,
        scanning,
    ) { settings, measurements, currentMessage, isScanning ->
        MainUiState(settings, measurements, currentMessage, isScanning)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MainUiState())

    val huaweiConfigured: Boolean get() = container.huaweiHealth.isConfigured
    val huaweiAvailableInBuild: Boolean get() = container.huaweiHealth.isAvailableInBuild
    val healthConnectAvailable: Boolean get() = container.healthConnect.isAvailable()
    val healthConnectPermissions: Set<String> get() = container.healthConnect.permissions

    fun saveProfile(height: String, birthDate: String, sex: Sex) {
        val profile = runCatching {
            UserProfile(height.replace(',', '.').toDouble(), LocalDate.parse(birthDate), sex)
        }.getOrElse {
            message.value = "Проверьте рост и дату рождения (ГГГГ-ММ-ДД)"
            return
        }
        val today = LocalDate.now()
        if (profile.birthDate.isAfter(today.minusYears(10)) ||
            profile.birthDate.isBefore(today.minusYears(100))
        ) {
            message.value = "Возраст для расчёта должен быть от 10 до 100 лет"
            return
        }
        container.profileStore.saveProfile(profile)
        message.value = "Профиль сохранён"
    }

    fun registerBackgroundScan() {
        if (container.profileStore.settings.value.scaleAddress == null) return
        message.value = BackgroundScanRegistrar.register(getApplication()).fold(
            onSuccess = { "Фоновое BLE-сканирование включено" },
            onFailure = { it.message ?: "Не удалось включить сканирование" },
        )
    }

    fun toggleManualScan() {
        if (scanning.value) {
            scanner.stop()
            scanning.value = false
            restoreAutomaticScanning()
            message.value = "Ручное сканирование остановлено"
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
                message.value = error
            },
        )
        started.onSuccess {
            scanning.value = true
            message.value = "Встаньте на весы и дождитесь финального измерения"
        }.onFailure {
            restoreAutomaticScanning()
            message.value = it.message
        }
    }

    fun setReliabilityMode(enabled: Boolean) {
        if (!BleSupport.hasScanPermission(getApplication())) {
            message.value = "Сначала разрешите Bluetooth-сканирование"
            return
        }
        if (enabled && container.profileStore.settings.value.scaleAddress == null) {
            message.value = "Сначала выберите весы"
            return
        }
        container.profileStore.setReliabilityMode(enabled)
        ReliabilityScanService.setEnabled(getApplication(), enabled)
        message.value = if (enabled) "Режим повышенной надёжности включён" else "Режим выключен"
    }

    fun authorizeHuawei() = viewModelScope.launch {
        message.value = when (val result = container.huaweiHealth.authorize()) {
            SyncResult.Success -> "Huawei Health: разрешение получено"
            is SyncResult.Disabled -> result.message
            is SyncResult.Blocked -> result.message
            is SyncResult.Retryable -> result.message
        }
        if (container.huaweiHealth.isConfigured) container.repository.retryPendingHuawei()
    }

    fun sendManualTest(weight: String, impedance: String) = viewModelScope.launch {
        val weightKg = weight.replace(',', '.').toDoubleOrNull()
        val impedanceOhm = impedance.toIntOrNull()
        if (weightKg == null || impedanceOhm == null ||
            weightKg !in 10.0..300.0 || impedanceOhm !in 80..3_000
        ) {
            message.value = "Проверьте вес и импеданс"
            return@launch
        }
        message.value = when (container.repository.insertManual(weightKg, impedanceOhm)) {
            is StoreResult.Inserted -> "Тестовая запись создана и поставлена в очередь"
            StoreResult.Duplicate -> "Такая тестовая запись уже существует"
            StoreResult.ProfileMissing -> "Сначала сохраните профиль"
        }
    }

    fun retry(id: String) = viewModelScope.launch {
        container.repository.retry(id)
        message.value = "Повторная отправка поставлена в очередь"
    }

    fun setMessage(text: String) {
        message.value = text
    }

    fun onBluetoothPermissionsReady() {
        registerBackgroundScan()
        if (container.profileStore.settings.value.reliabilityMode) {
            runCatching { ReliabilityScanService.setEnabled(getApplication(), true) }
        }
    }

    fun onHealthConnectPermissionsChanged(allGranted: Boolean) = viewModelScope.launch {
        if (allGranted) {
            container.repository.retryPendingHealthConnect()
            message.value = "Health Connect: разрешения выданы, очередь перезапущена"
        } else {
            message.value = "Health Connect: разрешены не все показатели"
        }
    }

    fun refreshHealthConnectQueue() = viewModelScope.launch {
        val allGranted = runCatching { container.healthConnect.hasPermissions() }.getOrDefault(false)
        if (allGranted) container.repository.retryPendingHealthConnect()
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
        message.value = "Весы выбраны: ${name ?: address}. Измерение принято"
    }

    private fun restoreAutomaticScanning() {
        if (container.profileStore.settings.value.scaleAddress == null) return
        BackgroundScanRegistrar.register(getApplication())
        if (container.profileStore.settings.value.reliabilityMode) {
            runCatching { ReliabilityScanService.setEnabled(getApplication(), true) }
        }
    }
}
