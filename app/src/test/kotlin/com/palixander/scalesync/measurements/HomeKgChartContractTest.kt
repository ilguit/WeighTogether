package com.palixander.scalesync.measurements

import com.palixander.scalesync.domain.PendingMeasurement
import com.palixander.scalesync.domain.PendingMeasurementId
import com.palixander.scalesync.R
import com.palixander.scalesync.ui.text.UiText
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeKgChartContractTest {
    @Test
    fun `catalog exposes exactly eight stable kg series with distinct colors`() {
        assertEquals(
            listOf(
                "weight_kg",
                "body_fat_mass_kg",
                "water_mass_kg",
                "muscle_mass_kg",
                "skeletal_muscle_mass_kg",
                "bone_mass_kg",
                "protein_mass_kg",
                "lean_body_mass_kg",
            ),
            HomeKgChartSeriesCatalog.map(HomeKgChartMetric::key),
        )
        assertEquals(8, HomeKgChartSeriesCatalog.size)
        assertEquals(8, HomeKgChartSeriesCatalog.map { it.color.argb }.distinct().size)
        assertEquals(8, DefaultHomeKgChartSeriesKeys.size)
        assertTrue(HomeKgChartSeriesCatalog.all { it.unit == UiText.Resource(R.string.unit_kg) && it.decimalPlaces == 2 })
    }

    @Test
    fun `catalog series map to the expected eight kilogram values`() {
        val clock = Clock.fixed(Instant.parse("2026-08-21T08:00:00Z"), ZoneOffset.UTC)
        val item = measurement("full", Instant.parse("2026-08-20T08:00:00Z").epochSecond)

        val state = buildHomeKgChartUiState(listOf(item), clock = clock, zoneId = ZoneOffset.UTC)

        assertEquals(
            listOf(72.0, 14.0, 38.5, 40.0, 20.0, 3.0, 12.6, 58.0),
            state.series.map { it.points.single().valueKg },
        )
    }

    @Test
    fun `period uses today and previous thirteen local calendar days across DST`() {
        val zoneId = ZoneId.of("Europe/Berlin")
        val clock = Clock.fixed(Instant.parse("2026-03-29T12:00:00Z"), ZoneOffset.UTC)

        val period = homeKgChartPeriod(zoneId = zoneId, clock = clock)

        assertEquals(LocalDate.of(2026, 3, 16), period.startDate)
        assertEquals(LocalDate.of(2026, 3, 29), period.endDateInclusive)
        assertEquals(
            LocalDate.of(2026, 3, 16).atStartOfDay(zoneId).toEpochSecond(),
            period.startInclusiveEpochSecond,
        )
        assertEquals(
            LocalDate.of(2026, 3, 30).atStartOfDay(zoneId).toEpochSecond(),
            period.endExclusiveEpochSecond,
        )
        assertEquals(14L, period.startDate.datesUntil(period.endDateInclusive.plusDays(1)).count())
        assertEquals(
            14L * 24L * 60L * 60L - 60L * 60L,
            period.endExclusiveEpochSecond - period.startInclusiveEpochSecond,
        )
    }

    @Test
    fun `builder retains the full history while period defines the initial viewport`() {
        val zoneId = ZoneId.of("Asia/Yekaterinburg")
        val clock = Clock.fixed(Instant.parse("2026-08-21T08:00:00Z"), ZoneOffset.UTC)
        val period = homeKgChartPeriod(zoneId, clock)
        val atStart = measurement("start", period.startInclusiveEpochSecond, weightKg = 71.0)
        val atEnd = measurement("end", period.endExclusiveEpochSecond, weightKg = 72.0)
        val before = measurement("before", period.startInclusiveEpochSecond - 1, weightKg = 70.0)

        val state = buildHomeKgChartUiState(
            measurements = listOf(atEnd, before, atStart),
            zoneId = zoneId,
            clock = clock,
        )

        assertEquals(
            listOf("before", "start", "end"),
            state.series.first().points.map(HomeKgChartPoint::measurementId),
        )
        assertEquals(period, state.period)
    }

    @Test
    fun `missing composition values are omitted only from their series`() {
        val clock = Clock.fixed(Instant.parse("2026-08-21T08:00:00Z"), ZoneOffset.UTC)
        val measuredAt = Instant.parse("2026-08-20T08:00:00Z").epochSecond
        val item = measurement("partial", measuredAt).copy(
            values = values(weightKg = 72.0).copy(
                bodyFatMassKg = null,
                boneMassKg = null,
            ),
        )

        val state = buildHomeKgChartUiState(listOf(item), clock = clock, zoneId = ZoneOffset.UTC)

        assertTrue(state.series.single { it.key == "body_fat_mass_kg" }.points.isEmpty())
        assertTrue(state.series.single { it.key == "bone_mass_kg" }.points.isEmpty())
        assertEquals(1, state.series.single { it.key == "weight_kg" }.points.size)
        assertEquals(1, state.series.single { it.key == "water_mass_kg" }.points.size)
    }

    @Test
    fun `weight only measurement contributes exclusively to weight even if composition leaks in`() {
        val clock = Clock.fixed(Instant.parse("2026-08-21T08:00:00Z"), ZoneOffset.UTC)
        val weightOnly = measurement(
            id = "weight-only",
            measuredAt = Instant.parse("2026-08-20T08:00:00Z").epochSecond,
            weightKg = 69.5,
            type = MeasurementUiType.WEIGHT_ONLY,
        )

        val state = buildHomeKgChartUiState(listOf(weightOnly), clock = clock, zoneId = ZoneOffset.UTC)

        val weightSeries = state.series.single { it.key == "weight_kg" }
        assertEquals(listOf(69.5), weightSeries.points.map(HomeKgChartPoint::valueKg))
        assertTrue(state.series.filterNot { it.key == "weight_kg" }.all { it.points.isEmpty() })
    }

    @Test
    fun `preliminary measurement contributes to home weight series`() {
        val now = Instant.parse("2026-08-20T08:00:00Z")
        val pending = PendingMeasurement(
            id = PendingMeasurementId("pending-chart"),
            deviceAddress = "AA:BB",
            measuredAt = now,
            weightKg = 69.5,
            impedanceOhm = 0,
            isStable = true,
            hasImpedance = false,
            rawPayload = byteArrayOf(1),
            deduplicationHash = "hash-chart",
            enqueuedAt = now,
        )

        val state = buildHomeKgChartUiState(
            measurements = listOf(pending.toPreliminaryMeasurementUiItem(now)),
            clock = Clock.fixed(now.plusSeconds(60), ZoneOffset.UTC),
            zoneId = ZoneOffset.UTC,
        )

        val point = state.series.single { it.key == "weight_kg" }.points.single()
        assertEquals(pending.id.value, point.measurementId)
        assertEquals(69.5, point.valueKg, 0.0)
        assertTrue(state.series.filterNot { it.key == "weight_kg" }.all { it.points.isEmpty() })
    }

    @Test
    fun `chart point identity remains pending key after finalization`() {
        val now = Instant.parse("2026-08-20T08:00:00Z")
        val finalized = measurement(
            id = "measurement-row",
            measuredAt = now.epochSecond,
        ).copy(
            presentationKey = "pending-stable",
            finalMeasurementId = "measurement-row",
            sourcePendingId = PendingMeasurementId("pending-stable"),
        )

        val state = buildHomeKgChartUiState(
            measurements = listOf(finalized),
            clock = Clock.fixed(now.plusSeconds(60), ZoneOffset.UTC),
            zoneId = ZoneOffset.UTC,
        )

        assertEquals(
            "pending-stable",
            state.series.single { it.key == "weight_kg" }.points.single().measurementId,
        )
    }

    @Test
    fun `builder sorts each series chronologically and retains measurement identity`() {
        val clock = Clock.fixed(Instant.parse("2026-08-21T08:00:00Z"), ZoneOffset.UTC)
        val earlier = measurement("earlier", Instant.parse("2026-08-19T08:00:00Z").epochSecond, 71.0)
        val later = measurement("later", Instant.parse("2026-08-20T08:00:00Z").epochSecond, 72.0)

        val state = buildHomeKgChartUiState(listOf(later, earlier), clock = clock, zoneId = ZoneOffset.UTC)

        assertEquals(
            listOf("earlier", "later"),
            state.series.single { it.key == "weight_kg" }.points.map(HomeKgChartPoint::measurementId),
        )
    }

    @Test
    fun `selection restoration distinguishes missing empty and stale keys`() {
        assertEquals(DefaultHomeKgChartSeriesKeys, restoreHomeKgChartSeriesKeys(null))
        assertTrue(restoreHomeKgChartSeriesKeys(emptySet()).isEmpty())
        assertEquals(
            linkedSetOf("weight_kg", "bone_mass_kg"),
            restoreHomeKgChartSeriesKeys(
                linkedSetOf("bone_mass_kg", "removed_metric", "weight_kg"),
            ),
        )
        assertTrue(restoreHomeKgChartSeriesKeys(setOf("removed_metric", "future_metric")).isEmpty())
    }

    @Test
    fun `first launch toggle persists canonical selection and second toggle restores series`() {
        val withoutWeight = toggleHomeKgChartSeriesKey(null, "weight_kg")

        assertEquals(DefaultHomeKgChartSeriesKeys - "weight_kg", withoutWeight)
        assertEquals(
            DefaultHomeKgChartSeriesKeys,
            toggleHomeKgChartSeriesKey(withoutWeight, "weight_kg"),
        )
    }

    @Test
    fun `toggle permits an explicitly empty persisted selection`() {
        val onlyWeight = setOf("weight_kg")

        assertTrue(toggleHomeKgChartSeriesKey(onlyWeight, "weight_kg")!!.isEmpty())
        assertEquals(setOf("weight_kg"), toggleHomeKgChartSeriesKey(emptySet(), "weight_kg"))
    }

    @Test
    fun `toggle drops stale persisted keys and ignores unknown event keys`() {
        assertEquals(
            linkedSetOf("weight_kg", "bone_mass_kg"),
            toggleHomeKgChartSeriesKey(
                persistedKeys = setOf("weight_kg", "removed_metric"),
                toggledKey = "bone_mass_kg",
            ),
        )
        assertEquals(null, toggleHomeKgChartSeriesKey(setOf("weight_kg"), "removed_metric"))
    }
}

private fun measurement(
    id: String,
    measuredAt: Long,
    weightKg: Double = 72.0,
    type: MeasurementUiType = MeasurementUiType.FULL,
): MeasurementUiItem = MeasurementUiItem(
    id = id,
    measuredAtEpochSecond = measuredAt,
    values = values(weightKg),
    sync = MeasurementSyncPresentation(
        state = MeasurementSyncPresentationState.SYNCED,
        directions = emptyList(),
        canRetry = false,
    ),
    type = type,
)

private fun values(weightKg: Double) = MeasurementUiValues(
    weightKg = weightKg,
    impedanceOhm = 500,
    bmi = 22.0,
    bodyFatPercent = 20.0,
    bodyFatMassKg = 14.0,
    waterPercent = 55.0,
    waterMassKg = 38.5,
    muscleMassKg = 40.0,
    skeletalMuscleMassKg = 20.0,
    boneMassKg = 3.0,
    proteinPercent = 18.0,
    proteinMassKg = 12.6,
    visceralFatLevel = 7.0,
    basalMetabolicRateKcal = 1_500.0,
    metabolicAge = 35,
    leanBodyMassKg = 58.0,
)
