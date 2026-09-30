package com.palixander.scalesync.charts

import com.palixander.scalesync.data.MeasurementEntity
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.*
import org.junit.Test

class AnalyticalChartCalculationsTest {
    private val utc = ZoneOffset.UTC
    private val start = Instant.parse("2026-01-01T06:00:00Z").epochSecond
    private fun row(id: String, time: Long, weight: Double) = MeasurementEntity(
        id = id, measuredAtEpochSecond = time, weightKg = weight, rawWeight = 0,
        deviceAddress = "", rawPayloadHex = "", impedanceOhm = null, bmi = null,
        bodyFatPercent = null, bodyFatMassKg = null, waterPercent = null, waterMassKg = null,
        muscleMassKg = null, skeletalMuscleMassKg = null, boneMassKg = null, proteinPercent = null,
        proteinMassKg = null, visceralFatLevel = null, basalMetabolicRateKcal = null,
        metabolicAge = null, leanBodyMassKg = null, algorithmVersion = null,
    )
    private fun series(vararg weights: Double) = weights.mapIndexed { i, w -> row("$i", start + i * 86400L, w) }

    @Test
    fun specificationExamplesPreserveTrendsThresholdEqualityAndCandidateBlocks() {
        for (spike in listOf(72.0, 68.0)) {
            assertEquals(listOf("3"), classifyAnalyticalOutliers(series(70.0, 70.1, 70.0,spike, 70.1, 70.0, 70.1)).excluded.map { it.id })
        }
        val examples = listOf(
            doubleArrayOf(70.0, 70.2, 70.4, 70.6, 70.8, 71.0, 71.2),
            doubleArrayOf(70.0, 70.0, 70.0, 70.5, 70.0, 70.0, 70.0),
            doubleArrayOf(70.0, 70.0, 70.0, 72.0, 72.0, 72.0, 72.0),
            doubleArrayOf(70.0, 70.0, 70.0, 70.0, 72.0, 70.0, 72.0, 70.0, 70.0, 70.0, 70.0),
            doubleArrayOf(70.0, 73.0, 70.0),
            doubleArrayOf(73.0, 70.0, 70.0, 70.0, 70.0, 70.0, 73.0),
        )
        examples.forEach { assertTrue(classifyAnalyticalOutliers(series(*it)).excluded.isEmpty()) }
    }

    @Test
    fun sevenDistinctTimesAndSixNearbyDifferencesAreBothRequired() {
        val rows = series(70.0, 70.0, 70.0, 73.0, 70.0, 70.0, 70.0)
        assertTrue(classifyAnalyticalOutliers(rows).sufficientHistory)
        assertFalse(classifyAnalyticalOutliers(rows.dropLast(1)).sufficientHistory)
        assertFalse(classifyAnalyticalOutliers(rows.map { it.copy(measuredAtEpochSecond = start) }).sufficientHistory)
        assertFalse(classifyAnalyticalOutliers(rows.mapIndexed { i, r -> r.copy(measuredAtEpochSecond = start + i * 8 * 86400L) }).sufficientHistory)
        val duplicate = rows[3].copy(id = "duplicate")
        assertTrue(classifyAnalyticalOutliers(rows + duplicate).excluded.isEmpty())
    }

    @Test
    fun sevenDayNeighborBoundaryIsInclusiveButLongerGapPreservesSpike() {
        val rows = series(70.0, 70.0, 70.0, 73.0, 70.0, 70.0, 70.0, 70.0, 70.0)
        val boundary = rows.mapIndexed { index, measurement ->
            if (index >= 3) measurement.copy(measuredAtEpochSecond = measurement.measuredAtEpochSecond + 6 * 86400L)
            else measurement
        }
        assertEquals(listOf("3"), classifyAnalyticalOutliers(boundary).excluded.map { it.id })
        val tooLong = boundary.mapIndexed { index, measurement ->
            if (index >= 3) measurement.copy(measuredAtEpochSecond = measurement.measuredAtEpochSecond + 1)
            else measurement
        }
        assertTrue(classifyAnalyticalOutliers(tooLong).sufficientHistory)
        assertTrue(classifyAnalyticalOutliers(tooLong).excluded.isEmpty())
    }

    @Test
    fun invalidWeightsNeitherBreakCandidateAdjacencyNorIncreaseSufficiency() {
        val rows = series(70.0, 70.0, 70.0, 72.0, 70.0, 70.0, 70.0)
        val invalid = listOf(Double.NaN, Double.POSITIVE_INFINITY, 0.0, -1.0).mapIndexed { i, w -> row("invalid$i", start + 3 * 86400L + i + 1, w) }
        assertEquals(listOf("3"), classifyAnalyticalOutliers((rows + invalid).reversed()).excluded.map { it.id })
        assertTrue(morningMeasurements(rows + invalid, MorningWindow(), MorningFilterMode.MANUAL, utc).retained.containsAll(invalid))
        assertFalse(classifyAnalyticalOutliers(rows.take(3) + invalid).sufficientHistory)
    }

    @Test
    fun dailyMinimumReturnsWholeEarliestRecordAndDoesNotReplaceFilteredDay() {
        val rows = series(70.0, 70.0, 70.0, 68.0, 70.0, 70.0, 70.0)
        val fallback = rows[3].copy(id = "fallback", weightKg = 70.0, measuredAtEpochSecond = rows[3].measuredAtEpochSecond + 3600)
        val result = dailyMinimumMeasurements(rows + fallback, utc)
        assertEquals(listOf("3"), result.excluded.map { it.id })
        assertFalse(result.retained.any { it.id == "fallback" })
        val first = rows[0].copy(id = "a", muscleMassKg = 50.0)
        val tied = first.copy(id = "b", muscleMassKg = 49.0)
        assertEquals(first, dailyMinimumMeasurements(listOf(tied, first, first.copy(id = "earlierId", measuredAtEpochSecond = start + 1)), utc).retained.single())
        assertTrue(dailyMinimumMeasurements(listOf(first.copy(weightKg = Double.NaN)), utc).retained.isEmpty())
    }

