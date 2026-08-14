package com.example.huaweimisync.ble

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.huaweimisync.data.ProfileStore

object BackgroundScanRegistrar {
    @SuppressLint("MissingPermission")
    fun register(context: Context): Result<Unit> = runCatching {
        check(BleSupport.hasScanPermission(context)) { "Нет разрешения Bluetooth Scan" }
        val address = checkNotNull(ProfileStore(context).settings.value.scaleAddress) {
            "Сначала выберите весы"
        }
        val scanner = checkNotNull(BleSupport.scanner(context)) { "Bluetooth выключен" }
        runCatching { scanner.stopScan(pendingIntent(context)) }
        val resultCode = scanner.startScan(
            listOf(BleSupport.scanFilter(address)),
            BleSupport.balancedSettings(),
            pendingIntent(context),
        )
        check(resultCode == 0) { "Не удалось включить BLE-сканирование: $resultCode" }
    }

    @SuppressLint("MissingPermission")
    fun unregister(context: Context) {
        if (!BleSupport.hasScanPermission(context)) return
        runCatching { BleSupport.scanner(context)?.stopScan(pendingIntent(context)) }
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, BleScanReceiver::class.java)
            .setAction(BleScanReceiver.ACTION_SCAN_RESULT)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(context, 181, intent, flags)
    }
}
