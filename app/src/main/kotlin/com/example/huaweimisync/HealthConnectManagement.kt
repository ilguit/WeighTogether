package com.example.huaweimisync

import android.content.Intent
import android.health.connect.HealthConnectManager
import android.os.Build
import android.provider.Settings
import androidx.core.net.toUri
import androidx.health.connect.client.HealthConnectClient

internal enum class HealthConnectManagementTarget {
    APP_PERMISSION_MANAGEMENT,
    GENERAL_HEALTH_CONNECT_SETTINGS,
    APPLICATION_SETTINGS,
}

/**
 * Ordered system-owned destinations for managing this app's Health Connect access.
 *
 * Android 14 introduced app-specific permission management. The general Health Connect screen is
 * retained as a compatibility fallback before the app settings screen because device vendors can
 * ship Health Connect independently from the platform implementation.
 */
internal fun healthConnectManagementTargets(sdkInt: Int): List<HealthConnectManagementTarget> =
    buildList {
        if (sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            add(HealthConnectManagementTarget.APP_PERMISSION_MANAGEMENT)
        }
        add(HealthConnectManagementTarget.GENERAL_HEALTH_CONNECT_SETTINGS)
        add(HealthConnectManagementTarget.APPLICATION_SETTINGS)
    }

internal fun HealthConnectManagementTarget.toIntent(packageName: String): Intent = when (this) {
    HealthConnectManagementTarget.APP_PERMISSION_MANAGEMENT ->
        Intent(HealthConnectManager.ACTION_MANAGE_HEALTH_PERMISSIONS).apply {
            putExtra(Intent.EXTRA_PACKAGE_NAME, packageName)
        }
    HealthConnectManagementTarget.GENERAL_HEALTH_CONNECT_SETTINGS ->
        Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)
    HealthConnectManagementTarget.APPLICATION_SETTINGS ->
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri())
}

/** Tries the next destination only when the current one has no matching Activity. */
internal fun <T> launchFirstAvailableActivity(
    targets: List<T>,
    launch: (T) -> Unit,
    isActivityNotFound: (RuntimeException) -> Boolean,
): Boolean {
    targets.forEach { target ->
        try {
            launch(target)
            return true
        } catch (error: RuntimeException) {
            if (!isActivityNotFound(error)) throw error
        }
    }
    return false
}
