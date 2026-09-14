package com.palixander.scalesync.worker

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.palixander.scalesync.R
import com.palixander.scalesync.MainActivity
import com.palixander.scalesync.NotificationChannelRegistry
import com.palixander.scalesync.data.SuccessfulMeasurementNotifier
import com.palixander.scalesync.domain.AccountMeasurement
import java.nio.charset.StandardCharsets

data class SuccessfulMeasurementNotificationIdentity(
    val notificationTag: String,
    val notificationId: Int,
    val requestCode: Int,
    val intentData: String,
)

/** Pure contract shared by the Android transport and JVM tests. */
object SuccessfulMeasurementNotificationContract {
    fun identityFor(measurementId: String): SuccessfulMeasurementNotificationIdentity {
        require(measurementId.isNotBlank()) { "Measurement id must not be blank" }
        val stableId = measurementId.hashCode() and Int.MAX_VALUE
        return SuccessfulMeasurementNotificationIdentity(
            notificationTag = "saved-measurement:$measurementId",
            notificationId = stableId,
            requestCode = stableId,
            intentData = "scalesync://measurement/saved/${encodePathSegment(measurementId)}",
        )
    }

    fun shouldPost(
        runtimePermissionGranted: Boolean,
        applicationNotificationsEnabled: Boolean,
        channelEnabled: Boolean,
    ): Boolean = runtimePermissionGranted && applicationNotificationsEnabled && channelEnabled

    fun contentText(accountDisplayName: String): String =
        "Измерение успешно сохранено для $accountDisplayName"

    private fun encodePathSegment(value: String): String = buildString {
        value.toByteArray(StandardCharsets.UTF_8).forEach { byte ->
            val unsigned = byte.toInt() and 0xff
            val isUnreserved = unsigned in 'a'.code..'z'.code ||
                unsigned in 'A'.code..'Z'.code ||
                unsigned in '0'.code..'9'.code ||
                unsigned == '-'.code || unsigned == '.'.code ||
                unsigned == '_'.code || unsigned == '~'.code
            if (isUnreserved) {
                append(unsigned.toChar())
            } else {
                append('%')
                append(HEX_DIGITS[unsigned ushr 4])
                append(HEX_DIGITS[unsigned and 0x0f])
            }
        }
    }

    private const val HEX_DIGITS = "0123456789ABCDEF"
}

class SuccessfulMeasurementNotificationHelper(
    private val context: Context,
) : SuccessfulMeasurementNotifier {
    private val notifications = NotificationManagerCompat.from(context)
    private val notificationManager = context.getSystemService(NotificationManager::class.java)

    override fun notifyMeasurementSaved(
        measurement: AccountMeasurement,
        accountDisplayName: String,
    ) {
        try {
            createChannel()
            if (!areNotificationsAllowed()) return
            post(measurement.measurementId, accountDisplayName)
        } catch (_: RuntimeException) {
            // The measurement is already durable. Notification failures must never affect saving.
        }
    }

    private fun areNotificationsAllowed(): Boolean {
        val permissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        val channelEnabled = Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            notificationManager.getNotificationChannel(CHANNEL_ID)?.importance !=
            NotificationManager.IMPORTANCE_NONE
        return SuccessfulMeasurementNotificationContract.shouldPost(
            runtimePermissionGranted = permissionGranted,
            applicationNotificationsEnabled = notifications.areNotificationsEnabled(),
            channelEnabled = channelEnabled,
        )
    }

    @SuppressLint("MissingPermission")
    private fun post(measurementId: String, accountDisplayName: String) {
        // Permission and channel state can change after the capability check. A transport failure
        // remains best-effort and is contained by notifyMeasurementSaved.
        val identity = SuccessfulMeasurementNotificationContract.identityFor(measurementId)
        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_SAVED_MEASUREMENT
            data = Uri.parse(identity.intentData)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            identity.requestCode,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        notifications.notify(
            identity.notificationTag,
            identity.notificationId,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Измерение сохранено")
                .setContentText(
                    SuccessfulMeasurementNotificationContract.contentText(accountDisplayName),
                )
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build(),
        )
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        NotificationChannelRegistry.register(
            notificationManager,
            NotificationChannelRegistry.successfulMeasurementSaves,
        )
    }

    companion object {
        const val ACTION_OPEN_SAVED_MEASUREMENT =
            "com.palixander.scalesync.action.OPEN_SAVED_MEASUREMENT"
        val CHANNEL_ID = NotificationChannelRegistry.successfulMeasurementSaves.id
    }
}
