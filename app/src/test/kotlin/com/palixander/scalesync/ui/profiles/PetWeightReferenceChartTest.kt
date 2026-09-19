package com.palixander.scalesync.ui.profiles

import com.palixander.scalesync.charts.ChartPoint
import com.palixander.scalesync.core.reference.ReferenceBasis
import com.palixander.scalesync.core.breedreference.BreedReferenceMeasure
import com.palixander.scalesync.core.breedreference.BreedReferenceSex
import com.palixander.scalesync.core.breedreference.BreedReferenceSnapshot
import com.palixander.scalesync.domain.reference.WeightReferenceProvenance
import com.palixander.scalesync.domain.reference.BreedWeightValue
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PetWeightReferenceChartTest {
    @Test fun `maximum is one upper boundary without synthetic lower or center`() {
        val date = LocalDate.of(2026, 9, 1)
        val timeline = listOf(
            timelinePoint(
                date,
                PetHistoryBreedChartValue.Boundary(
                    valueKg = 5.0,
                    direction = BreedWeightValue.Boundary.Direction.UPPER,
                    statisticLabel = "Максимальный вес",
                    accessibilityLabel = "Максимальный вес: 5 кг",
                    seriesId = "maximum",
                ),
            ),
        )

        val series = breedWeightReferenceChartSeries(timeline)
        assertEquals(listOf(BreedWeightReferenceSeriesKind.UPPER_BOUNDARY), series.map { it.kind })
        assertEquals(listOf(5.0), series.single().points.map { it.second })
        val displayed = petWeightDisplayedSeries(emptyList(), available(emptyList()), timeline, ZoneOffset.UTC)
        assertEquals(listOf(PetWeightDisplayedSeriesKind.BREED_UPPER), displayed.map { it.kind })
        assertEquals("Максимальный вес", displayed.single().label)
        assertTrue(displayed.none { it.kind == PetWeightDisplayedSeriesKind.BREED_LOWER })
        assertTrue(displayed.none { it.kind == PetWeightDisplayedSeriesKind.BREED_CENTER })
    }

    @Test fun `mixed birth observation and breed model render glyph and model band together`() {
        val birth = LocalDate.of(2026, 7, 1)
        val exact = PetHistoryReferenceSegment(
            "cat-maine-coon-male", "mugnier-cat-birth-weight-2023",
            WeightReferenceProvenance.BREED_EXACT_OBSERVATION, "Mugnier", "CC BY 4.0", null,
            listOf(PetHistoryReferencePoint(birth, 0.1, 0.12, 0.12, 0.14)),
        )
        val model = PetHistoryReferenceSegment(
            "cat-maine-coon-male", "wikipedia-en-maine-coon-1372828795",
            WeightReferenceProvenance.BREED_CURVE, "Wikipedia Maine Coon", "CC BY-SA 4.0", null,
            listOf(
                PetHistoryReferencePoint(birth.plusDays(56), 1.0, 1.5, 1.5, 2.0),
                PetHistoryReferencePoint(birth.plusDays(57), 1.1, 1.6, 1.6, 2.1),
            ),
        )
        val reference = PetHistoryWeightReference.Available(
            basis = ReferenceBasis.BREED, provenance = WeightReferenceProvenance.BREED_CURVE,
            segments = listOf(exact, model), approximate = false, ageLabel = "Возраст",
            basisLabel = "Эталон", sourceLabel = "Источник", citation = model.citation,
            license = model.license, constraints = emptyList(), accessibilityLabel = "Эталон",
        )

        assertEquals(1, exactObservationGlyphs(reference, ZoneOffset.UTC).size)
        assertEquals(1, populationWeightReferenceBands(reference, ZoneOffset.UTC).size)
        assertTrue(petWeightDisplayedSeries(emptyList(), reference, emptyList(), ZoneOffset.UTC)
            .any { it.kind == PetWeightDisplayedSeriesKind.BREED_CENTER })
    }

    @Test fun `breed curve ignores legacy timeline and exposes P9 P91 band with P50`() {
        val dates = listOf(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 9, 1))
        val reference = availableSegments(
            segments = listOf(dates.mapIndexed { index, date ->
                PetHistoryReferencePoint(date, 2.0 + index, 3.0 + index, 3.0 + index, 4.0 + index)
            }),
            provenance = WeightReferenceProvenance.BREED_CURVE,
            fittedPercentiles = true,
        )
        val legacy = listOf(timelinePoint(dates.first(), PetHistoryBreedChartValue.Single(99.0, "Устаревшее", "legacy")))

        val displayed = petWeightDisplayedSeries(emptyList(), reference, legacy, ZoneOffset.UTC)
        val band = populationWeightReferenceBands(reference, ZoneOffset.UTC).single()

        assertEquals(3, displayed.size)
        assertEquals(
            listOf(
                PetWeightDisplayedSeriesStyle.BREED_BOUNDARY,
                PetWeightDisplayedSeriesStyle.BREED_CENTER,
                PetWeightDisplayedSeriesStyle.BREED_BOUNDARY,
            ),
            displayed.map(PetWeightDisplayedSeries::style),
        )
        assertTrue(displayed.none { 99.0 in it.y })
        assertEquals(dates.size, band.points.size)
        assertEquals(
            listOf("▰ Светло-зелёная зона — модельный породный диапазон", "— Центр модельного диапазона"),
            referenceWeightChartLegendEntries(reference.provenance).map(PetWeightChartLegendEntry::label),
        )
    }

    @Test fun `cat breed curve and legacy dog timeline share breed roles styles and band semantics`() {
        val dates = listOf(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 9, 1))
        val points = dates.mapIndexed { index, date ->
            PetHistoryReferencePoint(
                date = date,
                lowerKg = 2.0 + index,
                medianLowerKg = 3.0 + index,
                medianUpperKg = 3.0 + index,
                upperKg = 4.0 + index,
            )
        }
        val catCurve = availableSegments(
            segments = listOf(points),
            provenance = WeightReferenceProvenance.BREED_CURVE,
        )
        val dogTimeline = points.map { point ->
            timelinePoint(
                point.date,
                PetHistoryBreedChartValue.Interval(
                    lowerKg = point.lowerKg,
                    upperKg = point.upperKg,
                    centerKg = point.medianLowerKg,
                    statisticLabel = "Диапазон",
                    accessibilityLabel = "Породный диапазон",
                    seriesId = "breed-range",
                ),
            )
        }
        val legacyReference = availableSegments(segments = listOf(points))

        val catSeries = petWeightDisplayedSeries(emptyList(), catCurve, emptyList(), ZoneOffset.UTC)
        val dogSeries = petWeightDisplayedSeries(emptyList(), legacyReference, dogTimeline, ZoneOffset.UTC)
        val breedRoleOrder = listOf(
            PetWeightDisplayedSeriesKind.BREED_LOWER,
            PetWeightDisplayedSeriesKind.BREED_CENTER,
            PetWeightDisplayedSeriesKind.BREED_UPPER,
        )
        fun visualContract(series: List<PetWeightDisplayedSeries>) = series
            .sortedBy { displayed -> breedRoleOrder.indexOf(displayed.kind) }
            .map { displayed -> displayed.kind to Triple(displayed.style, displayed.x, displayed.y) }

        assertEquals(3, catSeries.size)
        assertEquals(3, dogSeries.size)
        assertEquals(visualContract(dogSeries), visualContract(catSeries))
        assertEquals(
            breedWeightReferenceBands(dogTimeline),
            populationWeightReferenceBands(catCurve, ZoneOffset.UTC),
        )
        assertTrue((catSeries + dogSeries).none { it.style == PetWeightDisplayedSeriesStyle.CATEGORY })
        assertEquals(
            setOf(
                PetWeightDisplayedSeriesKind.BREED_LOWER,
                PetWeightDisplayedSeriesKind.BREED_CENTER,
                PetWeightDisplayedSeriesKind.BREED_UPPER,
            ),
            catSeries.map(PetWeightDisplayedSeries::kind).toSet(),
        )
    }

    @Test fun `breed model bands preserve gaps and never invent a singleton fill`() {
        val first = LocalDate.of(2026, 1, 1)
        val reference = availableSegments(
            segments = listOf(
                listOf(
                    PetHistoryReferencePoint(first, 1.0, 2.0, 2.0, 3.0),
                    PetHistoryReferencePoint(first.plusDays(1), 1.1, 2.1, 2.1, 3.1),
                ),
                listOf(PetHistoryReferencePoint(first.plusDays(56), 1.5, 2.5, 2.5, 3.5)),
            ),
            provenance = WeightReferenceProvenance.BREED_CURVE,
        )

        val bands = populationWeightReferenceBands(reference, ZoneOffset.UTC)

        assertEquals(1, bands.size)
        assertEquals(listOf(first, first.plusDays(1)), bands.single().points.map {
            java.time.Instant.ofEpochMilli(it.xEpochMillis).atZone(ZoneOffset.UTC).toLocalDate()
        })
    }

    @Test fun `exact breed observation becomes single date whisker and mean without chart lines`() {
        val date = LocalDate.of(2026, 9, 1)
        val reference = availableSegments(
            segments = listOf(listOf(PetHistoryReferencePoint(date, 3.0, 4.0, 4.0, 5.0))),
            provenance = WeightReferenceProvenance.BREED_EXACT_OBSERVATION,
        )

        assertTrue(petWeightDisplayedSeries(emptyList(), reference, emptyList(), ZoneOffset.UTC).isEmpty())
        assertEquals(
            listOf(PetWeightExactObservationGlyph(date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), 3.0, 4.0, 5.0)),
            exactObservationGlyphs(reference, ZoneOffset.UTC),
        )
        assertEquals(
            listOf("↕ Диапазон наблюдения породы в дату рождения", "● Средний вес породы в дату рождения"),
            referenceWeightChartLegendEntries(reference.provenance).map(PetWeightChartLegendEntry::label),
        )
    }

    @Test fun `selected breed population fallback legend explicitly says it is not breed data`() {
        val labels = referenceWeightChartLegendEntries(WeightReferenceProvenance.POPULATION_FALLBACK_FOR_SELECTED_BREED)
            .map(PetWeightChartLegendEntry::label)

        assertTrue(labels.all { "не по породе" in it })
    }

    @Test fun `population legend uses the approved non medical term`() {
        val labels = populationWeightChartLegendEntries().map(PetWeightChartLegendEntry::label)

        assertEquals(listOf("▰ Типичный диапазон веса", "— P50"), labels)
        assertTrue(labels.none { label -> listOf("норм", "идеаль", "медицин", "целев").any { it in label.lowercase() } })
    }

    @Test fun `monotone smoothing retains knots and never overshoots adjacent values`() {
        val x = listOf(0L, 10L, 30L, 40L)
        val y = listOf(2.0, 5.0, 3.0, 4.0)

        val rendered = monotoneSmoothedChartPoints(x, y, samplesPerInterval = 10)

        x.indices.forEach { index ->
            val renderedIndex = rendered.x.indexOf(x[index])
            assertTrue(renderedIndex >= 0)
            assertEquals(y[index], rendered.y[renderedIndex], 0.0)
        }
        rendered.x.zip(rendered.y).forEach { (renderedX, renderedY) ->
            val interval = x.zipWithNext().indexOfFirst { (start, end) -> renderedX in start..end }
            assertTrue(renderedY in minOf(y[interval], y[interval + 1])..maxOf(y[interval], y[interval + 1]))
        }
    }

    @Test fun `population fill boundaries and P50 share local date timestamps outside UTC`() {
        val zoneId = ZoneId.of("Asia/Yekaterinburg")
        val dates = listOf(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 9, 1))
        val reference = PetHistoryWeightReference.Available(
            basis = ReferenceBasis.POPULATION,
            segments = referenceSegments(
                dates.mapIndexed { index, date ->
                    PetHistoryReferencePoint(date, 2.0 + index, 3.0 + index, 3.0 + index, 4.0 + index)
                },
            ),
            approximate = false,
            ageLabel = "Возраст",
            basisLabel = "Популяция",
            sourceLabel = "Источник",
            citation = "test",
            license = "CC",
            constraints = emptyList(),
            accessibilityLabel = "test population reference",
            isFittedPopulationPercentiles = true,
        )
        val expectedX = dates.map { it.atStartOfDay(zoneId).toInstant().toEpochMilli() }

        val displayed = petWeightDisplayedSeries(emptyList(), reference, emptyList(), zoneId)
        val boundaryAndP50 = displayed.filter { it.style != PetWeightDisplayedSeriesStyle.FACTUAL }
        val band = populationWeightReferenceBands(reference, zoneId).single()

        assertEquals(expectedX, band.points.map(BreedWeightReferenceBandPoint::xEpochMillis))
        assertEquals(3, boundaryAndP50.size)
        assertEquals(
            listOf("Нижняя граница P9", "Медиана P50", "Верхняя граница P91"),
            boundaryAndP50.map(PetWeightDisplayedSeries::label),
        )
        assertEquals(
            listOf(
                PetWeightDisplayedSeriesKind.BREED_LOWER to PetWeightDisplayedSeriesStyle.BREED_BOUNDARY,
                PetWeightDisplayedSeriesKind.BREED_CENTER to PetWeightDisplayedSeriesStyle.BREED_CENTER,
                PetWeightDisplayedSeriesKind.BREED_UPPER to PetWeightDisplayedSeriesStyle.BREED_BOUNDARY,
            ),
            boundaryAndP50.map { it.kind to it.style },
        )
        assertTrue(boundaryAndP50.none { it.style == PetWeightDisplayedSeriesStyle.CATEGORY })
        assertTrue(boundaryAndP50.all { it.x == expectedX })
        assertTrue(boundaryAndP50.all { series -> series.x == band.points.map(BreedWeightReferenceBandPoint::xEpochMillis) })
        assertEquals(
            "Нижняя граница P9: 2.00 кг\nМедиана P50: 3.00 кг\nВерхняя граница P91: 4.00 кг",
            formatPetWeightDisplayedMarker(expectedX.first(), displayed, Locale.US),
        )
    }

    @Test fun `breed interval has one zone legend entry and no center entry`() {
        val series = listOf(
            displayedSeries(PetWeightDisplayedSeriesKind.FACTUAL, PetWeightDisplayedSeriesStyle.FACTUAL, "Фактический вес"),
            displayedSeries(PetWeightDisplayedSeriesKind.BREED_LOWER, PetWeightDisplayedSeriesStyle.BREED_BOUNDARY, "Нижняя граница"),
            displayedSeries(PetWeightDisplayedSeriesKind.BREED_UPPER, PetWeightDisplayedSeriesStyle.BREED_BOUNDARY, "Верхняя граница"),
        )

        assertEquals(
            listOf("● Фактический вес", "▰ Светло-зелёная зона — породный диапазон"),
            petWeightChartLegendEntries(series).map(PetWeightChartLegendEntry::label),
        )
    }

    @Test fun `breed interval and center have zone and center legend entries`() {
        val series = listOf(
            displayedSeries(PetWeightDisplayedSeriesKind.BREED_LOWER, PetWeightDisplayedSeriesStyle.BREED_BOUNDARY, "Нижняя граница"),
            displayedSeries(PetWeightDisplayedSeriesKind.BREED_UPPER, PetWeightDisplayedSeriesStyle.BREED_BOUNDARY, "Верхняя граница"),
            displayedSeries(PetWeightDisplayedSeriesKind.BREED_CENTER, PetWeightDisplayedSeriesStyle.BREED_CENTER, "Медиана или среднее"),
        )

        assertEquals(
            listOf(
                "▰ Светло-зелёная зона — породный диапазон",
                "— Медиана или среднее",
            ),
            petWeightChartLegendEntries(series).map(PetWeightChartLegendEntry::label),
        )
    }

    @Test fun `chart description announces displayed corgi timeline without adult companion`() {
        val reference = available(emptyList()).copy(
            accessibilityLabel = "Ориентир щенка корги. Дополнительный ориентир: взрослая собака 9–12 кг",
        )
        val displayed = listOf(
            displayedSeries(PetWeightDisplayedSeriesKind.FACTUAL, PetWeightDisplayedSeriesStyle.FACTUAL, "Фактический вес"),
            displayedSeries(PetWeightDisplayedSeriesKind.BREED_LOWER, PetWeightDisplayedSeriesStyle.BREED_BOUNDARY, "Нижняя граница щенка"),
            displayedSeries(PetWeightDisplayedSeriesKind.BREED_UPPER, PetWeightDisplayedSeriesStyle.BREED_BOUNDARY, "Верхняя граница щенка"),
        )

        val description = petWeightChartDescription(
            factualCount = 2,
            reference = reference,
            showReferenceExplanation = true,
            hasBreedTimeline = true,
            isPopulationReference = false,
            legendEntries = petWeightChartLegendEntries(displayed),
        )

        assertTrue(description.contains("Измерений: 2"))
        assertTrue(description.contains("● Фактический вес"))
        assertTrue(description.contains("▰ Светло-зелёная зона — породный диапазон"))
        assertTrue(!description.contains("Дополнительный ориентир"))
        assertTrue(!description.contains("взрослая собака"))
    }

    @Test fun `center-only breed source does not invent category boundaries`() {
        val date = LocalDate.of(2026, 9, 1)
        val x = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val displayed = petWeightDisplayedSeries(
            factual = emptyList(),
            reference = available(listOf(PetHistoryReferencePoint(date, 6.0, 8.0, 9.0, 12.0))),
            breedReferenceTimeline = listOf(
                timelinePoint(date, PetHistoryBreedChartValue.Single(10.4, "Среднее", "shiba-center")),
            ),
            zoneId = ZoneOffset.UTC,
        )

        assertEquals(listOf(PetWeightDisplayedSeriesKind.BREED_CENTER), displayed.map(PetWeightDisplayedSeries::kind))
        assertEquals(1, displayed.map(PetWeightDisplayedSeries::id).distinct().size)
        assertTrue(displayed.none { it.style == PetWeightDisplayedSeriesStyle.CATEGORY })
        assertEquals(
            listOf("— Среднее"),
            petWeightChartLegendEntries(displayed).map(PetWeightChartLegendEntry::label),
        )
        assertEquals(
            "Среднее: 10.40 кг",
            formatPetWeightDisplayedMarker(x, displayed, Locale.US),
        )
        assertEquals(listOf(x), petWeightDisplayedMarkerXs(displayed))
    }

    @Test fun `official point and minimum use exact labels and one series`() {
        val date = LocalDate.of(2026, 9, 1)
        val x = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        listOf("Идеальный вес", "Значение стандарта", "Минимальный вес").forEach { label ->
            val displayed = petWeightDisplayedSeries(
                emptyList(),
                available(listOf(PetHistoryReferencePoint(date, 1.0, 2.0, 3.0, 4.0))),
                listOf(timelinePoint(date, PetHistoryBreedChartValue.Single(11.0, label, label))),
                ZoneOffset.UTC,
            )

            assertEquals(listOf(PetWeightDisplayedSeriesKind.BREED_CENTER), displayed.map(PetWeightDisplayedSeries::kind))
            assertEquals(label, displayed.single().label)
            assertEquals("— $label", petWeightChartLegendEntries(displayed).single().label)
            assertEquals("$label: 11.00 кг", formatPetWeightDisplayedMarker(x, displayed, Locale.US))
        }
    }

    @Test fun `inactive-only Akita timeline suppresses generic numeric reference`() {
        val date = LocalDate.of(2026, 9, 1)
        val displayed = petWeightDisplayedSeries(
            emptyList(),
            available(listOf(PetHistoryReferencePoint(date, 20.0, 25.0, 30.0, 40.0))),
            listOf(PetHistoryBreedReferenceTimelinePoint(date, null)),
            ZoneOffset.UTC,
        )

        assertTrue(displayed.isEmpty())
        assertTrue(petWeightChartLegendEntries(displayed).isEmpty())
    }

    @Test fun `Shiba official ranges display only breed boundaries and green band`() {
        val date = LocalDate.of(2026, 9, 1)
        val x = date.atTime(14, 37).toInstant(ZoneOffset.UTC).toEpochMilli()
        val shiba = requireNotNull(BreedReferenceSnapshot.bundled().breed("VBO:0201220"))
        val sourceReference = available(listOf(PetHistoryReferencePoint(date, 8.0, 9.0, 10.0, 10.0)))

        listOf(BreedReferenceSex.MALE, BreedReferenceSex.FEMALE).forEach { sex ->
            val range = shiba.values.single {
                it.measure == BreedReferenceMeasure.WEIGHT && it.sex == sex && it.adult && it.activeForProduct
            }
            val sourceValue = PetHistoryBreedChartValue.Interval(
                requireNotNull(range.lower),
                requireNotNull(range.upper),
                null,
                "Диапазон",
                "shiba-${sex.name.lowercase()}",
            )
            val sourceTimeline = listOf(PetHistoryBreedReferenceTimelinePoint(x, date, listOf(sourceValue)))

            val displayed = petWeightDisplayedSeries(emptyList(), sourceReference, sourceTimeline, ZoneOffset.UTC)

            assertEquals(
                listOf(PetWeightDisplayedSeriesKind.BREED_LOWER, PetWeightDisplayedSeriesKind.BREED_UPPER),
                displayed.map(PetWeightDisplayedSeries::kind),
            )
            assertEquals(listOf(range.lower), displayed.single { it.kind == PetWeightDisplayedSeriesKind.BREED_LOWER }.y)
            assertEquals(listOf(range.upper), displayed.single { it.kind == PetWeightDisplayedSeriesKind.BREED_UPPER }.y)
            assertTrue(displayed.none { it.style == PetWeightDisplayedSeriesStyle.CATEGORY })
            assertTrue(petWeightChartLegendEntries(displayed).none { it.label.contains("ориентира") })
            assertEquals(listOf(x), petWeightDisplayedMarkerXs(displayed))
            assertEquals(sourceReference, sourceReference.copy())
            assertEquals(sourceTimeline, listOf(PetHistoryBreedReferenceTimelinePoint(x, date, listOf(sourceValue))))
        }
    }

    @Test fun `center-only observations retain marker timestamps without synthetic boundaries`() {
        val dates = (1L..3L).map { LocalDate.of(2026, 9, 1).plusDays(it - 1) }
        val xs = dates.mapIndexed { index, date ->
            date.atTime(8 + index, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        }
        val reference = available(dates.map { PetHistoryReferencePoint(it, 8.0, 9.0, 11.0, 12.0) })
        val centers = listOf(7.0, 10.0, 13.0)
        val timeline = dates.mapIndexed { index, date ->
            PetHistoryBreedReferenceTimelinePoint(
                xs[index],
                date,
                listOf(
                    PetHistoryBreedChartValue.Single(centers[index], "Среднее", "female"),
                    PetHistoryBreedChartValue.Single(centers[index], "Среднее", "female"),
                ),
            )
        }

        val displayed = petWeightDisplayedSeries(emptyList(), reference, timeline, ZoneOffset.UTC)

        assertEquals(1, displayed.size)
        assertEquals(listOf(7.0, 10.0, 13.0), displayed.single().y)
        assertEquals(xs, petWeightDisplayedMarkerXs(displayed))
        assertEquals(
            listOf("— Среднее"),
            petWeightChartLegendEntries(displayed).map(PetWeightChartLegendEntry::label),
        )
        assertTrue(
            formatPetWeightDisplayedMarker(xs.first(), displayed, Locale.US)
                .contains("Среднее: 7.00 кг"),
        )
    }

    @Test fun `breed interval suppresses base boundaries and duplicate breed series`() {
        val date = LocalDate.of(2026, 9, 1)
        val interval = PetHistoryBreedChartValue.Interval(8.0, 12.0, null, "Диапазон", "range")
        val displayed = petWeightDisplayedSeries(
            factual = emptyList(),
            reference = available(listOf(PetHistoryReferencePoint(date, 6.0, 8.0, 9.0, 14.0))),
            breedReferenceTimeline = listOf(timelinePoint(date, interval, interval)),
            zoneId = ZoneOffset.UTC,
        )

        assertEquals(
            listOf(PetWeightDisplayedSeriesKind.BREED_LOWER, PetWeightDisplayedSeriesKind.BREED_UPPER),
            displayed.map(PetWeightDisplayedSeries::kind),
        )
        assertTrue(displayed.none { it.style == PetWeightDisplayedSeriesStyle.CATEGORY })
    }

    @Test fun `bundled AmStaff keeps exactly one active adult interval per sex`() {
        val date = LocalDate.of(2026, 9, 1)
        val measuredAt = date.atTime(14, 37).toInstant(ZoneOffset.UTC).toEpochMilli()
        val amstaff = requireNotNull(BreedReferenceSnapshot.bundled().breed("VBO:0200055"))

        val activeAdultWeights = amstaff.values.filter {
            it.measure == BreedReferenceMeasure.WEIGHT && it.adult && it.statistic.name != "DOCUMENTED_GAP"
        }.filter { it.activeForProduct }
        assertEquals(2, activeAdultWeights.size)
        assertEquals(setOf("ams-wiki-male", "ams-wiki-female"), activeAdultWeights.map { it.id }.toSet())
        assertEquals(setOf(BreedReferenceSex.MALE, BreedReferenceSex.FEMALE), activeAdultWeights.map { it.sex }.toSet())
        assertTrue(amstaff.values.filter {
            it.measure == BreedReferenceMeasure.WEIGHT && it.adult && it.id !in setOf("ams-wiki-male", "ams-wiki-female")
        }.all { !it.activeForProduct })
        val displayed = petWeightDisplayedSeries(
            factual = listOf(ChartPoint(measuredAt / 1_000, 27.0)),
            reference = available(listOf(PetHistoryReferencePoint(date, 0.0, 20.0, 21.0, 30.0))),
            breedReferenceTimeline = listOf(PetHistoryBreedReferenceTimelinePoint(measuredAt, date, null)),
            zoneId = ZoneOffset.UTC,
        )

        assertEquals(listOf(PetWeightDisplayedSeriesKind.FACTUAL), displayed.map(PetWeightDisplayedSeries::kind))
    }

    private fun displayedSeries(
        kind: PetWeightDisplayedSeriesKind,
        style: PetWeightDisplayedSeriesStyle,
        label: String,
    ) = PetWeightDisplayedSeries(kind.name, kind, label, listOf(1L), listOf(1.0), style)

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

    @Test fun `screen reader visits exact displayed x values and uses visual tooltip text`() {
        val first = LocalDate.of(2026, 9, 1).atTime(8, 15).toInstant(ZoneOffset.UTC).toEpochMilli()
        val second = LocalDate.of(2026, 9, 1).atTime(19, 45).toInstant(ZoneOffset.UTC).toEpochMilli()
        val series = listOf(
            PetWeightDisplayedSeries("factual", PetWeightDisplayedSeriesKind.FACTUAL, "Фактический вес", listOf(first, second), listOf(24.5, 25.0), PetWeightDisplayedSeriesStyle.FACTUAL),
            PetWeightDisplayedSeries("lower", PetWeightDisplayedSeriesKind.BREED_LOWER, "Нижняя граница", listOf(first, second), listOf(22.0, 22.5), PetWeightDisplayedSeriesStyle.BREED_BOUNDARY),
            PetWeightDisplayedSeries("upper", PetWeightDisplayedSeriesKind.BREED_UPPER, "Верхняя граница", listOf(first), listOf(32.0), PetWeightDisplayedSeriesStyle.BREED_BOUNDARY),
        )

        val selectableXs = petWeightDisplayedMarkerXs(series)

        assertEquals(listOf(first, second), selectableXs)
        assertEquals(
            "Фактический вес: 24.50 кг\nНижняя граница: 22.00 кг\nВерхняя граница: 32.00 кг",
            formatPetWeightDisplayedMarker(selectableXs[0], series, Locale.US),
        )
        assertEquals(
            "Фактический вес: 25.00 кг\nНижняя граница: 22.50 кг",
            formatPetWeightDisplayedMarker(selectableXs[1], series, Locale.US),
        )
    }

    @Test fun `screen reader has no selectable x when nothing is displayed`() {
        assertEquals(emptyList<Long>(), petWeightDisplayedMarkerXs(emptyList()))
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
        assertTrue(shouldShowWeightReferenceExplanation(unavailable, PetHistoryBreedReference.Unavailable(
            com.palixander.scalesync.domain.reference.BreedWeightReferenceUnavailableReason.OtherBreed,
            "other breed",
            false,
        )))
        assertTrue(!shouldShowWeightReferenceExplanation(unavailable, PetHistoryBreedReference.Unavailable(
            com.palixander.scalesync.domain.reference.BreedWeightReferenceUnavailableReason.NoApplicableValue,
            "selected breed",
            false,
        )))
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
                BreedWeightReferenceChartSeries(BreedWeightReferenceSeriesKind.LOWER_BOUNDARY, listOf(firstDate to 8.0, secondDate to 9.0), xEpochMillis = listOf(firstDate, secondDate).map { it.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }),
                BreedWeightReferenceChartSeries(BreedWeightReferenceSeriesKind.UPPER_BOUNDARY, listOf(firstDate to 12.0, secondDate to 13.0), xEpochMillis = listOf(firstDate, secondDate).map { it.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }),
                BreedWeightReferenceChartSeries(BreedWeightReferenceSeriesKind.CENTER, listOf(firstDate to 10.0, secondDate to 11.0), xEpochMillis = listOf(firstDate, secondDate).map { it.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }, statisticLabel = "Диапазон"),
            ),
            breedWeightReferenceChartSeries(timeline),
        )
    }

    @Test fun `breed band pairs boundaries by original value and exact measurement timestamp`() {
        val first = LocalDate.of(2026, 8, 1).atTime(8, 15).toInstant(ZoneOffset.UTC).toEpochMilli()
        val second = LocalDate.of(2026, 9, 1).atTime(19, 45).toInstant(ZoneOffset.UTC).toEpochMilli()
        val timeline = listOf(
            PetHistoryBreedReferenceTimelinePoint(
                first,
                LocalDate.of(2026, 8, 1),
                listOf(
                    PetHistoryBreedChartValue.Interval(8.0, 12.0, 10.0, "Первый", "first"),
                    PetHistoryBreedChartValue.Interval(18.0, 22.0, null, "Второй", "second"),
                ),
            ),
            PetHistoryBreedReferenceTimelinePoint(
                second,
                LocalDate.of(2026, 9, 1),
                listOf(
                    PetHistoryBreedChartValue.Interval(9.0, 13.0, 11.0, "Первый", "first"),
                    PetHistoryBreedChartValue.Interval(19.0, 23.0, null, "Второй", "second"),
                ),
            ),
        )

        assertEquals(
            listOf(
                BreedWeightReferenceBand(
                    listOf(
                        BreedWeightReferenceBandPoint(first, 8.0, 12.0),
                        BreedWeightReferenceBandPoint(second, 9.0, 13.0),
                    ),
                ),
                BreedWeightReferenceBand(
                    listOf(
                        BreedWeightReferenceBandPoint(first, 18.0, 22.0),
                        BreedWeightReferenceBandPoint(second, 19.0, 23.0),
                    ),
                ),
            ),
            breedWeightReferenceBands(timeline),
        )
    }

    @Test fun `breed band splits at gaps and omits singleton polygons`() {
        val first = LocalDate.of(2026, 7, 1)
        val second = LocalDate.of(2026, 7, 2)
        val gap = LocalDate.of(2026, 8, 1)
        val singleton = LocalDate.of(2026, 9, 1)
        val timeline = listOf(
            timelinePoint(first, PetHistoryBreedChartValue.Interval(8.0, 12.0, null, "Диапазон", "range")),
            timelinePoint(second, PetHistoryBreedChartValue.Interval(9.0, 13.0, null, "Диапазон", "range")),
            PetHistoryBreedReferenceTimelinePoint(gap, null),
            timelinePoint(singleton, PetHistoryBreedChartValue.Interval(10.0, 14.0, null, "Диапазон", "range")),
        )

        val bands = breedWeightReferenceBands(timeline)

        assertEquals(1, bands.size)
        assertEquals(listOf(8.0, 9.0), bands.single().points.map(BreedWeightReferenceBandPoint::lowerKg))
        assertEquals(listOf(12.0, 13.0), bands.single().points.map(BreedWeightReferenceBandPoint::upperKg))
    }

    @Test fun `breed bands use stable source identity across reordered values and source gaps`() {
        fun interval(id: String, lower: Double, upper: Double) = PetHistoryBreedChartValue.Interval(
            lowerKg = lower,
            upperKg = upper,
            centerKg = null,
            statisticLabel = "Диапазон",
            accessibilityLabel = id,
            seriesId = id,
        )
        val dates = (1..3).map { LocalDate.of(2026, 8, it) }
        val timeline = listOf(
            timelinePoint(dates[0], interval("source-a:range", 8.0, 12.0), interval("source-b:range", 18.0, 22.0)),
            timelinePoint(dates[1], interval("source-b:range", 19.0, 23.0), interval("source-a:range", 9.0, 13.0)),
            timelinePoint(dates[2], interval("source-a:range", 10.0, 14.0)),
        )

        val bands = breedWeightReferenceBands(timeline)

        assertEquals(2, bands.size)
        assertEquals(listOf(8.0, 9.0, 10.0), bands[0].points.map(BreedWeightReferenceBandPoint::lowerKg))
        assertEquals(listOf(12.0, 13.0, 14.0), bands[0].points.map(BreedWeightReferenceBandPoint::upperKg))
        assertEquals(listOf(18.0, 19.0), bands[1].points.map(BreedWeightReferenceBandPoint::lowerKg))
        assertEquals(listOf(22.0, 23.0), bands[1].points.map(BreedWeightReferenceBandPoint::upperKg))
        assertTrue(bands.none { band -> band.points.any { it.lowerKg < 15.0 } && band.points.any { it.lowerKg > 15.0 } })
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

    @Test fun `breed boundary series never present point markers including singleton segments`() {
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
        assertTrue(boundarySeries.none(BreedWeightReferenceChartSeries::showsPointMarkers))
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
        assertTrue(breedWeightReferenceSeriesPresentation(series[0].kind).lowEmphasis)
        assertTrue(breedWeightReferenceSeriesPresentation(series[1].kind).lowEmphasis)
        assertTrue(breedWeightReferenceSeriesPresentation(series[2].kind).lowEmphasis)
        assertEquals(1, breedWeightReferenceSeriesPresentation(series[0].kind).strokeWidthDp)
        assertEquals(0, breedWeightReferenceSeriesPresentation(series[0].kind).pointSizeDp)
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

    private fun available(points: List<PetHistoryReferencePoint>) = availableSegments(listOf(points))

    private fun availableSegments(
        segments: List<List<PetHistoryReferencePoint>>,
        provenance: WeightReferenceProvenance = WeightReferenceProvenance.POPULATION,
        fittedPercentiles: Boolean = false,
    ) = PetHistoryWeightReference.Available(
        basis = ReferenceBasis.BREED,
        provenance = provenance,
        segments = segments.mapIndexed { index, points ->
            PetHistoryReferenceSegment(
                profileId = "test-$index",
                sourceId = "test",
                provenance = provenance,
                citation = "test",
                license = "CC",
                publicationUrl = null,
                points = points,
            )
        },
        approximate = false,
        ageLabel = "Возраст: 1 год",
        basisLabel = "Эталон по породе",
        sourceLabel = "Источник: test",
        citation = "test",
        license = "CC",
        constraints = listOf("test constraint"),
        accessibilityLabel = "test reference",
        isFittedPopulationPercentiles = fittedPercentiles,
    )

    private fun referenceSegments(vararg points: List<PetHistoryReferencePoint>) =
        points.mapIndexed { index, segmentPoints ->
            PetHistoryReferenceSegment(
                profileId = "test-$index",
                sourceId = "test",
                provenance = WeightReferenceProvenance.POPULATION,
                citation = "test",
                license = "CC",
                publicationUrl = null,
                points = segmentPoints,
            )
        }

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
