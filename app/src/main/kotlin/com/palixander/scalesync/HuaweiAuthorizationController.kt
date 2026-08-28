package com.palixander.scalesync

import com.palixander.scalesync.sync.HuaweiHealthGateway
import com.palixander.scalesync.sync.HuaweiPermissionCheckResult
import com.palixander.scalesync.sync.SyncResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class HuaweiAuthorizationAttempt(
    val requestResult: SyncResult,
    val confirmedState: HuaweiIntegrationUiState,
)

/** Keeps SDK authorization checks and queue restarts in one testable state machine. */
internal class HuaweiAuthorizationController(
    private val gateway: HuaweiHealthGateway,
    private val onExplicitAuthorizationConfirmed: suspend () -> Unit,
) {
    private val operationMutex = Mutex()

    val initialState: HuaweiIntegrationUiState
        get() = HuaweiIntegrationUiState.fromGateway(
            isAvailableInBuild = gateway.isAvailableInBuild,
            isConfigured = gateway.isConfigured,
        )

    suspend fun refresh(
        publishState: (HuaweiIntegrationUiState) -> Unit,
    ): HuaweiIntegrationUiState {
        val initial = initialState
        if (initial.status != HuaweiIntegrationStatus.CHECKING) {
            publishState(initial)
            return initial
        }

        return operationMutex.withLock {
            publishState(initial)
            checkAndPublish(publishState, explicitAuthorization = false)
        }
    }

    suspend fun authorize(
        publishState: (HuaweiIntegrationUiState) -> Unit,
    ): HuaweiAuthorizationAttempt = operationMutex.withLock {
        val initial = initialState
        publishState(initial)
        val requestResult = try {
            gateway.authorize()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            SyncResult.Retryable("Авторизация Huawei Health временно недоступна")
        }
        val confirmedState = if (initial.status == HuaweiIntegrationStatus.CHECKING) {
            checkAndPublish(publishState, explicitAuthorization = true)
        } else {
            initial
        }
        HuaweiAuthorizationAttempt(requestResult, confirmedState)
    }

    private suspend fun checkAndPublish(
        publishState: (HuaweiIntegrationUiState) -> Unit,
        explicitAuthorization: Boolean,
    ): HuaweiIntegrationUiState {
        val permission = try {
            gateway.checkWriteWeightPermission()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            HuaweiPermissionCheckResult.CHECK_FAILED
        }
        val confirmed = HuaweiIntegrationUiState.fromGateway(
            isAvailableInBuild = gateway.isAvailableInBuild,
            isConfigured = gateway.isConfigured,
            permission = permission,
        )
        publishState(confirmed)
        if (explicitAuthorization && confirmed.status == HuaweiIntegrationStatus.AUTHORIZED) {
            onExplicitAuthorizationConfirmed()
        }
        return confirmed
    }
}
