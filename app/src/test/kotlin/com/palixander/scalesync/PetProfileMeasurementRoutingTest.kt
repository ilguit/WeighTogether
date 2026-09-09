package com.palixander.scalesync

import com.palixander.scalesync.domain.Pet
import com.palixander.scalesync.domain.PetId
import com.palixander.scalesync.domain.PetSpecies
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun existingPetSelectionDismissesDialogBeforeOpeningManualWeight() {
        val states = mutableListOf<PetMeasurementUiState>()
        val coordinator = coordinator(states)
        coordinator.showSelection()
        var stateWhenManualOpened: PetMeasurementUiState? = null

        startPetProfileMeasurement(
            pet = pet,
            scaleAddress = null,
            dismissPetMeasurement = coordinator::cancel,
            openManualWeight = { stateWhenManualOpened = states.last() },
            startBleMeasurement = { error("BLE must not start") },
        )

        assertEquals(PetMeasurementUiState.Idle, stateWhenManualOpened)
        assertEquals(PetMeasurementUiState.Idle, states.last())
    }

    @Test
    fun newlyCreatedPetWithoutScaleUsesSameManualOrchestration() {
        val states = mutableListOf<PetMeasurementUiState>()
        val coordinator = coordinator(states)
        coordinator.showCreating()
        val events = mutableListOf<String>()

        startPetProfileMeasurement(
            pet = pet,
            scaleAddress = null,
            dismissPetMeasurement = {
                coordinator.cancel()
                events += "dismiss:${states.last()::class.simpleName}"
            },
            openManualWeight = { events += "manual:${it.id.value}" },
            startBleMeasurement = { events += "ble" },
        )

        assertEquals(listOf("dismiss:Idle", "manual:pet-1"), events)
        assertTrue(states.last() is PetMeasurementUiState.Idle)
    }

    @Test
    fun newlyCreatedPetWithScaleStartsBleWithoutDismissingDialog() {
        val events = mutableListOf<String>()

        startPetProfileMeasurement(
            pet = pet,
            scaleAddress = "AA:BB:CC:DD:EE:FF",
            dismissPetMeasurement = { events += "dismiss" },
            openManualWeight = { events += "manual" },
            startBleMeasurement = { events += "ble:${it.value}" },
        )

        assertEquals(listOf("ble:pet-1"), events)
    }

    private fun coordinator(states: MutableList<PetMeasurementUiState>) =
        PetMeasurementCoordinator(
            setState = states::add,
            stopScanner = {},
            restoreAutomaticScanning = {},
            showMessage = {},
            monotonicNowNanos = { 0L },
        )

    private val pet = Pet(
        id = PetId("pet-1"),
        displayName = "Мурка",
        species = PetSpecies.CAT,
        createdAt = Instant.parse("2026-09-08T00:00:00Z"),
        updatedAt = Instant.parse("2026-09-08T00:00:00Z"),
    )
}
