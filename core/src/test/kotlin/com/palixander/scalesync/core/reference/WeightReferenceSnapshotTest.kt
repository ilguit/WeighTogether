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
        assertTrue(snapshot.profiles.all { it.referenceKind == "empirical_observation_quartiles" })
    }

    @Test
    fun `bundled DSH scope is sex-specific intact and age-limited`() {
        val scopes = WeightReferenceSnapshot.bundled().manifest.scopes.filter { it.species == ReferenceSpecies.CAT }

        assertEquals(2, scopes.size)
        assertTrue(scopes.all { it.breedId == "VBO:0100119" })
        assertTrue(scopes.all { it.minimumAgeDays == 56 && it.maximumAgeDays == 546 })
        assertTrue(scopes.all { scope -> scope.constraints.any { "intact" in it.lowercase() } })
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
    fun `interpolation returns exact and in-profile linear values but never extrapolates`() {
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
        assertNull(snapshot.interpolate(profile.id, profile.points.last().ageDays + 1))
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
}
