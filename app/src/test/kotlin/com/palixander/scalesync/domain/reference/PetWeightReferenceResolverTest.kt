package com.palixander.scalesync.domain.reference

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.palixander.scalesync.core.reference.ReferenceBasis
import com.palixander.scalesync.core.reference.ReferenceKind
import com.palixander.scalesync.core.reference.WeightReferenceSnapshot
import com.palixander.scalesync.domain.BreedId
import com.palixander.scalesync.domain.PetSex
import com.palixander.scalesync.domain.PetSpecies
import com.palixander.scalesync.domain.PartialBirthDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.time.LocalDate
import java.time.YearMonth

class PetWeightReferenceResolverTest {
    private val resolver = PetWeightReferenceResolver()
    private val referenceDate = LocalDate.of(2025, 1, 15)

    @Test
    fun `breed category mappings contain only Salt Table 1 evidence`() {
        assertEquals(
            listOf(
                DogBreedAdultWeightCategoryMapping(
                    BreedId("VBO:0200131"),
                    DogAdultWeightCategory.III,
                    DogAdultWeightCategoryEvidence.SALT_2017_TABLE_1,
                ),
                DogBreedAdultWeightCategoryMapping(
                    BreedId("VBO:0200800"),
                    DogAdultWeightCategory.V,
                    DogAdultWeightCategoryEvidence.SALT_2017_TABLE_1,
                ),
            ),
            DogBreedAdultWeightCategoryMappings.entries,
        )
        assertEquals(
            DogAdultWeightCategory.III,
            DogBreedAdultWeightCategoryMappings.find(BreedId("VBO:0200131"))?.category,
        )
        assertEquals(
            DogAdultWeightCategory.V,
            DogBreedAdultWeightCategoryMappings.find(BreedId("VBO:0200800"))?.category,
        )
        assertEquals(null, DogBreedAdultWeightCategoryMappings.find(BreedId("VBO:0200174")))
        assertEquals(null, DogBreedAdultWeightCategoryMappings.find(null))
    }

    @Test
    fun `exact dog date resolves explicit category without approximation`() {
        val result = resolveDog(
            birthDate = PartialBirthDate.Day(referenceDate.minusDays(100)),
            dogAdultWeight = DogAdultWeight.Category(DogAdultWeightCategory.III),
        ).available()

        assertEquals("dog-male-III", result.profileId)
        assertEquals(ReferenceBasis.WEIGHT_CATEGORY, result.basis)
        assertEquals(100L..100L, result.ageDays)
        assertFalse(result.approximate)
        assertEquals(result.bounds.medianLowerKg, result.bounds.medianUpperKg, 0.0)
    }

    @Test
    fun `interpolation does not by itself make an exact birth date approximate`() {
        val snapshot = WeightReferenceSnapshot.bundled()
        val profile = snapshot.profiles.single { it.id == "dog-male-I" }
        val nonPointAge = (profile.points[0].ageDays + 1).toLong()

        val result = resolveDog(
            birthDate = PartialBirthDate.Day(referenceDate.minusDays(nonPointAge)),
            dogAdultWeight = DogAdultWeight.ExpectedWeightKg(6.49),
        ).available()

        assertFalse(result.approximate)
        assertEquals("dog-male-I", result.profileId)
        assertEquals(snapshot.interpolate(profile.id, nonPointAge.toInt())!!.medianKg, result.bounds.medianLowerKg, 1e-12)
    }

