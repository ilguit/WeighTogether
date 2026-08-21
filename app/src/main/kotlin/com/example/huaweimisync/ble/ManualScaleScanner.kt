package com.example.huaweimisync.ble

import android.annotation.SuppressLint
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context

class ManualScaleScanner(private val context: Context) {
    private var callback: ScanCallback? = null

    @SuppressLint("MissingPermission")
    fun start(
        address: String? = null,
        onResult: (ScanResult) -> Unit,
        onError: (String) -> Unit,
    ): Result<Unit> = runCatching {
        check(BleSupport.hasScanPermission(context)) { "Нет разрешения Bluetooth Scan" }
        stop()
        val scanner = checkNotNull(BleSupport.scanner(context)) { "Bluetooth выключен" }
        val newCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) = onResult(result)

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach(onResult)
            }

            override fun onScanFailed(errorCode: Int) {
                onError("Ошибка BLE-сканирования: $errorCode")
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
    fun stop() {
        val active = callback ?: return
        callback = null
        if (BleSupport.hasScanPermission(context)) {
            runCatching { BleSupport.scanner(context)?.stopScan(active) }
        }
    }
}