    @Test
    fun boundariesAreHalfOpenAndTimezoneChangesRegroupAllMeasurements() {
        val rows = listOf(row("a", start - 3600, 70.0), row("b", start + 6 * 3600, 70.0))
        assertEquals(listOf("a"), morningMeasurements(rows, MorningWindow(), MorningFilterMode.MANUAL, utc).retained.map { it.id })
        assertEquals(listOf("b"), morningMeasurements(rows, MorningWindow(720, 1440), MorningFilterMode.MANUAL, utc).retained.map { it.id })
        assertEquals(2, hourlyMeasurementCounts(rows, utc).sum())
        assertEquals(1, hourlyMeasurementCounts(rows, ZoneOffset.ofHours(3))[8])
    }

    @Test
    fun repeatedDstHourCountsBothInstantsAndMissingHourIsZero() {
        val zone = ZoneId.of("Europe/Berlin")
        val autumn = listOf("2026-10-25T00:30:00Z", "2026-10-25T01:30:00Z").mapIndexed { i, t -> row("$i", Instant.parse(t).epochSecond, Double.NaN) }
        val counts = hourlyMeasurementCounts(autumn, zone)
        assertEquals(24, counts.size)
        assertEquals(2, counts[2])
        assertEquals(2, counts.sum())
        val spring = listOf("2026-03-29T00:30:00Z", "2026-03-29T01:30:00Z").mapIndexed { i, t -> row("$i", Instant.parse(t).epochSecond, 70.0) }
        assertEquals(0, hourlyMeasurementCounts(spring, zone)[2])
        assertEquals(listOf(1, 1), listOf(hourlyMeasurementCounts(spring, zone)[1], hourlyMeasurementCounts(spring, zone)[3]))
    }

    @Test
    fun automaticClassificationUsesBaselineWhileManualUsesSelectedWindow() {
        val rows = series(70.0, 70.0, 70.0, 72.0, 70.0, 70.0, 70.0).mapIndexed { i, r ->
            if (i == 3) r.copy(measuredAtEpochSecond = r.measuredAtEpochSecond + 3600) else r
        }
        val window = MorningWindow(420, 480)
        assertEquals(listOf("3"), morningMeasurements(rows, window, MorningFilterMode.AUTOMATIC, utc).excludedByFilter.map { it.id })
        assertEquals(listOf("3"), morningMeasurements(rows, window, MorningFilterMode.MANUAL, utc).retained.map { it.id })
    }

    @Test
    fun equalScoresStopAtThreeHoursWithEarliestWindowAndDisjointCounts() {
        val rows = series(70.0, 70.0, 70.0, 72.0, 70.0, 70.0, 70.0, 70.0)
        val extras = listOf(row("late", start + 5 * 3600, Double.NaN), row("outside", start + 9 * 3600, 70.0))
        val result = selectMorningWindow(rows + extras, utc) as MorningSelectionResult.Success
        assertEquals(MorningWindow(300, 480), result.preview.window)
        assertEquals(8, result.acceptedWindows.size)
        assertEquals(result.preview.baseCount, result.preview.retained.size + result.preview.excludedByTimeCount + result.preview.excludedByFilter.size)
        assertEquals(1, result.preview.excludedByTimeCount)
        assertEquals(1, result.preview.excludedByFilter.size)
        assertEquals(result, selectMorningWindow((rows + extras).reversed(), utc))
        assertTrace(result)
    }

    @Test
    fun insufficientNarrowWindowFailsEvenWithSufficientBaseline() {
        val rows = (0..6).map { i -> row("$i", start - 3600 + i * 3600L, 70.0) }
        assertTrue(classifyAnalyticalOutliers(rows).sufficientHistory)
        assertEquals(MorningSelectionResult.InsufficientData, selectMorningWindow(rows, utc))
        assertEquals(MorningSelectionResult.InsufficientData, selectMorningWindow(rows.take(3), utc))
    }

    @Test
    fun strictStabilityImprovementCanStopAtTwoAndHalfOrTwoHours() {
        val fixtures = listOf(
            Triple(listOf(435, 465, 585), listOf(70.125, 70.25, 70.25), MorningWindow(450, 600)),
            Triple(listOf(390, 510, 540, 555, 570, 630), listOf(70.125, 70.375, 70.125, 70.25, 70.0, 70.375), MorningWindow(420, 540)),
        )
        fixtures.forEach { (times, weights, expected) ->
            val rows = (0..7).flatMap { day -> times.mapIndexed { i, minute ->
                row("$day-$i", start - 6 * 3600 + day * 86400L + minute * 60, weights[i])
            } }
            val result = selectMorningWindow(rows, utc) as MorningSelectionResult.Success
            assertEquals(expected, result.preview.window)
            assertEquals((420 - expected.durationMinutes) / 30, result.acceptedWindows.size)
            assertTrace(result)
        }
    }

    private fun assertTrace(result: MorningSelectionResult.Success) {
        assertTrue(result.acceptedWindows.size <= 10)
        (listOf(MorningWindow()) + result.acceptedWindows).zipWithNext().forEach { (a, b) ->
            assertEquals(30, a.durationMinutes - b.durationMinutes)
            assertTrue((a.startMinute == b.startMinute) xor (a.endMinute == b.endMinute))
        }
        assertTrue(result.preview.window.durationMinutes in 120..180)
    }
}
