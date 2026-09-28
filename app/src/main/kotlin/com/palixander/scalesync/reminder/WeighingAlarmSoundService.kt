package com.palixander.scalesync.reminder

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.palixander.scalesync.NotificationChannelRegistry
import com.palixander.scalesync.R

class WeighingAlarmSoundService : Service() {
    private var ringtone: Ringtone? = null
    private var token: String? = null
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val next = intent?.getStringExtra(EXTRA_TOKEN)?.takeIf(String::isNotBlank) ?: return START_NOT_STICKY
        startForeground(NOTIFICATION_ID, NotificationCompat.Builder(this, NotificationChannelRegistry.weighingAlarms.id)
            .setSmallIcon(R.drawable.ic_notification).setContentTitle(getString(R.string.weighing_alarm_notification_title_generic))
            .setCategory(Notification.CATEGORY_ALARM).setOngoing(true).setSilent(true).build(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        if (next != token || ringtone?.isPlaying != true) {
            ringtone?.stop(); token = next
            val requested = intent.getStringExtra(EXTRA_SOUND)?.let(Uri::parse)
            ringtone = (requested?.let { runCatching { RingtoneManager.getRingtone(this, it) }.getOrNull() }
                ?: RingtoneManager.getRingtone(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))).apply {
                audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
                isLooping = true; play()
            }
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() { ringtone?.stop(); ringtone = null; token = null; super.onDestroy() }
    companion object {
        private const val EXTRA_TOKEN = "occurrence_token"
        private const val EXTRA_SOUND = "sound_uri"
        private const val NOTIFICATION_ID = 0x5700fffe
        fun start(context: Context, token: String, soundUri: String?) = context.startForegroundService(
            Intent(context, WeighingAlarmSoundService::class.java).putExtra(EXTRA_TOKEN, token).putExtra(EXTRA_SOUND, soundUri))
        fun stop(context: Context) { context.stopService(Intent(context, WeighingAlarmSoundService::class.java)) }
    }
}
