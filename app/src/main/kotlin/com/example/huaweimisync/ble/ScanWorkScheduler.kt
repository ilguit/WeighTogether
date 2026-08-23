package com.example.huaweimisync.ble

import android.bluetooth.le.ScanResult
import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.example.huaweimisync.worker.ProcessMeasurementWorker
import com.example.huaweimisync.MiSyncApplication
import com.example.huaweimisync.worker.DirectPacketProcessingOrchestrator
import com.example.huaweimisync.worker.DirectPacketProcessingResult
import com.example.huaweimisync.worker.ScalePacket
import com.example.huaweimisync.data.ProfileStore
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.launch

object ScanWorkScheduler {
    fun processDirect(context: Context, result: ScanResult) {
        val packet = packet(context, result) ?: return
        val container = (context.applicationContext as MiSyncApplication).container
        container.applicationScope.launch { processDirectOrEnqueue(context, packet) }
    }

    suspend fun processDirectOrEnqueue(
        context: Context,
        result: ScanResult,
    ): DirectPacketProcessingResult? = packet(context, result)?.let { packet ->
        processDirectOrEnqueue(context, packet)
    }

    private suspend fun processDirectOrEnqueue(
        context: Context,
        packet: ScalePacket,
    ): DirectPacketProcessingResult {
        val container = (context.applicationContext as MiSyncApplication).container
        return DirectPacketProcessingOrchestrator(
            process = { container.packetProcessor.process(it) },
            enqueueFallback = { enqueue(context, it) },
        ).process(packet)
    }

    private fun packet(context: Context, result: ScanResult): ScalePacket? {
        val payload = BleSupport.serviceData(result) ?: return null
        val address = if (BleSupport.hasConnectPermission(context)) {
            runCatching { result.device.address }.getOrDefault("unknown")
        } else {
            "unknown"
        }
        val selectedAddress = ProfileStore(context).settings.value.scaleAddress ?: return null
        if (!address.equals(selectedAddress, ignoreCase = true)) return null
        return ScalePacket(payload, address)
    }

    private fun enqueue(context: Context, packet: ScalePacket) {
        val data = Data.Builder()
            .putByteArray(ProcessMeasurementWorker.KEY_PAYLOAD, packet.payload)
            .putString(ProcessMeasurementWorker.KEY_MAC, packet.deviceAddress)
            .build()
        val work = OneTimeWorkRequestBuilder<ProcessMeasurementWorker>()
            .setInputData(data)
            .setInitialDelay(150, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "ble-${packet.deviceAddress}-${packet.payload.contentHashCode()}",
            ExistingWorkPolicy.KEEP,
            work,
        )
    }
}
