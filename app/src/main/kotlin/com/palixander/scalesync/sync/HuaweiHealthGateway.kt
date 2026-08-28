package com.palixander.scalesync.sync

enum class HuaweiPermissionCheckResult {
    AUTHORIZED,
    NOT_AUTHORIZED,
    CHECK_FAILED,
    UNAVAILABLE,
}

interface HuaweiHealthGateway {
    val isAvailableInBuild: Boolean
    val isConfigured: Boolean

    suspend fun checkWriteWeightPermission(): HuaweiPermissionCheckResult
    suspend fun authorize(): SyncResult
    suspend fun write(payload: MeasurementSyncPayload): SyncResult
}
