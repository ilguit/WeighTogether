package com.palixander.scalesync.domain.reference

import com.palixander.scalesync.core.breedreference.BreedReferenceSex
import com.palixander.scalesync.core.breedreference.BreedReferenceSnapshotLoadResult
import com.palixander.scalesync.core.breedreference.BreedReferenceStatisticKind
import com.palixander.scalesync.domain.BreedId
import com.palixander.scalesync.domain.PetSex
import com.palixander.scalesync.domain.PetSpecies
import com.palixander.scalesync.domain.PartialBirthDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.Year
import java.time.YearMonth

class BreedWeightReferenceResolverTest {
    private val resolver = BreedWeightReferenceResolver()
    private val today = LocalDate.of(2025, 1, 15)

    @Test
    fun `selection table follows containing future then adult algorithm`() {
        val cases = listOf(
            Case("corgi containing first range", "VBO:0200995", 200, BreedWeightAgeScope.Age(122, 334, "4–11 months"), false),
            Case("corgi future second range", "VBO:0200995", 350, BreedWeightAgeScope.Age(365, 699, "12–23 months"), false),
            Case("labrador exact point", "VBO:0200800", 183, BreedWeightAgeScope.Age(183, 183, "6 months"), false),
            Case("labrador nearest future", "VBO:0200800", 184, BreedWeightAgeScope.Age(274, 274, "9 months"), false),
            Case("labrador after final point", "VBO:0200800", 731, BreedWeightAgeScope.Adult, true),
            Case("dobermann future observation", "VBO:0200442", 13, BreedWeightAgeScope.Age(15, 15, "15 days"), false),
            Case("dobermann after observations", "VBO:0200442", 16, BreedWeightAgeScope.Adult, true),
        )
        cases.forEach { case ->
            val result = resolve(case.breed, PetSex.MALE, PartialBirthDate.Day(today.minusDays(case.age.toLong()))).available()
            assertEquals(case.name, case.scope, result.ageScope)
            assertEquals(case.name, case.adultFallback, result.ageDisclosure.usedAdultFallback)
        }
    }

    @Test
    fun `missing birth date selects adult without calling it fallback`() {
        val result = resolve("VBO:0200800", PetSex.FEMALE, null).available()
        assertEquals(BreedWeightAgeScope.Adult, result.ageScope)
        assertEquals(null, result.ageDisclosure.selectedAgeDays)
        assertFalse(result.ageDisclosure.usedAdultFallback)
    }

    @Test
    fun `partial month selects upper possible age and discloses full interval`() {
        val result = resolve(
            "VBO:0200800",
            PetSex.MALE,
            PartialBirthDate.Month(YearMonth.of(2024, 8)),
        ).available()
        assertEquals(137L..167L, result.ageDisclosure.possibleAgeDays)
        assertEquals(167L, result.ageDisclosure.selectedAgeDays)
        assertTrue(result.ageDisclosure.partial)
        assertEquals(BreedWeightAgeScope.Age(183, 183, "6 months"), result.ageScope)
    }

    @Test
    fun `partial year uses upper possible age`() {
        val result = resolve("VBO:0200800", PetSex.MALE, PartialBirthDate.Year(Year.of(2024))).available()
        assertEquals(15L..380L, result.ageDisclosure.possibleAgeDays)
        assertEquals(380L, result.ageDisclosure.selectedAgeDays)
        assertEquals(BreedWeightAgeScope.Age(457, 457, "15 months"), result.ageScope)
    }

    @Test
    fun `sex specific adult value wins and combined value is honest fallback`() {
        val male = resolve("VBO:0200442", PetSex.MALE, null).available()
        assertEquals(BreedReferenceSex.MALE, male.sex)
        assertEquals(40.0, (male.values.single() as BreedWeightValue.Interval).lower, 0.0)

        val combined = resolve("VBO:0200309", PetSex.FEMALE, null).available()
        assertEquals(BreedReferenceSex.COMBINED, combined.sex)
        assertEquals(5.4, (combined.values.single() as BreedWeightValue.Interval).lower, 0.0)
    }

    @Test
    fun `source priority selects official adult range while preserving alternatives`() {
        val result = resolve("VBO:0200055", PetSex.MALE, null).available()
        assertEquals("Svenska Terrierklubben", result.source.title)
        assertEquals(BreedReferenceStatisticKind.RANGE, result.values.single().statistic)
        assertTrue(result.details.any { it.value?.statistic == BreedReferenceStatisticKind.MEAN })
        assertTrue(result.details.none { it.id == "ams-rejected" })
    }

