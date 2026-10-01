package com.palixander.weightogether

import android.content.Context
import android.content.Intent
import androidx.health.connect.client.HealthConnectClient

/**
 * Returns the SDK-owned Health Connect management destination only when Android can handle it.
 *
 * The generic boundary keeps the availability contract covered by local unit tests without
 * requiring Android framework objects.
 */
internal fun <T> resolveAvailableHealthConnectManagementIntent(
    createIntent: () -> T?,
    canResolve: (T) -> Boolean,
): T? = try {
    createIntent()?.takeIf(canResolve)
} catch (_: RuntimeException) {
    null
}

/** Safely launches a previously resolved destination. */
internal fun <T> launchHealthConnectManagement(
    intent: T?,
    launch: (T) -> Unit,
): Boolean {
    if (intent == null) return false
    return try {
        launch(intent)
        true
    } catch (_: RuntimeException) {
        false
    }
}

/**
 * Creates the canonical management intent supplied by Health Connect and verifies that a system
 * Activity is available before exposing the destination to callers.
 */
internal fun Context.healthConnectManagementIntent(): Intent? =
    resolveAvailableHealthConnectManagementIntent(
        createIntent = { HealthConnectClient.getHealthConnectManageDataIntent(this) },
        canResolve = { intent -> intent.resolveActivity(packageManager) != null },
    )