    @Test
    fun `dog category reference is unavailable before first point and carries last point indefinitely`() {
        val snapshot = WeightReferenceSnapshot.bundled()
        val last = snapshot.profiles.single { it.id == "dog-male-I" }.points.last()

        for (ageDays in 728L..730L) {
            val result = resolveDog(
                birthDate = PartialBirthDate.Day(referenceDate.minusDays(ageDays)),
                dogAdultWeight = DogAdultWeight.Category(DogAdultWeightCategory.I),
            ).available()

            assertEquals(ageDays..ageDays, result.ageDays)
            assertEquals(last.lowerKg, result.bounds.lowerKg, 1e-12)
            assertEquals(last.medianKg, result.bounds.medianLowerKg, 1e-12)
            assertEquals(last.medianKg, result.bounds.medianUpperKg, 1e-12)
            assertEquals(last.upperKg, result.bounds.upperKg, 1e-12)
        }

        val carriedAge = 10_000L
        val carried = resolveDog(
            birthDate = PartialBirthDate.Day(referenceDate.minusDays(carriedAge)),
            dogAdultWeight = DogAdultWeight.Category(DogAdultWeightCategory.I),
        ).available()
        assertEquals(last.lowerKg, carried.bounds.lowerKg, 1e-12)
        assertEquals(last.medianKg, carried.bounds.medianLowerKg, 1e-12)
        assertEquals(last.upperKg, carried.bounds.upperKg, 1e-12)

        assertReason<WeightReferenceUnavailableReason.AgeOutOfRange>(
            resolveDog(
                birthDate = PartialBirthDate.Day(referenceDate.minusDays(83)),
                dogAdultWeight = DogAdultWeight.Category(DogAdultWeightCategory.I),
            ),
        )
    }

    @Test
    fun `resolver enforces opt-in declared age range without changing legacy carry forward`() {
        val root = javaClass.classLoader.getResourceAsStream(WeightReferenceSnapshot.RESOURCE_PATH)!!
            .bufferedReader().use { JsonParser.parseString(it.readText()).asJsonObject }
        val profile = root.getAsJsonArray("profiles")
            .first { it.asJsonObject.get("id").asString == "dog-male-I" }.asJsonObject
        val lastAge = profile.getAsJsonArray("points").last().asJsonObject.get("ageDays").asInt
        profile.addProperty("ageAvailability", "declared_range_only")
        root.getAsJsonObject("manifest").getAsJsonArray("scopes")
            .first { it.asJsonObject.get("id").asString == "dog-male-I" }.asJsonObject.apply {
                addProperty("ageAvailability", "declared_range_only")
                addProperty("maximumAgeDays", lastAge)
            }
        val canonical = root.getAsJsonArray("profiles").toString().toByteArray()
        root.getAsJsonObject("manifest").addProperty(
            "numericalDataSha256",
            MessageDigest.getInstance("SHA-256").digest(canonical).joinToString("") { "%02x".format(it) },
        )
        val boundedResolver = PetWeightReferenceResolver(
            WeightReferenceSnapshot.load(streamProvider = { ByteArrayInputStream(Gson().toJson(root).toByteArray()) }),
        )

        val boundary = boundedResolver.resolve(
            PetSpecies.DOG,
            PetSex.MALE,
            null,
            PartialBirthDate.Day(referenceDate.minusDays(lastAge.toLong())),
            referenceDate,
            DogAdultWeight.Category(DogAdultWeightCategory.I),
        ).available()
        assertEquals(lastAge.toLong()..lastAge.toLong(), boundary.ageDays)

        val after = boundedResolver.resolve(
            PetSpecies.DOG,
            PetSex.MALE,
            null,
            PartialBirthDate.Day(referenceDate.minusDays(lastAge + 1L)),
            referenceDate,
            DogAdultWeight.Category(DogAdultWeightCategory.I),
        ).unavailable().reason as WeightReferenceUnavailableReason.AgeOutOfRange
        assertEquals(lastAge, after.supportedMaximumDays)

        assertTrue(
            resolveDog(
                birthDate = PartialBirthDate.Day(referenceDate.minusDays(10_000)),
                dogAdultWeight = DogAdultWeight.Category(DogAdultWeightCategory.I),
            ) is PetWeightReferenceResolution.Available,
        )
    }

