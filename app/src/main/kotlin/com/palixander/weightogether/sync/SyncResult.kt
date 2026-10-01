package com.palixander.weightogether.sync

sealed interface SyncResult {
    data object Success : SyncResult
    data class Disabled(val message: String) : SyncResult
    data class Retryable(val message: String) : SyncResult
    data class Blocked(val message: String) : SyncResult
}
