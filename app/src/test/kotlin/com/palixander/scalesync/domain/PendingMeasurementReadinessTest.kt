package com.palixander.scalesync.domain

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PendingMeasurementReadinessTest {
    @Test
    fun snapshotBecomesReadyAtDeadlineWithoutRoomEmission() = runBlocking {
        val clock = MutableClock(NOW)
        val waits = DeadlineWaitProbe()
        val source = MutableStateFlow(listOf(pending("pending", NOW.plusSeconds(10))))
        val output = Channel<PendingMeasurementReadinessSnapshot>(Channel.UNLIMITED)
        val collection = launch(start = CoroutineStart.UNDISPATCHED) {
            source.withPendingMeasurementReadiness(clock, waits::await).collect(output::send)
        }

        assertEquals(NOW, output.receive().observedAt)
        val wait = waits.calls.receive()
        assertEquals(Duration.ofSeconds(10), wait.duration)

        clock.instantValue = NOW.plusSeconds(10)
        wait.release.complete(Unit)
        assertEquals(NOW.plusSeconds(10), output.receive().observedAt)

        collection.cancelAndJoin()
    }

    @Test
    fun multipleDeadlinesAreEmittedInOrder() = runBlocking {
        val clock = MutableClock(NOW)
        val waits = DeadlineWaitProbe()
        val source = MutableStateFlow(
            listOf(
                pending("later", NOW.plusSeconds(12)),
                pending("first", NOW.plusSeconds(5)),
            ),
        )
        val output = Channel<PendingMeasurementReadinessSnapshot>(Channel.UNLIMITED)
        val collection = launch(start = CoroutineStart.UNDISPATCHED) {
            source.withPendingMeasurementReadiness(clock, waits::await).collect(output::send)
        }

        assertEquals(NOW, output.receive().observedAt)
        val firstWait = waits.calls.receive()
        assertEquals(Duration.ofSeconds(5), firstWait.duration)
        clock.instantValue = NOW.plusSeconds(5)
        firstWait.release.complete(Unit)
        assertEquals(NOW.plusSeconds(5), output.receive().observedAt)

        val secondWait = waits.calls.receive()
        assertEquals(Duration.ofSeconds(7), secondWait.duration)
        clock.instantValue = NOW.plusSeconds(12)
        secondWait.release.complete(Unit)
        assertEquals(NOW.plusSeconds(12), output.receive().observedAt)

        collection.cancelAndJoin()
    }

    @Test
    fun newRoomSnapshotCancelsStaleDeadlineWait() = runBlocking {
        val clock = MutableClock(NOW)
        val waits = DeadlineWaitProbe()
        val oldPending = pending("old", NOW.plusSeconds(10))
        val newPending = pending("new", NOW.plusSeconds(20))
        val source = MutableStateFlow(listOf(oldPending))
        val output = Channel<PendingMeasurementReadinessSnapshot>(Channel.UNLIMITED)
        val collection = launch(start = CoroutineStart.UNDISPATCHED) {
            source.withPendingMeasurementReadiness(clock, waits::await).collect(output::send)
        }

        assertEquals(listOf(oldPending), output.receive().measurements)
        val staleWait = waits.calls.receive()

        source.value = listOf(newPending)
        assertEquals(listOf(newPending), output.receive().measurements)
        val currentWait = waits.calls.receive()
        assertEquals(Duration.ofSeconds(20), currentWait.duration)

        staleWait.release.complete(Unit)
        assertNull(withTimeoutOrNull(50) { output.receive() })

        clock.instantValue = NOW.plusSeconds(20)
        currentWait.release.complete(Unit)
        val due = output.receive()
        assertEquals(listOf(newPending), due.measurements)
        assertEquals(NOW.plusSeconds(20), due.observedAt)

        collection.cancelAndJoin()
    }

    private class DeadlineWaitProbe {
        val calls = Channel<DeadlineWait>(Channel.UNLIMITED)

        suspend fun await(duration: Duration) {
            val wait = DeadlineWait(duration)
            calls.send(wait)
            wait.release.await()
        }
    }

    private data class DeadlineWait(
        val duration: Duration,
        val release: CompletableDeferred<Unit> = CompletableDeferred(),
    )

    private class MutableClock(
        var instantValue: Instant,
        private val zone: ZoneId = ZoneOffset.UTC,
    ) : Clock() {
        override fun getZone(): ZoneId = zone

        override fun withZone(zone: ZoneId): Clock = MutableClock(instantValue, zone)

        override fun instant(): Instant = instantValue
    }

    companion object {
        private val NOW = Instant.parse("2026-08-23T10:00:00Z")
    }
}

private fun pending(id: String, finalizeAfter: Instant) = PendingMeasurement(
    id = PendingMeasurementId(id),
    deviceAddress = "AA:BB:CC:DD:EE:FF",
    measuredAt = finalizeAfter.minusSeconds(10),
    weightKg = 70.0,
    impedanceOhm = 500,
    isStable = true,
    hasImpedance = true,
    rawPayload = byteArrayOf(1, 2, 3),
    deduplicationHash = "hash-$id",
    enqueuedAt = finalizeAfter.minusSeconds(10),
    finalizeAfter = finalizeAfter,
)
