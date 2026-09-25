package com.palixander.scalesync.core.reference

import java.io.ByteArrayInputStream
import com.google.gson.Gson
import com.google.gson.JsonParser
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.assertNull

class WeightReferenceSnapshotTest {
    @Test
    fun `profile metadata lookup exposes provenance without mutable constraints`() {
        val snapshot = WeightReferenceSnapshot.bundled()
        val profile = snapshot.profiles.first()

        val metadata = snapshot.metadataFor(profile.id)!!

        assertEquals(profile.id, metadata.profileId)
        assertEquals(profile.sourceId, metadata.source.id)
        assertEquals(profile.constraints, metadata.constraints)
        assertEquals(profile.ageAvailability, metadata.ageAvailability)
        assertEquals(
            snapshot.manifest.scopes.single { it.id == profile.id }.maximumAgeDays,
            metadata.supportedMaximumAgeDays,
        )
        org.junit.Assert.assertThrows(UnsupportedOperationException::class.java) {
            (metadata.constraints as MutableList<String>).add("changed")
        }
        assertNull(snapshot.metadataFor("missing"))
    }
    @Test
    fun `bundled snapshot contains empirical dog I-V profiles without claiming fitted centiles`() {
        val snapshot = WeightReferenceSnapshot.bundled()
        val dogScopes = snapshot.manifest.scopes.filter { it.species == ReferenceSpecies.DOG }

        assertEquals(setOf("I", "II", "III", "IV", "V"), dogScopes.mapNotNull { it.weightCategory }.toSet())
        assertEquals(setOf(ReferenceSex.FEMALE, ReferenceSex.MALE), dogScopes.map { it.sex }.toSet())
        assertEquals(10, dogScopes.size)
        assertTrue(dogScopes.all { it.minimumAgeDays == 84 && it.maximumAgeDays == 730 })
        assertTrue(dogScopes.all { it.maximumAdultWeightKg!! <= 40.0 })
        assertTrue(dogScopes.all { it.numericalAvailability == NumericalAvailability.AVAILABLE })
        assertEquals(10, snapshot.profiles.count { it.species == ReferenceSpecies.DOG })
        assertTrue(snapshot.profiles.filter { it.species == ReferenceSpecies.DOG }.all { it.referenceKind == ReferenceKind.EMPIRICAL_OBSERVATION_QUARTILES })
        assertTrue(dogScopes.all { it.ageAvailability == ReferenceAgeAvailability.BOUNDED_CARRY_FORWARD })
        assertTrue(snapshot.profiles.filter { it.species == ReferenceSpecies.DOG }
            .all { it.ageAvailability == ReferenceAgeAvailability.BOUNDED_CARRY_FORWARD })
    }

    @Test
    fun `bundled DSH scope is sex-specific intact and age-limited`() {
        val scopes = WeightReferenceSnapshot.bundled().manifest.scopes.filter { it.species == ReferenceSpecies.CAT }

        assertEquals(66, scopes.count { it.numericalAvailability == NumericalAvailability.AVAILABLE })
        val dsh = scopes.filter { it.breedId == "VBO:0100119" }
        assertEquals(2, dsh.size)
        assertTrue(dsh.all { it.minimumAgeDays == 56 && it.maximumAgeDays == 546 })
        assertTrue(dsh.all { scope -> scope.constraints.any { "intact" in it.lowercase() } })
        assertTrue(dsh.all { it.ageAvailability == ReferenceAgeAvailability.DECLARED_RANGE_ONLY })
    }

    @Test
    fun `bundled population cat profiles expose fitted P9 P50 P91 only`() {
        val snapshot = WeightReferenceSnapshot.bundled()
        val profiles = snapshot.profiles.filter { it.id.startsWith("cat-population-") }

        assertEquals(setOf(ReferenceSex.FEMALE, ReferenceSex.MALE), profiles.map { it.sex }.toSet())
        assertTrue(profiles.all { it.referenceKind == ReferenceKind.FITTED_BCCG_PERCENTILES })
        assertTrue(profiles.all { it.minimumBinN == 0 && it.points.size == 71 })
        assertTrue(profiles.all { it.ageAvailability == ReferenceAgeAvailability.DECLARED_RANGE_ONLY })
        assertEquals(ReferencePoint(56, 0.636271, 0.890118, 1.228598), profiles.single { it.sex == ReferenceSex.FEMALE }.points.first())
        assertEquals(ReferencePoint(546, 2.517972, 3.351739, 4.621410), profiles.single { it.sex == ReferenceSex.FEMALE }.points.last())
        assertEquals(ReferencePoint(56, 0.567307, 0.861525, 1.265159), profiles.single { it.sex == ReferenceSex.MALE }.points.first())
        assertEquals(ReferencePoint(546, 3.453672, 4.592127, 5.717056), profiles.single { it.sex == ReferenceSex.MALE }.points.last())
        assertTrue(profiles.all { profile -> profile.constraints.any { "P9/P50/P91" in it } })
        val source = snapshot.manifest.sources.single { it.id == "salt-dsh-kitten-2022" }
        assertEquals("docs/research/97/bccg-curves.csv", source.derivedArtifact)
        assertEquals("fa324dc464c038518d8cebce7dc234d637142c7dbe59b601be77b64212541da1", source.derivedArtifactSha256)
        assertTrue(source.derivationSoftware!!.contains("fit_bccg.py"))
    }

