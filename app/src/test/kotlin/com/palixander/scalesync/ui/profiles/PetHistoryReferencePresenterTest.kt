package com.palixander.scalesync.ui.profiles

import com.palixander.scalesync.charts.ChartDateRange
import com.palixander.scalesync.core.reference.ReferenceBasis
import com.palixander.scalesync.domain.Pet
import com.palixander.scalesync.domain.BreedId
import com.palixander.scalesync.domain.PetId
import com.palixander.scalesync.domain.PetSex
import com.palixander.scalesync.domain.PetSpecies
import com.palixander.scalesync.domain.PartialBirthDate
import com.palixander.scalesync.domain.reference.DogAdultWeightCategory
import com.palixander.scalesync.domain.reference.WeightReferenceUnavailableReason
import com.palixander.scalesync.core.reference.ReferenceBoundsStatistic
import com.palixander.scalesync.core.reference.ReferenceCenterStatistic
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PetHistoryReferencePresenterTest {
    private val presenter = PetHistoryReferencePresenter()

    @Test
    fun `selected date range produces ordered reference points with provenance`() {
        val start = LocalDate.of(2025, 1, 15)
        val pet = dog(PartialBirthDate.Day(start.minusDays(100)))

        val result = presenter.present(pet, ChartDateRange(start, start.plusDays(2)))
            as PetHistoryWeightReference.Available

        assertEquals(ReferenceBasis.WEIGHT_CATEGORY, result.basis)
        assertEquals(listOf(start, start.plusDays(1), start.plusDays(2)), result.segments.single().map { it.date })
        assertFalse(result.approximate)
        assertTrue(result.sourceLabel.startsWith("Источник:"))
        assertTrue(result.citation.isNotBlank())
        assertTrue(result.license.isNotBlank())
        assertTrue(result.constraints.isNotEmpty())
        assertTrue(result.accessibilityLabel.contains(result.ageLabel))
    }

    @Test
    fun `partial birth date is explicitly approximate`() {
        val date = LocalDate.of(2025, 1, 15)
        val result = presenter.present(
            dog(PartialBirthDate.Month(java.time.YearMonth.of(2024, 9))),
            ChartDateRange(date, date),
        ) as PetHistoryWeightReference.Available

        assertTrue(result.approximate)
        assertTrue(result.ageLabel.contains("примерно"))
        assertTrue(result.segments.single().single().medianLowerKg <= result.segments.single().single().medianUpperKg)
    }

    @Test
    fun `unsupported dates are omitted without bridging the available interval`() {
        val start = LocalDate.of(2025, 1, 15)
        val pet = dog(PartialBirthDate.Day(start.minusDays(82)))

        val result = presenter.present(pet, ChartDateRange(start, start.plusDays(5)))
            as PetHistoryWeightReference.Available

        assertEquals(start.plusDays(2), result.segments.single().first().date)
        assertEquals(start.plusDays(5), result.segments.single().last().date)
    }

    @Test
    fun `history beginning in 1800 keeps reference sampling bounded`() {
        val end = LocalDate.of(2026, 3, 20)
        val pet = dog(PartialBirthDate.Day(end.minusDays(100)))
        val range = ChartDateRange(LocalDate.of(1800, 1, 1), end)

        val dates = referenceSampleDates(range)
        val result = presenter.present(pet, range) as PetHistoryWeightReference.Available

        assertEquals(MAX_REFERENCE_CHART_SAMPLES, dates.size)
        assertEquals(range.startDate, dates.first())
        assertEquals(range.endDateInclusive, dates.last())
        assertTrue(result.segments.flatten().size <= MAX_REFERENCE_CHART_SAMPLES)
        assertEquals(end, result.segments.last().last().date)
    }

    @Test
    fun `unavailable presentation retains typed reason and concrete explanation`() {
        val date = LocalDate.of(2025, 1, 15)
        val pet = dog(PartialBirthDate.Day(date.minusDays(100))).copy(sex = null)

        val result = presenter.present(pet, ChartDateRange(date, date))
            as PetHistoryWeightReference.Unavailable

        assertEquals(WeightReferenceUnavailableReason.MissingSex, result.reason)
        assertEquals("Эталон недоступен: укажите пол питомца.", result.explanation)
    }

    @Test
    fun `every unavailable reason has a user facing explanation`() {
        val reasons = listOf(
            WeightReferenceUnavailableReason.MissingSex,
            WeightReferenceUnavailableReason.MissingBirthDate,
            WeightReferenceUnavailableReason.MissingBreed,
            WeightReferenceUnavailableReason.MissingDogAdultWeight,
            WeightReferenceUnavailableReason.UnsupportedSpecies,
            WeightReferenceUnavailableReason.UnknownBreed("breed"),
            WeightReferenceUnavailableReason.BreedSpeciesMismatch("breed"),
            WeightReferenceUnavailableReason.UnsupportedBreed("breed"),
            WeightReferenceUnavailableReason.DshIntactStatusUnknown,
            WeightReferenceUnavailableReason.DshNotIntact,
            WeightReferenceUnavailableReason.InvalidBirthDate,
            WeightReferenceUnavailableReason.ProfileUnavailable("profile"),
            WeightReferenceUnavailableReason.ReferenceDataGap("profile", 1L..2L),
            WeightReferenceUnavailableReason.AdultWeightAboveSupportedMaximum(41.0),
            WeightReferenceUnavailableReason.AgeOutOfRange(1, 2, 3, 4),
        )

        reasons.forEach { reason ->
            assertTrue(weightReferenceUnavailableExplanation(reason).startsWith("Эталон недоступен:"))
        }
    }

    @Test
    fun `breed observation presentation identifies mean and standard deviation`() {
        val date = LocalDate.of(2026, 9, 6)
        val pet = Pet(
            id = PetId("kitten"),
            displayName = "Барсик",
            species = PetSpecies.CAT,
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
            sex = PetSex.MALE,
            birthDate = PartialBirthDate.Day(date),
            breedId = BreedId("VBO:0100223"),
        )

        val result = presenter.present(pet, ChartDateRange(date, date))
            as PetHistoryWeightReference.Available

        assertEquals(ReferenceCenterStatistic.MEAN, result.centerStatistic)
        assertEquals(ReferenceBoundsStatistic.ONE_STANDARD_DEVIATION, result.boundsStatistic)
        assertTrue(result.basisLabel.contains("среднее ± одно стандартное отклонение"))
        assertFalse(result.basisLabel.contains("медиан", ignoreCase = true))
    }

    private fun dog(birthDate: PartialBirthDate) = Pet(
        id = PetId("dog"),
        displayName = "Бим",
        species = PetSpecies.DOG,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
        sex = PetSex.MALE,
        birthDate = birthDate,
        dogAdultWeightCategory = DogAdultWeightCategory.III,
    )
}
