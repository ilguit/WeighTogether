package com.palixander.scalesync.domain.reference

import com.palixander.scalesync.core.breedreference.BreedReferenceSex
import com.palixander.scalesync.core.breedreference.BreedReferenceSnapshotLoadResult
import com.palixander.scalesync.core.breedreference.BreedReferenceStatisticKind
import com.palixander.scalesync.domain.BreedId
import com.palixander.scalesync.domain.PetSex
import com.palixander.scalesync.domain.PetSpecies
import com.palixander.scalesync.domain.PartialBirthDate
import com.palixander.scalesync.domain.ageAt
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
    fun `selection table uses only containing age intervals then adult references`() {
        val cases = listOf(
            Case("corgi containing first range", "VBO:0200995", 200, BreedWeightAgeScope.Age(122, 334, "4–11 months"), false),
            Case("labrador exact point", "VBO:0200800", 183, BreedWeightAgeScope.Age(183, 183, "6 months"), false),
            Case("labrador exact adult-age point", "VBO:0200800", 730, BreedWeightAgeScope.Age(730, 730, "24 months"), false),
            Case("labrador after final point", "VBO:0200800", 731, BreedWeightAgeScope.Adult, true),
            Case("dobermann adult threshold", "VBO:0200442", 365, BreedWeightAgeScope.Adult, true),
        )
        cases.forEach { case ->
            val result = resolve(case.breed, PetSex.MALE, PartialBirthDate.Day(today.minusDays(case.age.toLong()))).available()
            assertEquals(case.name, case.scope, result.ageScope)
            assertEquals(case.name, case.adultFallback, result.ageDisclosure.usedAdultFallback)
        }
    }

    @Test
    fun `known juvenile never receives adult or future point reference`() {
        listOf(
            "corgi interval gap" to resolve("VBO:0200995", PetSex.MALE, PartialBirthDate.Day(today.minusDays(350))),
            "labrador day after exact point" to resolve("VBO:0200800", PetSex.MALE, PartialBirthDate.Day(today.minusDays(184))),
            "dobermann day between neonatal observations" to resolve("VBO:0200442", PetSex.MALE, PartialBirthDate.Day(today.minusDays(13))),
            "dobermann after neonatal observations" to resolve("VBO:0200442", PetSex.MALE, PartialBirthDate.Day(today.minusDays(16))),
        ).forEach { (name, result) ->
            assertEquals(name, BreedWeightReferenceUnavailableReason.NoApplicableValue, result.unavailable())
        }
        assertTrue(
            resolve("VBO:0201220", PetSex.MALE, PartialBirthDate.Day(today.minusDays(200))).unavailable() is
                BreedWeightReferenceUnavailableReason.DocumentedGap,
        )
    }

    @Test
    fun `exact neonatal observation applies only on its declared day`() {
        val exact = resolve("VBO:0200442", PetSex.MALE, PartialBirthDate.Day(today.minusDays(12))).available()
        assertEquals(BreedWeightAgeScope.Age(12, 12, "12 days"), exact.ageScope)

        val between = resolve("VBO:0200442", PetSex.MALE, PartialBirthDate.Day(today.minusDays(11)))
        assertEquals(BreedWeightReferenceUnavailableReason.NoApplicableValue, between.unavailable())
    }

    @Test
    fun `missing birth date selects adult without calling it fallback`() {
        val result = resolve("VBO:0200800", PetSex.FEMALE, null).available()
        assertEquals(BreedWeightAgeScope.Adult, result.ageScope)
        assertEquals(null, result.ageDisclosure.selectedAgeDays)
        assertFalse(result.ageDisclosure.usedAdultFallback)
    }

    @Test
    fun `partial month discloses interval without stretching the next point`() {
        val result = resolve(
            "VBO:0200800",
            PetSex.MALE,
            PartialBirthDate.Month(YearMonth.of(2024, 8)),
        )
        assertEquals(BreedWeightReferenceUnavailableReason.NoApplicableValue, result.unavailable())
        val age = PartialBirthDate.Month(YearMonth.of(2024, 8)).ageAt(today)
        assertEquals(137L..167L, age.minimumDays..age.maximumDays)
    }

    @Test
    fun `partial year can select an exact point at its conservative upper possible age`() {
        val result = resolve("VBO:0200800", PetSex.MALE, PartialBirthDate.Year(Year.of(2024))).available()
        assertEquals(15L..380L, result.ageDisclosure.possibleAgeDays)
        assertEquals(380L, result.ageDisclosure.selectedAgeDays)
        assertEquals(BreedWeightAgeScope.Adult, result.ageScope)
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
    fun `amstaff birth observation never leaks into an adult reference`() {
        listOf(PetSex.MALE, PetSex.FEMALE).forEach { sex ->
            listOf(365, 800).forEach { age ->
                val result = resolve("VBO:0200055", sex, PartialBirthDate.Day(today.minusDays(age.toLong()))).available()
                val range = result.values.single() as BreedWeightValue.Interval

                assertEquals("$sex at $age days uses adult scope", BreedWeightAgeScope.Adult, result.ageScope)
                assertEquals("$sex at $age days has no companion", emptyList<BreedWeightReferenceGroup>(), result.companionGroups)
                assertEquals(if (sex == PetSex.MALE) 28.0 else 19.0, range.lower, 0.0)
                assertEquals(if (sex == PetSex.MALE) 33.0 else 25.0, range.upper, 0.0)
            }
        }
    }

    @Test
    fun `amstaff birth date still uses the birth observation`() {
        val result = resolve("VBO:0200055", PetSex.MALE, PartialBirthDate.Day(today)).available()
        val birth = result.values.single() as BreedWeightValue.Interval

        assertEquals(BreedWeightAgeScope.Age(0, 0, "birth"), result.ageScope)
        assertEquals(0.5, birth.lower, 0.0)
        assertEquals(0.5, birth.upper, 0.0)
    }

    @Test
    fun `range quantiles and single statistics remain distinct immutable shapes`() {
        val range = resolve("VBO:0200995", PetSex.MALE, null).available().values.single()
        assertTrue(range is BreedWeightValue.Interval)
        val median = resolve("VBO:0200800", PetSex.MALE, PartialBirthDate.Day(today.minusDays(61))).available().values.single()
        assertTrue(median is BreedWeightValue.Single)
        val meanSd = resolve("VBO:0200131", PetSex.MALE, PartialBirthDate.Day(today.minusDays(91))).available().values.single()
        assertTrue(meanSd is BreedWeightValue.Single && meanSd.spread != null)
        val shiba = resolve("VBO:0201220", PetSex.FEMALE, null).available()
        val interval = shiba.values.single() as BreedWeightValue.Interval
        assertEquals(BreedReferenceStatisticKind.RANGE, interval.statistic)
        assertEquals(7.0, interval.lower, 0.0)
        assertEquals(9.0, interval.upper, 0.0)
        assertEquals("Nihon Ken Hozonkai", shiba.source.title)
        assertEquals("https://www.nihonken-hozonkai.or.jp/en/shibainu/", shiba.source.url)
        assertTrue(shiba.details.any {
            it.id == "shi-fw" &&
                it.sex == BreedReferenceSex.FEMALE &&
                (it.value as? BreedWeightValue.Single)?.value == 7.7 &&
                it.source?.id == "shibaclub"
        })
    }

    @Test
    fun `official point and minimum statistics retain their semantics`() {
        val basenji = resolve("VBO:0200120", PetSex.MALE, null).available().values.single() as BreedWeightValue.Single
        assertEquals(BreedReferenceStatisticKind.IDEAL, basenji.statistic)
        assertEquals(11.0, basenji.value, 0.0)

        val ridgeback = resolve("VBO:0201135", PetSex.MALE, null).available().values.single() as BreedWeightValue.Single
        assertEquals(BreedReferenceStatisticKind.STANDARD_POINT, ridgeback.statistic)
        assertEquals(36.5, ridgeback.value, 0.0)

        val centralAsian = resolve("VBO:0200321", PetSex.MALE, null).available().values.single() as BreedWeightValue.Single
        assertEquals(BreedReferenceStatisticKind.MINIMUM, centralAsian.statistic)
        assertEquals(50.0, centralAsian.value, 0.0)
    }

    @Test
    fun `only archived NSCA Shiba averages bypass inactive detail filtering`() {
        val femaleShiba = resolve("VBO:0201220", PetSex.FEMALE, null).available()
        val maleShiba = resolve("VBO:0201220", PetSex.MALE, null).available()
        val beagle = resolve("VBO:0200131", PetSex.MALE, null).available()
        val amstaff = resolve("VBO:0200055", PetSex.MALE, null).available()

        assertTrue(femaleShiba.details.any { it.id == "shi-fw" && it.source?.id == "shibaclub" })
        assertTrue(maleShiba.details.any { it.id == "shi-mw" && it.source?.id == "shibaclub" })
        assertTrue(femaleShiba.values.none { it.referenceId == "shibaclub:shi-fw" })
        assertTrue(maleShiba.values.none { it.referenceId == "shibaclub:shi-mw" })
        assertTrue(beagle.details.none { it.id == "bea-model-mature" })
        assertTrue(amstaff.details.none { it.id == "ams-rejected" })
    }

    @Test
    fun `age after the final point uses adult reference and retains observations in details`() {
        val result = resolve("VBO:0200800", PetSex.MALE, PartialBirthDate.Day(today.minusDays(731))).available()
        assertEquals(BreedWeightAgeScope.Adult, result.ageScope)
        assertTrue(result.values.single() is BreedWeightValue.Interval)
        assertTrue(result.companionGroups.isEmpty())
        assertTrue(result.details.filter { it.sex == BreedReferenceSex.MALE }.all { it.youngerThanSelectedAge })
    }

    @Test
    fun `labrador uses exact age medians with adult companion from twelve months`() {
        listOf(PetSex.MALE, PetSex.FEMALE).forEach { sex ->
            assertEquals(
                BreedWeightReferenceUnavailableReason.NoApplicableValue,
                resolve("VBO:0200800", sex, PartialBirthDate.Day(today.minusDays(334))).unavailable(),
            )

            listOf(365, 457, 730).forEach { age ->
                val result = resolve("VBO:0200800", sex, PartialBirthDate.Day(today.minusDays(age.toLong()))).available()
                assertTrue("$sex at $age days keeps Dogslife center", result.values.single() is BreedWeightValue.Single)
                val companion = result.companionGroups.single()
                assertEquals(BreedWeightAgeScope.Adult, companion.ageScope)
                assertEquals(if (sex == PetSex.MALE) BreedReferenceSex.MALE else BreedReferenceSex.FEMALE, companion.sex)
                val range = companion.values.single() as BreedWeightValue.Interval
                assertEquals(if (sex == PetSex.MALE) 29.5 else 24.9, range.lower, 0.0)
                assertEquals(if (sex == PetSex.MALE) 36.3 else 31.8, range.upper, 0.0)
                assertEquals("Labrador Retriever Club", companion.source.title)
                assertTrue(result.source.title.contains("Dogslife", ignoreCase = true))
            }

            val adult = resolve("VBO:0200800", sex, PartialBirthDate.Day(today.minusDays(800))).available()
            assertEquals(BreedWeightAgeScope.Adult, adult.ageScope)
            assertTrue(adult.values.single() is BreedWeightValue.Interval)
        }
    }

    @Test
    fun `known juvenile returns applicable documented gap instead of adult value`() {
        val result = resolve("VBO:0200712", PetSex.MALE, PartialBirthDate.Day(today.minusDays(100)))
        assertEquals(
            BreedWeightReferenceUnavailableReason.DocumentedGap(
                "No breed-specific age values published; size-cluster model was not substituted.",
            ),
            result.unavailable(),
        )
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
