package com.palixander.scalesync.ui.profiles

import com.palixander.scalesync.core.breedreference.BreedReferenceSnapshotLoadResult
import com.palixander.scalesync.domain.BreedId
import com.palixander.scalesync.domain.PartialBirthDate
import com.palixander.scalesync.domain.Pet
import com.palixander.scalesync.domain.PetId
import com.palixander.scalesync.domain.PetSex
import com.palixander.scalesync.domain.PetSpecies
import com.palixander.scalesync.domain.reference.BreedWeightReferenceResolver
import com.palixander.scalesync.domain.reference.BreedWeightReferenceUnavailableReason
import com.palixander.scalesync.domain.reference.BreedWeightValue
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.YearMonth
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PetHistoryBreedReferencePresenterTest {
    private val today = LocalDate.of(2026, 9, 1)
    private val clock = Clock.fixed(today.atStartOfDay().toInstant(ZoneOffset.UTC), ZoneOffset.UTC)

    @Test
    fun `normal presentation remains separate and exposes source metadata`() {
        val result = presenter().present(dog()) as PetHistoryBreedReference.Available

        assertEquals("Русский чёрный терьер", result.breedName)
        assertEquals("взрослой собаки", result.ageLabel)
        assertTrue(result.valueLabels.single().startsWith("Диапазон:"))
        assertEquals("официальный международный стандарт", result.sourceKindLabel)
        assertTrue(result.source.url.startsWith("https://"))
        assertTrue(result.source.title.isNotBlank())
        assertNull(result.partialDateDisclosure)
        assertTrue(result.accessibilityLabel.contains("Не является медицинской нормой"))
        val chartValue = result.chartValues.single() as PetHistoryBreedChartValue.Interval
        assertTrue(chartValue.lowerKg < chartValue.upperKg)
        assertTrue(chartValue.accessibilityLabel.contains("Русский чёрный терьер"))
        assertTrue(chartValue.accessibilityLabel.contains("Возраст источника: взрослой собаки"))
        assertTrue(chartValue.accessibilityLabel.contains("кг"))
        assertTrue(chartValue.accessibilityLabel.contains("официальный международный стандарт"))
    }

    @Test
    fun `official interval semantics remain visible in value labels`() {
        val pug = presenter().present(
            dog().copy(breedId = BreedId("VBO:0201089")),
        ) as PetHistoryBreedReference.Available
        val schnauzer = presenter().present(
            dog().copy(breedId = BreedId("VBO:0200898")),
        ) as PetHistoryBreedReference.Available

        assertTrue(pug.valueLabels.single().startsWith("Идеальный диапазон веса:"))
        assertTrue(schnauzer.valueLabels.single().startsWith("Приблизительный диапазон:"))
    }

    @Test
    fun `partial date discloses possible age and selected source age`() {
        val result = presenter().present(
            dog().copy(birthDate = PartialBirthDate.Month(YearMonth.of(2025, 1))),
        ) as PetHistoryBreedReference.Available

        val disclosure = requireNotNull(result.partialDateDisclosure)
        assertTrue(disclosure.startsWith("Дата рождения указана не полностью."))
        assertTrue(disclosure.contains("Показан ориентир"))
    }

    @Test
    fun `missing sex offers edit while other breed does not`() {
        val missingSex = presenter().present(dog().copy(sex = null)) as PetHistoryBreedReference.Unavailable
        val other = presenter().present(dog().copy(breedId = null)) as PetHistoryBreedReference.Unavailable

        assertEquals(BreedWeightReferenceUnavailableReason.MissingSex, missingSex.reason)
        assertTrue(missingSex.showEditAction)
        assertEquals("Укажите пол питомца, чтобы показать ориентиры породы.", missingSex.message)
        assertEquals(BreedWeightReferenceUnavailableReason.OtherBreed, other.reason)
        assertFalse(other.showEditAction)
        assertEquals("Для другой породы ориентиров пока нет.", other.message)
    }

    @Test
    fun `removed breed invalid date and snapshot failure have distinct text contracts`() {
        val removed = presenter().present(dog().copy(breedId = BreedId("legacy"))) as PetHistoryBreedReference.Unavailable
        val invalid = presenter().present(
            dog().copy(birthDate = PartialBirthDate.Day(today.plusDays(1))),
        ) as PetHistoryBreedReference.Unavailable
        val unavailable = PetHistoryBreedReferencePresenter(
            resolver = BreedWeightReferenceResolver(BreedReferenceSnapshotLoadResult.Unavailable("checksum")),
            clock = clock,
        ).present(dog()) as PetHistoryBreedReference.Unavailable

        assertFalse(removed.showEditAction)
        assertEquals("Для выбранной породы ориентиры сейчас недоступны.", removed.message)
        assertTrue(invalid.showEditAction)
        assertEquals("Исправьте дату рождения, чтобы показать ориентир для возраста.", invalid.message)
        assertEquals("Ориентиры породы временно недоступны.", unavailable.message)
    }

    @Test
    fun `juvenile without age data exposes typed unavailable presentation instead of adult range`() {
        val result = presenter().present(
            dog().copy(
                breedId = BreedId("VBO:0201220"),
                birthDate = PartialBirthDate.Day(today.minusDays(200)),
            ),
        ) as PetHistoryBreedReference.Unavailable

        assertTrue(result.reason is BreedWeightReferenceUnavailableReason.DocumentedGap)
        assertEquals("Для выбранного возраста опубликованные данные отсутствуют.", result.message)
        assertFalse(result.showEditAction)
    }

    @Test
    fun `juvenile in documented gap exposes gap-specific presentation`() {
        val result = presenter().present(
            dog().copy(
                breedId = BreedId("VBO:0200712"),
                birthDate = PartialBirthDate.Day(today.minusDays(100)),
            ),
        ) as PetHistoryBreedReference.Unavailable

        assertTrue(result.reason is BreedWeightReferenceUnavailableReason.DocumentedGap)
        assertEquals("Для выбранного возраста опубликованные данные отсутствуют.", result.message)
        assertFalse(result.showEditAction)
    }

    @Test
    fun `adult fallback breeds show published ranges while juvenile gaps stay unavailable`() {
        listOf("VBO:0200290", "VBO:0200880").forEach { breedId ->
            assertTrue(presenter().present(dog().copy(breedId = BreedId(breedId))) is PetHistoryBreedReference.Available)
            assertTrue(presenter().present(dog().copy(breedId = BreedId(breedId), birthDate = PartialBirthDate.Day(today.minusDays(800)))) is PetHistoryBreedReference.Available)
            val juvenile = presenter().present(dog().copy(breedId = BreedId(breedId), birthDate = PartialBirthDate.Day(today.minusDays(200)))) as PetHistoryBreedReference.Unavailable
            assertTrue(juvenile.reason is BreedWeightReferenceUnavailableReason.DocumentedGap)
            assertFalse(juvenile.showEditAction)
        }
    }

    @Test
    fun `QA breeds expose exact labels without miscalling standard points medians`() {
        data class Case(val id: String, val sex: PetSex, val expected: String)
        listOf(
            Case("VBO:0200055", PetSex.MALE, "Диапазон: 25–31"),
            Case("VBO:0200290", PetSex.FEMALE, "Диапазон: 11–15"),
            Case("VBO:0200470", PetSex.MALE, "Диапазон: 35–60"),
            Case("VBO:0200880", PetSex.FEMALE, "Приблизительный диапазон: 9,1–18,1"),
            Case("VBO:0200120", PetSex.MALE, "Идеальный вес: 11"),
            Case("VBO:0201135", PetSex.MALE, "Значение стандарта: 36,5"),
        ).forEach { case ->
            val result = presenter().present(dog().copy(breedId = BreedId(case.id), sex = case.sex)) as PetHistoryBreedReference.Available
            assertTrue("${case.id} label", result.valueLabels.single().startsWith(case.expected))
            assertFalse("${case.id} must not be called median", result.accessibilityLabel.contains("медиан", ignoreCase = true))
            assertFalse(result.chartValues.any { it.statisticLabel.contains("медиан", ignoreCase = true) })
        }

        val central = presenter().present(dog().copy(breedId = BreedId("VBO:0200321"), sex = PetSex.MALE)) as PetHistoryBreedReference.Available
        assertTrue(central.valueLabels.single().startsWith("Минимальный вес: 50"))
        assertTrue(central.companionReferences.single().valueLabels.single().startsWith("Диапазон: 40–80"))
        assertFalse(central.accessibilityLabel.contains("медиан", ignoreCase = true))
    }

    @Test
    fun `cat presentation is hidden`() {
        assertEquals(PetHistoryBreedReference.Hidden, presenter().present(dog().copy(species = PetSpecies.CAT)))
    }

    @Test
    fun `timeline resolves breed value from age at every measurement date`() {
        val pet = dog().copy(
            breedId = BreedId("VBO:0200800"),
            birthDate = PartialBirthDate.Day(LocalDate.of(2026, 1, 1)),
        )
        val twoMonths = LocalDate.of(2026, 3, 3)
        val threeMonths = LocalDate.of(2026, 4, 2)

        val timeline = presenter().presentTimeline(
            pet,
            listOf(
                PetHistoryBreedReferenceTimelineMoment(3, threeMonths),
                PetHistoryBreedReferenceTimelineMoment(1, twoMonths),
                PetHistoryBreedReferenceTimelineMoment(2, twoMonths),
            ),
        )

        assertEquals(listOf(twoMonths, twoMonths, threeMonths), timeline.map(PetHistoryBreedReferenceTimelinePoint::date))
        assertEquals(listOf(1L, 2L, 3L), timeline.map(PetHistoryBreedReferenceTimelinePoint::xEpochMillis))
        assertEquals(6.4, (timeline[0].values!!.single() as PetHistoryBreedChartValue.Single).valueKg, 0.0)
        assertEquals(6.4, (timeline[1].values!!.single() as PetHistoryBreedChartValue.Single).valueKg, 0.0)
        assertEquals(10.0, (timeline[2].values!!.single() as PetHistoryBreedChartValue.Single).valueKg, 0.0)
    }

    @Test
    fun `adult labrador presentation and timeline retain median range and distinct provenance`() {
        val pet = dog().copy(
            breedId = BreedId("VBO:0200800"),
            birthDate = PartialBirthDate.Day(LocalDate.of(2025, 9, 1)),
        )

        val result = presenter().present(pet) as PetHistoryBreedReference.Available

        assertTrue(result.chartValues.any { it is PetHistoryBreedChartValue.Single })
        assertTrue(result.chartValues.any { it is PetHistoryBreedChartValue.Interval })
        assertTrue(result.source.title.contains("Dogslife", ignoreCase = true))
        val companionSource = result.companionReferences.single().source
        assertEquals("Labrador Retriever Club", companionSource.title)
        assertEquals("https://thelabradorclub.com/labrador-breed-standard/", companionSource.url)
        assertTrue(companionSource.limitations.contains("Approximate working-condition weight"))
        assertTrue(result.source.url.isNotBlank())
        assertTrue(result.source.url != companionSource.url)
        assertTrue(result.accessibilityLabel.contains("Дополнительный ориентир"))

        val timelineValues = presenter().presentTimeline(
            pet,
            listOf(PetHistoryBreedReferenceTimelineMoment(1, today)),
        ).single().values!!
        assertEquals(1, timelineValues.size)
        assertTrue(timelineValues.single() is PetHistoryBreedChartValue.Single)
        assertEquals(
            listOf(BreedWeightReferenceSeriesKind.CENTER),
            breedWeightReferenceChartSeries(
                presenter().presentTimeline(pet, listOf(PetHistoryBreedReferenceTimelineMoment(1, today))),
            )
                .map(BreedWeightReferenceChartSeries::kind),
        )
    }

    @Test
    fun `young corgi timeline excludes adult companion while presentation retains it`() {
        val pet = dog().copy(
            breedId = BreedId("VBO:0200995"),
            birthDate = PartialBirthDate.Day(LocalDate.of(2025, 9, 1)),
        )

        val presentation = presenter().present(pet) as PetHistoryBreedReference.Available
        val timeline = presenter().presentTimeline(
            pet,
            listOf(PetHistoryBreedReferenceTimelineMoment(1, today)),
        )

        assertEquals(1, presentation.companionReferences.size)
        assertTrue(presentation.accessibilityLabel.contains("Дополнительный ориентир"))
        assertEquals(1, timeline.single().values!!.size)
        assertEquals(
            listOf(BreedWeightReferenceSeriesKind.LOWER_BOUNDARY, BreedWeightReferenceSeriesKind.UPPER_BOUNDARY, BreedWeightReferenceSeriesKind.CENTER),
            breedWeightReferenceChartSeries(timeline).map(BreedWeightReferenceChartSeries::kind),
        )
    }

    @Test
    fun `adult amstaff timeline produces only sex specific lower and upper chart series`() {
        val pet = dog().copy(
            breedId = BreedId("VBO:0200055"),
            birthDate = PartialBirthDate.Day(LocalDate.of(2024, 9, 1)),
        )

        val timeline = presenter().presentTimeline(
            pet,
            listOf(PetHistoryBreedReferenceTimelineMoment(1, today)),
        )
        val values = requireNotNull(timeline.single().values)
        val interval = values.single() as PetHistoryBreedChartValue.Interval
        assertEquals(25.0, interval.lowerKg, 0.0)
        assertEquals(31.0, interval.upperKg, 0.0)
        assertNull(interval.centerKg)
        assertEquals(
            listOf(BreedWeightReferenceSeriesKind.LOWER_BOUNDARY, BreedWeightReferenceSeriesKind.UPPER_BOUNDARY),
            breedWeightReferenceChartSeries(timeline).map(BreedWeightReferenceChartSeries::kind),
        )
    }

    @Test
    fun `adult Akita presentation has one sex interval and exactly two boundaries`() {
        listOf(PetSex.MALE to (27.0 to 59.0), PetSex.FEMALE to (25.0 to 45.0)).forEach { (sex, bounds) ->
            val pet = dog().copy(
                breedId = BreedId("VBO:0200734"),
                sex = sex,
                birthDate = PartialBirthDate.Day(LocalDate.of(2024, 9, 1)),
            )
            val presentation = presenter().present(pet) as PetHistoryBreedReference.Available
            val interval = presentation.chartValues.single() as PetHistoryBreedChartValue.Interval
            val timeline = presenter().presentTimeline(pet, listOf(PetHistoryBreedReferenceTimelineMoment(1, today)))

            assertEquals(bounds.first, interval.lowerKg, 0.0)
            assertEquals(bounds.second, interval.upperKg, 0.0)
            assertNull(interval.centerKg)
            assertTrue(presentation.companionReferences.isEmpty())
            assertEquals(
                listOf(BreedWeightReferenceSeriesKind.LOWER_BOUNDARY, BreedWeightReferenceSeriesKind.UPPER_BOUNDARY),
                breedWeightReferenceChartSeries(timeline).map(BreedWeightReferenceChartSeries::kind),
            )
        }
    }

    @Test
    fun `official point and minimum labels survive presentation into chart series`() {
        val cases = listOf(
            Triple("VBO:0200120", "Идеальный вес", 11.0),
            Triple("VBO:0201135", "Значение стандарта", 36.5),
            Triple("VBO:0200321", "Минимальный вес", 50.0),
        )
        cases.forEach { (breedId, label, expected) ->
            val pet = dog().copy(breedId = BreedId(breedId), birthDate = PartialBirthDate.Day(LocalDate.of(2024, 9, 1)))
            val presentation = presenter().present(pet) as PetHistoryBreedReference.Available
            val value = presentation.chartValues.filterIsInstance<PetHistoryBreedChartValue.Single>().single()
            val timeline = presenter().presentTimeline(pet, listOf(PetHistoryBreedReferenceTimelineMoment(1, today)))
            val series = breedWeightReferenceChartSeries(timeline).single()

            assertEquals(expected, value.valueKg, 0.0)
            assertEquals(label, value.statisticLabel)
            assertTrue(value.accessibilityLabel.contains(label))
            assertEquals(label, series.statisticLabel)
            assertEquals(listOf(BreedWeightReferenceSeriesKind.CENTER), listOf(series.kind))
        }
    }

    @Test
    fun `new breed maximum range point and gap retain exact presentation semantics`() {
        val maximum = presenter().present(
            dog().copy(breedId = BreedId("VBO:0200410"), sex = PetSex.FEMALE),
        ) as PetHistoryBreedReference.Available
        val maximumValue = maximum.chartValues.single() as PetHistoryBreedChartValue.Boundary
        assertEquals(5.0, maximumValue.valueKg, 0.0)
        assertEquals(BreedWeightValue.Boundary.Direction.UPPER, maximumValue.direction)
        assertEquals("Максимальный вес", maximumValue.statisticLabel)
        assertTrue(maximumValue.accessibilityLabel.contains("Максимальный вес"))
        assertTrue(maximumValue.accessibilityLabel.contains("5 кг"))
        assertEquals("Infobox", maximum.source.pageOrTable)
        assertTrue(maximum.source.method!!.contains("Miniature-size infobox maximum of 5.0 kg"))
        assertTrue(!maximum.source.method.contains("16–32 lb"))
        assertTrue(maximum.source.limitations.any { it.contains("5.5 kg") })
        assertTrue(maximum.accessibilityLabel.contains("Miniature-size infobox maximum of 5.0 kg"))
        assertTrue(maximum.accessibilityLabel.contains("5.5 kg"))
        assertTrue(!maximum.accessibilityLabel.contains("16–32 lb"))
        assertTrue(maximum.companionReferences.isEmpty())

        val range = presenter().present(
            dog().copy(breedId = BreedId("VBO:0201198")),
        ) as PetHistoryBreedReference.Available
        val rangeValue = range.chartValues.single() as PetHistoryBreedChartValue.Interval
        assertEquals(8.5, rangeValue.lowerKg, 0.0)
        assertEquals(10.5, rangeValue.upperKg, 0.0)
        assertNull(rangeValue.centerKg)

        val point = presenter().present(
            dog().copy(breedId = BreedId("VBO:0201143"), sex = PetSex.MALE),
        ) as PetHistoryBreedReference.Available
        val pointValue = point.chartValues.single() as PetHistoryBreedChartValue.Single
        assertEquals(50.0, pointValue.valueKg, 0.0)
        assertEquals("Значение стандарта", pointValue.statisticLabel)

        val gap = presenter().present(
            dog().copy(breedId = BreedId("VBO:0200027")),
        ) as PetHistoryBreedReference.Unavailable
        assertTrue(gap.reason is BreedWeightReferenceUnavailableReason.DocumentedGap)
        assertEquals("Для выбранного возраста опубликованные данные отсутствуют.", gap.message)
        assertFalse(gap.showEditAction)
    }

    @Test
    fun `Shiba presentation uses NIPPO interval and retains inactive NSCA average in details`() {
        val result = presenter().present(
            dog().copy(breedId = BreedId("VBO:0201220"), sex = PetSex.FEMALE),
        ) as PetHistoryBreedReference.Available

        val interval = result.chartValues.single() as PetHistoryBreedChartValue.Interval
        assertEquals(7.0, interval.lowerKg, 0.0)
        assertEquals(9.0, interval.upperKg, 0.0)
        assertNull(interval.centerKg)
        assertEquals("Nihon Ken Hozonkai", result.source.title)
        assertEquals("https://www.nihonken-hozonkai.or.jp/en/shibainu/", result.source.url)
        assertTrue(result.details.any {
            it.sexLabel.contains("Самка") && it.valueLabel.contains("7,7") &&
                it.sourceTitle == "National Shiba Club of America"
        })
    }

    private fun presenter() = PetHistoryBreedReferencePresenter(clock = clock, locale = Locale.forLanguageTag("ru-RU"))

    private fun dog() = Pet(
        id = PetId("dog"),
        displayName = "Бим",
        species = PetSpecies.DOG,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
        sex = PetSex.MALE,
        breedId = BreedId("VBO:0200174"),
    )
}
