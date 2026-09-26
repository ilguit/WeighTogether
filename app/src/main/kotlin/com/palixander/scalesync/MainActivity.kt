package com.palixander.scalesync

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.health.connect.client.PermissionController
import com.palixander.scalesync.ble.BleSupport
import com.palixander.scalesync.backup.BackupImportMode
import com.palixander.scalesync.worker.PendingMeasurementNotificationHelper
import java.time.LocalDate

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private val measurementsViewModel: MeasurementsViewModel by viewModels()
    private val chartsViewModel: ChartsViewModel by viewModels()
    private var healthConnectSystemManagementAvailable by mutableStateOf(false)
    private var requestedImportMode = BackupImportMode.MERGE

    private val createBackup = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        viewModel.exportBackup(uri)
    }
    private val openBackup = registerForActivityResult(ActivityResultContracts.OpenDocument()) {
        viewModel.previewBackup(it, requestedImportMode)
    }

    private val bluetoothPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (grants.values.all { it }) viewModel.onBluetoothPermissionsReady()
        else viewModel.setMessage(getString(R.string.message_bluetooth_permission_denied))
        requestNotificationPermission()
    }

    private val notificationPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        val granted = grants.values.all { it }
        viewModel.setNotificationPermissionGranted(granted)
        if (!granted) {
            viewModel.setMessage(
                getString(R.string.message_notification_permission_denied),
            )
        }
    }

    private val healthPermissions = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract(),
    ) { granted ->
        viewModel.onHealthConnectPermissionsChanged(granted)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        refreshHealthConnectSystemManagementAvailability()
        val systemBarColor = getColor(R.color.huawei_primary)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(systemBarColor),
            navigationBarStyle = SystemBarStyle.dark(systemBarColor),
        )
        setContent {
            ScaleSyncApp(
                viewModel = viewModel,
                measurementsViewModel = measurementsViewModel,
                chartsViewModel = chartsViewModel,
                healthConnectSystemManagementAvailable =
                    healthConnectSystemManagementAvailable,
                requestHealthConnectPermissions = {
                    if (viewModel.healthConnectAvailable) {
                        healthPermissions.launch(viewModel.healthConnectPermissions)
                    } else {
                        viewModel.setMessage(getString(R.string.message_health_connect_unavailable))
                    }
                },
                openHealthConnectAccessManagement = ::openHealthConnectAccessManagement,
                openBatterySettings = ::openBatterySettings,
                openApplicationSettings = ::openApplicationSettings,
                createBackup = { createBackup.launch(defaultBackupFileName()) },
                openBackup = { mode ->
                    requestedImportMode = mode
                    openBackup.launch(arrayOf("application/json"))
                },
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
        refreshHealthConnectSystemManagementAvailability()
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
        val opened = launchHealthConnectManagement(
            intent = healthConnectManagementIntent(),
            launch = ::startActivity,
        )
        healthConnectSystemManagementAvailable = opened
        if (!opened) viewModel.setMessage(getString(R.string.message_health_connect_management_failed))
    }

    private fun refreshHealthConnectSystemManagementAvailability() {
        healthConnectSystemManagementAvailable = healthConnectManagementIntent() != null
    }
}

internal fun defaultBackupFileName(date: LocalDate = LocalDate.now()): String =
    "scalesync-backup-$date.json"