    @Test
    fun `partial dog birth date may end exactly at declared upper boundary`() {
        val boundaryDate = LocalDate.of(2025, 1, 31)
        val result = resolver.resolve(
            PetSpecies.DOG,
            PetSex.MALE,
            null,
            PartialBirthDate.Month(YearMonth.of(2023, 2)),
            boundaryDate,
            DogAdultWeight.Category(DogAdultWeightCategory.I),
        ).available()

        assertEquals(703L..730L, result.ageDays)
        assertTrue(result.approximate)

        val crossing = resolveDog(
            birthDate = PartialBirthDate.Month(YearMonth.of(2023, 1)),
            dogAdultWeight = DogAdultWeight.Category(DogAdultWeightCategory.I),
        ).available()
        assertEquals(715L..745L, crossing.ageDays)
        assertTrue(crossing.bounds.medianUpperKg <= crossing.bounds.upperKg)
    }

    @Test
    fun `partial date evaluates both age endpoints and aggregates conservative bounds`() {
        val birthDate = PartialBirthDate.Month(YearMonth.of(2024, 8))
        val snapshot = WeightReferenceSnapshot.bundled()
        val profile = snapshot.profiles.single { it.id == "dog-male-IV" }
        val younger = snapshot.interpolate(profile.id, 137)!!
        val older = snapshot.interpolate(profile.id, 167)!!

        val result = resolveDog(
            birthDate = birthDate,
            dogAdultWeight = DogAdultWeight.Category(DogAdultWeightCategory.IV),
        ).available()

        assertTrue(result.approximate)
        assertEquals(137L..167L, result.ageDays)
        assertEquals(minOf(younger.lowerKg, older.lowerKg), result.bounds.lowerKg, 1e-12)
        assertEquals(minOf(younger.medianKg, older.medianKg), result.bounds.medianLowerKg, 1e-12)
        assertEquals(maxOf(younger.medianKg, older.medianKg), result.bounds.medianUpperKg, 1e-12)
        assertEquals(maxOf(younger.upperKg, older.upperKg), result.bounds.upperKg, 1e-12)
    }

    @Test
    fun `partial date includes internal extrema instead of only interval endpoints`() {
        val date = LocalDate.of(2024, 8, 8)
        val snapshot = WeightReferenceSnapshot.bundled()
        val profile = snapshot.profiles.single { it.id == "dog-female-I" }
        val expected = profile.points.filter { it.ageDays in 161..189 }

        val result = resolver.resolve(
            PetSpecies.DOG,
            PetSex.FEMALE,
            null,
            PartialBirthDate.Month(YearMonth.of(2024, 2)),
            date,
            DogAdultWeight.Category(DogAdultWeightCategory.I),
        ).available()

        assertEquals(161L..189L, result.ageDays)
        assertEquals(expected.minOf { it.lowerKg }, result.bounds.lowerKg, 1e-12)
        assertEquals(expected.minOf { it.medianKg }, result.bounds.medianLowerKg, 1e-12)
        assertEquals(expected.maxOf { it.medianKg }, result.bounds.medianUpperKg, 1e-12)
        assertEquals(expected.maxOf { it.upperKg }, result.bounds.upperKg, 1e-12)
    }

    @Test
    fun `cat breed profile interpolates former long gaps and carries final percentiles indefinitely`() {
        val breed = BreedId("VBO:0100119")
        for (sex in listOf(PetSex.FEMALE, PetSex.MALE)) {
            val interpolated = resolver.resolve(
                PetSpecies.CAT,
                sex,
                breed,
                PartialBirthDate.Day(referenceDate.minusDays(230)),
                referenceDate,
                intactStatus = IntactStatus.CONFIRMED_INTACT,
            ).available()
            val carried = resolver.resolve(
                PetSpecies.CAT,
                sex,
                breed,
                PartialBirthDate.Day(referenceDate.minusDays(10_000)),
                referenceDate,
                intactStatus = IntactStatus.CONFIRMED_INTACT,
            ).available()
            listOf(interpolated.bounds, carried.bounds).forEach { bounds ->
                assertTrue(bounds.lowerKg <= bounds.medianLowerKg)
                assertTrue(bounds.medianLowerKg <= bounds.medianUpperKg)
                assertTrue(bounds.medianUpperKg <= bounds.upperKg)
            }
        }
    }

