package com.palixander.weightogether.domain

import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.transformLatest

/** A durable pending snapshot paired with the time used to project its UI readiness. */
data class PendingMeasurementReadinessSnapshot(
    val measurements: List<PendingMeasurement>,
    val observedAt: Instant,
)

/**
 * Re-emits an unchanged Room snapshot when its next aggregate becomes ready for presentation.
 * A newer Room snapshot cancels the previous deadline wait through [transformLatest].
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun Flow<List<PendingMeasurement>>.withPendingMeasurementReadiness(
    clock: Clock = Clock.systemUTC(),
    await: suspend (Duration) -> Unit = ::awaitPendingMeasurementDeadline,
): Flow<PendingMeasurementReadinessSnapshot> = transformLatest { measurements ->
    var observedAt = clock.instant()
    emit(PendingMeasurementReadinessSnapshot(measurements, observedAt))

    while (true) {
        val nextDeadline = measurements.asSequence()
            .map(PendingMeasurement::finalizeAfter)
            .filter { it.isAfter(observedAt) }
            .minOrNull()
            ?: break

        do {
            await(Duration.between(observedAt, nextDeadline))
            observedAt = clock.instant()
        } while (observedAt.isBefore(nextDeadline))

        emit(PendingMeasurementReadinessSnapshot(measurements, observedAt))
    }
}

private suspend fun awaitPendingMeasurementDeadline(duration: Duration) {
    // A positive minimum also protects against a sub-millisecond early wake-up becoming a busy loop.
    delay(duration.toMillis().coerceAtLeast(1L))
}
