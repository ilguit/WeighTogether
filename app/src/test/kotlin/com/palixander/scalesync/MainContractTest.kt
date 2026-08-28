package com.palixander.scalesync

import com.palixander.scalesync.core.Sex
import com.palixander.scalesync.core.UserProfile
import com.palixander.scalesync.domain.PendingMeasurementId
import com.palixander.scalesync.ui.routing.PendingResolverReturnDestination
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MainContractTest {
    private val today = LocalDate.of(2026, 8, 15)

    @Test
    fun `events queued before collection are delivered in order and only once`() = runBlocking {
        val emitter = MainUiEventEmitter()

        emitter.showSnackbar("Первое")
        emitter.showSnackbar("Второе")

        assertEquals(MainUiEvent.ShowSnackbar("Первое"), emitter.events.first())
        assertEquals(MainUiEvent.ShowSnackbar("Второе"), emitter.events.first())
        assertNull(withTimeoutOrNull(50) { emitter.events.first() })
    }

    @Test
    fun `pending completion event carries addressed id and explicit return destination`() =
        runBlocking {
            val emitter = MainUiEventEmitter()
            val pendingId = PendingMeasurementId("pending")

            emitter.pendingResolutionCompleted(
                pendingId = pendingId,
                returnDestination = PendingResolverReturnDestination.PENDING_QUEUE,
            )

            assertEquals(
                MainUiEvent.PendingResolutionCompleted(
                    pendingId,
                    PendingResolverReturnDestination.PENDING_QUEUE,
                ),
                emitter.events.first(),
            )
        }

    @Test
    fun `opening editor copies the real profile using the existing date format`() {
        val profile = UserProfile(181.5, LocalDate.of(1988, 2, 29), Sex.FEMALE)
        val controller = controller()

        controller.open(profile)

        assertEquals(
            ProfileEditorUiState(
                isOpen = true,
                height = "181.5",
                birthDate = "1988-02-29",
                sex = Sex.FEMALE,
            ),
            controller.state.value,
        )
    }

    @Test
    fun `invalid profile remains in editor with validation error`() = runBlocking {
        val emitter = MainUiEventEmitter()
        val saved = mutableListOf<UserProfile>()
        val controller = controller(saved, emitter)
        controller.open(null)
        controller.updateBirthDate("15.08.1990")

        controller.save()

        assertTrue(controller.state.value.isOpen)
        assertEquals(PROFILE_FORMAT_ERROR_MESSAGE, controller.state.value.errorMessage)
        assertTrue(saved.isEmpty())
        assertEquals(
            MainUiEvent.ShowSnackbar(PROFILE_FORMAT_ERROR_MESSAGE),
            emitter.events.first(),
        )
    }

    @Test
    fun `successful save persists real fields closes editor and emits snackbar`() = runBlocking {
        val emitter = MainUiEventEmitter()
        val saved = mutableListOf<UserProfile>()
        val controller = controller(saved, emitter)
        controller.open(null)
        controller.updateHeight("176,5")
        controller.updateBirthDate("1992-11-03")
        controller.updateSex(Sex.FEMALE)

        controller.save()

        assertEquals(
            listOf(UserProfile(176.5, LocalDate.of(1992, 11, 3), Sex.FEMALE)),
            saved,
        )
        assertFalse(controller.state.value.isOpen)
        assertNull(controller.state.value.errorMessage)
        assertEquals(MainUiEvent.ShowSnackbar(PROFILE_SAVED_MESSAGE), emitter.events.first())
    }

    @Test
    fun `closing editor discards the draft`() {
        val controller = controller()
        controller.open(UserProfile(175.0, LocalDate.of(1990, 1, 1), Sex.MALE))
        controller.updateHeight("190")

        controller.close()

        assertEquals(ProfileEditorUiState(), controller.state.value)
    }

    @Test
    fun `profile age boundaries from 10 through 100 are inclusive`() {
        assertTrue(validateProfile("175", today.minusYears(10).toString(), Sex.MALE, today) is ProfileValidationResult.Valid)
        assertTrue(validateProfile("175", today.minusYears(100).toString(), Sex.MALE, today) is ProfileValidationResult.Valid)
        assertEquals(
            ProfileValidationResult.Invalid(PROFILE_AGE_ERROR_MESSAGE),
            validateProfile("175", today.minusYears(10).plusDays(1).toString(), Sex.MALE, today),
        )
        assertEquals(
            ProfileValidationResult.Invalid(PROFILE_AGE_ERROR_MESSAGE),
            validateProfile("175", today.minusYears(100).minusDays(1).toString(), Sex.MALE, today),
        )
    }

    @Test
    fun `health connect requires every mandatory permission`() {
        val required = setOf("weight", "fat", "water", "bone", "lean", "bmr")
        val partial = HealthConnectPermissionsUiState.snapshot(
            isAvailable = true,
            requiredPermissions = required,
            grantedPermissions = required - "bmr",
        )
        val complete = HealthConnectPermissionsUiState.snapshot(
            isAvailable = true,
            requiredPermissions = required,
            grantedPermissions = required + "unrelated",
        )

        assertFalse(partial.isConnected)
        assertEquals(setOf("bmr"), partial.missingPermissions)
        assertEquals(false, partial.permissionStates.getValue("bmr"))
        assertTrue(complete.isConnected)
        assertEquals(required, complete.grantedPermissions)
    }

    @Test
    fun `health connect cannot be connected when unavailable or permission check failed`() {
        val required = setOf("weight")

        assertFalse(
            HealthConnectPermissionsUiState.snapshot(false, required, required).isConnected,
        )
        assertFalse(
            HealthConnectPermissionsUiState.checkFailed(required, required).isConnected,
        )
        assertFalse(
            HealthConnectPermissionsUiState.snapshot(true, emptySet(), emptySet()).isConnected,
        )
    }

    @Test
    fun `health connect management requires available sdk and management intent`() {
        HealthConnectAvailability.values().forEach { availability ->
            listOf(false, true).forEach { managementIntentAvailable ->
                val capabilities = healthConnectIntegrationCapabilities(
                    permissions = HealthConnectPermissionsUiState(
                        availability = availability,
                        requiredPermissions = setOf("weight"),
                        grantedPermissions = setOf("weight"),
                    ),
                    managementIntentAvailable = managementIntentAvailable,
                    selectedAccountSyncEligible = true,
                )

                assertEquals(
                    "$availability with handler=$managementIntentAvailable",
                    availability == HealthConnectAvailability.AVAILABLE &&
                        managementIntentAvailable,
                    capabilities.systemManagementAvailable,
                )
            }
        }
    }

    @Test
    fun `health connect management availability is independent from selected account sync`() {
        val capabilities = healthConnectIntegrationCapabilities(
            permissions = HealthConnectPermissionsUiState.snapshot(
                isAvailable = true,
                requiredPermissions = setOf("weight", "fat"),
                grantedPermissions = setOf("weight", "fat"),
            ),
            managementIntentAvailable = true,
            selectedAccountSyncEligible = false,
        )

        assertTrue(capabilities.systemManagementAvailable)
        assertFalse(capabilities.selectedAccountSyncEligible)
        assertFalse(capabilities.selectedAccountSyncReady)
    }

    @Test
    fun `selected account sync readiness requires availability permissions and eligibility`() {
        val required = setOf("weight", "fat")
        val partialPermissions = healthConnectIntegrationCapabilities(
            permissions = HealthConnectPermissionsUiState.snapshot(
                isAvailable = true,
                requiredPermissions = required,
                grantedPermissions = setOf("weight"),
            ),
            managementIntentAvailable = true,
            selectedAccountSyncEligible = true,
        )
        val ready = healthConnectIntegrationCapabilities(
            permissions = HealthConnectPermissionsUiState.snapshot(
                isAvailable = true,
                requiredPermissions = required,
                grantedPermissions = required,
            ),
            managementIntentAvailable = true,
            selectedAccountSyncEligible = true,
        )

        assertTrue(partialPermissions.systemManagementAvailable)
        assertFalse(partialPermissions.selectedAccountSyncReady)
        assertTrue(ready.selectedAccountSyncReady)
    }

    private fun controller(
        saved: MutableList<UserProfile> = mutableListOf(),
        emitter: MainUiEventEmitter = MainUiEventEmitter(),
    ) = ProfileEditorController(
        saveProfile = saved::add,
        eventEmitter = emitter,
        today = { today },
    )
}
