package com.palixander.weightogether.domain.reference

import com.palixander.weightogether.core.breedreference.BreedReferenceSex
import com.palixander.weightogether.core.breedreference.BreedReferenceSnapshotLoadResult
import com.palixander.weightogether.core.breedreference.BreedReferenceStatisticKind
import com.palixander.weightogether.domain.BreedId
import com.palixander.weightogether.domain.PetSex
import com.palixander.weightogether.domain.PetSpecies
import com.palixander.weightogether.domain.PartialBirthDate
import com.palixander.weightogether.domain.ageAt
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
    fun `AmStaff uses sex specific French Wikipedia intervals`() {
        listOf(
            Triple(PetSex.MALE, 25.0, 31.0),
            Triple(PetSex.FEMALE, 18.0, 25.0),
        ).forEach { (sex, lower, upper) ->
            val reference = resolve("VBO:0200055", sex, null).available()
            val interval = reference.values.single() as BreedWeightValue.Interval

            assertEquals(if (sex == PetSex.MALE) BreedReferenceSex.MALE else BreedReferenceSex.FEMALE, reference.sex)
            assertEquals(BreedReferenceStatisticKind.RANGE, interval.statistic)
            assertEquals(lower, interval.lower, 0.0)
            assertEquals(upper, interval.upper, 0.0)
            assertTrue(interval.referenceId!!.startsWith("wiki-amstaff:ams-wiki-"))
            assertTrue(reference.companionGroups.isEmpty())
            assertTrue(reference.source.url.startsWith("https://fr.wikipedia.org/"))
        }
    }

    @Test
    fun `QA fallback breeds resolve exact adult semantics`() {
        data class AdultRange(val id: String, val sex: PetSex, val expectedSex: BreedReferenceSex, val lower: Double, val upper: Double, val source: String)
        listOf(
            AdultRange("VBO:0200290", PetSex.MALE, BreedReferenceSex.MALE, 14.0, 17.0, "wiki-cardigan"),
            AdultRange("VBO:0200290", PetSex.FEMALE, BreedReferenceSex.FEMALE, 11.0, 15.0, "wiki-cardigan"),
            AdultRange("VBO:0200470", PetSex.MALE, BreedReferenceSex.MALE, 35.0, 60.0, "wiki-veo"),
            AdultRange("VBO:0200470", PetSex.FEMALE, BreedReferenceSex.FEMALE, 30.0, 50.0, "wiki-veo"),
            AdultRange("VBO:0200880", PetSex.MALE, BreedReferenceSex.COMBINED, 9.1, 18.1, "akc-mas-weight-chart"),
            AdultRange("VBO:0200880", PetSex.FEMALE, BreedReferenceSex.COMBINED, 9.1, 18.1, "akc-mas-weight-chart"),
        ).forEach { case ->
            val result = resolve(case.id, case.sex, null).available()
            val interval = result.values.single() as BreedWeightValue.Interval
            assertEquals(case.expectedSex, result.sex)
            assertEquals(case.lower, interval.lower, 0.0)
            assertEquals(case.upper, interval.upper, 0.0)
            assertTrue(interval.referenceId!!.startsWith("${case.source}:"))
        }
    }

    @Test
    fun `German Shepherd resolves its official sex specific adult ranges`() {
        listOf(
            Triple(PetSex.MALE, 30.0, 40.0),
            Triple(PetSex.FEMALE, 22.0, 32.0),
        ).forEach { (sex, lower, upper) ->
            val reference = resolve("VBO:0200577", sex, null).available()
            val interval = reference.values.single() as BreedWeightValue.Interval

            assertEquals("VBO:0200577", reference.breedId)
            assertEquals("Немецкая овчарка", reference.breedRussianName)
            assertEquals(BreedWeightAgeScope.Adult, reference.ageScope)
            assertEquals(lower, interval.lower, 0.0)
            assertEquals(upper, interval.upper, 0.0)
            assertTrue(interval.referenceId!!.startsWith("fci166:"))
        }
    }

    @Test
    fun `amstaff birth observation never leaks into the adult Wikipedia fallback`() {
        listOf(PetSex.MALE, PetSex.FEMALE).forEach { sex ->
            listOf(365, 800).forEach { age ->
                val reference = resolve("VBO:0200055", sex, PartialBirthDate.Day(today.minusDays(age.toLong()))).available()

                assertEquals("$sex at $age days uses one adult interval", 1, reference.values.size)
                assertTrue(reference.values.single() is BreedWeightValue.Interval)
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
        val ridgeback = resolve("VBO:0201135", PetSex.MALE, null).available().values.single() as BreedWeightValue.Single
        assertEquals(BreedReferenceStatisticKind.STANDARD_POINT, ridgeback.statistic)
        assertEquals(36.5, ridgeback.value, 0.0)

        val centralAsian = resolve("VBO:0200321", PetSex.MALE, null).available().values.single() as BreedWeightValue.Single
        assertEquals(BreedReferenceStatisticKind.MINIMUM, centralAsian.statistic)
        assertEquals(50.0, centralAsian.value, 0.0)
        val centralReference = resolve("VBO:0200321", PetSex.MALE, null).available()
        val publishedRange = centralReference.companionGroups.single().values.single() as BreedWeightValue.Interval
        assertEquals(BreedReferenceSex.COMBINED, centralReference.companionGroups.single().sex)
        assertEquals(BreedReferenceStatisticKind.RANGE, publishedRange.statistic)
        assertEquals(40.0, publishedRange.lower, 0.0)
        assertEquals(80.0, publishedRange.upper, 0.0)
    }

    @Test
    fun `QA2 fallbacks select one applicable range and inactive gaps never mask it`() {
        data class Expected(
            val breedId: String,
            val sex: PetSex,
            val referenceSex: BreedReferenceSex,
            val lower: Double,
            val upper: Double,
            val sourceId: String,
        )
        listOf(
            Expected("VBO:0200027", PetSex.MALE, BreedReferenceSex.MALE, 45.0, 59.0, "wiki-american-akita"),
            Expected("VBO:0200027", PetSex.FEMALE, BreedReferenceSex.FEMALE, 32.0, 45.0, "wiki-american-akita"),
            Expected("VBO:0200120", PetSex.MALE, BreedReferenceSex.COMBINED, 9.1, 10.9, "wiki-basenji"),
            Expected("VBO:0200120", PetSex.FEMALE, BreedReferenceSex.COMBINED, 9.1, 10.9, "wiki-basenji"),
            Expected("VBO:0201217", PetSex.MALE, BreedReferenceSex.COMBINED, 6.8, 11.3, "wiki-ru-sheltie"),
            Expected("VBO:0201217", PetSex.FEMALE, BreedReferenceSex.COMBINED, 6.8, 11.3, "wiki-ru-sheltie"),
            Expected("VBO:0201143", PetSex.MALE, BreedReferenceSex.MALE, 50.0, 60.0, "wiki-rottweiler"),
            Expected("VBO:0201143", PetSex.FEMALE, BreedReferenceSex.FEMALE, 35.0, 48.0, "wiki-rottweiler"),
        ).forEach { expected ->
            val reference = resolve(expected.breedId, expected.sex, null).available()
            val range = reference.values.single() as BreedWeightValue.Interval
            assertEquals(expected.breedId, expected.referenceSex, reference.sex)
            assertEquals(expected.breedId, BreedReferenceStatisticKind.RANGE, range.statistic)
            assertEquals(expected.breedId, expected.lower, range.lower, 0.0)
            assertEquals(expected.breedId, expected.upper, range.upper, 0.0)
            assertEquals(expected.breedId, expected.sourceId, reference.source.id)
            assertTrue(expected.breedId, reference.companionGroups.isEmpty())
        }
    }

    @Test
    fun `Akita exposes exactly one sex specific Wikipedia interval without companions`() {
        listOf(
            Triple(PetSex.MALE, 27.0, 59.0),
            Triple(PetSex.FEMALE, 25.0, 45.0),
        ).forEach { (sex, lower, upper) ->
            val reference = resolve("VBO:0200734", sex, null).available()
            val interval = reference.values.single() as BreedWeightValue.Interval

            assertEquals(if (sex == PetSex.MALE) BreedReferenceSex.MALE else BreedReferenceSex.FEMALE, reference.sex)
            assertEquals(lower, interval.lower, 0.0)
            assertEquals(upper, interval.upper, 0.0)
            assertTrue(reference.companionGroups.isEmpty())
            assertTrue(reference.details.none { it.id in setOf("aki-adult-weight-gap", "aki-mw", "aki-fw") && it.sex == reference.sex })
        }
    }

    @Test
    fun `archived Shiba averages and official replacement provenance bypass inactive detail filtering`() {
        val femaleShiba = resolve("VBO:0201220", PetSex.FEMALE, null).available()
        val maleShiba = resolve("VBO:0201220", PetSex.MALE, null).available()
        val beagle = resolve("VBO:0200131", PetSex.MALE, null).available()
        val rottweiler = resolve("VBO:0201143", PetSex.MALE, null).available()

        assertTrue(femaleShiba.details.any { it.id == "shi-fw" && it.source?.id == "shibaclub" })
        assertTrue(maleShiba.details.any { it.id == "shi-mw" && it.source?.id == "shibaclub" })
        assertTrue(femaleShiba.values.none { it.referenceId == "shibaclub:shi-fw" })
        assertTrue(maleShiba.values.none { it.referenceId == "shibaclub:shi-mw" })
        assertTrue(beagle.details.none { it.id == "bea-model-mature" })
        assertTrue(rottweiler.details.any { it.id == "rot-male" && it.source?.id == "fci147" })
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
    fun `published adult fallbacks apply while juvenile gaps stay age specific`() {
        listOf(
            "VBO:0200290" to "No open peer-reviewed or official breed-specific age/weight observations were located; do not infer from adult standards.",
            "VBO:0200880" to "No peer-reviewed or open dataset with exact breed-specific 0–24 month tabular weights was identified. Size-category curves were not relabelled as breed observations.",
        ).forEach { (breedId, juvenileGap) ->
            assertTrue(resolve(breedId, PetSex.MALE, null) is BreedWeightReferenceResolution.Available)
            assertTrue(resolve(breedId, PetSex.MALE, PartialBirthDate.Day(today.minusDays(800))) is BreedWeightReferenceResolution.Available)
            val juvenile = resolve(breedId, PetSex.MALE, PartialBirthDate.Day(today.minusDays(200))).documentedGap()
            assertEquals("$breedId at juvenile age", juvenileGap, juvenile.description)
        }
    }

    @Test
    fun `ranks 31 through 50 resolve approved adult references or explicit gaps`() {
        val availableCases = listOf(
            AdultCase("VBO:0201415", PetSex.MALE, BreedReferenceSex.COMBINED, setOf(BreedReferenceStatisticKind.RANGE)),
            AdultCase("VBO:0201448", PetSex.FEMALE, BreedReferenceSex.COMBINED, setOf(BreedReferenceStatisticKind.MAXIMUM)),
            AdultCase("VBO:0200161", PetSex.MALE, BreedReferenceSex.MALE, setOf(BreedReferenceStatisticKind.RANGE)),
            AdultCase("VBO:0200339", PetSex.FEMALE, BreedReferenceSex.COMBINED, setOf(BreedReferenceStatisticKind.RANGE, BreedReferenceStatisticKind.IDEAL_RANGE)),
            AdultCase("VBO:0200962", PetSex.MALE, BreedReferenceSex.COMBINED, setOf(BreedReferenceStatisticKind.RANGE)),
            AdultCase("VBO:0200485", PetSex.FEMALE, BreedReferenceSex.FEMALE, setOf(BreedReferenceStatisticKind.STANDARD_POINT)),
            AdultCase("VBO:0200163", PetSex.MALE, BreedReferenceSex.COMBINED, setOf(BreedReferenceStatisticKind.APPROXIMATE_AVERAGE)),
            AdultCase("VBO:0201198", PetSex.FEMALE, BreedReferenceSex.COMBINED, setOf(BreedReferenceStatisticKind.RANGE)),
            AdultCase("VBO:0200713", PetSex.MALE, BreedReferenceSex.COMBINED, setOf(BreedReferenceStatisticKind.MAXIMUM)),
            AdultCase("VBO:0201403", PetSex.FEMALE, BreedReferenceSex.FEMALE, setOf(BreedReferenceStatisticKind.APPROXIMATE_RANGE)),
            AdultCase("VBO:0200340", PetSex.MALE, BreedReferenceSex.COMBINED, setOf(BreedReferenceStatisticKind.RANGE, BreedReferenceStatisticKind.IDEAL_RANGE)),
            AdultCase("VBO:0200345", PetSex.FEMALE, BreedReferenceSex.COMBINED, setOf(BreedReferenceStatisticKind.RANGE)),
            AdultCase("VBO:0201348", PetSex.MALE, BreedReferenceSex.MALE, setOf(BreedReferenceStatisticKind.RANGE)),
            AdultCase("VBO:0200410", PetSex.FEMALE, BreedReferenceSex.COMBINED, setOf(BreedReferenceStatisticKind.MAXIMUM)),
            AdultCase("VBO:0200027", PetSex.MALE, BreedReferenceSex.MALE, setOf(BreedReferenceStatisticKind.RANGE)),
            AdultCase("VBO:0201217", PetSex.FEMALE, BreedReferenceSex.COMBINED, setOf(BreedReferenceStatisticKind.RANGE)),
            AdultCase("VBO:0200882", PetSex.MALE, BreedReferenceSex.COMBINED, setOf(BreedReferenceStatisticKind.RANGE)),
            AdultCase("VBO:0200764", PetSex.FEMALE, BreedReferenceSex.FEMALE, setOf(BreedReferenceStatisticKind.RANGE)),
            AdultCase("VBO:0200375", PetSex.MALE, BreedReferenceSex.MALE, setOf(BreedReferenceStatisticKind.RANGE)),
            AdultCase("VBO:0201143", PetSex.FEMALE, BreedReferenceSex.FEMALE, setOf(BreedReferenceStatisticKind.RANGE)),
        )
        availableCases.forEach { case ->
            val reference = resolve(case.breed, case.sex, null).available()
            assertEquals(case.breed, case.referenceSex, reference.sex)
            assertEquals(case.breed, case.statistics, reference.values.map { it.statistic }.toSet())
        }

        assertTrue(
            resolve("VBO:0200375", PetSex.FEMALE, null).documentedGap().description
                .contains("no numeric female interval was synthesized"),
        )
    }

    @Test
    fun `new researched breeds expose growth gaps without adult or category substitution`() {
        val breedIds = listOf(
            "VBO:0201415", "VBO:0201448", "VBO:0200161", "VBO:0200339", "VBO:0200962",
            "VBO:0200485", "VBO:0200163", "VBO:0201198", "VBO:0200713", "VBO:0201403",
            "VBO:0200340", "VBO:0200345", "VBO:0201348", "VBO:0200410", "VBO:0200027",
            "VBO:0201217", "VBO:0200882", "VBO:0200764", "VBO:0200375", "VBO:0201143",
        )
        breedIds.forEach { breedId ->
            val gap = resolve(
                breedId,
                PetSex.MALE,
                PartialBirthDate.Day(today.minusDays(200)),
            ).documentedGap()
            assertTrue(breedId, gap.description.contains("size-category curves were not substituted"))
        }

        val dachshund = resolve("VBO:0200410", PetSex.MALE, null).available()
        val maximum = dachshund.values.single() as BreedWeightValue.Boundary
        assertEquals(5.0, maximum.value, 0.0)
        assertEquals(BreedWeightValue.Boundary.Direction.UPPER, maximum.direction)
        assertEquals("wiki-dachshund-miniature", dachshund.source.id)
        assertEquals("Infobox", dachshund.source.pageOrTable)
        assertTrue(dachshund.source.method!!.contains("Miniature-size infobox maximum of 5.0 kg"))
        assertTrue(!dachshund.source.method.contains("16–32 lb"))
        assertTrue(dachshund.limitations.any { it.contains("5.5 kg") })
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
    private fun BreedWeightReferenceResolution.documentedGap() =
        unavailable() as BreedWeightReferenceUnavailableReason.DocumentedGap

    private data class Case(
        val name: String,
        val breed: String,
        val age: Int,
        val scope: BreedWeightAgeScope,
        val adultFallback: Boolean,
    )

    private data class AdultCase(
        val breed: String,
        val sex: PetSex,
        val referenceSex: BreedReferenceSex,
        val statistics: Set<BreedReferenceStatisticKind>,
    )
}
