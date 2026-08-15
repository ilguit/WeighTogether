package com.example.huaweimisync.worker

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.huaweimisync.MainActivity
import com.example.huaweimisync.R
import com.example.huaweimisync.data.PendingDecisionNotifier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface PendingDecisionFallback {
    data object Hidden : PendingDecisionFallback
    data class ShowOnForeground(val pendingCount: Int) : PendingDecisionFallback
}

/** Pure state machine used by the Android notification transport and JVM tests. */
class PendingDecisionPresentationCoordinator(
    private val notificationsAllowed: () -> Boolean,
    private val postNotification: (Int) -> Unit,
    private val cancelNotification: () -> Unit,
) : PendingDecisionNotifier {
    private val mutableFallback = MutableStateFlow<PendingDecisionFallback>(
        PendingDecisionFallback.Hidden,
    )
    val notificationDeniedFallback: StateFlow<PendingDecisionFallback> =
        mutableFallback.asStateFlow()

    override fun updatePendingCount(count: Int) {
        require(count >= 0) { "Pending count cannot be negative" }
        if (count == 0) {
            cancelSafely()
            mutableFallback.value = PendingDecisionFallback.Hidden
        } else if (postSafely(count)) {
            mutableFallback.value = PendingDecisionFallback.Hidden
        } else {
            cancelSafely()
            mutableFallback.value = PendingDecisionFallback.ShowOnForeground(count)
        }
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
    )

    val notificationDeniedFallback: StateFlow<PendingDecisionFallback>
        get() = presentation.notificationDeniedFallback

    override fun updatePendingCount(count: Int) {
        presentation.updatePendingCount(count)
    }

    fun areNotificationsAllowed(): Boolean {
        val runtimePermissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        return runtimePermissionGranted && notifications.areNotificationsEnabled()
    }

    private fun post(count: Int) {
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
                .setAutoCancel(true)
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
    }
}