    @Test
    fun `tampered numerical array fails checksum validation`() {
        val original = javaClass.classLoader.getResourceAsStream(WeightReferenceSnapshot.RESOURCE_PATH)!!
            .bufferedReader().use { it.readText() }
        val tampered = original.replaceFirst("\"ageDays\": 84", "\"ageDays\": 85")

        assertFailsWith<IllegalArgumentException> {
            WeightReferenceSnapshot.load({ ByteArrayInputStream(tampered.toByteArray()) })
        }
    }

    @Test
    fun `age lookup interpolates internal intervals and carries the last point to the inclusive boundary`() {
        val snapshot = WeightReferenceSnapshot.bundled()
        val profile = snapshot.profiles.first()
        val first = profile.points[0]
        val second = profile.points[1]

        assertEquals(first, snapshot.interpolate(profile.id, first.ageDays))
        val age = (first.ageDays + second.ageDays) / 2
        val interpolated = snapshot.interpolate(profile.id, age)!!
        val fraction = (age - first.ageDays).toDouble() / (second.ageDays - first.ageDays)
        assertEquals(first.medianKg + (second.medianKg - first.medianKg) * fraction, interpolated.medianKg, 1e-12)
        assertNull(snapshot.interpolate(profile.id, first.ageDays - 1))
        val carriedAge = snapshot.manifest.scopes.single { it.id == profile.id }.maximumAgeDays
        assertEquals(profile.points.last().copy(ageDays = carriedAge), snapshot.interpolate(profile.id, carriedAge))
        assertNull(snapshot.interpolate(profile.id, carriedAge + 1))

        val sparseProfile = snapshot.profiles.single { it.id == "cat-dsh-female" }
        val sparsePair = sparseProfile.points.zipWithNext().maxBy { (lower, upper) -> upper.ageDays - lower.ageDays }
        val sparseAge = (sparsePair.first.ageDays + sparsePair.second.ageDays) / 2
        val sparse = snapshot.interpolate(sparseProfile.id, sparseAge)!!
        val sparseFraction = (sparseAge - sparsePair.first.ageDays).toDouble() /
            (sparsePair.second.ageDays - sparsePair.first.ageDays)
        assertEquals(
            sparsePair.first.medianKg + (sparsePair.second.medianKg - sparsePair.first.medianKg) * sparseFraction,
            sparse.medianKg,
            1e-12,
        )
        assertNull(snapshot.interpolate("missing-profile", age))
    }

    @Test
    fun `declared-range profile does not carry its final point forward`() {
        val root = bundledJson()
        val profile = root.getAsJsonArray("profiles")[0].asJsonObject
        val id = profile.get("id").asString
        profile.addProperty("ageAvailability", "declared_range_only")
        root.getAsJsonObject("manifest").getAsJsonArray("scopes")
            .first { it.asJsonObject.get("id").asString == id }.asJsonObject
            .addProperty("ageAvailability", "declared_range_only")
        refreshChecksum(root)
        val snapshot = WeightReferenceSnapshot.load(streamProvider = { ByteArrayInputStream(Gson().toJson(root).toByteArray()) })
        val last = snapshot.profiles.single { it.id == id }.points.last()

        assertEquals(last, snapshot.interpolate(id, last.ageDays))
        assertNull(snapshot.interpolate(id, last.ageDays + 1))
        assertEquals(
            ReferenceAgeAvailability.BOUNDED_CARRY_FORWARD,
            WeightReferenceSnapshot.bundled().profiles.single { it.id == id }.ageAvailability,
        )
    }

