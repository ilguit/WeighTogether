package com.palixander.weightogether

import com.palixander.weightogether.core.RawScaleMeasurement
import java.time.Instant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundScaleReadingContractTest {
    @Test
    fun `manual path reports valid transient synchronously and does not complete`() {
        val observed = mutableListOf<RawScaleMeasurement>()
        val transient = raw(isStable = false)

        val shouldComplete = shouldProcessForegroundScaleReading(transient) { observed += it }

        assertFalse(shouldComplete)
        assertTrue(observed.single() === transient)
    }

    @Test
    fun `refresh path only reports selected device transient`() {
        val observed = mutableListOf<RawScaleMeasurement>()
        val otherDevice = raw(deviceAddress = "11:22:33:44:55:66", isStable = false)

        assertFalse(
            shouldProcessForegroundScaleReading(otherDevice, SELECTED_ADDRESS, observed::add),
        )
        assertTrue(observed.isEmpty())

        val selected = otherDevice.copy(deviceAddress = SELECTED_ADDRESS.lowercase())
        assertFalse(
            shouldProcessForegroundScaleReading(selected, SELECTED_ADDRESS, observed::add),
        )
        assertTrue(observed.single() === selected)
    }

    @Test
    fun `stable invalid noise and invalid transient do not notify gate`() {
        val observed = mutableListOf<RawScaleMeasurement>()

        assertFalse(shouldProcessForegroundScaleReading(raw(weightKg = 0.0), observeTransient = observed::add))
        assertFalse(
            shouldProcessForegroundScaleReading(
                raw(weightKg = 301.0, isStable = false),
                observeTransient = observed::add,
            ),
        )

        assertTrue(observed.isEmpty())
    }

    @Test
    fun `valid stable selected reading proceeds without transient notification`() {
        val observed = mutableListOf<RawScaleMeasurement>()

        assertTrue(
            shouldProcessForegroundScaleReading(
                raw(),
                selectedAddress = SELECTED_ADDRESS,
                observeTransient = observed::add,
            ),
        )
        assertTrue(observed.isEmpty())
    }

    private fun raw(
        deviceAddress: String = SELECTED_ADDRESS,
        weightKg: Double = 70.0,
        isStable: Boolean = true,
    ) = RawScaleMeasurement(
        deviceAddress = deviceAddress,
        measuredAt = Instant.parse("2026-08-30T10:00:00Z"),
        weightKg = weightKg,
        impedanceOhm = 0,
        isStable = isStable,
        hasImpedance = false,
        rawPayload = byteArrayOf(1),
    )

    companion object {
        private const val SELECTED_ADDRESS = "AA:BB:CC:DD:EE:FF"
    }
}
