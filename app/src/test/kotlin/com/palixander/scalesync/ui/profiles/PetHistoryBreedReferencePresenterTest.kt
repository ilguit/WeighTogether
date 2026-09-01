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
