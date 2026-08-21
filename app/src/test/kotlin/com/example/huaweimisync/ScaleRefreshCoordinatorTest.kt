package com.example.huaweimisync

import org.junit.Assert.assertEquals
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
    fun `repeated gesture starts exactly one scanner operation`() {
        var scannerStarts = 0
        fun requestRefresh() {
            coordinator.start() ?: return
            scannerStarts++
        }

        requestRefresh()
        requestRefresh()

        assertEquals(1, scannerStarts)
        assertEquals(listOf(true), refreshingStates)
        assertEquals(0, scannerStops)
        assertEquals(0, automaticScanRestores)
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

    private fun assertFinished() {
        assertEquals(listOf(true, false), refreshingStates)
        assertEquals(1, scannerStops)
        assertEquals(1, automaticScanRestores)
    }
}
