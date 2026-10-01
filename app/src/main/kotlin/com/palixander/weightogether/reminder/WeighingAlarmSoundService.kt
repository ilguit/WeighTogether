package com.palixander.weightogether.reminder

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.annotation.RequiresApi

class WeighingAlarmSoundService : Service() {
    private var ringtone: Ringtone? = null
    private var legacyPlayer: MediaPlayer? = null
    private var fallbackTone: ToneGenerator? = null
    private val fallbackHandler = Handler(Looper.getMainLooper())
    private val repeatFallbackTone = object : Runnable {
        override fun run() {
            fallbackTone?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, FALLBACK_TONE_MS)
            if (fallbackTone != null) fallbackHandler.postDelayed(this, FALLBACK_REPEAT_MS)
        }
    }
    private var token: String? = null
    private var scheduleId: String? = null
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            val requestedSchedule = intent.getStringExtra(EXTRA_SCHEDULE_ID)
            val requestedToken = intent.getStringExtra(EXTRA_TOKEN)
            if (shouldStopWeighingAlarm(scheduleId, token, requestedSchedule, requestedToken)) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
                stopSelf()
            }
            return START_NOT_STICKY
        }
        val next = intent?.getStringExtra(EXTRA_TOKEN)?.takeIf(String::isNotBlank) ?: return START_NOT_STICKY
        val nextSchedule = intent.getStringExtra(EXTRA_SCHEDULE_ID)?.takeIf(String::isNotBlank)
            ?: return START_NOT_STICKY
        @Suppress("DEPRECATION")
        val notification = intent.getParcelableExtra<Notification>(EXTRA_NOTIFICATION)
            ?: return START_NOT_STICKY
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0).takeIf { it != 0 }
            ?: return START_NOT_STICKY
        @Suppress("DEPRECATION")
        val fallbackNotification = intent.getParcelableExtra<Notification>(EXTRA_FALLBACK_NOTIFICATION)
            ?: return START_NOT_STICKY
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    notificationId,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
                )
            } else {
                startForeground(notificationId, notification)
            }
        } catch (_: RuntimeException) {
            getSystemService(NotificationManager::class.java).notify(notificationId, fallbackNotification)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (next != token || !isSoundPlaying()) {
            releaseSound()
            token = next
            scheduleId = nextSchedule
            val selectedSound = intent.getStringExtra(EXTRA_SOUND)?.let { runCatching { Uri.parse(it) }.getOrNull() }
            val systemSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            val attributes = AudioAttributes.Builder()
                .setUsage(WEIGHING_ALARM_AUDIO_USAGE)
                .setContentType(WEIGHING_ALARM_AUDIO_CONTENT_TYPE)
                .build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ringtone = firstPlayableAlarmSound(alarmSoundCandidates(selectedSound, systemSound)) { sound ->
                    playRingtone(sound, attributes)
                }
            } else {
                legacyPlayer = firstPlayableAlarmSound(alarmSoundCandidates(selectedSound, systemSound)) { sound ->
                    createLoopingPlayer(sound, attributes)
                }
            }
            if (ringtone == null && legacyPlayer == null) startFallbackTone()
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        token = null
        scheduleId = null
        releaseSound()
        super.onDestroy()
    }

    private fun isSoundPlaying(): Boolean = ringtone?.isPlaying == true || legacyPlayer?.isPlaying == true

    private fun createLoopingPlayer(uri: Uri, attributes: AudioAttributes): MediaPlayer? {
        val player = MediaPlayer()
        return runCatching {
            player.apply {
                setAudioAttributes(attributes)
                setDataSource(this@WeighingAlarmSoundService, uri)
                isLooping = true
                prepare()
                start()
            }
        }.getOrElse {
            player.release()
            null
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun playRingtone(uri: Uri, attributes: AudioAttributes): Ringtone? {
        var candidate: Ringtone? = null
        return runCatching {
            val loaded = RingtoneManager.getRingtone(this, uri) ?: error("Ringtone is unavailable")
            candidate = loaded
            loaded.audioAttributes = attributes
            loaded.isLooping = true
            loaded.play()
            loaded
        }.getOrElse {
            runCatching { candidate?.stop() }
            null
        }
    }

    private fun startFallbackTone() {
        fallbackTone = runCatching { ToneGenerator(AudioManager.STREAM_ALARM, ToneGenerator.MAX_VOLUME) }.getOrNull()
        fallbackTone?.let {
            it.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, FALLBACK_TONE_MS)
            fallbackHandler.postDelayed(repeatFallbackTone, FALLBACK_REPEAT_MS)
        }
    }

    private fun releaseSound() {
        runCatching { ringtone?.stop() }
        ringtone = null
        runCatching { legacyPlayer?.stop() }
        runCatching { legacyPlayer?.release() }
        legacyPlayer = null
        fallbackHandler.removeCallbacks(repeatFallbackTone)
        fallbackTone?.release()
        fallbackTone = null
    }
    companion object {
        internal const val ACTION_START = "com.palixander.weightogether.action.START_WEIGHING_ALARM_SOUND"
        internal const val ACTION_STOP = "com.palixander.weightogether.action.STOP_WEIGHING_ALARM_SOUND"
        private const val EXTRA_TOKEN = "occurrence_token"
        private const val EXTRA_SOUND = "sound_uri"
        private const val EXTRA_SCHEDULE_ID = "schedule_id"
        private const val EXTRA_NOTIFICATION_ID = "notification_id"
        private const val EXTRA_NOTIFICATION = "notification"
        private const val EXTRA_FALLBACK_NOTIFICATION = "fallback_notification"
        fun start(
            context: Context,
            scheduleId: String,
            token: String,
            soundUri: String?,
            notificationId: Int,
            notification: Notification,
            fallbackNotification: Notification,
        ): Boolean = try {
            context.startForegroundService(Intent(context, WeighingAlarmSoundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_SCHEDULE_ID, scheduleId)
                putExtra(EXTRA_TOKEN, token)
                putExtra(EXTRA_SOUND, soundUri)
                putExtra(EXTRA_NOTIFICATION_ID, notificationId)
                putExtra(EXTRA_NOTIFICATION, notification)
                putExtra(EXTRA_FALLBACK_NOTIFICATION, fallbackNotification)
            })
            true
        } catch (_: RuntimeException) {
            false
        }

        fun stop(context: Context, scheduleId: String, token: String? = null) {
            runCatching {
                context.startService(Intent(context, WeighingAlarmSoundService::class.java).apply {
                    action = ACTION_STOP
                    putExtra(EXTRA_SCHEDULE_ID, scheduleId)
                    token?.let { putExtra(EXTRA_TOKEN, it) }
                })
            }
        }

        private const val FALLBACK_TONE_MS = 700
        private const val FALLBACK_REPEAT_MS = 1_000L
    }
}

internal fun alarmSoundCandidates(selected: Uri?, systemDefault: Uri?): List<Uri> =
    listOfNotNull(selected, systemDefault).distinct()

internal fun <T : Any> firstPlayableAlarmSound(candidates: List<Uri>, play: (Uri) -> T?): T? {
    candidates.forEach { candidate ->
        runCatching { play(candidate) }.getOrNull()?.let { return it }
    }
    return null
}

internal const val WEIGHING_ALARM_AUDIO_USAGE = AudioAttributes.USAGE_ALARM
internal const val WEIGHING_ALARM_AUDIO_CONTENT_TYPE = AudioAttributes.CONTENT_TYPE_SONIFICATION

internal fun shouldStopWeighingAlarm(
    activeScheduleId: String?,
    activeToken: String?,
    requestedScheduleId: String?,
    requestedToken: String?,
): Boolean = requestedScheduleId == activeScheduleId &&
    (requestedToken == null || requestedToken == activeToken)
