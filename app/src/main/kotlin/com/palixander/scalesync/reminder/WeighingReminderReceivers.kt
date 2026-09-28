package com.palixander.scalesync.reminder

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.palixander.scalesync.ScaleSyncApplication
import com.palixander.scalesync.data.ReminderCallbackKind
import com.palixander.scalesync.domain.WeighingReminderId
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class WeighingReminderOpenActivity : Activity() {
    private val actionStarted = AtomicBoolean(false)
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(WeighingReminderAlarmGateway.EXTRA_SCHEDULE_ID)
        val token = intent.getStringExtra(WeighingReminderAlarmGateway.EXTRA_OCCURRENCE_TOKEN)
        val application = applicationContext as? ScaleSyncApplication
        if (id.isNullOrBlank() || token.isNullOrBlank() || application == null) {
            finish()
            return
        }
        if (intent.getBooleanExtra(WeighingReminderCoordinator.EXTRA_ALARM, false)) {
            setFinishOnTouchOutside(false)
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val owner = intent.getStringExtra(WeighingReminderCoordinator.EXTRA_OWNER_NAME).orEmpty()
            val padding = (24 * resources.displayMetrics.density).toInt()
            val layout = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER
                setPadding(padding, padding, padding, padding)
            }
            layout.addView(android.widget.TextView(this).apply {
                text = getString(com.palixander.scalesync.R.string.weighing_alarm_screen_title, owner)
                textSize = 28f; gravity = android.view.Gravity.CENTER
            })
            layout.addView(android.widget.Button(this).apply {
                text = getString(com.palixander.scalesync.R.string.weighing_reminder_stop)
                setOnClickListener { perform(application, id, token, false) }
            })
            layout.addView(android.widget.Button(this).apply {
                text = getString(com.palixander.scalesync.R.string.weighing_reminder_snooze)
                setOnClickListener { perform(application, id, token, true) }
            })
            setContentView(layout)
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

    private fun perform(app: ScaleSyncApplication, id: String, token: String, snooze: Boolean) {
        if (!actionStarted.compareAndSet(false, true)) return
        app.container.applicationScope.launch {
            try {
                if (snooze) app.container.weighingReminders.onSnooze(WeighingReminderId(id), token)
                else app.container.weighingReminders.onStop(WeighingReminderId(id), token)
            } finally { runOnUiThread(::finish) }
        }
    }

    @Deprecated("Android invokes this callback for the system Back action")
    override fun onBackPressed() {
        val application = applicationContext as? ScaleSyncApplication
        val id = intent.getStringExtra(WeighingReminderAlarmGateway.EXTRA_SCHEDULE_ID)
        val token = intent.getStringExtra(WeighingReminderAlarmGateway.EXTRA_OCCURRENCE_TOKEN)
        if (application == null || id.isNullOrBlank() || token.isNullOrBlank()) {
            finish()
        } else {
            perform(application, id, token, false)
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
            WeighingReminderCoordinator.ACTION_STOP -> async(context) { onStop(WeighingReminderId(id), token) }
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
