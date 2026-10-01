package com.palixander.weightogether.sync

import com.palixander.weightogether.data.SyncStatus

data class SyncStateUpdate(
    val status: SyncStatus,
    val error: String?,
    val shouldRetry: Boolean,
)

fun SyncResult.toStateUpdate(): SyncStateUpdate = when (this) {
    SyncResult.Success -> SyncStateUpdate(SyncStatus.SYNCED, null, false)
    is SyncResult.Disabled -> SyncStateUpdate(SyncStatus.DISABLED, message, false)
    is SyncResult.Blocked -> SyncStateUpdate(SyncStatus.BLOCKED, message, false)
    is SyncResult.Retryable -> SyncStateUpdate(SyncStatus.PENDING, message, true)
}
