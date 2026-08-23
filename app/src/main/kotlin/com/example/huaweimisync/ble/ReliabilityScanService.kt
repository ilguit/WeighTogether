package com.example.huaweimisync.ble

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.annotation.DrawableRes
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.huaweimisync.R
import com.example.huaweimisync.data.ProfileStore

class ReliabilityScanService : Service() {
    private var callback: ScanCallback? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(
            NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(reliabilityScanNotificationSmallIcon())
                .setContentTitle(getString(R.string.app_name))
                .setContentText("Повышенная надёжность: весы ожидаются")
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build(),
        )
    }

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        callback?.let { active ->
            if (BleSupport.hasScanPermission(this)) {
                runCatching { BleSupport.scanner(this)?.stopScan(active) }
            }
        }
        callback = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!ProfileStore(this).settings.value.reliabilityMode) {
            stopSelf()
            return START_NOT_STICKY
        }
        stopScanner()
        startScanner()
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startScanner() {
        if (!BleSupport.hasScanPermission(this)) return
        val scanner = BleSupport.scanner(this) ?: return
        val address = ProfileStore(this).settings.value.scaleAddress ?: run {
            stopSelf()
            return
        }
        val newCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                ScanWorkScheduler.processDirect(this@ReliabilityScanService, result)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { ScanWorkScheduler.processDirect(this@ReliabilityScanService, it) }
            }
        }
        callback = newCallback
        scanner.startScan(
            listOf(BleSupport.scanFilter(address)),
            BleSupport.lowLatencySettings(),
            newCallback,
        )
    }

    @SuppressLint("MissingPermission")
    private fun stopScanner() {
        callback?.let { active ->
            if (BleSupport.hasScanPermission(this)) {
                runCatching { BleSupport.scanner(this)?.stopScan(active) }
            }
        }
        callback = null
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Сканирование весов",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    companion object {
        private const val CHANNEL_ID = "scale_scanning"
        private const val NOTIFICATION_ID = 181

        fun setEnabled(context: Context, enabled: Boolean) {
            val intent = Intent(context, ReliabilityScanService::class.java)
            if (enabled) ContextCompat.startForegroundService(context, intent)
            else context.stopService(intent)
        }
    }
}

@DrawableRes
internal fun reliabilityScanNotificationSmallIcon(): Int = R.drawable.ic_app_monochrome
