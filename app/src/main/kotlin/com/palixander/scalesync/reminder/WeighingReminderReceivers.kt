package com.palixander.scalesync.reminder

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.palixander.scalesync.ScaleSyncApplication
import com.palixander.scalesync.data.ReminderCallbackKind
import com.palixander.scalesync.domain.WeighingReminderId
import kotlinx.coroutines.launch

class WeighingReminderOpenActivity : Activity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(WeighingReminderAlarmGateway.EXTRA_SCHEDULE_ID)
        val token = intent.getStringExtra(WeighingReminderAlarmGateway.EXTRA_OCCURRENCE_TOKEN)
        val application = applicationContext as? ScaleSyncApplication
        if (id.isNullOrBlank() || token.isNullOrBlank() || application == null) {
            finish()
            return
        }
        application.container.applicationScope.launch {
            try {
                application.container.weighingReminders.onContentTap(WeighingReminderId(id), token)
            } finally {
                runOnUiThread(::finish)
            }
        }
    }
}

class WeighingReminderFireReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != WeighingReminderAlarmGateway.ACTION_FIRE) return
        val id = intent.getStringExtra(WeighingReminderAlarmGateway.EXTRA_SCHEDULE_ID)?.takeIf(String::isNotBlank)
            ?: return
        val token = intent.getStringExtra(WeighingReminderAlarmGateway.EXTRA_OCCURRENCE_TOKEN)?.takeIf(String::isNotBlank)
            ?: return
        val kind = intent.getStringExtra(WeighingReminderAlarmGateway.EXTRA_CALLBACK_KIND)
            ?.let { runCatching { ReminderCallbackKind.valueOf(it) }.getOrNull() } ?: return
        async(context) { onFire(WeighingReminderId(id), kind, token) }
    }
}

class WeighingReminderActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(WeighingReminderAlarmGateway.EXTRA_SCHEDULE_ID)?.takeIf(String::isNotBlank)
            ?: return
        val token = intent.getStringExtra(WeighingReminderAlarmGateway.EXTRA_OCCURRENCE_TOKEN)?.takeIf(String::isNotBlank)
            ?: return
        when (intent.action) {
            WeighingReminderCoordinator.ACTION_SNOOZE -> async(context) { onSnooze(WeighingReminderId(id), token) }
        }
    }
}

class WeighingReminderReconcileReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in SUPPORTED_ACTIONS) return
        async(context) { reconcile() }
    }

    private companion object {
        val SUPPORTED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
        )
    }
}

private fun BroadcastReceiver.async(
    context: Context,
    block: suspend WeighingReminderCoordinator.() -> Unit,
) {
    val application = context.applicationContext as? ScaleSyncApplication ?: return
    val pendingResult = goAsync()
    application.container.applicationScope.launch {
        try {
            application.container.weighingReminders.block()
        } finally {
            pendingResult.finish()
        }
    }
}
