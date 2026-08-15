package com.example.huaweimisync

import com.example.huaweimisync.sync.HuaweiHealthGateway
import com.example.huaweimisync.sync.HuaweiPermissionCheckResult
import com.example.huaweimisync.sync.SyncResult
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
    private val retryPendingHuawei: suspend () -> Unit,
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
            checkAndPublish(publishState)
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
            checkAndPublish(publishState)
        } else {
            initial
        }
        HuaweiAuthorizationAttempt(requestResult, confirmedState)
    }

    private suspend fun checkAndPublish(
        publishState: (HuaweiIntegrationUiState) -> Unit,
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
        if (confirmed.status == HuaweiIntegrationStatus.AUTHORIZED) {
            retryPendingHuawei()
        }
        return confirmed
    }
}