    @Test
    fun `partial date across final point uses extrema across the entire possible interval`() {
        val snapshot = WeightReferenceSnapshot.bundled()
        val profile = snapshot.profiles.single { it.id == "dog-male-I" }
        val last = profile.points.last()
        val result = resolver.resolve(
            PetSpecies.DOG,
            PetSex.MALE,
            null,
            PartialBirthDate.Month(YearMonth.of(2023, 1)),
            referenceDate,
            DogAdultWeight.Category(DogAdultWeightCategory.I),
        ).available()
        val candidates = buildList {
            add(snapshot.interpolate(profile.id, result.ageDays.first.toInt())!!)
            addAll(profile.points.filter { it.ageDays.toLong() in result.ageDays })
            add(last.copy(ageDays = result.ageDays.last.toInt()))
        }
        assertEquals(candidates.minOf { it.lowerKg }, result.bounds.lowerKg, 1e-12)
        assertEquals(candidates.minOf { it.medianKg }, result.bounds.medianLowerKg, 1e-12)
        assertEquals(candidates.maxOf { it.medianKg }, result.bounds.medianUpperKg, 1e-12)
        assertEquals(candidates.maxOf { it.upperKg }, result.bounds.upperKg, 1e-12)
        assertTrue(result.bounds.lowerKg <= result.bounds.medianLowerKg)
        assertTrue(result.bounds.medianLowerKg <= result.bounds.medianUpperKg)
        assertTrue(result.bounds.medianUpperKg <= result.bounds.upperKg)
    }

    @Test
    fun `future birth date is typed invalid data instead of throwing`() {
        assertReason<WeightReferenceUnavailableReason.InvalidBirthDate>(
            resolveDog(
                birthDate = PartialBirthDate.Day(referenceDate.plusDays(1)),
                dogAdultWeight = DogAdultWeight.Category(DogAdultWeightCategory.I),
            ),
        )
    }

    @Test
    fun `expected dog weights map deterministically at category boundaries`() {
        val cases = listOf(
            6.4999 to "I",
            6.5 to "II",
            8.9999 to "II",
            9.0 to "III",
            15.0 to "IV",
            30.0 to "V",
        )
        cases.forEach { (weight, category) ->
            val result = resolveDog(
                birthDate = PartialBirthDate.Day(referenceDate.minusDays(100)),
                dogAdultWeight = DogAdultWeight.ExpectedWeightKg(weight),
            ).available()
            assertEquals("dog-male-$category", result.profileId)
        }
    }

    @Test
    fun `ordinary VBO dog breeds fall back to weight category and report actual basis`() {
        val birthDate = PartialBirthDate.Day(referenceDate.minusDays(100))
        listOf("VBO:0000661", "VBO:0000663", "VBO:0000664").forEach { breedId ->
            val result = resolveDog(
                breedId = BreedId(breedId),
                birthDate = birthDate,
                dogAdultWeight = DogAdultWeight.Category(DogAdultWeightCategory.II),
            ).available()

            assertEquals("dog-male-II", result.profileId)
            assertEquals(ReferenceBasis.WEIGHT_CATEGORY, result.basis)
        }
    }

    @Test
    fun `ordinary VBO dog breed without category reports missing adult weight`() {
        assertReason<WeightReferenceUnavailableReason.MissingDogAdultWeight>(
            resolveDog(
                breedId = BreedId("VBO:0000661"),
                birthDate = PartialBirthDate.Day(referenceDate.minusDays(100)),
            ),
        )
    }

