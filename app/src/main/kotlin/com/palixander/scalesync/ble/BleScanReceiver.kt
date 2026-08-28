package com.palixander.scalesync.ble

import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.palixander.scalesync.ScaleSyncApplication
import kotlinx.coroutines.launch

class BleScanReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SCAN_RESULT) return
        val errorCode = intent.getIntExtra(BluetoothLeScanner.EXTRA_ERROR_CODE, 0)
        if (errorCode != 0) {
            Log.e(TAG, "Background BLE scan failed: $errorCode")
            return
        }
        val pendingResult = goAsync()
        val container = (context.applicationContext as? ScaleSyncApplication)?.container ?: run {
            pendingResult.finish()
            return
        }
        container.applicationScope.launch {
            try {
                scanResults(intent).forEach {
                    ScanWorkScheduler.processDirectOrEnqueue(context, it)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun scanResults(intent: Intent): List<ScanResult> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(
                BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT,
                ScanResult::class.java,
            ).orEmpty()
        } else {
            intent.getParcelableArrayListExtra<ScanResult>(
                BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT,
            ).orEmpty()
        }

    companion object {
        private const val TAG = "ScaleScanReceiver"
        const val ACTION_SCAN_RESULT = "com.palixander.scalesync.BODY_SCALE_SCAN_RESULT"
    }
}
