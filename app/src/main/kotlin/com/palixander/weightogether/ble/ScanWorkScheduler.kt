package com.palixander.weightogether.ble

import android.bluetooth.le.ScanResult
import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.palixander.weightogether.worker.ProcessMeasurementWorker
import com.palixander.weightogether.ScaleSyncApplication
import com.palixander.weightogether.worker.DirectPacketProcessingOrchestrator
import com.palixander.weightogether.worker.DirectPacketProcessingResult
import com.palixander.weightogether.worker.ScalePacket
import com.palixander.weightogether.data.ProfileStore
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.launch

object ScanWorkScheduler {
    fun processDirect(context: Context, result: ScanResult) {
        val packet = packet(context, result) ?: return
        val container = (context.applicationContext as ScaleSyncApplication).container
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
        val container = (context.applicationContext as ScaleSyncApplication).container
        return container.scalePacketProcessingGate.processIfSelected(
            packet = packet,
            selectedAddress = { container.profileStore.settings.value.scaleAddress },
        ) {
            DirectPacketProcessingOrchestrator(
                process = { container.packetProcessor.process(it) },
                enqueueFallback = { enqueue(context, it) },
            ).process(packet)
        } ?: DirectPacketProcessingResult.REJECTED_STALE_SCALE
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
            .addTag(BLE_PROCESSING_WORK_TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "ble-${packet.deviceAddress}-${packet.payload.contentHashCode()}",
            ExistingWorkPolicy.KEEP,
            work,
        )
    }

    const val BLE_PROCESSING_WORK_TAG = "ble-measurement-processing"
}
