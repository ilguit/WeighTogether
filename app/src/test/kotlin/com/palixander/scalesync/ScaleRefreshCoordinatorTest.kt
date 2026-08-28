package com.palixander.scalesync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun `refresh timeout is seven seconds`() {
        assertEquals(7_000L, SCALE_REFRESH_TIMEOUT_MILLIS)
    }

    @Test
    fun `null address is rejected with settings message without activating refresh`() {
        assertRejectedWithoutSideEffects(null)
    }

    @Test
    fun `blank address is rejected with settings message without activating refresh`() {
        assertRejectedWithoutSideEffects("  \t ")
    }

    @Test
    fun `non MAC address is rejected without activating refresh`() {
        assertRejectedWithoutSideEffects("selected-scale")
        assertRejectedWithoutSideEffects("AA:BB:CC:DD:EE")
        assertRejectedWithoutSideEffects("AA:BB:CC:DD:EE:GG")
    }

    @Test
    fun `valid address is trimmed before refresh starts`() {
        assertEquals(
            ScaleRefreshPreflightResult.Ready("AA:BB:CC:DD:EE:FF"),
            scaleRefreshPreflight("  AA:BB:CC:DD:EE:FF\n"),
        )
        val start = beginScaleRefresh(
            address = "  AA:BB:CC:DD:EE:FF\n",
            coordinator = coordinator,
            showMessage = messages::add,
        )

        requireNotNull(start)
        assertEquals("AA:BB:CC:DD:EE:FF", start.address)
        assertTrue(start.address.isNotEmpty())
        assertEquals(listOf(true), refreshingStates)
        assertEquals(emptyList<String>(), messages)

        coordinator.complete(start.operation)
    }

    @Test
    fun `only selected scale address is accepted case insensitively`() {
        assertTrue(isSelectedScaleAddress("AA:BB:CC:DD:EE:FF", "aa:bb:cc:dd:ee:ff"))
        assertTrue(isSelectedScaleAddress("AA:BB:CC:DD:EE:FF", " AA:BB:CC:DD:EE:FF "))
        assertEquals(
            false,
            isSelectedScaleAddress("AA:BB:CC:DD:EE:FF", "11:22:33:44:55:66"),
        )
    }

    @Test
    fun `repeated gesture starts exactly one scanner operation`() {
        var scannerStarts = 0
        fun requestRefresh() {
            beginScaleRefresh(
                address = "AA:BB:CC:DD:EE:FF",
                coordinator = coordinator,
                showMessage = messages::add,
            ) ?: return
            scannerStarts++
        }

        requestRefresh()
        requestRefresh()

        assertEquals(1, scannerStarts)
        assertEquals(listOf(true), refreshingStates)
        assertEquals(0, scannerStops)
        assertEquals(0, automaticScanRestores)
        assertEquals(emptyList<String>(), messages)
    }

    @Test
    fun `stable measurement completes refresh and cancels timeout`() {
        val operation = start()
        coordinator.attachTimeout(operation) { timeoutCancellations++ }

        coordinator.complete(operation)

        assertFinished()
        assertEquals(1, timeoutCancellations)
        assertEquals(emptyList<String>(), messages)
    }

    @Test
    fun `asynchronous scanner error completes refresh and exposes its message`() {
        val operation = start()
        coordinator.attachTimeout(operation) { timeoutCancellations++ }

        coordinator.fail(operation, "Ошибка BLE-сканирования: 2")

        assertFinished()
        assertEquals(1, timeoutCancellations)
        assertEquals(listOf("Ошибка BLE-сканирования: 2"), messages)
    }

    @Test
    fun `timeout stops direct scan restores automatic scan and reports unavailable scale`() {
        val operation = start()
        coordinator.attachTimeout(operation) { timeoutCancellations++ }

        coordinator.timeout(operation)

        assertFinished()
        assertEquals(1, timeoutCancellations)
        assertEquals(listOf(SCALE_REFRESH_UNAVAILABLE_MESSAGE), messages)
    }

    @Test
    fun `clear cancels operation without user feedback`() {
        val operation = start()
        coordinator.attachTimeout(operation) { timeoutCancellations++ }

        coordinator.clear()

        assertFinished()
        assertEquals(1, timeoutCancellations)
        assertEquals(emptyList<String>(), messages)
    }

    @Test
    fun `start error finishes before timer and late attachment is cancelled`() {
        val operation = start()
        coordinator.fail(operation, "Bluetooth выключен")

        coordinator.attachTimeout(operation) { timeoutCancellations++ }

        assertFinished()
        assertEquals(1, timeoutCancellations)
        assertEquals(listOf("Bluetooth выключен"), messages)
    }

    @Test
    fun `duplicate terminal events restore scanning exactly once`() {
        val operation = start()
        coordinator.attachTimeout(operation) { timeoutCancellations++ }

        coordinator.complete(operation)
        coordinator.fail(operation, "late error")
        coordinator.timeout(operation)

        assertFinished()
        assertEquals(1, timeoutCancellations)
        assertEquals(emptyList<String>(), messages)
    }

    @Test
    fun `events and timeout attachment from prior operation cannot finish replacement`() {
        val prior = start()
        coordinator.complete(prior)
        val replacement = start()

        coordinator.attachTimeout(prior) { timeoutCancellations++ }
        coordinator.fail(prior, "stale error")
        coordinator.timeout(prior)

        assertEquals(listOf(true, false, true), refreshingStates)
        assertEquals(1, scannerStops)
        assertEquals(1, automaticScanRestores)
        assertEquals(1, timeoutCancellations)
        assertEquals(emptyList<String>(), messages)

        coordinator.complete(replacement)

        assertEquals(listOf(true, false, true, false), refreshingStates)
        assertEquals(2, scannerStops)
        assertEquals(2, automaticScanRestores)
    }

    private fun start(): ScaleRefreshCoordinator.OperationToken =
        requireNotNull(coordinator.start())

    private fun assertRejectedWithoutSideEffects(address: String?) {
        val initialMessageCount = messages.size
        assertEquals(
            ScaleRefreshPreflightResult.Rejected(SCALE_REFRESH_SCALE_REQUIRED_MESSAGE),
            scaleRefreshPreflight(address),
        )
        var timeoutCreations = 0
        val start = beginScaleRefresh(
            address = address,
            coordinator = coordinator,
            showMessage = messages::add,
        )
        if (start != null) {
            timeoutCreations++
            coordinator.attachTimeout(start.operation) { }
        }

        assertNull(start)
        assertEquals(initialMessageCount + 1, messages.size)
        assertEquals(SCALE_REFRESH_SCALE_REQUIRED_MESSAGE, messages.last())
        assertEquals("Сначала выберите весы в настройках", messages.last())
        assertEquals(emptyList<Boolean>(), refreshingStates)
        assertEquals(0, timeoutCreations)
        assertEquals(0, scannerStops)
        assertEquals(0, automaticScanRestores)
    }

    private fun assertFinished() {
        assertEquals(listOf(true, false), refreshingStates)
        assertEquals(1, scannerStops)
        assertEquals(1, automaticScanRestores)
    }
}
