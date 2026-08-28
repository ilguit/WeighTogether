package com.palixander.scalesync.sync

import android.content.Context
fun createHuaweiHealthGateway(context: Context): HuaweiHealthGateway =
    PersonalHuaweiHealthGateway

private data object PersonalHuaweiHealthGateway : HuaweiHealthGateway {
    override val isAvailableInBuild: Boolean = false
    override val isConfigured: Boolean = false

    override suspend fun checkWriteWeightPermission(): HuaweiPermissionCheckResult =
        HuaweiPermissionCheckResult.UNAVAILABLE

    override suspend fun authorize(): SyncResult = disabled()

    override suspend fun write(payload: MeasurementSyncPayload): SyncResult = disabled()

    private fun disabled() = SyncResult.Disabled(
        "Прямая запись Huawei отключена: Extended Health Service Kit требует enterprise-доступ",
    )
}
