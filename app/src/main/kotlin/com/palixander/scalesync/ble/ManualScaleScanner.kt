package com.palixander.scalesync.ble

import android.annotation.SuppressLint
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import com.palixander.scalesync.R

class ManualScaleScanner(private val context: Context) {
    @Volatile
    private var callback: ScanCallback? = null

    @SuppressLint("MissingPermission")
    fun start(
        address: String? = null,
        onResult: (ScanResult) -> Unit,
        onError: (String) -> Unit,
    ): Result<Unit> = runCatching {
        check(BleSupport.hasScanPermission(context)) {
            context.getString(R.string.error_bluetooth_scan_permission)
        }
        stop()
        val scanner = checkNotNull(BleSupport.scanner(context)) {
            context.getString(R.string.error_bluetooth_disabled)
        }
        val newCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (callback === this) onResult(result)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                if (callback === this) results.forEach(onResult)
            }

            override fun onScanFailed(errorCode: Int) {
                if (callback === this) {
                    onError(context.getString(R.string.error_bluetooth_scan, errorCode))
                }
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
