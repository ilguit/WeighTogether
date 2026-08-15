package com.example.huaweimisync

import android.content.Intent
import android.health.connect.HealthConnectManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.net.toUri
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import com.example.huaweimisync.ble.BleSupport
import com.example.huaweimisync.worker.PendingMeasurementNotificationHelper

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
        val granted = grants.values.all { it }
        viewModel.setNotificationPermissionGranted(granted)
        if (!granted) {
            viewModel.setMessage(
                "Уведомления запрещены: ожидающие измерения будут показаны в приложении",
            )
        }
    }

    private val healthPermissions = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract(),
    ) { granted ->
        viewModel.onHealthConnectPermissionsChanged(granted)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val systemBarColor = getColor(R.color.huawei_primary)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(systemBarColor),
            navigationBarStyle = SystemBarStyle.dark(systemBarColor),
        )
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
                openHealthConnectAccessManagement = ::openHealthConnectAccessManagement,
                openBatterySettings = ::openBatterySettings,
                openApplicationSettings = ::openApplicationSettings,
            )
        }
        handleIntent(intent)

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
        viewModel.onForeground()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun requestNotificationPermission() {
        val missing = BleSupport.requiredNotificationPermissions().filterNot { permission ->
            checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            notificationPermissions.launch(missing.toTypedArray())
        } else {
            viewModel.setNotificationPermissionGranted(true)
        }
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == PendingMeasurementNotificationHelper.ACTION_RESOLVE_PENDING) {
            viewModel.openResolver()
            intent.action = null
        }
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

    /** Opens system-owned permission management; the app never revokes HC permissions itself. */
    private fun openHealthConnectAccessManagement() {
        val intents = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                add(
                    Intent(HealthConnectManager.ACTION_MANAGE_HEALTH_PERMISSIONS).apply {
                        putExtra(Intent.EXTRA_PACKAGE_NAME, packageName)
                    },
                )
            }
            add(Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS))
            add(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    "package:$packageName".toUri(),
                ),
            )
        }
        val opened = intents.any { intent ->
            runCatching {
                startActivity(intent)
                true
            }.getOrDefault(false)
        }
        if (!opened) viewModel.setMessage("Не удалось открыть управление доступом Health Connect")
    }
}