    @Test
    fun `bounded carry forward stops after the declared finite maximum`() {
        val root = bundledJson()
        val profile = root.getAsJsonArray("profiles")[0].asJsonObject
        val id = profile.get("id").asString
        val lastAge = profile.getAsJsonArray("points").last().asJsonObject.get("ageDays").asInt
        root.getAsJsonObject("manifest").getAsJsonArray("scopes")
            .first { it.asJsonObject.get("id").asString == id }.asJsonObject
            .addProperty("maximumAgeDays", lastAge + 10)
        refreshChecksum(root)
        val snapshot = load(root)

        assertEquals(
            snapshot.profiles.single { it.id == id }.points.last().copy(ageDays = lastAge + 10),
            snapshot.interpolate(id, lastAge + 10),
        )
        assertNull(snapshot.interpolate(id, lastAge + 11))
        val metadata = snapshot.metadataFor(id)!!
        assertEquals(ReferenceAgeAvailability.BOUNDED_CARRY_FORWARD, metadata.ageAvailability)
        assertEquals(lastAge + 10, metadata.supportedMaximumAgeDays)
    }

    @Test
    fun `five selected cat breeds expose sex specific modelled ranges with audited provenance`() {
        val snapshot = WeightReferenceSnapshot.bundled()
        val adultRanges = mapOf(
            "cat-british-shorthair-female" to (3.0 to 4.0),
            "cat-british-shorthair-male" to (5.0 to 8.0),
            "cat-scottish-fold-female" to (2.7 to 4.0),
            "cat-scottish-fold-male" to (4.0 to 6.0),
            "cat-siamese-female" to (3.0 to 4.0),
            "cat-siamese-male" to (4.0 to 5.0),
            "cat-maine-coon-female" to (5.44310844 to 6.80388555),
            "cat-maine-coon-male" to (8.16466266 to 9.97903214),
            "cat-siberian-female" to (3.0 to 6.0),
            "cat-siberian-male" to (4.5 to 8.0),
        )
        assertEquals(5, snapshot.manifest.schemaVersion)
        assertEquals("2026-09-25.1", snapshot.manifest.snapshotVersion)
        adultRanges.forEach { (id, range) ->
            val profile = snapshot.profiles.single { it.id == id }
            assertEquals(ReferenceKind.MODELLED_BREED_ADULT_RANGE, profile.referenceKind)
            assertEquals(ReferenceBoundsStatistic.ADULT_TYPICAL_RANGE, profile.boundsStatistic)
            assertEquals(ReferenceCenterStatistic.ARITHMETIC_MIDPOINT, profile.centerStatistic)
            assertEquals(ReferenceAgeAvailability.DECLARED_RANGE_ONLY, profile.ageAvailability)
            assertTrue(profile.constraints.any { "Модель" in it })
            val adult = profile.points.last()
            assertEquals(730, adult.ageDays)
            assertEquals(range.first, adult.lowerKg, 1e-12)
            assertEquals(range.second, adult.upperKg, 1e-12)
            assertTrue(snapshot.interpolate(id, 56) != null)
            assertTrue(snapshot.interpolate(id, 365) != null)
            assertTrue(snapshot.interpolate(id, 730) != null)
        }
        val maine = snapshot.profiles.single { it.id == "cat-maine-coon-female" }
        assertEquals(ReferencePoint(0, 0.1004, 0.1191, 0.1378, "mugnier-cat-birth-weight-2023", true), maine.points.first())
        assertNull(snapshot.interpolate(maine.id, 1))
        assertNull(snapshot.interpolate(maine.id, 55))
        val siberian = snapshot.profiles.single { it.id == "cat-siberian-male" }
        assertEquals(ReferencePoint(0, 0.0826, 0.0993, 0.116, "mugnier-cat-birth-weight-2023", true), siberian.points.first())
        assertTrue(snapshot.manifest.sources.count { "wikipedia" in it.id } == 5)
        assertTrue(snapshot.manifest.sources.filter { "wikipedia" in it.id }.all { it.accessedDate == "2026-09-08" })
    }

