package com.palixander.weightogether.ui.profiles

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.palixander.weightogether.R
import com.palixander.weightogether.core.breedreference.BreedReferenceSnapshotLoadResult
import com.palixander.weightogether.domain.BreedId
import com.palixander.weightogether.domain.PartialBirthDate
import com.palixander.weightogether.domain.Pet
import com.palixander.weightogether.domain.PetId
import com.palixander.weightogether.domain.PetSex
import com.palixander.weightogether.domain.PetSpecies
import com.palixander.weightogether.domain.reference.BreedWeightReferenceResolver
import com.palixander.weightogether.domain.reference.BreedWeightReferenceUnavailableReason
import com.palixander.weightogether.domain.reference.BreedWeightValue
import com.palixander.weightogether.ui.text.UiText
import com.palixander.weightogether.ui.text.resolve
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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "ru-rRU")
class PetHistoryBreedReferencePresenterTest {
    private val resources = ApplicationProvider.getApplicationContext<Context>().resources
    private val today = LocalDate.of(2026, 9, 1)
    private val clock = Clock.fixed(today.atStartOfDay().toInstant(ZoneOffset.UTC), ZoneOffset.UTC)

    @Test
    fun `normal presentation remains separate and exposes source metadata`() {
        val result = presenter().present(dog()) as PetHistoryBreedReference.Available

        assertEquals("Русский чёрный терьер", result.breedName)
        assertEquals(R.string.pet_breed_reference_adult_dog, result.ageLabel.resourceId())
        assertTrue(result.valueLabels.single().testContractText(), result.valueLabels.single().startsWith("Диапазон:"))
        assertEquals(R.string.pet_breed_source_international, result.sourceKindLabel.resourceId())
        assertTrue(result.source.url.startsWith("https://"))
        assertTrue(result.source.title.isNotBlank())
        assertNull(result.partialDateDisclosure)
        assertTrue(result.accessibilityLabel.testContractText().isNotBlank())
        val chartValue = result.chartValues.single() as PetHistoryBreedChartValue.Interval
        assertTrue(chartValue.lowerKg < chartValue.upperKg)
        assertTrue(chartValue.accessibilityLabel.testContractText().contains("Русский чёрный терьер"))
        assertTrue(chartValue.accessibilityLabel.testContractText().contains("кг"))
    }

    @Test
    fun `official interval semantics remain visible in value labels`() {
        val pug = presenter().present(
            dog().copy(breedId = BreedId("VBO:0201089")),
        ) as PetHistoryBreedReference.Available
        val schnauzer = presenter().present(
            dog().copy(breedId = BreedId("VBO:0200898")),
        ) as PetHistoryBreedReference.Available

        assertTrue(pug.valueLabels.single().testContractText(), pug.valueLabels.single().startsWith("Диапазон идеального веса:"))
        assertTrue(schnauzer.valueLabels.single().startsWith("Приблизительный диапазон:"))
    }

    @Test
    fun `partial date discloses possible age and selected source age`() {
        val result = presenter().present(
            dog().copy(birthDate = PartialBirthDate.Month(YearMonth.of(2025, 1))),
        ) as PetHistoryBreedReference.Available

        val disclosure = requireNotNull(result.partialDateDisclosure)
        assertTrue(disclosure.testContractText(), disclosure.startsWith("Дата рождения указана неполно."))
        assertTrue(disclosure.contains("Показан ориентир"))
    }

