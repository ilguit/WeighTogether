package com.palixander.weightogether.ble

import com.palixander.weightogether.worker.ScalePacket
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes forgetting a scale with packets whose callbacks may outlive BLE scanning. */
class ScalePacketProcessingGate {
    private val mutex = Mutex()

    suspend fun <T> processIfSelected(
        packet: ScalePacket,
        selectedAddress: () -> String?,
        block: suspend () -> T,
    ): T? = mutex.withLock {
        if (!packet.deviceAddress.equals(selectedAddress(), ignoreCase = true)) return@withLock null
        block()
    }

    suspend fun forget(block: suspend () -> Unit) = mutex.withLock { block() }
}
