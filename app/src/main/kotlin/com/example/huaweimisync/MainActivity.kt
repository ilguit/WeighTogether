package com.example.huaweimisync

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.net.toUri
import androidx.health.connect.client.PermissionController
import com.example.huaweimisync.ble.BleSupport

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private val measurementsViewModel: MeasurementsViewModel by viewModels()
    private val chartsViewModel: ChartsViewModel by viewModels()

    private val bluetoothPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (grants.values.all { it }) viewModel.onBluetoothPermissionsReady()
        else viewModel.setMessage("Без разрешения Bluetooth автоматический приём невозможен")
        requestNotificationPermission()
    }

    private val notificationPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (grants.values.any { !it }) {
            viewModel.setMessage(
                "Уведомления запрещены: обычный фоновый приём работает, но режим повышенной надёжности может быть ограничен",
            )
        }
    }

    private val healthPermissions = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract(),
    ) { granted ->
        viewModel.onHealthConnectPermissionsChanged(
            granted.containsAll(viewModel.healthConnectPermissions),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HuaweiMiSyncApp(
                viewModel = viewModel,
                measurementsViewModel = measurementsViewModel,
                chartsViewModel = chartsViewModel,
                requestHealthConnectPermissions = {
                    if (viewModel.healthConnectAvailable) {
                        healthPermissions.launch(viewModel.healthConnectPermissions)
                    } else {
                        viewModel.setMessage("Health Connect недоступен на этом устройстве")
                    }
                },
                openBatterySettings = ::openBatterySettings,
                openApplicationSettings = ::openApplicationSettings,
            )
        }

        val missing = BleSupport.requiredBluetoothPermissions().filterNot { permission ->
            checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            viewModel.onBluetoothPermissionsReady()
            requestNotificationPermission()
        }
        else bluetoothPermissions.launch(missing.toTypedArray())
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshHealthConnectQueue()
    }

    private fun requestNotificationPermission() {
        val missing = BleSupport.requiredNotificationPermissions().filterNot { permission ->
            checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) notificationPermissions.launch(missing.toTypedArray())
    }

    private fun openBatterySettings() {
        runCatching {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }.onFailure { openApplicationSettings() }
    }

    private fun openApplicationSettings() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri()),
        )
    }
}