    @Test
    fun `missing sex offers edit while other breed does not`() {
        val missingSex = presenter().present(dog().copy(sex = null)) as PetHistoryBreedReference.Unavailable
        val other = presenter().present(dog().copy(breedId = null)) as PetHistoryBreedReference.Unavailable

        assertEquals(BreedWeightReferenceUnavailableReason.MissingSex, missingSex.reason)
        assertTrue(missingSex.showEditAction)
        assertEquals(R.string.pet_breed_reference_prompt_sex, missingSex.message.resourceId())
        assertEquals(BreedWeightReferenceUnavailableReason.OtherBreed, other.reason)
        assertFalse(other.showEditAction)
        assertEquals(R.string.pet_breed_reference_other_breed, other.message.resourceId())
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
        assertEquals(R.string.pet_breed_reference_unavailable, removed.message.resourceId())
        assertTrue(invalid.showEditAction)
        assertEquals(R.string.pet_breed_reference_fix_birth, invalid.message.resourceId())
        assertEquals(R.string.pet_breed_reference_temporary, unavailable.message.resourceId())
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
        assertEquals(R.string.pet_breed_reference_age_gap, result.message.resourceId())
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
        assertEquals(R.string.pet_breed_reference_age_gap, result.message.resourceId())
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
            Case("VBO:0200120", PetSex.MALE, "Диапазон: 9,1–10,9"),
            Case("VBO:0201135", PetSex.MALE, "Стандартное значение: 36,5"),
        ).forEach { case ->
            val result = presenter().present(dog().copy(breedId = BreedId(case.id), sex = case.sex)) as PetHistoryBreedReference.Available
            assertTrue("${case.id}: ${result.valueLabels.single().testContractText()} expected ${case.expected}", result.valueLabels.single().startsWith(case.expected))
            assertFalse("${case.id} must not be called median", result.accessibilityLabel.contains("median", ignoreCase = true))
            assertFalse(result.chartValues.any { it.statisticLabel.contains("median", ignoreCase = true) })
        }

