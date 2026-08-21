package com.example.huaweimisync.worker

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes external writes with Room exclusion/deletion and pause/resume queue rebuilds. */
class ExternalSyncOperationSerializer {
    private val mutex = Mutex()

    suspend fun <T> runExclusive(block: suspend () -> T): T = mutex.withLock { block() }
}
