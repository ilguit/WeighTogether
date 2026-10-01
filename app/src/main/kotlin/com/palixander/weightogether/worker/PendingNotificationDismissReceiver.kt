package com.palixander.weightogether.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.palixander.weightogether.ScaleSyncApplication
import com.palixander.weightogether.domain.PendingMeasurementId

class PendingNotificationDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != PendingMeasurementNotificationHelper.ACTION_DISMISS_PENDING) return
        val pendingIds = intent.getStringArrayListExtra(
            PendingMeasurementNotificationHelper.EXTRA_PENDING_IDS,
        ).orEmpty().filter(String::isNotBlank).mapTo(linkedSetOf(), ::PendingMeasurementId)
        if (pendingIds.isEmpty()) return

        val application = context.applicationContext as? ScaleSyncApplication ?: return
        application.container.pendingMeasurementNotifications.recordNotificationDismissed(pendingIds)
    }
}