        val central = presenter().present(dog().copy(breedId = BreedId("VBO:0200321"), sex = PetSex.MALE)) as PetHistoryBreedReference.Available
        assertTrue(central.valueLabels.single().startsWith("Минимальный вес: 50"))
        assertTrue(central.companionReferences.single().valueLabels.single().startsWith("Диапазон: 40–80"))
        assertFalse(central.accessibilityLabel.contains("median", ignoreCase = true))
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
    fun `unchanged official point and minimum labels survive presentation into chart series`() {
        val cases = listOf(
            Triple("VBO:0200321", "Минимальный вес", 50.0),
            Triple("VBO:0200485", "Стандартное значение", 25.0),
        )
        cases.forEach { (breedId, label, expected) ->
            val pet = dog().copy(breedId = BreedId(breedId), birthDate = PartialBirthDate.Day(LocalDate.of(2024, 9, 1)))
            val presentation = presenter().present(pet) as PetHistoryBreedReference.Available
            val value = presentation.chartValues.filterIsInstance<PetHistoryBreedChartValue.Single>().single()
            val timeline = presenter().presentTimeline(pet, listOf(PetHistoryBreedReferenceTimelineMoment(1, today)))
            val series = breedWeightReferenceChartSeries(timeline).single()

            assertEquals(expected, value.valueKg, 0.0)
            assertTrue(value.statisticLabel.testContractText(), value.statisticLabel.contains(label))
            assertTrue(value.accessibilityLabel.contains(label))
            assertTrue(requireNotNull(series.statisticLabel).contains(label))
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
        assertEquals(R.string.pet_breed_stat_maximum, maximumValue.statisticLabel.resourceId())
        assertTrue(maximumValue.accessibilityLabel.testContractText(), maximumValue.accessibilityLabel.contains("Максимальный вес"))
        assertTrue(maximumValue.accessibilityLabel.contains("5 кг"))
        assertEquals("Infobox", maximum.source.pageOrTable)
        assertTrue(maximum.source.method!!.contains("Miniature-size infobox maximum of 5.0 kg"))
        assertTrue(!maximum.source.method.contains("16–32 lb"))
        assertTrue(maximum.source.limitations.any { it.contains("5.5 kg") })
        assertTrue(maximum.accessibilityLabel.testContractText().contains("Максимальный вес"))
        assertTrue(maximum.companionReferences.isEmpty())

        val range = presenter().present(
            dog().copy(breedId = BreedId("VBO:0201198")),
        ) as PetHistoryBreedReference.Available
        val rangeValue = range.chartValues.single() as PetHistoryBreedChartValue.Interval
        assertEquals(8.5, rangeValue.lowerKg, 0.0)
        assertEquals(10.5, rangeValue.upperKg, 0.0)
        assertNull(rangeValue.centerKg)

        val rottweiler = presenter().present(
            dog().copy(breedId = BreedId("VBO:0201143"), sex = PetSex.MALE),
        ) as PetHistoryBreedReference.Available
        val rottweilerRange = rottweiler.chartValues.single() as PetHistoryBreedChartValue.Interval
        assertEquals(50.0, rottweilerRange.lowerKg, 0.0)
        assertEquals(60.0, rottweilerRange.upperKg, 0.0)

        val americanAkita = presenter().present(
            dog().copy(breedId = BreedId("VBO:0200027")),
        ) as PetHistoryBreedReference.Available
        val akitaRange = americanAkita.chartValues.single() as PetHistoryBreedChartValue.Interval
        assertEquals(45.0, akitaRange.lowerKg, 0.0)
        assertEquals(59.0, akitaRange.upperKg, 0.0)
    }


    @Test
    fun `new fallback ranges retain inactive official values in accessible details`() {
        data class Case(
            val breedId: String,
            val sex: PetSex,
            val lower: Double,
            val upper: Double,
            val inactiveValue: String,
            val inactiveSource: String,
        )
        listOf(
            Case("VBO:0200120", PetSex.MALE, 9.1, 10.9, "11", "FCI Standard No. 43 Basenji"),
            Case("VBO:0201143", PetSex.MALE, 50.0, 60.0, "50", "FCI Standard No. 147 Rottweiler"),
        ).forEach { case ->
            val result = presenter().present(
                dog().copy(breedId = BreedId(case.breedId), sex = case.sex),
            ) as PetHistoryBreedReference.Available
            val interval = result.chartValues.single() as PetHistoryBreedChartValue.Interval

            assertEquals(case.lower, interval.lowerKg, 0.0)
            assertEquals(case.upper, interval.upperKg, 0.0)
            assertTrue(result.accessibilityLabel.contains("Диапазон"))
            assertTrue(result.details.any { detail ->
                detail.valueLabel.contains(case.inactiveValue) && detail.sourceTitle == case.inactiveSource
            })
        }
    }

    @Test
    fun `Sheltie uses one combined range for either sex`() {
        listOf(PetSex.MALE, PetSex.FEMALE).forEach { sex ->
            val result = presenter().present(
                dog().copy(breedId = BreedId("VBO:0201217"), sex = sex),
            ) as PetHistoryBreedReference.Available
            val interval = result.chartValues.single() as PetHistoryBreedChartValue.Interval
            assertEquals(6.8, interval.lowerKg, 0.0)
            assertEquals(11.3, interval.upperKg, 0.0)
            assertTrue(result.accessibilityLabel.contains("6,8–11,3"))
        }
    }

    @Test
    fun `Yorkie Italian Greyhound and Miniature Dachshund maxima render upper-domain bands`() {
        listOf("VBO:0201448", "VBO:0200713", "VBO:0200410").forEach { breedId ->
            val pet = dog().copy(breedId = BreedId(breedId), sex = PetSex.FEMALE)
            val presentation = presenter().present(pet) as PetHistoryBreedReference.Available
            val published = presentation.chartValues.single() as PetHistoryBreedChartValue.Boundary
            val moments = listOf(
                PetHistoryBreedReferenceTimelineMoment(1L, today.minusDays(1)),
                PetHistoryBreedReferenceTimelineMoment(2L, today),
            )
            val timeline = presenter().presentTimeline(pet, moments)
            val band = breedWeightReferenceBands(timeline).single()

            assertEquals(BreedWeightValue.Boundary.Direction.UPPER, published.direction)
            assertEquals(R.string.pet_breed_stat_maximum, published.statisticLabel.resourceId())
            assertEquals(BreedWeightReferenceBand.LowerEdge.CHART_DOMAIN_MINIMUM, band.lowerEdge)
            assertTrue(band.points.all { it.upperKg == published.valueKg })
            assertTrue(band.points.all { it.lowerKg == published.valueKg })
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

    private fun UiText.resourceId(): Int = (this as UiText.Resource).id

    private fun UiText.contains(value: String, ignoreCase: Boolean = false): Boolean =
        testContractText().contains(value, ignoreCase)

    private fun UiText.startsWith(value: String): Boolean = testContractText().startsWith(value)

    private fun UiText.testContractText(): String = resolve(resources)

    private fun Any.testContractText(): String = (this as? UiText)?.testContractText() ?: toString()
}
