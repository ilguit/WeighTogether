package com.palixander.weightogether.data

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
        var failure: Throwable? = null
        try {
            return operationMutex.withLock {
                val result = mutation()
                deletionCandidates += result.dereferencedPhotoPaths.filter(String::isNotBlank)
                result.value
            }
        } catch (caught: Throwable) {
            failure = caught
            throw caught
        } finally {
            withContext(NonCancellable) {
                var cleanupFailure: Throwable? = null
                try {
                    release(retained)
                } catch (caught: Throwable) {
                    cleanupFailure = caught
                }
                try {
                    // Always retry queued candidates after releasing this operation's lease. The
                    // candidate is removed only after its lifecycle callback succeeds, so a failed
                    // or cancelled caller leaves cleanup work available to the next mutation.
                    operationMutex.withLock { reconcileLocked() }
                } catch (caught: Throwable) {
                    cleanupFailure?.addSuppressed(caught) ?: run { cleanupFailure = caught }
                }
                cleanupFailure?.let { caught ->
                    failure?.addSuppressed(caught) ?: throw caught
                }
            }
        }
    }

    /** Keeps referenced files alive while export copies one database snapshot to private staging. */
    suspend fun <T> withStableReferences(block: suspend () -> T): T = operationMutex.withLock { block() }

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
        val iterator = deletionCandidates.iterator()
        while (iterator.hasNext()) {
            val path = iterator.next()
            if (isReferenced(path)) continue
            val deleted = leaseMutex.withLock {
                if (path in leasedPaths) {
                    false
                } else {
                    // Keep the lease decision stable until deletion finishes. A mutation trying to
                    // install this path either leased it first or waits until this cleanup is done.
                    lifecycle.onPhotoDereferenced(path)
                    true
                }
            }
            if (deleted) iterator.remove()
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
