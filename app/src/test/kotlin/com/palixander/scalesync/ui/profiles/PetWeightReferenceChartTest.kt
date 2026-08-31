package com.palixander.scalesync.ui.profiles

import com.palixander.scalesync.charts.ChartPoint
import com.palixander.scalesync.core.reference.ReferenceBasis
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PetWeightReferenceChartTest {
    @Test fun `range combines factual and reference extremes`() {
        val date = LocalDate.of(2026, 8, 1)
        val reference = available(listOf(PetHistoryReferencePoint(date, 2.0, 3.0, 4.0, 5.0)))

        val range = requireNotNull(petWeightChartRange(listOf(ChartPoint(1, 8.0)), reference))

        assertTrue(range.min < 2.0)
        assertTrue(range.max > 8.0)
    }

    @Test fun `reference alone provides chart range`() {
        val date = LocalDate.of(2026, 8, 1)
        val range = requireNotNull(petWeightChartRange(emptyList(), available(listOf(
            PetHistoryReferencePoint(date, 2.0, 3.0, 4.0, 5.0),
        ))))

        assertTrue(range.min < 2.0)
        assertTrue(range.max > 5.0)
    }

    @Test fun `no factual or available reference has no range`() {
        assertEquals(
            null,
            petWeightChartRange(
                emptyList(),
                PetHistoryWeightReference.Unavailable(
                    com.palixander.scalesync.domain.reference.WeightReferenceUnavailableReason.MissingSex,
                    "missing",
                ),
            ),
        )
    }

    @Test fun `marker maps measurement and singleton reference by local date`() {
        val date = LocalDate.of(2026, 8, 1)
        val referencePoint = PetHistoryReferencePoint(date, 2.0, 3.0, 4.0, 5.0)
        val epochSecond = date.atTime(14, 30).toEpochSecond(ZoneOffset.UTC)

        val selection = petWeightMarkerSelection(
            targetXEpochMillis = date.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
            factual = listOf(ChartPoint(epochSecond, 4.25)),
            reference = available(listOf(referencePoint)),
            zoneId = ZoneOffset.UTC,
        )

        assertEquals(date, selection.date)
        assertEquals(4.25, selection.measurementKg!!, 0.0)
        assertEquals(referencePoint, selection.reference)
        assertEquals(
            "01.08.2026\n" +
                "Измерение: 4.25 кг\n" +
                "Нижняя граница: 2.00 кг\n" +
                "Медиана: 3.00 кг–4.00 кг\n" +
                "Верхняя граница: 5.00 кг",
            formatPetWeightMarker(selection, Locale.US),
        )
    }

    @Test fun `marker keeps distinct unavailable values explicit`() {
        val selection = PetWeightMarkerSelection(LocalDate.of(2026, 8, 2), null, null)

        assertEquals(
            "02.08.2026\nИзмерение: —\nНижняя граница: —\nМедиана: —\nВерхняя граница: —",
            formatPetWeightMarker(selection, Locale.US),
        )
    }

    private fun available(points: List<PetHistoryReferencePoint>) = PetHistoryWeightReference.Available(
        basis = ReferenceBasis.BREED,
        segments = listOf(points),
        approximate = false,
        ageLabel = "Возраст: 1 год",
        basisLabel = "Эталон по породе",
        sourceLabel = "Источник: test",
        citation = "test",
        license = "CC",
        constraints = listOf("test constraint"),
        accessibilityLabel = "test reference",
    )
}
