package com.example.huaweimisync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthConnectManagementContractTest {
    @Test
    fun `android 14 starts with app-specific permissions then preserves both fallbacks`() {
        assertEquals(
            listOf(
                HealthConnectManagementTarget.APP_PERMISSION_MANAGEMENT,
                HealthConnectManagementTarget.GENERAL_HEALTH_CONNECT_SETTINGS,
                HealthConnectManagementTarget.APPLICATION_SETTINGS,
            ),
            healthConnectManagementTargets(sdkInt = 34),
        )
    }

    @Test
    fun `older android starts with general Health Connect settings then app settings`() {
        assertEquals(
            listOf(
                HealthConnectManagementTarget.GENERAL_HEALTH_CONNECT_SETTINGS,
                HealthConnectManagementTarget.APPLICATION_SETTINGS,
            ),
            healthConnectManagementTargets(sdkInt = 33),
        )
    }

    @Test
    fun `ActivityNotFound falls through in order until app settings opens`() {
        val attempts = mutableListOf<HealthConnectManagementTarget>()

        val opened = launchFirstAvailableActivity(
            targets = healthConnectManagementTargets(sdkInt = 34),
            launch = { target ->
                attempts += target
                if (target != HealthConnectManagementTarget.APPLICATION_SETTINGS) {
                    throw FakeActivityNotFoundException()
                }
            },
            isActivityNotFound = { error -> error is FakeActivityNotFoundException },
        )

        assertTrue(opened)
        assertEquals(
            listOf(
                HealthConnectManagementTarget.APP_PERMISSION_MANAGEMENT,
                HealthConnectManagementTarget.GENERAL_HEALTH_CONNECT_SETTINGS,
                HealthConnectManagementTarget.APPLICATION_SETTINGS,
            ),
            attempts,
        )
    }

    @Test
    fun `all missing activities report that management could not be opened`() {
        val attempts = mutableListOf<HealthConnectManagementTarget>()

        val opened = launchFirstAvailableActivity(
            targets = healthConnectManagementTargets(sdkInt = 33),
            launch = { target ->
                attempts += target
                throw FakeActivityNotFoundException()
            },
            isActivityNotFound = { error -> error is FakeActivityNotFoundException },
        )

        assertFalse(opened)
        assertEquals(
            listOf(
                HealthConnectManagementTarget.GENERAL_HEALTH_CONNECT_SETTINGS,
                HealthConnectManagementTarget.APPLICATION_SETTINGS,
            ),
            attempts,
        )
    }

    @Test
    fun `unexpected launch failures are not swallowed as ActivityNotFound`() {
        val failure = IllegalStateException("broken launcher")

        val thrown = assertThrows(IllegalStateException::class.java) {
            launchFirstAvailableActivity(
                targets = listOf(HealthConnectManagementTarget.APPLICATION_SETTINGS),
                launch = { throw failure },
                isActivityNotFound = { error -> error is FakeActivityNotFoundException },
            )
        }

        assertEquals(failure, thrown)
    }

    private class FakeActivityNotFoundException : RuntimeException()
}
