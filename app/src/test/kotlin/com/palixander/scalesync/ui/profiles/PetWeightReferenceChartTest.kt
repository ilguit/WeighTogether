package com.palixander.scalesync.ui.profiles

import com.palixander.scalesync.charts.ChartPoint
import com.palixander.scalesync.core.reference.ReferenceBasis
import com.palixander.scalesync.core.breedreference.BreedReferenceMeasure
import com.palixander.scalesync.core.breedreference.BreedReferenceSex
import com.palixander.scalesync.core.breedreference.BreedReferenceSnapshot
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PetWeightReferenceChartTest {
    @Test fun `bundled AmStaff range displays factual lower and upper without zero or center`() {
        val date = LocalDate.of(2026, 9, 1)
        val measuredAt = date.atTime(14, 37).toInstant(ZoneOffset.UTC).toEpochMilli()
        val amstaff = requireNotNull(BreedReferenceSnapshot.bundled().breed("VBO:0200055"))

        listOf(BreedReferenceSex.MALE, BreedReferenceSex.FEMALE).forEach { sex ->
            val range = amstaff.values.single {
                it.measure == BreedReferenceMeasure.WEIGHT && it.sex == sex && it.adult &&
                    it.activeForProduct && it.lower != null && it.upper != null
            }
            val displayed = petWeightDisplayedSeries(
                factual = listOf(ChartPoint(measuredAt / 1_000, 27.0)),
                reference = available(listOf(PetHistoryReferencePoint(date, 0.0, 20.0, 21.0, 30.0))),
                breedReferenceTimeline = listOf(
                    PetHistoryBreedReferenceTimelinePoint(
                        measuredAt,
                        date,
                        listOf(
                            PetHistoryBreedChartValue.Interval(
                                requireNotNull(range.lower),
                                requireNotNull(range.upper),
                                null,
                                "Диапазон",
                                "Диапазон",
                            ),
                        ),
                    ),
                ),
                zoneId = ZoneOffset.UTC,
            )

            assertEquals(
                listOf(
                    PetWeightDisplayedSeriesKind.FACTUAL,
                    PetWeightDisplayedSeriesKind.BREED_LOWER,
                    PetWeightDisplayedSeriesKind.BREED_UPPER,
                ),
                displayed.map(PetWeightDisplayedSeries::kind),
            )
            assertTrue(displayed.flatMap(PetWeightDisplayedSeries::y).none { it == 0.0 })
            assertTrue(displayed.all { it.x == listOf(measuredAt) })
        }
    }

    @Test fun `tooltip contains only displayed names and exact x values`() {
        val selectedX = 1_000L
        val series = listOf(
            PetWeightDisplayedSeries("factual", PetWeightDisplayedSeriesKind.FACTUAL, "Фактический вес", listOf(selectedX), listOf(24.5), PetWeightDisplayedSeriesStyle.FACTUAL),
            PetWeightDisplayedSeries("lower", PetWeightDisplayedSeriesKind.BREED_LOWER, "Нижняя граница", listOf(selectedX), listOf(22.0), PetWeightDisplayedSeriesStyle.BREED_BOUNDARY),
            PetWeightDisplayedSeries("upper", PetWeightDisplayedSeriesKind.BREED_UPPER, "Верхняя граница", listOf(2_000L), listOf(32.0), PetWeightDisplayedSeriesStyle.BREED_BOUNDARY),
        )

        assertEquals(
            "Фактический вес: 24.50 кг\nНижняя граница: 22.00 кг",
            formatPetWeightDisplayedMarker(selectedX, series, Locale.US),
        )
        assertEquals("", formatPetWeightDisplayedMarker(3_000L, series, Locale.US))
    }

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

    @Test fun `breed series connect lower and upper values across distinct measurement dates`() {
        val firstDate = LocalDate.of(2026, 8, 1)
        val secondDate = LocalDate.of(2026, 9, 1)
        val timeline = listOf(
            timelinePoint(firstDate, PetHistoryBreedChartValue.Interval(8.0, 12.0, 10.0, "Диапазон", "range")),
            timelinePoint(secondDate, PetHistoryBreedChartValue.Interval(9.0, 13.0, 11.0, "Диапазон", "range")),
        )

        assertEquals(
            listOf(
                BreedWeightReferenceChartSeries(BreedWeightReferenceSeriesKind.LOWER_BOUNDARY, listOf(firstDate to 8.0, secondDate to 9.0)),
                BreedWeightReferenceChartSeries(BreedWeightReferenceSeriesKind.UPPER_BOUNDARY, listOf(firstDate to 12.0, secondDate to 13.0)),
                BreedWeightReferenceChartSeries(BreedWeightReferenceSeriesKind.CENTER, listOf(firstDate to 10.0, secondDate to 11.0)),
            ),
            breedWeightReferenceChartSeries(timeline),
        )
    }

    @Test fun `breed series split at unavailable measurement and keep singleton segments`() {
        val firstDate = LocalDate.of(2026, 7, 1)
        val gapDate = LocalDate.of(2026, 8, 1)
        val lastDate = LocalDate.of(2026, 9, 1)
        val timeline = listOf(
            timelinePoint(firstDate, PetHistoryBreedChartValue.Single(6.0, "Медиана", "first")),
            PetHistoryBreedReferenceTimelinePoint(gapDate, null),
            timelinePoint(lastDate, PetHistoryBreedChartValue.Single(11.0, "Медиана", "last")),
        )

        val series = breedWeightReferenceChartSeries(timeline)

        assertEquals(2, series.size)
        assertEquals(listOf(firstDate to 6.0), series[0].points)
        assertEquals(listOf(lastDate to 11.0), series[1].points)
        assertTrue(series.all { it.kind == BreedWeightReferenceSeriesKind.CENTER })
    }

    @Test fun `every breed boundary series presents point markers including singleton segments`() {
        val firstDate = LocalDate.of(2026, 7, 1)
        val gapDate = LocalDate.of(2026, 8, 1)
        val lastDate = LocalDate.of(2026, 9, 1)
        val timeline = listOf(
            timelinePoint(firstDate, PetHistoryBreedChartValue.Interval(8.0, 12.0, null, "Первый", "first")),
            PetHistoryBreedReferenceTimelinePoint(gapDate, null),
            timelinePoint(lastDate, PetHistoryBreedChartValue.Interval(9.0, 13.0, null, "Последний", "last")),
        )

        val boundarySeries = breedWeightReferenceChartSeries(timeline).filter {
            it.kind != BreedWeightReferenceSeriesKind.CENTER
        }

        assertEquals(4, boundarySeries.size)
        assertTrue(boundarySeries.all { it.points.size == 1 })
        assertTrue(boundarySeries.all(BreedWeightReferenceChartSeries::showsPointMarkers))
    }

    @Test fun `interval keeps both boundaries when center exists and maps stable presentations`() {
        val firstDate = LocalDate.of(2026, 8, 1)
        val secondDate = LocalDate.of(2026, 9, 1)
        val series = breedWeightReferenceChartSeries(
            listOf(
                timelinePoint(firstDate, PetHistoryBreedChartValue.Interval(8.0, 12.0, 10.0, "Диапазон", "range")),
                timelinePoint(secondDate, PetHistoryBreedChartValue.Interval(9.0, 13.0, 11.0, "Диапазон", "range")),
            ),
        )

        assertEquals(
            listOf(
                BreedWeightReferenceSeriesKind.LOWER_BOUNDARY,
                BreedWeightReferenceSeriesKind.UPPER_BOUNDARY,
                BreedWeightReferenceSeriesKind.CENTER,
            ),
            series.map(BreedWeightReferenceChartSeries::kind),
        )
        assertEquals(listOf(8.0, 9.0), series[0].points.map { it.second })
        assertEquals(listOf(12.0, 13.0), series[1].points.map { it.second })
        assertEquals(listOf(10.0, 11.0), series[2].points.map { it.second })
        assertTrue(!breedWeightReferenceSeriesPresentation(series[0].kind).lowEmphasis)
        assertTrue(!breedWeightReferenceSeriesPresentation(series[1].kind).lowEmphasis)
        assertTrue(breedWeightReferenceSeriesPresentation(series[2].kind).lowEmphasis)
        assertTrue(
            breedWeightReferenceSeriesPresentation(series[0].kind).strokeWidthDp >
                breedWeightReferenceSeriesPresentation(series[2].kind).strokeWidthDp,
        )
    }

    @Test fun `multiple values retain boundary center order and split every kind at gaps`() {
        val firstDate = LocalDate.of(2026, 7, 1)
        val gapDate = LocalDate.of(2026, 8, 1)
        val lastDate = LocalDate.of(2026, 9, 1)
        val series = breedWeightReferenceChartSeries(
            listOf(
                timelinePoint(
                    firstDate,
                    PetHistoryBreedChartValue.Interval(8.0, 12.0, 10.0, "Первый", "first"),
                    PetHistoryBreedChartValue.Interval(18.0, 22.0, 20.0, "Второй", "second"),
                ),
                PetHistoryBreedReferenceTimelinePoint(gapDate, null),
                timelinePoint(
                    lastDate,
                    PetHistoryBreedChartValue.Interval(9.0, 13.0, 11.0, "Первый", "first"),
                    PetHistoryBreedChartValue.Interval(19.0, 23.0, 21.0, "Второй", "second"),
                ),
            ),
        )

        assertEquals(12, series.size)
        assertEquals(
            listOf(
                BreedWeightReferenceSeriesKind.LOWER_BOUNDARY,
                BreedWeightReferenceSeriesKind.LOWER_BOUNDARY,
                BreedWeightReferenceSeriesKind.UPPER_BOUNDARY,
                BreedWeightReferenceSeriesKind.UPPER_BOUNDARY,
                BreedWeightReferenceSeriesKind.CENTER,
                BreedWeightReferenceSeriesKind.CENTER,
                BreedWeightReferenceSeriesKind.LOWER_BOUNDARY,
                BreedWeightReferenceSeriesKind.LOWER_BOUNDARY,
                BreedWeightReferenceSeriesKind.UPPER_BOUNDARY,
                BreedWeightReferenceSeriesKind.UPPER_BOUNDARY,
                BreedWeightReferenceSeriesKind.CENTER,
                BreedWeightReferenceSeriesKind.CENTER,
            ),
            series.map(BreedWeightReferenceChartSeries::kind),
        )
        assertTrue(series.all { it.points.size == 1 })
        assertEquals(
            listOf(8.0, 9.0, 12.0, 13.0, 10.0, 11.0, 18.0, 19.0, 22.0, 23.0, 20.0, 21.0),
            series.map { it.points.single().second },
        )
    }

    @Test fun `hidden breed has no native chart series`() {
        assertTrue(breedWeightReferenceChartSeries(emptyList()).isEmpty())
    }

    @Test fun `model input changes between breed intervals while factual series stays stable`() {
        val date = LocalDate.of(2026, 9, 1)
        val factual = listOf(ChartPoint(date.atStartOfDay().toEpochSecond(ZoneOffset.UTC), 9.5))
        val first = modelSeries(factual, date, PetHistoryBreedChartValue.Interval(8.0, 12.0, null, "Первый", "first"))
        val second = modelSeries(factual, date, PetHistoryBreedChartValue.Interval(10.0, 14.0, null, "Второй", "second"))

        assertEquals(first.first(), second.first())
        assertEquals(listOf(8.0), first[1].y)
        assertEquals(listOf(12.0), first[2].y)
        assertEquals(listOf(10.0), second[1].y)
        assertEquals(listOf(14.0), second[2].y)
        assertTrue(first != second)
    }

    @Test fun `model input changes from breed interval to single while factual series stays stable`() {
        val date = LocalDate.of(2026, 9, 1)
        val factual = listOf(ChartPoint(date.atStartOfDay().toEpochSecond(ZoneOffset.UTC), 9.5))
        val interval = modelSeries(factual, date, PetHistoryBreedChartValue.Interval(8.0, 12.0, null, "Диапазон", "range"))
        val single = modelSeries(factual, date, PetHistoryBreedChartValue.Single(11.0, "Медиана", "median"))

        assertEquals(interval.first(), single.first())
        assertEquals(listOf(8.0), interval[1].y)
        assertEquals(listOf(12.0), interval[2].y)
        assertEquals(listOf(11.0), single.last().y)
        assertTrue(interval != single)
    }

    @Test fun `model input removes unavailable breed while factual series stays stable`() {
        val date = LocalDate.of(2026, 9, 1)
        val factual = listOf(ChartPoint(date.atStartOfDay().toEpochSecond(ZoneOffset.UTC), 9.5))
        val available = modelSeries(factual, date, PetHistoryBreedChartValue.Interval(8.0, 12.0, null, "Диапазон", "range"))
        val unavailable = petWeightChartModelSeries(factual, emptyList(), emptyList(), ZoneOffset.UTC)

        assertEquals(available.first(), unavailable.first())
        assertEquals(3, available.size)
        assertEquals(1, unavailable.size)
    }

    @Test fun `breed interval suppresses category lines and keeps exact measurement time`() {
        val date = LocalDate.of(2026, 9, 1)
        val measuredAt = date.atTime(14, 37).toInstant(ZoneOffset.UTC).toEpochMilli()
        val factual = listOf(ChartPoint(measuredAt / 1000, 24.0))
        val category = petWeightReferenceChartSeries(
            available(listOf(PetHistoryReferencePoint(date, 0.0, 20.0, 21.0, 30.0))),
        )
        val breed = breedWeightReferenceChartSeries(
            listOf(
                PetHistoryBreedReferenceTimelinePoint(
                    measuredAt,
                    date,
                    listOf(PetHistoryBreedChartValue.Interval(22.0, 32.0, null, "Диапазон", "range")),
                ),
            ),
        )

        val model = petWeightChartModelSeries(factual, category, breed, ZoneOffset.UTC)

        assertEquals(3, model.size)
        assertEquals(listOf(24.0), model[0].y)
        assertEquals(listOf(22.0), model[1].y)
        assertEquals(listOf(32.0), model[2].y)
        assertTrue(model.drop(1).all { it.x == listOf(measuredAt) })
        assertTrue(breed.none { it.kind == BreedWeightReferenceSeriesKind.CENTER })
        assertTrue(model.flatMap(PetWeightChartModelSeries::y).none { it == 0.0 })
    }

    @Test fun `two measurements on same date keep distinct breed timestamps`() {
        val date = LocalDate.of(2026, 9, 1)
        val morning = date.atTime(8, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        val evening = date.atTime(20, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        val value = PetHistoryBreedChartValue.Interval(22.0, 32.0, null, "Диапазон", "range")

        val series = breedWeightReferenceChartSeries(
            listOf(
                PetHistoryBreedReferenceTimelinePoint(morning, date, listOf(value)),
                PetHistoryBreedReferenceTimelinePoint(evening, date, listOf(value)),
            ),
        )

        assertTrue(series.all { it.xEpochMillis == listOf(morning, evening) })
    }

    @Test fun `category lines remain fallback without breed timeline`() {
        val date = LocalDate.of(2026, 9, 1)
        val category = petWeightReferenceChartSeries(
            available(listOf(PetHistoryReferencePoint(date, 10.0, 11.0, 12.0, 13.0))),
        )

        val model = petWeightChartModelSeries(emptyList(), category, emptyList(), ZoneOffset.UTC)

        assertEquals(4, model.size)
        assertEquals(listOf(10.0, 11.0, 12.0, 13.0), model.map { it.y.single() })
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

    @Test fun `marker uses breed reference for selected date`() {
        val selectedDate = LocalDate.of(2026, 8, 2)
        val otherDate = LocalDate.of(2026, 8, 3)
        val selectedValue = PetHistoryBreedChartValue.Interval(
            8.0,
            12.0,
            10.0,
            "Диапазон 8–12 кг",
            "Диапазон породы: 8–12 кг",
        )
        val selection = petWeightMarkerSelection(
            selectedDate.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
            emptyList(),
            available(emptyList()),
            ZoneOffset.UTC,
            listOf(
                timelinePoint(selectedDate, selectedValue),
                timelinePoint(otherDate, PetHistoryBreedChartValue.Single(14.0, "Медиана", "Не выбран")),
            ),
        )

        assertEquals(listOf(selectedValue), selection.breedValues)
        assertTrue(formatPetWeightMarker(selection, Locale.US).endsWith("Породный ориентир: Диапазон породы: 8–12 кг"))
        assertTrue(!formatPetWeightMarker(selection, Locale.US).contains("Нижняя граница"))
    }

    @Test fun `marker omits breed reference when selected date has none`() {
        val selectedDate = LocalDate.of(2026, 8, 2)
        val selection = petWeightMarkerSelection(
            selectedDate.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
            emptyList(),
            available(emptyList()),
            ZoneOffset.UTC,
            listOf(PetHistoryBreedReferenceTimelinePoint(selectedDate, null)),
        )

        assertEquals(null, selection.breedValues)
        assertTrue(!formatPetWeightMarker(selection, Locale.US).contains("Породный ориентир"))
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
        breedSeries = breedWeightReferenceChartSeries(listOf(timelinePoint(date, value))),
        zoneId = ZoneOffset.UTC,
    )

    private fun timelinePoint(
        date: LocalDate,
        vararg values: PetHistoryBreedChartValue,
    ) = PetHistoryBreedReferenceTimelinePoint(date, values.toList())
}
