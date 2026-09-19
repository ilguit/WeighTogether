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
    fun `adult and juvenile documented gaps remain available through presentation`() {
        listOf("VBO:0200290", "VBO:0200880").forEach { breedId ->
            val results = listOf(
                presenter().present(dog().copy(breedId = BreedId(breedId))),
                presenter().present(
                    dog().copy(
                        breedId = BreedId(breedId),
                        birthDate = PartialBirthDate.Day(today.minusDays(800)),
                    ),
                ),
                presenter().present(
                    dog().copy(
                        breedId = BreedId(breedId),
                        birthDate = PartialBirthDate.Day(today.minusDays(200)),
                    ),
                ),
            ).map { it as PetHistoryBreedReference.Unavailable }

            results.forEach { result ->
                assertTrue("$breedId exposes a documented gap", result.reason is BreedWeightReferenceUnavailableReason.DocumentedGap)
                assertEquals("Для выбранного возраста опубликованные данные отсутствуют.", result.message)
                assertFalse(result.showEditAction)
            }
            val descriptions = results.map {
                (it.reason as BreedWeightReferenceUnavailableReason.DocumentedGap).description
            }
            assertEquals("$breedId uses the same adult gap with or without a birth date", descriptions[0], descriptions[1])
            assertTrue("$breedId juvenile gap does not get masked by adult gap", descriptions[1] != descriptions[2])
        }
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
    fun `adult amstaff timeline produces only approximate lower and upper chart series`() {
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
        assertEquals(23.0, interval.lowerKg, 0.0)
        assertEquals(36.0, interval.upperKg, 0.0)
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
            val value = presentation.chartValues.single() as PetHistoryBreedChartValue.Single
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
