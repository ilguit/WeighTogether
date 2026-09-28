package com.palixander.scalesync.reminder

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.IBinder

class WeighingAlarmSoundService : Service() {
    private var ringtone: Ringtone? = null
    private var legacyPlayer: MediaPlayer? = null
    private var token: String? = null
    private var scheduleId: String? = null
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            val requestedSchedule = intent.getStringExtra(EXTRA_SCHEDULE_ID)
            val requestedToken = intent.getStringExtra(EXTRA_TOKEN)
            if (shouldStopWeighingAlarm(scheduleId, token, requestedSchedule, requestedToken)) {
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                notificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } else {
            startForeground(notificationId, notification)
        }
        if (next != token || !isSoundPlaying()) {
            releaseSound()
            token = next
            scheduleId = nextSchedule
            val sound = intent.getStringExtra(EXTRA_SOUND)?.let(Uri::parse)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            val attributes = AudioAttributes.Builder()
                .setUsage(WEIGHING_ALARM_AUDIO_USAGE)
                .setContentType(WEIGHING_ALARM_AUDIO_CONTENT_TYPE)
                .build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ringtone = (runCatching { RingtoneManager.getRingtone(this, sound) }.getOrNull()
                    ?: RingtoneManager.getRingtone(
                        this,
                        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                    ))?.apply {
                    audioAttributes = attributes
                    isLooping = true
                    play()
                }
            } else {
                legacyPlayer = createLoopingPlayer(sound, attributes)
                    ?: createLoopingPlayer(
                        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                        attributes,
                    )
            }
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

    private fun releaseSound() {
        ringtone?.stop()
        ringtone = null
        legacyPlayer?.stop()
        legacyPlayer?.release()
        legacyPlayer = null
    }
    companion object {
        internal const val ACTION_START = "com.palixander.scalesync.action.START_WEIGHING_ALARM_SOUND"
        internal const val ACTION_STOP = "com.palixander.scalesync.action.STOP_WEIGHING_ALARM_SOUND"
        private const val EXTRA_TOKEN = "occurrence_token"
        private const val EXTRA_SOUND = "sound_uri"
        private const val EXTRA_SCHEDULE_ID = "schedule_id"
        private const val EXTRA_NOTIFICATION_ID = "notification_id"
        private const val EXTRA_NOTIFICATION = "notification"
        fun start(
            context: Context,
            scheduleId: String,
            token: String,
            soundUri: String?,
            notificationId: Int,
            notification: Notification,
        ) = context.startForegroundService(Intent(context, WeighingAlarmSoundService::class.java).apply {
            action = ACTION_START
            putExtra(EXTRA_SCHEDULE_ID, scheduleId)
            putExtra(EXTRA_TOKEN, token)
            putExtra(EXTRA_SOUND, soundUri)
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
            putExtra(EXTRA_NOTIFICATION, notification)
        })

        fun stop(context: Context, scheduleId: String, token: String? = null) {
            runCatching {
                context.startService(Intent(context, WeighingAlarmSoundService::class.java).apply {
                    action = ACTION_STOP
                    putExtra(EXTRA_SCHEDULE_ID, scheduleId)
                    token?.let { putExtra(EXTRA_TOKEN, it) }
                })
            }
        }
    }
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
