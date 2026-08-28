package com.palixander.scalesync

import com.palixander.scalesync.sync.HuaweiHealthGateway
import com.palixander.scalesync.sync.HuaweiPermissionCheckResult
import com.palixander.scalesync.sync.MeasurementSyncPayload
import com.palixander.scalesync.sync.SyncResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HuaweiAuthorizationControllerTest {
    @Test
    fun `personal build is unavailable and never calls SDK check`() = runBlocking {
        val gateway = FakeHuaweiGateway(isAvailableInBuild = false, isConfigured = false)
        val states = mutableListOf<HuaweiIntegrationStatus>()

        controller(gateway).refresh { states += it.status }

        assertEquals(listOf(HuaweiIntegrationStatus.UNAVAILABLE_IN_BUILD), states)
        assertEquals(0, gateway.checkCalls)
    }

    @Test
    fun `unconfigured enterprise build requests configuration and never calls SDK check`() =
        runBlocking {
            val gateway = FakeHuaweiGateway(isAvailableInBuild = true, isConfigured = false)
            val states = mutableListOf<HuaweiIntegrationStatus>()

            controller(gateway).refresh { states += it.status }

            assertEquals(listOf(HuaweiIntegrationStatus.CONFIGURATION_REQUIRED), states)
            assertEquals(0, gateway.checkCalls)
        }

    @Test
    fun `refresh publishes checking before denied permission`() = runBlocking {
        val gateway = FakeHuaweiGateway(checkResult = HuaweiPermissionCheckResult.NOT_AUTHORIZED)
        val states = mutableListOf<HuaweiIntegrationStatus>()

        controller(gateway).refresh { states += it.status }

        assertEquals(
            listOf(
                HuaweiIntegrationStatus.CHECKING,
                HuaweiIntegrationStatus.AUTHORIZATION_REQUIRED,
            ),
            states,
        )
    }

    @Test
    fun `passive granted permission never restarts queue`() = runBlocking {
        val gateway = FakeHuaweiGateway(checkResult = HuaweiPermissionCheckResult.AUTHORIZED)
        var queueRetries = 0

        val result = controller(gateway) { queueRetries++ }.refresh {}

        assertEquals(HuaweiIntegrationStatus.AUTHORIZED, result.status)
        assertEquals(0, queueRetries)
    }

    @Test
    fun `SDK failure becomes check failed and never restarts queue`() = runBlocking {
        val gateway = FakeHuaweiGateway(checkFailure = IllegalStateException("SDK failed"))
        var queueRetries = 0

        val result = controller(gateway) { queueRetries++ }.refresh {}

        assertEquals(HuaweiIntegrationStatus.CHECK_FAILED, result.status)
        assertEquals(0, queueRetries)
    }

    @Test
    fun `authorize always rechecks and queue waits for confirmed permission`() = runBlocking {
        val deniedGateway = FakeHuaweiGateway(
            authorizeResult = SyncResult.Success,
            checkResult = HuaweiPermissionCheckResult.NOT_AUTHORIZED,
        )
        var deniedQueueRetries = 0
        val deniedStates = mutableListOf<HuaweiIntegrationStatus>()
        val denied = controller(deniedGateway) { deniedQueueRetries++ }.authorize {
            deniedStates += it.status
        }

        assertEquals(listOf("authorize", "check"), deniedGateway.calls)
        assertEquals(
            listOf(
                HuaweiIntegrationStatus.CHECKING,
                HuaweiIntegrationStatus.AUTHORIZATION_REQUIRED,
            ),
            deniedStates,
        )
        assertEquals(HuaweiIntegrationStatus.AUTHORIZATION_REQUIRED, denied.confirmedState.status)
        assertEquals(0, deniedQueueRetries)

        val grantedGateway = FakeHuaweiGateway(
            authorizeResult = SyncResult.Success,
            checkResult = HuaweiPermissionCheckResult.AUTHORIZED,
        )
        var grantedQueueRetries = 0
        val granted = controller(grantedGateway) { grantedQueueRetries++ }.authorize {}

        assertEquals(listOf("authorize", "check"), grantedGateway.calls)
        assertEquals(HuaweiIntegrationStatus.AUTHORIZED, granted.confirmedState.status)
        assertEquals(1, grantedQueueRetries)
    }

    @Test
    fun `explicit gateway check failure maps to check error`() = runBlocking {
        val gateway = FakeHuaweiGateway(checkResult = HuaweiPermissionCheckResult.CHECK_FAILED)

        val state = controller(gateway).refresh {}

        assertEquals(HuaweiIntegrationStatus.CHECK_FAILED, state.status)
        assertTrue(gateway.checkCalls == 1)
    }

    @Test
    fun `synchronous authorization failure still rechecks actual permission`() = runBlocking {
        val gateway = FakeHuaweiGateway(
            authorizeFailure = IllegalStateException("SDK setup failed"),
            checkResult = HuaweiPermissionCheckResult.NOT_AUTHORIZED,
        )
        var queueRetries = 0

        val attempt = controller(gateway) { queueRetries++ }.authorize {}

        assertEquals(listOf("authorize", "check"), gateway.calls)
        assertTrue(attempt.requestResult is SyncResult.Retryable)
        assertEquals(
            HuaweiIntegrationStatus.AUTHORIZATION_REQUIRED,
            attempt.confirmedState.status,
        )
        assertEquals(0, queueRetries)
    }

    private fun controller(
        gateway: FakeHuaweiGateway,
        retryQueue: suspend () -> Unit = {},
    ) = HuaweiAuthorizationController(gateway, retryQueue)

    private class FakeHuaweiGateway(
        override val isAvailableInBuild: Boolean = true,
        override val isConfigured: Boolean = true,
        private val authorizeResult: SyncResult = SyncResult.Success,
        private val authorizeFailure: Throwable? = null,
        private val checkResult: HuaweiPermissionCheckResult =
            HuaweiPermissionCheckResult.NOT_AUTHORIZED,
        private val checkFailure: Throwable? = null,
    ) : HuaweiHealthGateway {
        val calls = mutableListOf<String>()
        var checkCalls = 0
            private set

        override suspend fun checkWriteWeightPermission(): HuaweiPermissionCheckResult {
            calls += "check"
            checkCalls++
            checkFailure?.let { throw it }
            return checkResult
        }

        override suspend fun authorize(): SyncResult {
            calls += "authorize"
            authorizeFailure?.let { throw it }
            return authorizeResult
        }

        override suspend fun write(payload: MeasurementSyncPayload): SyncResult = SyncResult.Success
    }
}
