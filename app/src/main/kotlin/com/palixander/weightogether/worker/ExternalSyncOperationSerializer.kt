package com.palixander.weightogether.worker

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.runBlocking

/** Serializes external writes with Room exclusion/deletion and pause/resume queue rebuilds. */
class ExternalSyncOperationSerializer {
    private val mutex = Mutex()

    suspend fun <T> runExclusive(block: suspend () -> T): T = mutex.withLock { block() }

    fun <T> runExclusiveBlocking(block: () -> T): T = runBlocking {
        mutex.withLock { block() }
    }
}
