package com.example.huaweimisync.ble

import android.bluetooth.le.ScanResult
import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.example.huaweimisync.worker.ProcessMeasurementWorker
import com.example.huaweimisync.data.ProfileStore
import java.util.concurrent.TimeUnit

object ScanWorkScheduler {
    fun enqueue(context: Context, result: ScanResult) {
        val payload = BleSupport.serviceData(result) ?: return
        val address = if (BleSupport.hasConnectPermission(context)) {
            runCatching { result.device.address }.getOrDefault("unknown")
        } else {
            "unknown"
        }
        val selectedAddress = ProfileStore(context).settings.value.scaleAddress ?: return
        if (!address.equals(selectedAddress, ignoreCase = true)) return
        val data = Data.Builder()
            .putByteArray(ProcessMeasurementWorker.KEY_PAYLOAD, payload)
            .putString(ProcessMeasurementWorker.KEY_MAC, address)
            .build()
        val work = OneTimeWorkRequestBuilder<ProcessMeasurementWorker>()
            .setInputData(data)
            .setInitialDelay(150, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "ble-${payload.contentHashCode()}-${result.timestampNanos}",
            ExistingWorkPolicy.KEEP,
            work,
        )
    }
}
