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
    @Test fun `reference series keep segment boundaries and lower to upper order`() {
        val firstDate = LocalDate.of(2026, 8, 1)
        val secondDate = LocalDate.of(2026, 8, 2)
        val separatedDate = LocalDate.of(2026, 8, 4)
        val reference = availableSegments(
            listOf(
                listOf(
                    PetHistoryReferencePoint(firstDate, 1.0, 2.0, 3.0, 4.0),
                    PetHistoryReferencePoint(secondDate, 5.0, 6.0, 7.0, 8.0),
                ),
                listOf(PetHistoryReferencePoint(separatedDate, 9.0, 10.0, 11.0, 12.0)),
            ),
        )

        assertEquals(
            listOf(
                PetWeightReferenceChartSeries(
                    PetWeightReferenceSeriesKind.LOWER,
                    listOf(firstDate to 1.0, secondDate to 5.0),
                ),
                PetWeightReferenceChartSeries(
                    PetWeightReferenceSeriesKind.MEDIAN_LOWER,
                    listOf(firstDate to 2.0, secondDate to 6.0),
                ),
                PetWeightReferenceChartSeries(
                    PetWeightReferenceSeriesKind.MEDIAN_UPPER,
                    listOf(firstDate to 3.0, secondDate to 7.0),
                ),
                PetWeightReferenceChartSeries(
                    PetWeightReferenceSeriesKind.UPPER,
                    listOf(firstDate to 4.0, secondDate to 8.0),
                ),
                PetWeightReferenceChartSeries(
                    PetWeightReferenceSeriesKind.LOWER,
                    listOf(separatedDate to 9.0),
                ),
                PetWeightReferenceChartSeries(
                    PetWeightReferenceSeriesKind.MEDIAN_LOWER,
                    listOf(separatedDate to 10.0),
                ),
                PetWeightReferenceChartSeries(
                    PetWeightReferenceSeriesKind.MEDIAN_UPPER,
                    listOf(separatedDate to 11.0),
                ),
                PetWeightReferenceChartSeries(
                    PetWeightReferenceSeriesKind.UPPER,
                    listOf(separatedDate to 12.0),
                ),
            ),
            petWeightReferenceChartSeries(reference),
        )
    }

    @Test fun `unavailable reference produces no chart series`() {
        val unavailable = PetHistoryWeightReference.Unavailable(
            com.palixander.scalesync.domain.reference.WeightReferenceUnavailableReason.MissingSex,
            "missing",
        )

        assertTrue(petWeightReferenceChartSeries(unavailable).isEmpty())
    }

    @Test fun `available breed suppresses only unavailable category explanation`() {
        val unavailable = PetHistoryWeightReference.Unavailable(
            com.palixander.scalesync.domain.reference.WeightReferenceUnavailableReason.MissingDogAdultWeight,
            "missing category",
        )
        val breed = breedAvailable(
            listOf(PetHistoryBreedChartValue.Single(11.0, "Медиана", "median")),
        )

        assertTrue(!shouldShowWeightReferenceExplanation(unavailable, breed))
        assertTrue(shouldShowWeightReferenceExplanation(unavailable, PetHistoryBreedReference.Hidden))
        assertTrue(shouldShowWeightReferenceExplanation(available(emptyList()), breed))
    }

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

    @Test fun `breed interval and single values extend chart range without measurements or category lines`() {
        val breed = breedAvailable(
            listOf(
                PetHistoryBreedChartValue.Interval(8.0, 12.0, 10.0, "Диапазон", "range"),
                PetHistoryBreedChartValue.Single(11.0, "Медиана", "median"),
            ),
        )

        val range = requireNotNull(
            petWeightChartRange(
                emptyList(),
                PetHistoryWeightReference.Unavailable(
                    com.palixander.scalesync.domain.reference.WeightReferenceUnavailableReason.MissingDogAdultWeight,
                    "missing",
                ),
                breed,
            ),
        )

        assertTrue(range.min < 8.0)
        assertTrue(range.max > 12.0)
    }

    @Test fun `breed series keep interval and diamond in chart data coordinates`() {
        val date = LocalDate.of(2026, 9, 1)
        val breed = breedAvailable(
            listOf(
                PetHistoryBreedChartValue.Interval(8.0, 12.0, 10.0, "Диапазон", "range"),
                PetHistoryBreedChartValue.Single(11.0, "Медиана", "median"),
            ),
        )

        assertEquals(
            listOf(
                BreedWeightReferenceChartSeries(listOf(date to 8.0, date to 12.0), true),
                BreedWeightReferenceChartSeries(listOf(date to 10.0), false),
                BreedWeightReferenceChartSeries(listOf(date to 11.0), false),
            ),
            breedWeightReferenceChartSeries(breed, date),
        )
    }

    @Test fun `breed series remain data coordinates across viewport ranges`() {
        val date = LocalDate.of(2026, 9, 1)
        val breed = breedAvailable(
            listOf(PetHistoryBreedChartValue.Interval(3.25, 47.75, null, "Диапазон", "range")),
        )

        val series = breedWeightReferenceChartSeries(breed, date).single()

        // Vico receives domain values, not canvas fractions. Its current layer bounds, axes,
        // scroll, zoom, font scale, and Y range therefore own both coordinate transforms.
        assertEquals(listOf(date to 3.25, date to 47.75), series.points)
        assertTrue(series.drawsInterval)
    }

    @Test fun `hidden breed has no native chart series`() {
        assertTrue(
            breedWeightReferenceChartSeries(
                PetHistoryBreedReference.Hidden,
                LocalDate.of(2026, 9, 1),
            ).isEmpty(),
        )
    }

    @Test fun `model input changes between breed intervals while factual series stays stable`() {
        val date = LocalDate.of(2026, 9, 1)
        val factual = listOf(ChartPoint(date.atStartOfDay().toEpochSecond(ZoneOffset.UTC), 9.5))
        val first = modelSeries(factual, date, PetHistoryBreedChartValue.Interval(8.0, 12.0, null, "Первый", "first"))
        val second = modelSeries(factual, date, PetHistoryBreedChartValue.Interval(10.0, 14.0, null, "Второй", "second"))

        assertEquals(first.first(), second.first())
        assertEquals(listOf(8.0, 12.0), first.last().y)
        assertEquals(listOf(10.0, 14.0), second.last().y)
        assertTrue(first != second)
    }

    @Test fun `model input changes from breed interval to single while factual series stays stable`() {
        val date = LocalDate.of(2026, 9, 1)
        val factual = listOf(ChartPoint(date.atStartOfDay().toEpochSecond(ZoneOffset.UTC), 9.5))
        val interval = modelSeries(factual, date, PetHistoryBreedChartValue.Interval(8.0, 12.0, null, "Диапазон", "range"))
        val single = modelSeries(factual, date, PetHistoryBreedChartValue.Single(11.0, "Медиана", "median"))

        assertEquals(interval.first(), single.first())
        assertEquals(listOf(8.0, 12.0), interval.last().y)
        assertEquals(listOf(11.0), single.last().y)
        assertTrue(interval != single)
    }

    @Test fun `model input removes unavailable breed while factual series stays stable`() {
        val date = LocalDate.of(2026, 9, 1)
        val factual = listOf(ChartPoint(date.atStartOfDay().toEpochSecond(ZoneOffset.UTC), 9.5))
        val available = modelSeries(factual, date, PetHistoryBreedChartValue.Interval(8.0, 12.0, null, "Диапазон", "range"))
        val unavailable = petWeightChartModelSeries(factual, emptyList(), emptyList(), ZoneOffset.UTC)

        assertEquals(available.first(), unavailable.first())
        assertEquals(2, available.size)
        assertEquals(1, unavailable.size)
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

    private fun available(points: List<PetHistoryReferencePoint>) = availableSegments(listOf(points))

    private fun availableSegments(
        segments: List<List<PetHistoryReferencePoint>>,
    ) = PetHistoryWeightReference.Available(
        basis = ReferenceBasis.BREED,
        segments = segments,
        approximate = false,
        ageLabel = "Возраст: 1 год",
        basisLabel = "Эталон по породе",
        sourceLabel = "Источник: test",
        citation = "test",
        license = "CC",
        constraints = listOf("test constraint"),
        accessibilityLabel = "test reference",
    )

    private fun breedAvailable(values: List<PetHistoryBreedChartValue>) = PetHistoryBreedReference.Available(
        breedName = "Бигль",
        ageLabel = "6 месяцев",
        valueLabels = listOf("Диапазон: 8–12 кг"),
        sourceKindLabel = "наблюдаемая выборка",
        sexLabel = "Самец",
        partialDateDisclosure = null,
        accessibilityLabel = "Ориентиры породы Бигль",
        chartValues = values,
        source = PetHistoryBreedSource("Источник", "https://example.com", 2024, "наблюдаемая выборка", null, null, null, null, emptyList()),
        details = emptyList(),
    )

    private fun modelSeries(
        factual: List<ChartPoint>,
        date: LocalDate,
        value: PetHistoryBreedChartValue,
    ) = petWeightChartModelSeries(
        factual = factual,
        referenceSeries = emptyList(),
        breedSeries = breedWeightReferenceChartSeries(breedAvailable(listOf(value)), date),
        zoneId = ZoneOffset.UTC,
    )
}
