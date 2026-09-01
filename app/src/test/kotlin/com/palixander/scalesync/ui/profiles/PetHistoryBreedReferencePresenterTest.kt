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
    fun `partial date discloses possible age and selected source age`() {
        val result = presenter().present(
            dog().copy(birthDate = PartialBirthDate.Month(YearMonth.of(2026, 1))),
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

        val timeline = presenter().presentTimeline(pet, listOf(threeMonths, twoMonths, twoMonths))

        assertEquals(listOf(twoMonths, threeMonths), timeline.map(PetHistoryBreedReferenceTimelinePoint::date))
        assertEquals(6.4, (timeline[0].values!!.single() as PetHistoryBreedChartValue.Single).valueKg, 0.0)
        assertEquals(10.0, (timeline[1].values!!.single() as PetHistoryBreedChartValue.Single).valueKg, 0.0)
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

        val timelineValues = presenter().presentTimeline(pet, listOf(today)).single().values!!
        assertTrue(timelineValues.any { it is PetHistoryBreedChartValue.Single })
        assertTrue(timelineValues.any { it is PetHistoryBreedChartValue.Interval })
        assertEquals(
            listOf(
                BreedWeightReferenceSeriesKind.CENTER,
                BreedWeightReferenceSeriesKind.LOWER_BOUNDARY,
                BreedWeightReferenceSeriesKind.UPPER_BOUNDARY,
            ),
            breedWeightReferenceChartSeries(presenter().presentTimeline(pet, listOf(today)))
                .map(BreedWeightReferenceChartSeries::kind),
        )
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