    @Test
    fun `approved evidence exposes exactly twenty six canonical breeds and honest source classes`() {
        val snapshot = WeightReferenceSnapshot.bundled()
        val batch = snapshot.profiles.filter { it.id.matches(Regex("cat-breed-\\d{7}-(female|male)")) }

        assertEquals(52, batch.size)
        assertEquals(26, batch.map { it.breedId }.toSet().size)
        assertTrue(batch.none { it.breedId == "VBO:0100061" })
        assertEquals(setOf(ReferenceSex.FEMALE, ReferenceSex.MALE), batch.groupBy { it.breedId }.values.flatMap { profiles ->
            listOf(profiles.map { it.sex }.toSet()).onEach { assertEquals(setOf(ReferenceSex.FEMALE, ReferenceSex.MALE), it) }
        }.flatten().toSet())
        assertTrue(batch.all { it.centerStatistic == ReferenceCenterStatistic.ARITHMETIC_MIDPOINT })
        assertTrue(batch.all { it.ageAvailability == ReferenceAgeAvailability.DECLARED_RANGE_ONLY })
        assertTrue(batch.all { it.points.first().ageDays == 56 && it.points.none(ReferencePoint::empirical) })
        batch.forEach { profile ->
            val adult = profile.points.last()
            val maximumAge = snapshot.manifest.scopes.single { it.id == profile.id }.maximumAgeDays
            assertEquals(adult.copy(ageDays = maximumAge), snapshot.interpolate(profile.id, maximumAge))
            assertNull(snapshot.interpolate(profile.id, maximumAge + 1))
        }
        val russian = snapshot.metadataFor("cat-breed-0100200-female")!!
        assertEquals(ReferenceSourceAuthorityClass.PROFESSIONAL_REFERENCE, russian.source.authorityClass)
        assertTrue(russian.source.disclosure.contains("not an official", ignoreCase = true))
        assertEquals(34, batch.count {
            snapshot.metadataFor(it.id)!!.source.authorityClass == ReferenceSourceAuthorityClass.OFFICIAL_BREED_ORGANIZATION
        })
        assertEquals(18, batch.count {
            snapshot.metadataFor(it.id)!!.source.authorityClass == ReferenceSourceAuthorityClass.PROFESSIONAL_REFERENCE
        })
    }

    @Test
    fun `batch one preserves published maturity and fallback maturity`() {
        val snapshot = WeightReferenceSnapshot.bundled()
        assertEquals(1825, snapshot.profiles.single { it.id == "cat-breed-0100178-female" }.points.last().ageDays)
        assertEquals(1460, snapshot.profiles.single { it.id == "cat-breed-0100196-male" }.points.last().ageDays)
        assertEquals(730, snapshot.profiles.single { it.id == "cat-breed-0100036-female" }.points.last().ageDays)
    }

    @Test
    fun `validated payload rejects tampered source provenance`() {
        val root = bundledJson()
        root.getAsJsonObject("manifest").getAsJsonArray("sources")[0].asJsonObject.addProperty("disclosure", "")
        refreshChecksum(root)
        assertFailsWith<IllegalArgumentException> { load(root) }
    }

    @Test
    fun `validated payload rejects tampered midpoint and maturity`() {
        val midpoint = bundledJson()
        midpoint.getAsJsonArray("profiles").first { it.asJsonObject.get("id").asString == "cat-breed-0100000-female" }
            .asJsonObject.getAsJsonArray("points")[0].asJsonObject.addProperty("medianKg", 999.0)
        refreshChecksum(midpoint)
        assertFailsWith<IllegalArgumentException> { load(midpoint) }

        val maturity = bundledJson()
        maturity.getAsJsonArray("profiles").first { it.asJsonObject.get("id").asString == "cat-breed-0100000-female" }
            .asJsonObject.getAsJsonArray("points").remove(maturity.getAsJsonArray("profiles").first { it.asJsonObject.get("id").asString == "cat-breed-0100000-female" }.asJsonObject.getAsJsonArray("points").size() - 1)
        refreshChecksum(maturity)
        assertFailsWith<IllegalArgumentException> { load(maturity) }
    }

    @Test
    fun `validated payload rejects tampered scope source and bounds after checksum refresh`() {
        val scope = bundledJson()
        scope.getAsJsonObject("manifest").getAsJsonArray("scopes")[0].asJsonObject.addProperty("sex", "male")
        refreshChecksum(scope)
        assertFailsWith<IllegalArgumentException> { load(scope) }

        val source = bundledJson()
        source.getAsJsonArray("profiles")[0].asJsonObject.addProperty("sourceId", "missing")
        refreshChecksum(source)
        assertFailsWith<IllegalArgumentException> { load(source) }

        val bounds = bundledJson()
        bounds.getAsJsonArray("profiles")[0].asJsonObject.getAsJsonArray("points")[0].asJsonObject
            .addProperty("lowerKg", 999.0)
        refreshChecksum(bounds)
        assertFailsWith<IllegalArgumentException> { load(bounds) }
    }