    @Test
    fun `supported DSH breed resolves for both sexes without intact status`() {
        val breed = BreedId("VBO:0100119")
        val birthDate = PartialBirthDate.Day(referenceDate.minusDays(100))
        listOf(PetSex.FEMALE to "cat-dsh-female", PetSex.MALE to "cat-dsh-male").forEach { (sex, profileId) ->
            for (intactStatus in IntactStatus.entries) {
                val result = resolver.resolve(
                    PetSpecies.CAT,
                    sex,
                    breed,
                    birthDate,
                    referenceDate,
                    intactStatus = intactStatus,
                ).available()
                assertEquals(profileId, result.profileId)
                assertEquals(ReferenceBasis.BREED, result.basis)
            }
        }
    }

    @Test
    fun `licensed breed birth observations resolve only on the exact day`() {
        val cases = listOf(
            BreedId("VBO:0100154") to listOf(0.1004, 0.1191, 0.1378),
            BreedId("VBO:0100223") to listOf(0.0826, 0.0993, 0.116),
        )
        cases.forEach { (breedId, weights) ->
            for (sex in listOf(PetSex.FEMALE, PetSex.MALE)) {
                val atBirth = resolver.resolve(
                    PetSpecies.CAT,
                    sex,
                    breedId,
                    PartialBirthDate.Day(referenceDate),
                    referenceDate,
                ).available()
                assertEquals(ReferenceBasis.BREED, atBirth.basis)
                assertEquals(weights[0], atBirth.bounds.lowerKg, 1e-12)
                assertEquals(weights[1], atBirth.bounds.medianLowerKg, 1e-12)
                assertEquals(weights[1], atBirth.bounds.medianUpperKg, 1e-12)
                assertEquals(weights[2], atBirth.bounds.upperKg, 1e-12)

                assertReason<WeightReferenceUnavailableReason.AgeOutOfRange>(
                    resolver.resolve(
                        PetSpecies.CAT,
                        sex,
                        breedId,
                        PartialBirthDate.Day(referenceDate.minusDays(1)),
                        referenceDate,
                    ),
                )
            }
        }
    }

    @Test
    fun `other cat breed uses sex specific fitted population profile`() {
        val snapshot = WeightReferenceSnapshot.bundled()
        val birthDate = PartialBirthDate.Day(referenceDate.minusDays(56))
        val cases = listOf(
            CatPopulationCase(PetSex.FEMALE, "cat-population-female", 0.636271, 0.890118, 1.228598),
            CatPopulationCase(PetSex.MALE, "cat-population-male", 0.567307, 0.861525, 1.265159),
        )
        cases.forEach { case ->
            val result = resolver.resolve(PetSpecies.CAT, case.sex, null, birthDate, referenceDate).available()

            assertEquals(case.profileId, result.profileId)
            assertEquals(ReferenceBasis.POPULATION, result.basis)
            assertEquals(ReferenceKind.FITTED_BCCG_PERCENTILES, snapshot.metadataFor(result.profileId)!!.referenceKind)
            assertEquals(case.p9, result.bounds.lowerKg, 1e-12)
            assertEquals(case.p50, result.bounds.medianLowerKg, 1e-12)
            assertEquals(case.p50, result.bounds.medianUpperKg, 1e-12)
            assertEquals(case.p91, result.bounds.upperKg, 1e-12)
        }
    }

    @Test
    fun `unsupported and unknown cat breeds use fitted population profile`() {
        val snapshot = WeightReferenceSnapshot.bundled()
        val birthDate = PartialBirthDate.Day(referenceDate.minusDays(56))
        listOf(BreedId("VBO:0100000"), BreedId("external:cat:future")).forEach { breedId ->
            val result = resolver.resolve(
                PetSpecies.CAT,
                PetSex.MALE,
                breedId,
                birthDate,
                referenceDate,
            ).available()

            assertEquals("cat-population-male", result.profileId)
            assertEquals(ReferenceBasis.POPULATION, result.basis)
            assertEquals(ReferenceKind.FITTED_BCCG_PERCENTILES, snapshot.metadataFor(result.profileId)!!.referenceKind)
            assertEquals(0.567307, result.bounds.lowerKg, 1e-12)
            assertEquals(0.861525, result.bounds.medianLowerKg, 1e-12)
            assertEquals(0.861525, result.bounds.medianUpperKg, 1e-12)
            assertEquals(1.265159, result.bounds.upperKg, 1e-12)
        }
    }

