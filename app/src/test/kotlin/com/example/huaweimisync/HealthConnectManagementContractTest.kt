package com.example.huaweimisync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthConnectManagementContractTest {
    @Test
    fun `foreground and recreation refresh never activate a connected opt-out`() {
        assertFalse(
            shouldActivateHealthConnectAfterPermissionRefresh(
                isConnected = true,
                explicitAuthorization = false,
            ),
        )
    }

    @Test
    fun `explicit reconnect activates a connected destination`() {
        assertTrue(
            shouldActivateHealthConnectAfterPermissionRefresh(
                isConnected = true,
                explicitAuthorization = true,
            ),
        )
    }

    @Test
    fun `SDK management intent is exposed when a system handler is available`() {
        val sdkIntent = Any()

        val resolved = resolveAvailableHealthConnectManagementIntent(
            createIntent = { sdkIntent },
            canResolve = { true },
        )

        assertSame(sdkIntent, resolved)
    }

    @Test
    fun `SDK management intent is unavailable when no system handler exists`() {
        val sdkIntent = Any()

        val resolved = resolveAvailableHealthConnectManagementIntent(
            createIntent = { sdkIntent },
            canResolve = { false },
        )

        assertNull(resolved)
    }

    @Test
    fun `missing SDK management intent is unavailable without querying a handler`() {
        var resolverCalled = false

        val resolved = resolveAvailableHealthConnectManagementIntent<Any>(
            createIntent = { null },
            canResolve = {
                resolverCalled = true
                true
            },
        )

        assertNull(resolved)
        assertFalse(resolverCalled)
    }

    @Test
    fun `SDK or package manager failures make management unavailable`() {
        val sdkFailure = resolveAvailableHealthConnectManagementIntent<Any>(
            createIntent = { throw IllegalStateException("SDK failed") },
            canResolve = { true },
        )
        val resolverFailure = resolveAvailableHealthConnectManagementIntent(
            createIntent = { Any() },
            canResolve = { throw SecurityException("query denied") },
        )

        assertNull(sdkFailure)
        assertNull(resolverFailure)
    }

    @Test
    fun `resolved SDK management intent launches exactly once`() {
        val sdkIntent = Any()
        val launched = mutableListOf<Any>()

        val opened = launchHealthConnectManagement(sdkIntent) { launched += it }

        assertTrue(opened)
        assertEquals(listOf(sdkIntent), launched)
    }

    @Test
    fun `missing management intent is not launched`() {
        var launchCalled = false

        val opened = launchHealthConnectManagement<Any>(null) { launchCalled = true }

        assertFalse(opened)
        assertFalse(launchCalled)
    }

    @Test
    fun `launch exception is handled as management unavailable`() {
        val opened = launchHealthConnectManagement(Any()) {
            throw FakeActivityNotFoundException()
        }

        assertFalse(opened)
    }

    private class FakeActivityNotFoundException : RuntimeException()
}