    @Test
    fun `range quantiles and single statistics remain distinct immutable shapes`() {
        val range = resolve("VBO:0200995", PetSex.MALE, null).available().values.single()
        assertTrue(range is BreedWeightValue.Interval)
        val median = resolve("VBO:0200800", PetSex.MALE, PartialBirthDate.Day(today.minusDays(61))).available().values.single()
        assertTrue(median is BreedWeightValue.Single)
        val meanSd = resolve("VBO:0200131", PetSex.MALE, PartialBirthDate.Day(today.minusDays(91))).available().values.single()
        assertTrue(meanSd is BreedWeightValue.Single && meanSd.spread != null)
        val approximate = resolve("VBO:0201220", PetSex.FEMALE, null).available().values.single()
        assertEquals(BreedReferenceStatisticKind.APPROXIMATE_AVERAGE, approximate.statistic)
    }

    @Test
    fun `younger observations are retained only in details`() {
        val result = resolve("VBO:0200800", PetSex.MALE, PartialBirthDate.Day(today.minusDays(731))).available()
        assertEquals(BreedWeightAgeScope.Adult, result.ageScope)
        assertTrue(result.details.filter { it.sex == BreedReferenceSex.MALE }.all { it.youngerThanSelectedAge })
    }

    @Test
    fun `documented gap is provenance detail and adult value is fallback`() {
        val result = resolve("VBO:0200712", PetSex.MALE, PartialBirthDate.Day(today.minusDays(100))).available()
        assertEquals(BreedWeightAgeScope.Adult, result.ageScope)
        assertTrue(result.ageDisclosure.usedAdultFallback)
        assertTrue(result.details.any { it.documentedGap != null && it.value == null })
    }

    @Test
    fun `aliases resolve to canonical breed`() {
        val result = resolve("VBO:0201146", PetSex.MALE, null).available()
        assertEquals("VBO:0200174", result.breedId)
    }

    @Test
    fun `unavailable table returns typed non throwing states`() {
        val cases = listOf(
            BreedWeightReferenceResolver().resolve(PetSpecies.CAT, BreedId("VBO:0200800"), PetSex.MALE, null, today) to BreedWeightReferenceUnavailableReason.UnsupportedSpecies,
            BreedWeightReferenceResolver().resolve(PetSpecies.DOG, null, PetSex.MALE, null, today) to BreedWeightReferenceUnavailableReason.OtherBreed,
            BreedWeightReferenceResolver().resolve(PetSpecies.DOG, BreedId("removed"), PetSex.MALE, null, today) to BreedWeightReferenceUnavailableReason.RemovedOrUnsupportedBreed("removed"),
            BreedWeightReferenceResolver().resolve(PetSpecies.DOG, BreedId("VBO:0200800"), null, null, today) to BreedWeightReferenceUnavailableReason.MissingSex,
            resolve("VBO:0200800", PetSex.MALE, PartialBirthDate.Day(today.plusDays(1))) to BreedWeightReferenceUnavailableReason.InvalidBirthDate,
        )
        cases.forEach { (actual, expected) -> assertEquals(expected, actual.unavailable()) }
    }

    @Test
    fun `corrupt snapshot is an explicit unavailable state`() {
        val result = BreedWeightReferenceResolver(BreedReferenceSnapshotLoadResult.Unavailable("checksum"))
            .resolve(PetSpecies.DOG, BreedId("VBO:0200800"), PetSex.MALE, null, today)
        assertEquals(BreedWeightReferenceUnavailableReason.SnapshotUnavailable("checksum"), result.unavailable())
    }

    private fun resolve(breed: String, sex: PetSex, birthDate: PartialBirthDate?) =
        resolver.resolve(PetSpecies.DOG, BreedId(breed), sex, birthDate, today)

    private fun BreedWeightReferenceResolution.available() = (this as BreedWeightReferenceResolution.Available).reference
    private fun BreedWeightReferenceResolution.unavailable() = (this as BreedWeightReferenceResolution.Unavailable).reason

    private data class Case(
        val name: String,
        val breed: String,
        val age: Int,
        val scope: BreedWeightAgeScope,
        val adultFallback: Boolean,
    )
}
