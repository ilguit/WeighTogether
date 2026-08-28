package com.example.huaweimisync.ble

import com.example.huaweimisync.worker.ScalePacket
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ForgetScaleCoordinatorTest {
    @Test
    fun `forget stops every producer cancels work and clears settings in order`() = runBlocking {
        val events = mutableListOf<String>()
        val gate = ScalePacketProcessingGate()
        val coordinator = ForgetScaleCoordinator(
            stopBleSessions = { events += "sessions" },
            unregisterPendingIntentScan = { events += "pending-intent" },
            stopReliabilityService = { events += "service" },
            cancelBleWork = { events += "work" },
            packetGate = gate,
            clearSettings = { events += "settings" },
        )

        coordinator.forget()
        coordinator.forget()

        assertEquals(
            listOf(
                "sessions", "pending-intent", "service", "work", "settings",
                "sessions", "pending-intent", "service", "work", "settings",
            ),
            events,
        )
    }

    @Test
    fun `packet callback queued behind forget is rejected after scale is cleared`() = runBlocking {
        val gate = ScalePacketProcessingGate()
        var selectedAddress: String? = "AA:BB"
        val forgettingEntered = CompletableDeferred<Unit>()
        val releaseForget = CompletableDeferred<Unit>()
        val forget = async {
            gate.forget {
                selectedAddress = null
                forgettingEntered.complete(Unit)
                releaseForget.await()
            }
        }
        forgettingEntered.await()

        val packet = async {
            gate.processIfSelected(
                ScalePacket(byteArrayOf(1), "AA:BB"),
                selectedAddress = { selectedAddress },
            ) { "processed" }
        }
        releaseForget.complete(Unit)
        forget.await()

        assertNull(packet.await())
    }

    @Test
    fun `packet from previous address is rejected after another scale is selected`() = runBlocking {
        val gate = ScalePacketProcessingGate()
        val result = gate.processIfSelected(
            ScalePacket(byteArrayOf(1), "AA:BB"),
            selectedAddress = { "CC:DD" },
        ) { "processed" }

        assertNull(result)
    }
}
