package com.palixander.scalesync.ui.profiles

import com.palixander.scalesync.charts.ChartDateRange
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.palixander.scalesync.core.reference.ReferenceBasis
import com.palixander.scalesync.core.reference.WeightReferenceSnapshot
import com.palixander.scalesync.domain.Pet
import com.palixander.scalesync.domain.BreedId
import com.palixander.scalesync.domain.PetId
import com.palixander.scalesync.domain.PetSex
import com.palixander.scalesync.domain.PetSpecies
import com.palixander.scalesync.domain.PartialBirthDate
import com.palixander.scalesync.domain.reference.DogAdultWeightCategory
import com.palixander.scalesync.domain.reference.PetWeightReferenceResolver
import com.palixander.scalesync.domain.reference.WeightReferenceUnavailableReason
import com.palixander.scalesync.domain.reference.WeightReferenceProvenance
import com.palixander.scalesync.core.reference.ReferenceBoundsStatistic
import com.palixander.scalesync.core.reference.ReferenceCenterStatistic
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.util.Locale
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
    fun `selected dog breed without reference presents breed unavailable instead of category fallback`() {
        val date = LocalDate.of(2025, 1, 15)
        listOf("VBO:0200290", "VBO:0200470", "VBO:0200880").forEach { breedId ->
            val result = presenter.present(
                dog(PartialBirthDate.Day(date.minusDays(100))).copy(breedId = BreedId(breedId)),
                ChartDateRange(date, date),
            ) as PetHistoryWeightReference.Unavailable

            assertEquals(WeightReferenceUnavailableReason.UnsupportedBreed(breedId), result.reason)
            assertEquals("Для выбранной породы ориентиры сейчас недоступны.", result.explanation)
        }
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
    fun `bounded full history includes interior reference window when endpoints are unavailable`() {
        val birth = LocalDate.of(2025, 1, 1)
        val pet = Pet(
            id = PetId("cat"), displayName = "Барсик", species = PetSpecies.CAT,
            createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH, sex = PetSex.MALE,
            birthDate = PartialBirthDate.Day(birth), breedId = BreedId("VBO:0100223"),
        )
        val range = ChartDateRange(LocalDate.of(1800, 1, 1), LocalDate.of(2500, 1, 1))

        val result = presenter.present(pet, range) as PetHistoryWeightReference.Available
        val dates = result.segments.flatten().map { it.date }

        assertTrue(dates.size <= MAX_REFERENCE_CHART_SAMPLES)
        assertTrue(birth in dates)
        assertTrue(birth.plusDays(56) in dates)
    }

    @Test
    fun `exact observation gap is not bridged by bounded sampling`() {
        val birth = LocalDate.of(2025, 1, 1)
        val pet = Pet(
            id = PetId("cat"), displayName = "Барсик", species = PetSpecies.CAT,
            createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH, sex = PetSex.MALE,
            birthDate = PartialBirthDate.Day(birth), breedId = BreedId("VBO:0100223"),
        )

        val result = presenter.present(
            pet,
            ChartDateRange(LocalDate.of(1800, 1, 1), LocalDate.of(2500, 1, 1)),
        ) as PetHistoryWeightReference.Available

        assertEquals(birth, result.segments.first().single().date)
        assertEquals(birth.plusDays(56), result.segments[1].first().date)
    }

    @Test
    fun `maine coon and siberian birth through day 56 retain distinct observation and model provenance`() {
        val birth = LocalDate.of(2026, 7, 1)
        listOf(BreedId("VBO:0100154"), BreedId("VBO:0100223")).forEach { breedId ->
            PetSex.entries.forEach { sex ->
                val pet = Pet(
                    id = PetId("${breedId.value}-${sex.name}"), displayName = "Барсик",
                    species = PetSpecies.CAT, createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH,
                    sex = sex, birthDate = PartialBirthDate.Day(birth), breedId = breedId,
                )

                val result = presenter.present(pet, ChartDateRange(birth, birth.plusDays(56)))
                    as PetHistoryWeightReference.Available

                assertEquals(2, result.segments.size)
                val exact = result.segments.single { it.provenance == WeightReferenceProvenance.BREED_EXACT_OBSERVATION }
                val model = result.segments.single { it.provenance == WeightReferenceProvenance.BREED_CURVE }
                assertEquals(birth, exact.single().date)
                assertEquals("mugnier-cat-birth-weight-2023", exact.sourceId)
                assertTrue(exact.citation.startsWith("Mugnier"))
                assertEquals("CC BY 4.0", exact.license)
                assertEquals(birth.plusDays(56), model.first().date)
                assertTrue(model.sourceId.startsWith("wikipedia-"))
                assertTrue(model.citation.contains(if (breedId.value == "VBO:0100154") "Maine Coon" else "Sibirische Katze"))
                assertEquals("CC BY-SA 4.0", model.license)
            }
        }
    }

    @Test
    fun `future snapshot exceeding semantic date capacity omits overlay without throwing`() {
        val snapshot = snapshotWithDailyDogProfile(pointCount = MAX_REFERENCE_CHART_SAMPLES + 1)
        val customPresenter = PetHistoryReferencePresenter(
            snapshot = snapshot,
            resolver = PetWeightReferenceResolver(snapshot),
        )
        val birth = LocalDate.of(2025, 1, 1)

        val result = customPresenter.present(
            dog(PartialBirthDate.Day(birth)),
            ChartDateRange(LocalDate.of(1800, 1, 1), birth.plusDays(730)),
        ) as PetHistoryWeightReference.Unavailable

        assertEquals(WeightReferenceUnavailableReason.ProfileUnavailable("dog-male-III"), result.reason)
        assertTrue(result.explanation.contains("dog-male-III"))
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
            assertTrue(weightReferenceUnavailableExplanation(reason).isNotBlank())
        }
    }

    @Test
    fun `cat unavailable states use the approved exact wording`() {
        val date = LocalDate.of(2026, 9, 6)
        val base = Pet(
            id = PetId("cat-state"), displayName = "Барсик", species = PetSpecies.CAT,
            createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH,
            sex = PetSex.MALE, birthDate = PartialBirthDate.Day(date.minusDays(56)),
            breedId = BreedId("VBO:0100000"),
        )
        fun explanation(pet: Pet) = (presenter.present(pet, ChartDateRange(date, date))
            as PetHistoryWeightReference.Unavailable).explanation

        assertEquals(
            "Укажите пол питомца, чтобы показать породный ориентир.",
            explanation(base.copy(sex = null)),
        )
        assertEquals(
            "Укажите дату рождения, чтобы показать ориентир для возраста.",
            explanation(base.copy(birthDate = null)),
        )
        assertEquals(
            "Для выбранной породы ориентиры сейчас недоступны.",
            explanation(base.copy(breedId = BreedId("VBO:0100091"))),
        )
        assertEquals(
            "Для выбранного возраста опубликованные данные отсутствуют.",
            explanation(base.copy(birthDate = PartialBirthDate.Day(date.minusDays(55)))),
        )
        assertEquals(
            "Исправьте дату рождения, чтобы показать ориентир для возраста.",
            explanation(base.copy(birthDate = PartialBirthDate.Day(date.plusDays(1)))),
        )
    }

    @Test
    fun `unavailable bundled snapshot fails closed without hiding pet history`() {
        val date = LocalDate.of(2026, 9, 6)
        val pet = Pet(
            id = PetId("cat-corrupt"), displayName = "Барсик", species = PetSpecies.CAT,
            createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH,
            sex = PetSex.MALE, birthDate = PartialBirthDate.Day(date.minusDays(100)),
            breedId = BreedId("VBO:0100000"),
        )

        val result = PetHistoryReferencePresenter(snapshot = null, resolver = null)
            .present(pet, ChartDateRange(date, date)) as PetHistoryWeightReference.Unavailable

        assertEquals(WeightReferenceUnavailableReason.ProfileUnavailable("bundled-snapshot"), result.reason)
        assertEquals("Ориентиры породы временно недоступны.", result.explanation)
    }

    @Test
    fun `breed observation presentation identifies mean and standard deviation`() {
        val date = LocalDate.of(2026, 9, 6)
        listOf(BreedId("VBO:0100154"), BreedId("VBO:0100223")).forEach { breedId ->
            val pet = Pet(
                id = PetId("kitten-${breedId.value}"),
                displayName = "Барсик",
                species = PetSpecies.CAT,
                createdAt = Instant.EPOCH,
                updatedAt = Instant.EPOCH,
                sex = PetSex.MALE,
                birthDate = PartialBirthDate.Day(date),
                breedId = breedId,
            )

            val result = presenter.present(pet, ChartDateRange(date, date))
                as PetHistoryWeightReference.Available

            assertEquals(ReferenceCenterStatistic.MEAN, result.centerStatistic)
            assertEquals(ReferenceBoundsStatistic.ONE_STANDARD_DEVIATION, result.boundsStatistic)
            assertTrue(result.basisLabel.contains("среднее ± одно стандартное отклонение"))
            assertFalse(result.basisLabel.contains("медиан", ignoreCase = true))
            assertEquals(WeightReferenceProvenance.BREED_EXACT_OBSERVATION, result.provenance)
            assertEquals(pet.breedId, result.selectedBreedId)
            assertTrue(result.provenanceExplanation!!.contains("только точечное наблюдение"))
        }
    }

    @Test
    fun `all five modelled breeds expose breed curves from day 56 through day 730`() {
        val date = LocalDate.of(2026, 9, 6)
        val cases = listOf(
            BreedId("VBO:0100052"),
            BreedId("VBO:0100209"),
            BreedId("VBO:0100221"),
            BreedId("VBO:0100154"),
            BreedId("VBO:0100223"),
        )

        cases.forEach { breedId ->
            val pet = Pet(
                id = PetId("kitten-${breedId.value}"), displayName = "Барсик", species = PetSpecies.CAT,
                createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH, sex = PetSex.MALE,
                birthDate = PartialBirthDate.Day(date.minusDays(56)), breedId = breedId,
            )
            val result = presenter.present(pet, ChartDateRange(date, date.plusDays(674)))
                as PetHistoryWeightReference.Available

            assertEquals(WeightReferenceProvenance.BREED_CURVE, result.provenance)
            assertEquals(breedId, result.selectedBreedId)
            assertTrue(result.provenanceExplanation!!.contains("модельный возрастной диапазон"))
            assertEquals(date, result.segments.last().first().date)
            assertEquals(date.plusDays(674), result.segments.last().last().date)
            assertTrue(result.segments.last().all { point ->
                point.lowerKg <= point.medianLowerKg &&
                    point.medianLowerKg == point.medianUpperKg &&
                    point.medianUpperKg <= point.upperKg
            })
            assertTrue(result.constraints.all { constraint ->
                constraint.none { character -> character in 'A'..'Z' || character in 'a'..'z' }
            })
        }
    }

    @Test
    fun `presenter identifies DSH as a full breed curve`() {
        val date = LocalDate.of(2026, 9, 6)
        val breedId = BreedId("VBO:0100119")
        val pet = Pet(
            id = PetId("dsh"), displayName = "Барсик", species = PetSpecies.CAT,
            createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH, sex = PetSex.MALE,
            birthDate = PartialBirthDate.Day(date.minusDays(56)), breedId = breedId,
        )

        val result = presenter.present(pet, ChartDateRange(date, date))
            as PetHistoryWeightReference.Available

        assertEquals(WeightReferenceProvenance.BREED_CURVE, result.provenance)
        assertEquals(breedId, result.selectedBreedId)
        assertTrue(result.provenanceExplanation!!.contains("модельный возрастной диапазон"))
        assertEquals(
            listOf(
                "Только домашние короткошёрстные кошки",
                "Нестерилизованные котята из США",
                "Возраст от 8 до 78 недель",
            ),
            result.constraints,
        )
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

    private fun snapshotWithDailyDogProfile(pointCount: Int): WeightReferenceSnapshot {
        val root = JsonParser.parseReader(
            javaClass.classLoader!!.getResourceAsStream(WeightReferenceSnapshot.RESOURCE_PATH)!!
                .bufferedReader(),
        ).asJsonObject
        val profile = root.getAsJsonArray("profiles")
            .first { it.asJsonObject.get("id").asString == "dog-male-III" }.asJsonObject
        profile.add("points", JsonArray().apply {
            repeat(pointCount) { index ->
                add(JsonObject().apply {
                    addProperty("ageDays", 84 + index)
                    addProperty("lowerKg", 8.0)
                    addProperty("medianKg", 10.0)
                    addProperty("upperKg", 12.0)
                })
            }
        })
        root.getAsJsonObject("manifest").addProperty("numericalDataSha256", "")
        val canonicalPayload = root.toString().toByteArray()
        val checksum = MessageDigest.getInstance("SHA-256").digest(canonicalPayload)
            .joinToString("") { "%02x".format(Locale.ROOT, it) }
        root.getAsJsonObject("manifest").addProperty("numericalDataSha256", checksum)
        return WeightReferenceSnapshot.load(streamProvider = {
            ByteArrayInputStream(root.toString().toByteArray())
        })
    }
}
