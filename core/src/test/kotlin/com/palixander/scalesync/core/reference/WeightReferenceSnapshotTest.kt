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
    }

    @Test
    fun `bundled DSH scope is sex-specific intact and age-limited`() {
        val scopes = WeightReferenceSnapshot.bundled().manifest.scopes.filter { it.species == ReferenceSpecies.CAT }

        assertEquals(4, scopes.size)
        val dsh = scopes.filter { it.breedId == "VBO:0100119" }
        assertEquals(2, dsh.size)
        assertTrue(dsh.all { it.minimumAgeDays == 56 && it.maximumAgeDays == 546 })
        assertTrue(dsh.all { scope -> scope.constraints.any { "intact" in it.lowercase() } })
    }

    @Test
    fun `bundled population cat profiles expose fitted P9 P50 P91 only`() {
        val snapshot = WeightReferenceSnapshot.bundled()
        val profiles = snapshot.profiles.filter { it.id.startsWith("cat-population-") }

        assertEquals(setOf(ReferenceSex.FEMALE, ReferenceSex.MALE), profiles.map { it.sex }.toSet())
        assertTrue(profiles.all { it.referenceKind == ReferenceKind.FITTED_BCCG_PERCENTILES })
        assertTrue(profiles.all { it.minimumBinN == 0 && it.points.size == 71 })
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
    fun `age lookup interpolates every internal interval and carries the last point forward`() {
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
        val carriedAge = profile.points.last().ageDays + 10_000
        assertEquals(profile.points.last().copy(ageDays = carriedAge), snapshot.interpolate(profile.id, carriedAge))

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
        val canonical = root.getAsJsonArray("profiles").toString().toByteArray()
        val checksum = MessageDigest.getInstance("SHA-256").digest(canonical).joinToString("") { "%02x".format(it) }
        root.getAsJsonObject("manifest").addProperty("numericalDataSha256", checksum)

        assertFailsWith<IllegalArgumentException> {
            WeightReferenceSnapshot.load({ ByteArrayInputStream(Gson().toJson(root).toByteArray()) })
        }
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
        val canonical = root.getAsJsonArray("profiles").toString().toByteArray()
        val checksum = MessageDigest.getInstance("SHA-256").digest(canonical)
            .joinToString("") { "%02x".format(it) }
        root.getAsJsonObject("manifest").addProperty("numericalDataSha256", checksum)
    }
}
