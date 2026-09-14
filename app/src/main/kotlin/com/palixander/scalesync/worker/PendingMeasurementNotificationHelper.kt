package com.palixander.scalesync.worker

import android.Manifest
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
import com.palixander.scalesync.MainActivity
import com.palixander.scalesync.NotificationChannelRegistry
import com.palixander.scalesync.R
import com.palixander.scalesync.data.PendingDecisionNotifier
import com.palixander.scalesync.domain.PendingMeasurementId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface PendingDecisionFallback {
    data object Hidden : PendingDecisionFallback
    data class ShowOnForeground(val pendingCount: Int) : PendingDecisionFallback
}

interface PendingNotificationDismissalStore {
    fun addDismissedIds(ids: Set<PendingMeasurementId>): Set<PendingMeasurementId>
    fun retainDismissedIds(pendingIds: Set<PendingMeasurementId>): Set<PendingMeasurementId>
}

class SharedPreferencesPendingNotificationDismissalStore(
    private val preferences: SharedPreferences,
) : PendingNotificationDismissalStore {
    override fun addDismissedIds(ids: Set<PendingMeasurementId>): Set<PendingMeasurementId> =
        synchronized(preferencesLock) {
            val updated = readDismissedIdsLocked() + ids
            writeDismissedIdsLocked(updated)
            updated
        }

    override fun retainDismissedIds(
        pendingIds: Set<PendingMeasurementId>,
    ): Set<PendingMeasurementId> = synchronized(preferencesLock) {
        val retained = readDismissedIdsLocked().intersect(pendingIds)
        writeDismissedIdsLocked(retained)
        retained
    }

    private fun readDismissedIdsLocked(): Set<PendingMeasurementId> =
        preferences.getStringSet(KEY_DISMISSED_IDS, emptySet()).orEmpty()
            .mapTo(linkedSetOf(), ::PendingMeasurementId)

    private fun writeDismissedIdsLocked(ids: Set<PendingMeasurementId>) {
        preferences.edit()
            .putStringSet(KEY_DISMISSED_IDS, ids.mapTo(linkedSetOf()) { it.value })
            .commit()
    }

    private companion object {
        const val KEY_DISMISSED_IDS = "dismissed_pending_measurement_ids"
        val preferencesLock = Any()
    }
}

/** Pure state machine used by the Android notification transport and JVM tests. */
class PendingDecisionPresentationCoordinator(
    private val notificationsAllowed: () -> Boolean,
    private val postNotification: (Set<PendingMeasurementId>) -> Unit,
    private val cancelNotification: () -> Unit,
    private val dismissalStore: PendingNotificationDismissalStore? = null,
) : PendingDecisionNotifier {
    private val mutableFallback = MutableStateFlow<PendingDecisionFallback>(
        PendingDecisionFallback.Hidden,
    )
    val notificationDeniedFallback: StateFlow<PendingDecisionFallback> =
        mutableFallback.asStateFlow()

    override fun updatePendingMeasurements(pendingIds: Set<PendingMeasurementId>) {
        val retainedDismissedIds = dismissalStore?.retainDismissedIds(pendingIds).orEmpty()
        val count = pendingIds.size
        if (pendingIds.isEmpty()) {
            cancelSafely()
            mutableFallback.value = PendingDecisionFallback.Hidden
        } else if (pendingIds == retainedDismissedIds) {
            cancelSafely()
            mutableFallback.value = PendingDecisionFallback.Hidden
        } else if (postSafely(pendingIds)) {
            mutableFallback.value = PendingDecisionFallback.Hidden
        } else {
            cancelSafely()
            mutableFallback.value = PendingDecisionFallback.ShowOnForeground(count)
        }
    }

    fun recordNotificationDismissed(pendingIds: Set<PendingMeasurementId>) {
        if (pendingIds.isNotEmpty()) dismissalStore?.addDismissedIds(pendingIds)
    }

    private fun postSafely(pendingIds: Set<PendingMeasurementId>): Boolean = try {
        if (notificationsAllowed()) {
            postNotification(pendingIds.toSet())
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

    fun recordNotificationDismissed(pendingIds: Set<PendingMeasurementId>) {
        presentation.recordNotificationDismissed(pendingIds)
    }

    fun areNotificationsAllowed(): Boolean {
        val runtimePermissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        return runtimePermissionGranted && notifications.areNotificationsEnabled()
    }

    private fun post(pendingIds: Set<PendingMeasurementId>) {
        val count = pendingIds.size
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
        val dismissIntent = Intent(context, PendingNotificationDismissReceiver::class.java).apply {
            action = ACTION_DISMISS_PENDING
            putStringArrayListExtra(
                EXTRA_PENDING_IDS,
                ArrayList(pendingIds.map(PendingMeasurementId::value)),
            )
        }
        val deleteIntent = PendingIntent.getBroadcast(
            context,
            DISMISS_REQUEST_CODE,
            dismissIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = if (count == 1) {
            "Ожидает назначения профиля: 1 измерение"
        } else {
            "Ожидают назначения профиля: $count измерений"
        }
        notifications.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.app_name))
                .setContentText(text)
                .setContentIntent(contentIntent)
                .setDeleteIntent(deleteIntent)
                // Opening the resolver is navigation, not dismissal; only an explicit swipe invokes
                // the delete intent and suppresses this exact displayed snapshot.
                .setAutoCancel(false)
                .setOnlyAlertOnce(true)
                .setNumber(count)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build(),
        )
    }

    private fun createChannel() {
        NotificationChannelRegistry.register(
            context.getSystemService(NotificationManager::class.java),
            NotificationChannelRegistry.pendingMeasurementRouting,
        )
    }

    companion object {
        const val ACTION_RESOLVE_PENDING =
            "com.palixander.scalesync.action.RESOLVE_PENDING_MEASUREMENT"
        const val ACTION_DISMISS_PENDING =
            "com.palixander.scalesync.action.DISMISS_PENDING_MEASUREMENT"
        const val EXTRA_PENDING_COUNT = "pending_measurement_count"
        const val EXTRA_PENDING_IDS = "pending_measurement_ids"
        private val CHANNEL_ID = NotificationChannelRegistry.pendingMeasurementRouting.id
        private const val NOTIFICATION_ID = 183
        private const val RESOLVER_REQUEST_CODE = 183
        private const val DISMISS_REQUEST_CODE = 184
        private const val PREFERENCES_NAME = "pending_measurement_notifications"
    }
}
