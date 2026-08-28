package com.example.huaweimisync.worker

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.huaweimisync.MainActivity
import com.example.huaweimisync.R
import com.example.huaweimisync.data.PendingDecisionNotifier
import com.example.huaweimisync.domain.PendingMeasurementId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface PendingDecisionFallback {
    data object Hidden : PendingDecisionFallback
    data class ShowOnForeground(val pendingCount: Int) : PendingDecisionFallback
}

interface PendingNotificationDismissalStore {
    fun readDismissedIds(): Set<PendingMeasurementId>
    fun writeDismissedIds(ids: Set<PendingMeasurementId>)
}

class SharedPreferencesPendingNotificationDismissalStore(
    private val preferences: SharedPreferences,
) : PendingNotificationDismissalStore {
    override fun readDismissedIds(): Set<PendingMeasurementId> =
        preferences.getStringSet(KEY_DISMISSED_IDS, emptySet()).orEmpty()
            .mapTo(linkedSetOf(), ::PendingMeasurementId)

    override fun writeDismissedIds(ids: Set<PendingMeasurementId>) {
        preferences.edit().putStringSet(KEY_DISMISSED_IDS, ids.mapTo(linkedSetOf()) { it.value }).apply()
    }

    private companion object {
        const val KEY_DISMISSED_IDS = "dismissed_pending_measurement_ids"
    }
}

/** Pure state machine used by the Android notification transport and JVM tests. */
class PendingDecisionPresentationCoordinator(
    private val notificationsAllowed: () -> Boolean,
    private val postNotification: (Int) -> Unit,
    private val cancelNotification: () -> Unit,
    private val dismissalStore: PendingNotificationDismissalStore? = null,
) : PendingDecisionNotifier {
    private val mutableFallback = MutableStateFlow<PendingDecisionFallback>(
        PendingDecisionFallback.Hidden,
    )
    val notificationDeniedFallback: StateFlow<PendingDecisionFallback> =
        mutableFallback.asStateFlow()

    private var currentPendingIds = emptySet<PendingMeasurementId>()

    override fun updatePendingMeasurements(pendingIds: Set<PendingMeasurementId>) {
        currentPendingIds = pendingIds.toSet()
        val dismissedIds = dismissalStore?.readDismissedIds().orEmpty()
        val retainedDismissedIds = dismissedIds.intersect(pendingIds)
        if (retainedDismissedIds != dismissedIds) {
            dismissalStore?.writeDismissedIds(retainedDismissedIds)
        }
        val count = pendingIds.size
        if (pendingIds.isEmpty()) {
            cancelSafely()
            mutableFallback.value = PendingDecisionFallback.Hidden
        } else if (pendingIds == retainedDismissedIds) {
            cancelSafely()
            mutableFallback.value = PendingDecisionFallback.Hidden
        } else if (postSafely(count)) {
            mutableFallback.value = PendingDecisionFallback.Hidden
        } else {
            cancelSafely()
            mutableFallback.value = PendingDecisionFallback.ShowOnForeground(count)
        }
    }

    fun recordCurrentNotificationDismissed() {
        if (currentPendingIds.isNotEmpty()) dismissalStore?.writeDismissedIds(currentPendingIds)
        cancelSafely()
        mutableFallback.value = PendingDecisionFallback.Hidden
    }

    private fun postSafely(count: Int): Boolean = try {
        if (notificationsAllowed()) {
            postNotification(count)
            true
        } else {
            false
        }
    } catch (_: RuntimeException) {
        false
    }

    private fun cancelSafely() {
        try {
            cancelNotification()
        } catch (_: RuntimeException) {
            // The durable queue is authoritative; foreground fallback must remain available.
        }
    }
}

/**
 * Updating notification for unresolved durable measurements. Its content intent only defines the
 * resolver navigation contract; the integration stage consumes [ACTION_RESOLVE_PENDING].
 */
class PendingMeasurementNotificationHelper(
    private val context: Context,
) : PendingDecisionNotifier {
    private val notifications = NotificationManagerCompat.from(context)
    private val presentation = PendingDecisionPresentationCoordinator(
        notificationsAllowed = ::areNotificationsAllowed,
        postNotification = ::post,
        cancelNotification = { notifications.cancel(NOTIFICATION_ID) },
        dismissalStore = SharedPreferencesPendingNotificationDismissalStore(
            context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
        ),
    )

    val notificationDeniedFallback: StateFlow<PendingDecisionFallback>
        get() = presentation.notificationDeniedFallback

    override fun updatePendingMeasurements(pendingIds: Set<PendingMeasurementId>) {
        presentation.updatePendingMeasurements(pendingIds)
    }

    fun recordCurrentNotificationDismissed() {
        presentation.recordCurrentNotificationDismissed()
    }

    fun areNotificationsAllowed(): Boolean {
        val runtimePermissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        return runtimePermissionGranted && notifications.areNotificationsEnabled()
    }

    private fun post(count: Int) {
        // Re-check at the transport boundary: permission can be revoked after the coordinator's
        // capability check. Throwing keeps that race on the existing foreground-fallback path.
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            throw SecurityException("POST_NOTIFICATIONS was revoked before notification delivery")
        }
        createChannel()
        val resolverIntent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_RESOLVE_PENDING
            putExtra(EXTRA_PENDING_COUNT, count)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            RESOLVER_REQUEST_CODE,
            resolverIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = if (count == 1) {
            "Ожидает назначения аккаунта: 1 измерение"
        } else {
            "Ожидают назначения аккаунта: $count измерений"
        }
        notifications.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(context.getString(R.string.app_name))
                .setContentText(text)
                .setContentIntent(contentIntent)
                // Opening the resolver is navigation, not resolution. Only a durable queue change
                // may remove this ongoing entry via an empty pending-ID snapshot.
                .setAutoCancel(false)
                .setOnlyAlertOnce(true)
                .setNumber(count)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build(),
        )
    }

    private fun createChannel() {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Нераспознанные измерения",
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
    }

    companion object {
        const val ACTION_RESOLVE_PENDING =
            "com.example.huaweimisync.action.RESOLVE_PENDING_MEASUREMENT"
        const val EXTRA_PENDING_COUNT = "pending_measurement_count"
        private const val CHANNEL_ID = "pending_measurement_routing"
        private const val NOTIFICATION_ID = 183
        private const val RESOLVER_REQUEST_CODE = 183
        private const val PREFERENCES_NAME = "pending_measurement_notifications"
    }
}
