package com.palixander.weightogether.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.PowerManager
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import com.palixander.weightogether.ScaleSyncApplication
import com.palixander.weightogether.data.ReminderCallbackKind
import com.palixander.weightogether.domain.WeighingReminderId
import kotlinx.coroutines.launch

class WeighingReminderOpenActivity : ComponentActivity() {
    private val actionGuard = AlarmOccurrenceActionGuard()
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        val id = intent.getStringExtra(WeighingReminderAlarmGateway.EXTRA_SCHEDULE_ID)
        val token = intent.getStringExtra(WeighingReminderAlarmGateway.EXTRA_OCCURRENCE_TOKEN)
        val application = applicationContext as? ScaleSyncApplication
        if (id.isNullOrBlank() || token.isNullOrBlank() || application == null) {
            finish()
            return
        }
        val presentation = actionGuard.install(id, token)
        if (intent.getBooleanExtra(WeighingReminderCoordinator.EXTRA_PERFORM_WEIGH, false)) {
            perform(application, presentation, AlarmActivityAction.WEIGH)
            return
        }
        if (intent.getBooleanExtra(WeighingReminderCoordinator.EXTRA_ALARM, false)) {
            setFinishOnTouchOutside(false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                setShowWhenLocked(true)
                setTurnScreenOn(true)
            } else {
                @Suppress("DEPRECATION")
                window.addFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
                )
            }
            onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = dismissAlarmOrFinish()
            })
            val owner = intent.getStringExtra(WeighingReminderCoordinator.EXTRA_OWNER_NAME).orEmpty()
            val padding = (24 * resources.displayMetrics.density).toInt()
            val avatarSize = (128 * resources.displayMetrics.density).toInt()
            val layout = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER
                setPadding(padding, padding, padding, padding)
                setBackgroundColor(Color.rgb(250, 250, 250))
            }
            layout.addView(android.widget.ImageView(this).apply {
                contentDescription = owner
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setOval(0, 0, view.width, view.height)
                    }
                }
                clipToOutline = true
                val photoPath = intent.getStringExtra(WeighingReminderCoordinator.EXTRA_OWNER_PHOTO_PATH)
                val photo = photoPath?.let {
                    application.container.profilePhotos.decodeForDisplay(it, avatarSize)
                }
                if (photo != null) {
                    setImageBitmap(photo)
                } else {
                    setImageDrawable(applicationInfo.loadIcon(packageManager))
                    setPadding(padding, padding, padding, padding)
                }
                layoutParams = android.widget.LinearLayout.LayoutParams(avatarSize, avatarSize)
            })
            layout.addView(android.widget.TextView(this).apply {
                text = getString(com.palixander.weightogether.R.string.weighing_alarm_screen_title, owner)
                textSize = 28f; gravity = android.view.Gravity.CENTER
                setTextColor(Color.rgb(24, 24, 24))
                setPadding(0, padding, 0, padding)
            })
            layout.addView(android.widget.Button(this).apply {
                text = getString(com.palixander.weightogether.R.string.weighing_reminder_weigh)
                setOnClickListener { perform(application, presentation, AlarmActivityAction.WEIGH) }
                minHeight = (56 * resources.displayMetrics.density).toInt()
                setTextColor(Color.WHITE)
                background = alarmButtonBackground(Color.rgb(183, 28, 28))
                layoutParams = alarmButtonLayoutParams(topMargin = 0)
            })
            layout.addView(android.widget.Button(this).apply {
                text = getString(com.palixander.weightogether.R.string.weighing_reminder_snooze)
                setOnClickListener { perform(application, presentation, AlarmActivityAction.SNOOZE) }
                minHeight = (56 * resources.displayMetrics.density).toInt()
                setTextColor(Color.WHITE)
                background = alarmButtonBackground(Color.rgb(21, 101, 192))
                layoutParams = alarmButtonLayoutParams(topMargin = 12)
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

    private fun perform(
        app: ScaleSyncApplication,
        presentation: AlarmOccurrenceActionGuard.Presentation,
        action: AlarmActivityAction,
    ) {
        if (!actionGuard.begin(presentation)) return
        app.container.applicationScope.launch {
            var navigationTarget: WeighingReminderNavigationTarget? = null
            try {
                val id = WeighingReminderId(presentation.scheduleId)
                val token = presentation.occurrenceToken
                when (action) {
                    AlarmActivityAction.SNOOZE ->
                        app.container.weighingReminders.onSnooze(id, token)
                    AlarmActivityAction.DISMISS ->
                        app.container.weighingReminders.onDismiss(id, token)
                    AlarmActivityAction.WEIGH ->
                        navigationTarget = app.container.weighingReminders.onStop(id, token)
                }
            } finally {
                runOnUiThread {
                    if (actionGuard.isCurrent(presentation)) {
                        navigationTarget?.let(::openOwner)
                        finish()
                    }
                }
            }
        }
    }

    private fun openOwner(target: WeighingReminderNavigationTarget) {
        startActivity(target.putInto(Intent(this, com.palixander.weightogether.MainActivity::class.java)).apply {
            action = WeighingReminderNavigationTarget.ACTION_OPEN_PROFILE
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        })
    }

    private fun alarmButtonBackground(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = 12 * resources.displayMetrics.density
    }

    private fun alarmButtonLayoutParams(topMargin: Int) = android.widget.LinearLayout.LayoutParams(
        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply {
        this.topMargin = (topMargin * resources.displayMetrics.density).toInt()
    }

    private fun dismissAlarmOrFinish() {
        val application = applicationContext as? ScaleSyncApplication
        val presentation = actionGuard.current()
        if (application == null || presentation == null) {
            finish()
        } else {
            perform(application, presentation, AlarmActivityAction.DISMISS)
        }
    }
}

internal enum class AlarmActivityAction { WEIGH, SNOOZE, DISMISS }

/** Keeps action idempotency and Activity completion scoped to the occurrence currently on screen. */
internal class AlarmOccurrenceActionGuard {
    private var generation = 0L
    private var current: Presentation? = null
    private val started = mutableSetOf<Occurrence>()

    @Synchronized
    fun install(scheduleId: String, occurrenceToken: String): Presentation {
        val occurrence = Occurrence(scheduleId, occurrenceToken)
        current?.takeIf { it.key == occurrence }?.let { return it }
        return Presentation(scheduleId, occurrenceToken, ++generation).also { current = it }
    }

    @Synchronized
    fun begin(presentation: Presentation): Boolean {
        if (current != presentation) return false
        return started.add(presentation.key)
    }

    @Synchronized
    fun isCurrent(presentation: Presentation): Boolean = current == presentation

    @Synchronized
    fun current(): Presentation? = current

    internal data class Occurrence(
        val scheduleId: String,
        val occurrenceToken: String,
    )

    internal data class Presentation(
        val scheduleId: String,
        val occurrenceToken: String,
        val generation: Long,
    ) {
        internal val key: Occurrence get() = Occurrence(scheduleId, occurrenceToken)
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
            WeighingReminderCoordinator.ACTION_STOP -> async(context) { onDismiss(WeighingReminderId(id), token) }
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
    val wakeLock = runCatching {
        context.getSystemService(PowerManager::class.java).newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "ScaleSync:weighing-reminder-receiver",
        ).apply { acquire(RECEIVER_WAKE_LOCK_TIMEOUT_MILLIS) }
    }.getOrNull()
    try {
        application.container.applicationScope.launch {
            try {
                application.container.weighingReminders.block()
            } finally {
                try {
                    if (wakeLock?.isHeld == true) wakeLock.release()
                } finally {
                    pendingResult.finish()
                }
            }
        }
    } catch (failure: Throwable) {
        try {
            if (wakeLock?.isHeld == true) wakeLock.release()
        } finally {
            pendingResult.finish()
        }
        throw failure
    }
}

internal const val RECEIVER_WAKE_LOCK_TIMEOUT_MILLIS = 60_000L
