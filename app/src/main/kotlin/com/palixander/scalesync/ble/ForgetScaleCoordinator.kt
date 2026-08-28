package com.palixander.scalesync.ble

/** Idempotent shutdown sequence shared by every future "forget scale" UI entry point. */
class ForgetScaleCoordinator(
    private val stopBleSessions: () -> Unit,
    private val unregisterPendingIntentScan: () -> Unit,
    private val stopReliabilityService: () -> Unit,
    private val cancelBleWork: () -> Unit,
    private val packetGate: ScalePacketProcessingGate,
    private val clearSettings: () -> Unit,
) {
    suspend fun forget() {
        stopBleSessions()
        unregisterPendingIntentScan()
        stopReliabilityService()
        cancelBleWork()
        packetGate.forget(clearSettings)
    }
}