    @Test
    fun `missing inputs and unsupported species have distinct reasons`() {
        val date = PartialBirthDate.Day(referenceDate.minusDays(100))
        assertReason<WeightReferenceUnavailableReason.MissingSex>(
            resolver.resolve(PetSpecies.DOG, null, null, date, referenceDate),
        )
        assertReason<WeightReferenceUnavailableReason.MissingBirthDate>(
            resolver.resolve(PetSpecies.DOG, PetSex.MALE, null, null, referenceDate),
        )
        assertReason<WeightReferenceUnavailableReason.MissingDogAdultWeight>(
            resolveDog(birthDate = date),
        )
        assertReason<WeightReferenceUnavailableReason.MissingSex>(
            resolver.resolve(PetSpecies.CAT, null, null, date, referenceDate),
        )
        assertReason<WeightReferenceUnavailableReason.MissingBirthDate>(
            resolver.resolve(PetSpecies.CAT, PetSex.MALE, null, null, referenceDate),
        )
        assertReason<WeightReferenceUnavailableReason.UnsupportedSpecies>(
            resolver.resolve(PetSpecies.UNSPECIFIED, PetSex.MALE, null, date, referenceDate),
        )
    }

    @Test
    fun `dog breed lookup failures and species mismatch remain distinct`() {
        val date = PartialBirthDate.Day(referenceDate.minusDays(100))
        assertReason<WeightReferenceUnavailableReason.UnknownBreed>(
            resolveDog(BreedId("not-in-catalog"), date, DogAdultWeight.Category(DogAdultWeightCategory.I)),
        )
        assertReason<WeightReferenceUnavailableReason.BreedSpeciesMismatch>(
            resolveDog(BreedId("VBO:0100119"), date, DogAdultWeight.Category(DogAdultWeightCategory.I)),
        )
        assertReason<WeightReferenceUnavailableReason.BreedSpeciesMismatch>(
            resolver.resolve(
                PetSpecies.CAT,
                PetSex.MALE,
                BreedId("VBO:0000661"),
                date,
                referenceDate,
            ),
        )
    }

    @Test
    fun `over forty kg and ages crossing lower profile edge are typed unavailable`() {
        assertReason<WeightReferenceUnavailableReason.AdultWeightAboveSupportedMaximum>(
            resolveDog(
                birthDate = PartialBirthDate.Day(referenceDate.minusDays(100)),
                dogAdultWeight = DogAdultWeight.ExpectedWeightKg(40.01),
            ),
        )
        val tooYoung = resolveDog(
            birthDate = PartialBirthDate.Day(referenceDate.minusDays(83)),
            dogAdultWeight = DogAdultWeight.Category(DogAdultWeightCategory.I),
        ).unavailable().reason as WeightReferenceUnavailableReason.AgeOutOfRange
        assertEquals(83, tooYoung.actualMinimumDays)

    }

    private fun resolveDog(
        breedId: BreedId? = null,
        birthDate: PartialBirthDate?,
        dogAdultWeight: DogAdultWeight? = null,
    ) = resolver.resolve(
        PetSpecies.DOG,
        PetSex.MALE,
        breedId,
        birthDate,
        referenceDate,
        dogAdultWeight,
    )

    private data class CatPopulationCase(
        val sex: PetSex,
        val profileId: String,
        val p9: Double,
        val p50: Double,
        val p91: Double,
    )

    private fun PetWeightReferenceResolution.available() =
        (this as PetWeightReferenceResolution.Available).reference

    private fun PetWeightReferenceResolution.unavailable() =
        this as PetWeightReferenceResolution.Unavailable

    private inline fun <reified T : WeightReferenceUnavailableReason> assertReason(
        result: PetWeightReferenceResolution,
    ) {
        assertTrue(result.unavailable().reason is T)
    }
}
