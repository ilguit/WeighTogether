package com.example.huaweimisync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScaleRefreshCoordinatorTest {
    private val refreshingStates = mutableListOf<Boolean>()
    private var scannerStops = 0
    private var automaticScanRestores = 0
    private val messages = mutableListOf<String>()
    private var timeoutCancellations = 0
    private val coordinator = ScaleRefreshCoordinator(
        setRefreshing = refreshingStates::add,
        stopScanner = { scannerStops++ },
        restoreAutomaticScanning = { automaticScanRestores++ },
        showMessage = messages::add,
    )

    @Test
    fun `active refresh ignores repeated requests`() {
        assertTrue(coordinator.start())
        assertFalse(coordinator.start())

        assertEquals(listOf(true), refreshingStates)
        assertEquals(0, scannerStops)
        assertEquals(0, automaticScanRestores)
    }

    @Test
    fun `stable measurement completes refresh and cancels timeout`() {
        coordinator.start()
        coordinator.attachTimeout { timeoutCancellations++ }

        coordinator.complete()

        assertFinished()
        assertEquals(1, timeoutCancellations)
        assertTrue(messages.isEmpty())
    }

    @Test
    fun `scanner error completes refresh and exposes its message`() {
        coordinator.start()
        coordinator.attachTimeout { timeoutCancellations++ }

        coordinator.fail("Ошибка BLE-сканирования: 2")

        assertFinished()
        assertEquals(1, timeoutCancellations)
        assertEquals(listOf("Ошибка BLE-сканирования: 2"), messages)
    }

    @Test
    fun `timeout stops direct scan restores automatic scan and reports unavailable scale`() {
        coordinator.start()
        coordinator.attachTimeout { timeoutCancellations++ }

        coordinator.timeout()

        assertFinished()
        assertEquals(1, timeoutCancellations)
        assertEquals(listOf(SCALE_REFRESH_UNAVAILABLE_MESSAGE), messages)
    }

    @Test
    fun `clear cancels operation without user feedback`() {
        coordinator.start()
        coordinator.attachTimeout { timeoutCancellations++ }

        coordinator.clear()

        assertFinished()
        assertEquals(1, timeoutCancellations)
        assertTrue(messages.isEmpty())
    }

    @Test
    fun `late timeout attachment is cancelled after synchronous start failure`() {
        coordinator.start()
        coordinator.fail("Bluetooth выключен")

        coordinator.attachTimeout { timeoutCancellations++ }

        assertEquals(1, timeoutCancellations)
        assertEquals(listOf("Bluetooth выключен"), messages)
    }

    private fun assertFinished() {
        assertEquals(listOf(true, false), refreshingStates)
        assertEquals(1, scannerStops)
        assertEquals(1, automaticScanRestores)
        assertTrue(coordinator.start())
    }
}
