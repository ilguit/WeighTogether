package com.palixander.scalesync

import org.junit.Assert.assertEquals
import org.junit.Test

class PetProfileMeasurementRoutingTest {
    @Test
    fun missingScaleRoutesPetProfileMeasurementToManualWeight() {
        assertEquals(
            PetProfileMeasurementStartRoute.MANUAL_WEIGHT,
            petProfileMeasurementStartRoute(scaleAddress = null),
        )
    }

    @Test
    fun selectedScalePreservesBlePetMeasurement() {
        assertEquals(
            PetProfileMeasurementStartRoute.BLE,
            petProfileMeasurementStartRoute(scaleAddress = "AA:BB:CC:DD:EE:FF"),
        )
    }
}
