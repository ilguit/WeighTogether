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
    fun `selected unsupported cat breed never falls back to population reference`() {
        val result = resolver.resolve(
            species = PetSpecies.CAT,
            sex = PetSex.FEMALE,
            breedId = BreedId("VBO:0100278"),
            birthDate = PartialBirthDate.Day(referenceDate.minusDays(200)),
            referenceDate = referenceDate,
        )

        assertEquals(
            WeightReferenceUnavailableReason.UnsupportedBreed("VBO:0100278"),
            (result as PetWeightReferenceResolution.Unavailable).reason,
        )
    }

    @Test
    fun `legacy Canadian Sphynx id resolves through canonical Sphynx profile`() {
        val result = resolver.resolve(
            species = PetSpecies.CAT,
            sex = PetSex.FEMALE,
            breedId = BreedId("VBO:0100061"),
            birthDate = PartialBirthDate.Day(referenceDate.minusDays(365)),
            referenceDate = referenceDate,
        ).available()

        assertEquals(BreedId("VBO:0100230"), result.selectedBreedId)
        assertEquals(ReferenceBasis.BREED, result.basis)
    }

    @Test
    fun `all modelled cat breed ranges carry their adult plateau through finite product maximum`() {
        val snapshot = WeightReferenceSnapshot.bundled()
        val profiles = snapshot.profiles.filter { it.id.matches(Regex("cat-breed-\\d{7}-(female|male)")) }

        assertEquals(52, profiles.size)
        profiles.forEach { profile ->
            val maturity = profile.points.last().ageDays.toLong()
            val supportedMaximum = snapshot.metadataFor(profile.id)!!.supportedMaximumAgeDays.toLong()
            val sex = when (profile.sex.name) {
                "FEMALE" -> PetSex.FEMALE
                "MALE" -> PetSex.MALE
                else -> error("Unexpected breed profile sex: ${profile.sex}")
            }
            val result = resolver.resolve(
                species = PetSpecies.CAT,
                sex = sex,
                breedId = BreedId(profile.breedId!!),
                birthDate = PartialBirthDate.Day(referenceDate.minusDays(maturity + 1)),
                referenceDate = referenceDate,
            ).available()
            val adult = profile.points.last()

            assertEquals(profile.id, result.profileId)
            assertEquals(maturity + 1..maturity + 1, result.ageDays)
            assertEquals(adult.lowerKg, result.bounds.lowerKg, 1e-12)
            assertEquals(adult.medianKg, result.bounds.medianLowerKg, 1e-12)
            assertEquals(adult.medianKg, result.bounds.medianUpperKg, 1e-12)
            assertEquals(adult.upperKg, result.bounds.upperKg, 1e-12)

            val after = resolver.resolve(
                species = PetSpecies.CAT,
                sex = sex,
                breedId = BreedId(profile.breedId!!),
                birthDate = PartialBirthDate.Day(referenceDate.minusDays(supportedMaximum + 1)),
                referenceDate = referenceDate,
            ).unavailable().reason as WeightReferenceUnavailableReason.AgeOutOfRange
            assertEquals(supportedMaximum.toInt(), after.supportedMaximumDays)
        }
    }

    @Test
    fun `cat without selected breed retains population reference behavior`() {
        val result = resolver.resolve(
            species = PetSpecies.CAT,
            sex = PetSex.FEMALE,
            breedId = null,
            birthDate = PartialBirthDate.Day(referenceDate.minusDays(200)),
            referenceDate = referenceDate,
        ) as PetWeightReferenceResolution.Available

        assertEquals(ReferenceBasis.POPULATION, result.reference.basis)
        assertEquals(WeightReferenceProvenance.POPULATION, result.reference.provenance)
    }

    @Test
    fun `null unknown and mixed breeds use generic species reference routing`() {
        val genericBreedIds = listOf(
            null,
            BreedId("scalesync:cat:breed-unknown"),
            BreedId("scalesync:cat:mixed-breed"),
        )
        for (sex in listOf(PetSex.FEMALE, PetSex.MALE)) {
            for (breedId in genericBreedIds) {
                val result = resolver.resolve(
                    PetSpecies.CAT,
                    sex,
                    breedId,
                    PartialBirthDate.Day(referenceDate.minusDays(1_000)),
                    referenceDate,
                ).available()
                assertEquals("cat-population-${sex.name.lowercase()}", result.profileId)
                assertEquals(ReferenceBasis.POPULATION, result.basis)
                assertEquals(breedId, result.selectedBreedId)
            }
        }

        for (sex in listOf(PetSex.FEMALE, PetSex.MALE)) {
            for (breedId in listOf(
                null,
                BreedId("scalesync:dog:breed-unknown"),
                BreedId("scalesync:dog:mixed-breed"),
            )) {
                val result = resolver.resolve(
                    PetSpecies.DOG,
                    sex,
                    breedId,
                    PartialBirthDate.Day(referenceDate.minusDays(1_000)),
                    referenceDate,
                    DogAdultWeight.Category(DogAdultWeightCategory.III),
                ).available()
                assertEquals("dog-${sex.name.lowercase()}-III", result.profileId)
                assertEquals(ReferenceBasis.WEIGHT_CATEGORY, result.basis)
                assertEquals(breedId, result.selectedBreedId)
            }
        }
    }

    @Test
    fun `Russian Blue adult plateau is available for both sexes through finite product maximum`() {
        val snapshot = WeightReferenceSnapshot.bundled()
        val breedId = BreedId("VBO:0100200")
        for (sex in listOf(PetSex.FEMALE, PetSex.MALE)) {
            val profile = snapshot.profiles.single { it.breedId == breedId.value && it.sex.name == sex.name }
            val maturity = profile.points.last().ageDays.toLong()
            val adult = profile.points.last()
            for (ageDays in listOf(maturity, maturity + 1, 3_000L, 10_958L)) {
                val result = resolver.resolve(
                    PetSpecies.CAT,
                    sex,
                    breedId,
                    PartialBirthDate.Day(referenceDate.minusDays(ageDays)),
                    referenceDate,
                ).available()
                assertEquals(profile.id, result.profileId)
                assertEquals(adult.lowerKg, result.bounds.lowerKg, 1e-12)
                assertEquals(adult.upperKg, result.bounds.upperKg, 1e-12)
            }
            val after = resolver.resolve(
                PetSpecies.CAT,
                sex,
                breedId,
                PartialBirthDate.Day(referenceDate.minusDays(10_959)),
                referenceDate,
            ).unavailable().reason as WeightReferenceUnavailableReason.AgeOutOfRange
            assertEquals(10_958, after.supportedMaximumDays)
        }
    }

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
    fun `dog category reference carries final point through explicit thirty year maximum`() {
        val snapshot = WeightReferenceSnapshot.bundled()
        val last = snapshot.profiles.single { it.id == "dog-male-I" }.points.last()

        for (ageDays in listOf(728L, 730L, 731L, 10_958L)) {
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

        val afterBoundary = resolveDog(
            birthDate = PartialBirthDate.Day(referenceDate.minusDays(10_959)),
            dogAdultWeight = DogAdultWeight.Category(DogAdultWeightCategory.I),
        ).unavailable().reason as WeightReferenceUnavailableReason.AgeOutOfRange
        assertEquals(10_958, afterBoundary.supportedMaximumDays)
        assertEquals(10_959, afterBoundary.actualMinimumDays)

        assertReason<WeightReferenceUnavailableReason.AgeOutOfRange>(
            resolveDog(
                birthDate = PartialBirthDate.Day(referenceDate.minusDays(83)),
                dogAdultWeight = DogAdultWeight.Category(DogAdultWeightCategory.I),
            ),
        )
    }

    @Test
    fun `resolver uses finite scope maximum for bounded carry forward`() {
        val root = javaClass.classLoader.getResourceAsStream(WeightReferenceSnapshot.RESOURCE_PATH)!!
            .bufferedReader().use { JsonParser.parseString(it.readText()).asJsonObject }
        val profile = root.getAsJsonArray("profiles")
            .first { it.asJsonObject.get("id").asString == "dog-male-I" }.asJsonObject
        val lastAge = profile.getAsJsonArray("points").last().asJsonObject.get("ageDays").asInt
        root.getAsJsonObject("manifest").getAsJsonArray("scopes")
            .first { it.asJsonObject.get("id").asString == "dog-male-I" }.asJsonObject.apply {
                addProperty("maximumAgeDays", lastAge + 10)
            }
        root.getAsJsonObject("manifest").addProperty("numericalDataSha256", "")
        val canonical = root.toString().toByteArray()
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
            PartialBirthDate.Day(referenceDate.minusDays(lastAge + 10L)),
            referenceDate,
            DogAdultWeight.Category(DogAdultWeightCategory.I),
        ).available()
        assertEquals(lastAge + 10L..lastAge + 10L, boundary.ageDays)

        val after = boundedResolver.resolve(
            PetSpecies.DOG,
            PetSex.MALE,
            null,
            PartialBirthDate.Day(referenceDate.minusDays(lastAge + 11L)),
            referenceDate,
            DogAdultWeight.Category(DogAdultWeightCategory.I),
        ).unavailable().reason as WeightReferenceUnavailableReason.AgeOutOfRange
        assertEquals(lastAge + 10, after.supportedMaximumDays)
    }

    @Test
    fun `partial dog birth dates retain category bounds across former day 730 boundary`() {
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
        assertTrue(crossing.approximate)
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
    fun `DSH profile is bounded for both sexes`() {
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
            val boundary = resolver.resolve(
                PetSpecies.CAT,
                sex,
                breed,
                PartialBirthDate.Day(referenceDate.minusDays(546)),
                referenceDate,
                intactStatus = IntactStatus.CONFIRMED_INTACT,
            ).available()
            listOf(interpolated.bounds, boundary.bounds).forEach { bounds ->
                assertTrue(bounds.lowerKg <= bounds.medianLowerKg)
                assertTrue(bounds.medianLowerKg <= bounds.medianUpperKg)
                assertTrue(bounds.medianUpperKg <= bounds.upperKg)
            }
            val after = resolver.resolve(
                PetSpecies.CAT,
                sex,
                breed,
                PartialBirthDate.Day(referenceDate.minusDays(547)),
                referenceDate,
                intactStatus = IntactStatus.CONFIRMED_INTACT,
            ).unavailable().reason as WeightReferenceUnavailableReason.AgeOutOfRange
            assertEquals(546, after.supportedMaximumDays)
        }
    }

    @Test
    fun `partial date crossing supported maximum is unavailable as a whole`() {
        val reason = resolver.resolve(
            PetSpecies.DOG,
            PetSex.MALE,
            null,
            PartialBirthDate.Month(YearMonth.of(1995, 1)),
            referenceDate,
            DogAdultWeight.Category(DogAdultWeightCategory.I),
        ).unavailable().reason as WeightReferenceUnavailableReason.AgeOutOfRange
        assertEquals(10_942L, reason.actualMinimumDays)
        assertEquals(10_972L, reason.actualMaximumDays)
        assertEquals(10_958, reason.supportedMaximumDays)
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
    fun `selected dog breeds without breed profiles never fall back to weight category`() {
        val birthDate = PartialBirthDate.Day(referenceDate.minusDays(100))
        listOf(
            "VBO:0200290",
            "VBO:0200470",
            "VBO:0200880",
            "VBO:0200410",
            "VBO:0200027",
            "VBO:0201217",
        ).forEach { breedId ->
            assertEquals(
                WeightReferenceUnavailableReason.UnsupportedBreed(breedId),
                resolveDog(
                    breedId = BreedId(breedId),
                    birthDate = birthDate,
                    dogAdultWeight = DogAdultWeight.Category(DogAdultWeightCategory.II),
                ).unavailable().reason,
            )
        }

        assertEquals(
            WeightReferenceUnavailableReason.UnsupportedBreed("VBO:0200577"),
            resolveDog(
                breedId = BreedId("VBO:0200577"),
                birthDate = birthDate,
                dogAdultWeight = DogAdultWeight.Category(DogAdultWeightCategory.V),
            ).unavailable().reason,
        )
    }

    @Test
    fun `selected dog breed without profile reports breed unavailable before category requirements`() {
        assertEquals(
            WeightReferenceUnavailableReason.UnsupportedBreed("VBO:0200470"),
            resolveDog(
                breedId = BreedId("VBO:0200470"),
                birthDate = PartialBirthDate.Day(referenceDate.minusDays(100)),
            ).unavailable().reason,
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
                assertEquals(WeightReferenceProvenance.BREED_CURVE, result.provenance)
                assertEquals(breed, result.selectedBreedId)
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
                assertEquals(WeightReferenceProvenance.BREED_EXACT_OBSERVATION, atBirth.provenance)
                assertEquals("mugnier-cat-birth-weight-2023", atBirth.sourceId)
                assertEquals(breedId, atBirth.selectedBreedId)
                assertEquals(weights[0], atBirth.bounds.lowerKg, 1e-12)
                assertEquals(weights[1], atBirth.bounds.medianLowerKg, 1e-12)
                assertEquals(weights[1], atBirth.bounds.medianUpperKg, 1e-12)
                assertEquals(weights[2], atBirth.bounds.upperKg, 1e-12)

                assertReason<WeightReferenceUnavailableReason.ReferenceDataGap>(
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
    fun `maine coon and siberian use breed model after neonatal gap`() {
        val cases = listOf(
            BreedId("VBO:0100154"),
            BreedId("VBO:0100223"),
        )
        cases.forEach { breedId ->
            for (sex in listOf(PetSex.FEMALE, PetSex.MALE)) {
                val result = resolver.resolve(
                    PetSpecies.CAT,
                    sex,
                    breedId,
                    PartialBirthDate.Day(referenceDate.minusDays(56)),
                    referenceDate,
                ).available()

                assertTrue(result.profileId.startsWith(if (breedId.value == "VBO:0100154") "cat-maine-coon-" else "cat-siberian-"))
                assertEquals(ReferenceBasis.BREED, result.basis)
                assertEquals(WeightReferenceProvenance.BREED_CURVE, result.provenance)
                assertTrue(result.sourceId.startsWith("wikipedia-"))
                assertEquals(breedId, result.selectedBreedId)
                assertEquals(56L..56L, result.ageDays)
            }
        }
    }

    @Test
    fun `partial cat birth date spanning modelled neonatal gap is unavailable as a whole`() {
        for (breedId in listOf(BreedId("VBO:0100154"), BreedId("VBO:0100223"))) {
            for (sex in listOf(PetSex.FEMALE, PetSex.MALE)) {
                val reason = resolver.resolve(
                    PetSpecies.CAT,
                    sex,
                    breedId,
                    PartialBirthDate.Month(YearMonth.of(2024, 12)),
                    referenceDate,
                ).unavailable().reason

                assertEquals(
                    WeightReferenceUnavailableReason.ReferenceDataGap(
                        if (breedId.value == "VBO:0100154") {
                            "cat-maine-coon-${sex.name.lowercase()}"
                        } else {
                            "cat-siberian-${sex.name.lowercase()}"
                        },
                        15L..45L,
                    ),
                    reason,
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
    fun `unsupported and unknown cat breeds are unavailable without population fallback`() {
        val birthDate = PartialBirthDate.Day(referenceDate.minusDays(56))
        listOf(BreedId("VBO:0100208"), BreedId("external:cat:future")).forEach { breedId ->
            val result = resolver.resolve(
                PetSpecies.CAT,
                PetSex.MALE,
                breedId,
                birthDate,
                referenceDate,
            ).unavailable()

            assertEquals(WeightReferenceUnavailableReason.UnsupportedBreed(breedId.value), result.reason)
        }
    }

    @Test
    fun `all five researched breeds resolve breed models for both sexes and kitten adult ages`() {
        val snapshot = WeightReferenceSnapshot.bundled()
        val cases = listOf(
            "VBO:0100052", // British Shorthair: no reproducible numerical breed profile
            "VBO:0100209", // Scottish Fold: no reproducible numerical breed profile
            "VBO:0100221", // Siamese: evidence combines breeds
            "VBO:0100154", // Maine Coon: exact birth observation only
            "VBO:0100223", // Siberian: exact birth observation only
        )

        cases.forEach { rawBreedId ->
            for (sex in listOf(PetSex.FEMALE, PetSex.MALE)) {
                for (ageDays in listOf(56L, 365L, 730L)) {
                    val breedId = BreedId(rawBreedId)
                    val result = resolver.resolve(
                        PetSpecies.CAT,
                        sex,
                        breedId,
                        PartialBirthDate.Day(referenceDate.minusDays(ageDays)),
                        referenceDate,
                    ).available()

                    assertEquals(ReferenceBasis.BREED, result.basis)
                    assertEquals(WeightReferenceProvenance.BREED_CURVE, result.provenance)
                    assertEquals(breedId, result.selectedBreedId)
                    assertEquals(ReferenceKind.MODELLED_BREED_ADULT_RANGE, snapshot.metadataFor(result.profileId)!!.referenceKind)
                }
            }
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