    @Test
    fun `unavailable scope requires typed reason`() {
        val root = bundledJson()
        val id = root.getAsJsonArray("profiles")[0].asJsonObject.get("id").asString
        root.getAsJsonObject("manifest").getAsJsonArray("scopes")
            .first { it.asJsonObject.get("id").asString == id }.asJsonObject
            .addProperty("numericalAvailability", "not_reproducible_from_published_artifacts")
        root.getAsJsonArray("profiles").remove(0)
        refreshChecksum(root)

        assertFailsWith<IllegalArgumentException> {
            WeightReferenceSnapshot.load(streamProvider = { ByteArrayInputStream(Gson().toJson(root).toByteArray()) })
        }
    }

    @Test
    fun `unknown VBO identifier is rejected`() {
        val original = javaClass.classLoader.getResourceAsStream(WeightReferenceSnapshot.RESOURCE_PATH)!!
            .bufferedReader().use { it.readText() }
        val tampered = original.replace("VBO:0100119", "VBO:9999999")

        assertFailsWith<IllegalArgumentException> {
            WeightReferenceSnapshot.load({ ByteArrayInputStream(tampered.toByteArray()) })
        }
    }

    @Test
    fun `profile must remain consistent with its declared scope`() {
        val original = javaClass.classLoader.getResourceAsStream(WeightReferenceSnapshot.RESOURCE_PATH)!!
            .bufferedReader().use { it.readText() }
        val root = JsonParser.parseString(original).asJsonObject
        root.getAsJsonArray("profiles")[0].asJsonObject.addProperty("sex", "male")
        refreshChecksum(root)

        assertFailsWith<IllegalArgumentException> {
            WeightReferenceSnapshot.load({ ByteArrayInputStream(Gson().toJson(root).toByteArray()) })
        }
    }

    @Test
    fun `age availability is required for scopes and profiles`() {
        val missingScopePolicy = bundledJson()
        missingScopePolicy.getAsJsonObject("manifest").getAsJsonArray("scopes")[0].asJsonObject
            .remove("ageAvailability")
        refreshChecksum(missingScopePolicy)
        assertFailsWith<IllegalArgumentException> { load(missingScopePolicy) }

        val missingProfilePolicy = bundledJson()
        missingProfilePolicy.getAsJsonArray("profiles")[0].asJsonObject.remove("ageAvailability")
        refreshChecksum(missingProfilePolicy)
        assertFailsWith<IllegalArgumentException> { load(missingProfilePolicy) }
    }

    @Test
    fun `bounded carry forward policy must match between scope and profile`() {
        val root = bundledJson()
        root.getAsJsonArray("profiles")[0].asJsonObject.addProperty("ageAvailability", "declared_range_only")
        refreshChecksum(root)

        assertFailsWith<IllegalArgumentException> { load(root) }
    }

    @Test
    fun `available scope must have exactly one profile`() {
        val root = bundledJson()
        root.getAsJsonArray("profiles").remove(0)
        refreshChecksum(root)

        assertFailsWith<IllegalArgumentException> {
            WeightReferenceSnapshot.load(streamProvider = { ByteArrayInputStream(Gson().toJson(root).toByteArray()) })
        }
    }

    @Test
    fun `unavailable scope must have no profile`() {
        val root = bundledJson()
        val id = root.getAsJsonArray("profiles")[0].asJsonObject.get("id").asString
        root.getAsJsonObject("manifest").getAsJsonArray("scopes")
            .first { it.asJsonObject.get("id").asString == id }.asJsonObject
            .addProperty("numericalAvailability", "not_reproducible_from_published_artifacts")
        refreshChecksum(root)

        assertFailsWith<IllegalArgumentException> {
            WeightReferenceSnapshot.load(streamProvider = { ByteArrayInputStream(Gson().toJson(root).toByteArray()) })
        }
    }

    private fun bundledJson() = javaClass.classLoader
        .getResourceAsStream(WeightReferenceSnapshot.RESOURCE_PATH)!!
        .bufferedReader().use { JsonParser.parseString(it.readText()).asJsonObject }

    private fun refreshChecksum(root: com.google.gson.JsonObject) {
        root.getAsJsonObject("manifest").addProperty("numericalDataSha256", "")
        val canonical = root.toString().toByteArray()
        val checksum = MessageDigest.getInstance("SHA-256").digest(canonical)
            .joinToString("") { "%02x".format(it) }
        root.getAsJsonObject("manifest").addProperty("numericalDataSha256", checksum)
    }

    private fun load(root: com.google.gson.JsonObject) = WeightReferenceSnapshot.load(
        streamProvider = { ByteArrayInputStream(Gson().toJson(root).toByteArray()) },
    )
}
