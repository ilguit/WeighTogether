package com.palixander.scalesync.data

import androidx.room.withTransaction
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Serializes profile-photo reference changes across every owner type.
 *
 * Paths an operation intends to install are leased before it waits for the mutation lock. This is
 * important for swaps: an account changing A -> B must not delete A while a concurrently-started
 * pet update is waiting to change B -> A. Filesystem cleanup runs after the Room transaction has
 * committed, but while later reference mutations are still excluded.
 */
class ProfilePhotoReferenceCoordinator(
    private val database: AppDatabase,
    private val lifecycle: ProfilePhotoLifecycle = ProfilePhotoLifecycle.None,
) {
    private val operationMutex = Mutex()
    private val leaseMutex = Mutex()
    private val leasedPaths = mutableMapOf<String, Int>()
    private val deletionCandidates = linkedSetOf<String>()

    suspend fun <T> mutate(
        retainedPhotoPaths: Set<String>,
        mutation: suspend () -> ProfilePhotoMutation<T>,
    ): T {
        val retained = retainedPhotoPaths.filterTo(linkedSetOf(), String::isNotBlank)
        lease(retained)
        var reconciliationStarted = false
        var failure: Throwable? = null
        try {
            return operationMutex.withLock {
                val result = mutation()
                deletionCandidates += result.dereferencedPhotoPaths.filter(String::isNotBlank)
                reconciliationStarted = true
                reconcileLocked()
                result.value
            }
        } catch (caught: Throwable) {
            failure = caught
            throw caught
        } finally {
            withContext(NonCancellable) {
                release(retained)
                if (!reconciliationStarted) {
                    try {
                        // A failed/cancelled operation may have been leasing a candidate queued by
                        // an earlier mutation. Reconsider it after releasing this operation's lease.
                        operationMutex.withLock { reconcileLocked() }
                    } catch (cleanupFailure: Throwable) {
                        failure?.addSuppressed(cleanupFailure) ?: throw cleanupFailure
                    }
                }
            }
        }
    }

    private suspend fun lease(paths: Set<String>) = leaseMutex.withLock {
        paths.forEach { path -> leasedPaths[path] = leasedPaths.getOrDefault(path, 0) + 1 }
    }

    private suspend fun release(paths: Set<String>) = leaseMutex.withLock {
        paths.forEach { path ->
            val remaining = requireNotNull(leasedPaths[path]) - 1
            if (remaining == 0) leasedPaths.remove(path) else leasedPaths[path] = remaining
        }
    }

    private suspend fun reconcileLocked() {
        val leased = leaseMutex.withLock { leasedPaths.keys.toSet() }
        val iterator = deletionCandidates.iterator()
        while (iterator.hasNext()) {
            val path = iterator.next()
            if (path in leased || isReferenced(path)) continue
            lifecycle.onPhotoDereferenced(path)
            iterator.remove()
        }
    }

    private suspend fun isReferenced(path: String): Boolean = database.withTransaction {
        database.accountDao().countPhotoReferences(path) > 0 ||
            database.petDao().countPhotoReferences(path) > 0
    }
}

data class ProfilePhotoMutation<T>(
    val value: T,
    val dereferencedPhotoPaths: Set<String> = emptySet(),
)
