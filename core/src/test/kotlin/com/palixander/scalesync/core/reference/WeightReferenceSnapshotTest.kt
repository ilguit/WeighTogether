package com.palixander.scalesync.core.reference

import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WeightReferenceSnapshotTest {
    @Test
    fun `bundled snapshot records dog I-V and sex scopes without inventing centiles`() {
        val snapshot = WeightReferenceSnapshot.bundled()
        val dogScopes = snapshot.manifest.scopes.filter { it.species == ReferenceSpecies.DOG }

        assertEquals(setOf("I", "II", "III", "IV", "V"), dogScopes.mapNotNull { it.weightCategory }.toSet())
        assertEquals(setOf(ReferenceSex.FEMALE, ReferenceSex.MALE), dogScopes.map { it.sex }.toSet())
        assertEquals(10, dogScopes.size)
        assertTrue(dogScopes.all { it.minimumAgeDays == 84 && it.maximumAgeDays == 730 })
        assertTrue(dogScopes.all { it.maximumAdultWeightKg!! <= 40.0 })
        assertTrue(dogScopes.all { it.numericalAvailability == NumericalAvailability.NOT_REPRODUCIBLE_FROM_PUBLISHED_ARTIFACTS })
        assertTrue(snapshot.profiles.isEmpty())
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
        val tampered = original.replace("\"profiles\": []", "\"profiles\": [{\"id\":\"tampered\"}]")

        assertFailsWith<IllegalArgumentException> {
            WeightReferenceSnapshot.load({ ByteArrayInputStream(tampered.toByteArray()) })
        }
    }

    @Test
    fun `unknown VBO identifier is rejected`() {
        val original = javaClass.classLoader.getResourceAsStream(WeightReferenceSnapshot.RESOURCE_PATH)!!
            .bufferedReader().use { it.readText() }
        val tampered = original.replace("VBO:0100119", "VBO:9999999")

        assertFailsWith<IllegalStateException> {
            WeightReferenceSnapshot.load({ ByteArrayInputStream(tampered.toByteArray()) })
        }
    }
}
