package com.example.huaweimisync.sync

import com.example.huaweimisync.data.MeasurementEntity

interface HuaweiHealthGateway {
    val isAvailableInBuild: Boolean
    val isConfigured: Boolean

    suspend fun authorize(): SyncResult
    suspend fun write(measurement: MeasurementEntity): SyncResult
}
