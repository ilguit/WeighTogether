package com.example.huaweimisync.worker

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes Room exclusion/deletion with queue rebuilds performed by pause and resume. */
class ExternalSyncOperationSerializer {
    private val mutex = Mutex()

    suspend fun <T> runExclusive(block: suspend () -> T): T = mutex.withLock { block() }
}
