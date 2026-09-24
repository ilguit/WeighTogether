package com.palixander.scalesync.measurements

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalDateRefreshTest {
    @Test
    fun `next refresh is the next local midnight across a short DST day`() {
        val zoneId = ZoneId.of("Europe/Berlin")
        val localDayStart = Instant.parse("2026-03-28T23:00:00Z")

        val nextDayStart = nextLocalDayStart(localDayStart, zoneId)

        assertEquals(Instant.parse("2026-03-29T22:00:00Z"), nextDayStart)
        assertEquals(Duration.ofHours(23), Duration.between(localDayStart, nextDayStart))
    }

    @Test
    fun `date flow waits exactly to midnight and then emits the new zoned date`() = runBlocking {
        val zoneId = ZoneId.of("Asia/Yekaterinburg")
        val clock = MutableClock(
            instant = Instant.parse("2026-08-21T18:59:59.250Z"),
            zone = zoneId,
        )
        val waits = mutableListOf<Duration>()

        val dates = currentLocalDates(
            zoneId = zoneId,
            clock = clock,
            awaitDuration = { duration ->
                waits += duration
                clock.advance(duration)
            },
        ).take(2).toList()

        assertEquals(listOf(LocalDate.of(2026, 8, 21), LocalDate.of(2026, 8, 22)), dates)
        assertEquals(listOf(Duration.ofMillis(750)), waits)
    }

    @Test
    fun `date emission rebuilds chart viewport without dropping full history`() = runBlocking {
        val zoneId = ZoneOffset.UTC
        val boundaryMeasurement = refreshMeasurement(
            id = "old-boundary",
            measuredAt = Instant.parse("2026-08-08T12:00:00Z").epochSecond,
        )
        val dates = Channel<LocalDate>(Channel.RENDEZVOUS)
        val states = Channel<HomeKgChartUiState>(Channel.UNLIMITED)
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            homeChartRefreshInputs(
                measurements = flowOf(listOf(boundaryMeasurement)),
                persistedActiveSeriesKeys = flowOf(null),
                currentDates = dates.receiveAsFlow(),
            ).collect { input ->
                states.send(
                    buildHomeKgChartUiState(
                        measurements = input.measurements,
                        persistedActiveSeriesKeys = input.persistedActiveSeriesKeys,
                        currentDate = input.currentDate,
                        zoneId = zoneId,
                    ),
                )
            }
        }

        dates.send(LocalDate.of(2026, 8, 21))
        val beforeMidnight = states.receive()
        dates.send(LocalDate.of(2026, 8, 22))
        val afterMidnight = states.receive()
        collector.cancelAndJoin()

        assertEquals(LocalDate.of(2026, 8, 8), beforeMidnight.period.startDate)
        assertEquals(listOf("old-boundary"), beforeMidnight.series.first().points.map { it.measurementId })
        assertEquals(LocalDate.of(2026, 8, 9), afterMidnight.period.startDate)
        assertEquals(listOf("old-boundary"), afterMidnight.series.first().points.map { it.measurementId })
    }

    @Test
    fun `cancelling collector cancels the pending midnight wait`() = runBlocking {
        val waitStarted = CompletableDeferred<Unit>()
        val waitCancelled = CompletableDeferred<Unit>()
        val firstDate = CompletableDeferred<LocalDate>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            currentLocalDates(
                zoneId = ZoneOffset.UTC,
                clock = Clock.fixed(Instant.parse("2026-08-21T12:00:00Z"), ZoneOffset.UTC),
                awaitDuration = {
                    waitStarted.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        waitCancelled.complete(Unit)
                    }
                },
            ).collect { firstDate.complete(it) }
        }

        assertEquals(LocalDate.of(2026, 8, 21), firstDate.await())
        waitStarted.await()
        collector.cancelAndJoin()

        assertTrue(waitCancelled.isCompleted)
    }
}

private fun refreshMeasurement(id: String, measuredAt: Long) = MeasurementUiItem(
    id = id,
    measuredAtEpochSecond = measuredAt,
    values = MeasurementUiValues(
        weightKg = 72.0,
        impedanceOhm = null,
        bmi = null,
        bodyFatPercent = null,
        bodyFatMassKg = null,
        waterPercent = null,
        waterMassKg = null,
        muscleMassKg = null,
        skeletalMuscleMassKg = null,
        boneMassKg = null,
        proteinPercent = null,
        proteinMassKg = null,
        visceralFatLevel = null,
        basalMetabolicRateKcal = null,
        metabolicAge = null,
        leanBodyMassKg = null,
    ),
    sync = MeasurementSyncPresentation(
        state = MeasurementSyncPresentationState.SYNCED,
        directions = emptyList(),
        canRetry = false,
    ),
    type = MeasurementUiType.WEIGHT_ONLY,
)

private class MutableClock(
    private var instant: Instant,
    private val zone: ZoneId,
) : Clock() {
    override fun getZone(): ZoneId = zone

    override fun withZone(zone: ZoneId): Clock = MutableClock(instant, zone)

    override fun instant(): Instant = instant

    fun advance(duration: Duration) {
        instant = instant.plus(duration)
    }
}
