package com.palixander.scalesync.core.breedreference

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BreedReferenceSnapshotTest {
    @Test
    fun `bundled snapshot contains all ten researched breeds and Russian Black Terrier alias`() {
        val snapshot = BreedReferenceSnapshot.bundled()

        assertEquals(1, snapshot.manifest.schemaVersion)
        assertEquals(10, snapshot.breeds.size)
        assertEquals((1..10).toList(), snapshot.breeds.map { it.popularityRank }.sorted())
        assertSame(snapshot.breed("VBO:0200174"), snapshot.breed("VBO:0201146"))
        snapshot.breeds.forEach { breed ->
            assertTrue(breed.values.any { it.adult && it.measure == BreedReferenceMeasure.WEIGHT })
            assertTrue(breed.values.any { it.adult && it.measure == BreedReferenceMeasure.HEIGHT })
        }
    }

    @Test
    fun `snapshot preserves shapes gaps provenance and inactive height`() {
        val snapshot = BreedReferenceSnapshot.bundled()
        val statistics = snapshot.breeds.flatMap { it.values }.map { it.statistic }.toSet()

        assertTrue(BreedReferenceStatisticKind.RANGE in statistics)
        assertTrue(BreedReferenceStatisticKind.QUANTILES in statistics)
        assertTrue(BreedReferenceStatisticKind.MEAN in statistics)
        assertTrue(BreedReferenceStatisticKind.MEDIAN in statistics)
        assertTrue(BreedReferenceStatisticKind.APPROXIMATE_AVERAGE in statistics)
        assertTrue(BreedReferenceStatisticKind.MEAN_SD in statistics)
        assertTrue(BreedReferenceStatisticKind.DOCUMENTED_GAP in statistics)
        assertTrue(snapshot.breeds.flatMap { it.values }.any { it.measure == BreedReferenceMeasure.HEIGHT })
        assertTrue(snapshot.breed("VBO:0200712")!!.values.any { it.gap?.contains("No breed-specific") == true })
        assertTrue(snapshot.manifest.sources.all { it.url.startsWith("https://") })
    }

    @Test
    fun `AmStaff QA replacement is sex specific and rejected combined range is provenance only`() {
        val values = BreedReferenceSnapshot.bundled().breed("VBO:0200055")!!.values

        assertTrue(values.any { it.sex == BreedReferenceSex.MALE && it.lower == 28.0 && it.upper == 33.0 && it.activeForProduct })
        assertTrue(values.any { it.sex == BreedReferenceSex.FEMALE && it.lower == 19.0 && it.upper == 25.0 && it.activeForProduct })
        assertTrue(values.any { it.sex == BreedReferenceSex.MALE && it.center == 28.3 && it.sampleSize == 570 })
        assertTrue(values.any { it.sex == BreedReferenceSex.FEMALE && it.center == 23.5 && it.sampleSize == 637 })
        assertTrue(values.any { it.sex == BreedReferenceSex.COMBINED && it.lower == 18.1 && !it.activeForProduct })
    }

    @Test
    fun `Shiba uses official NIPPO ranges and preserves NSCA averages as inactive provenance`() {
        val snapshot = BreedReferenceSnapshot.bundled()
        val values = snapshot.breed("VBO:0201220")!!.values
        val nippo = snapshot.manifest.sources.single { it.id == "nippo" }

        assertEquals("https://www.nihonken-hozonkai.or.jp/en/shibainu/", nippo.url)
        assertTrue(values.any {
            it.sex == BreedReferenceSex.MALE && it.lower == 9.0 && it.upper == 11.0 &&
                it.sourceId == "nippo" && it.activeForProduct
        })
        assertTrue(values.any {
            it.sex == BreedReferenceSex.FEMALE && it.lower == 7.0 && it.upper == 9.0 &&
                it.sourceId == "nippo" && it.activeForProduct
        })
        assertTrue(values.any { it.sex == BreedReferenceSex.MALE && it.center == 10.4 && !it.activeForProduct })
        assertTrue(values.any { it.sex == BreedReferenceSex.FEMALE && it.center == 7.7 && !it.activeForProduct })
    }

    @Test
    fun `runtime returns unavailable instead of throwing for corrupt bundle`() {
        val result = BreedReferenceSnapshot.loadOrUnavailable(
            streamProvider = { ByteArrayInputStream("{}".toByteArray()) },
        )
        assertIs<BreedReferenceSnapshotLoadResult.Unavailable>(result)
    }

    @Test
    fun `checksum tampering is rejected`() {
        val root = bundledJson()
        root.getAsJsonArray("breeds")[0].asJsonObject.addProperty("registrations", 999)

        val result = load(root)

        assertIs<BreedReferenceSnapshotLoadResult.Unavailable>(result)
        assertTrue(result.reason.contains("checksum"))
    }

    @Test
    fun `schema source breed alias sex age unit statistic and gap violations are rejected`() {
        val mutations: List<(JsonObject) -> Unit> = listOf(
            { it.getAsJsonObject("manifest").addProperty("schemaVersion", 2) },
            { it.getAsJsonArray("breeds")[0].asJsonObject.getAsJsonArray("values")[0].asJsonObject.addProperty("sourceId", "missing") },
            { it.getAsJsonArray("breeds")[0].asJsonObject.addProperty("breedId", "VBO:missing") },
            { it.getAsJsonArray("breeds")[0].asJsonObject.add("aliases", Gson().toJsonTree(listOf("VBO:0201146"))) },
            { it.getAsJsonArray("breeds")[0].asJsonObject.getAsJsonArray("values")[0].asJsonObject.addProperty("sex", "unknown") },
            { it.getAsJsonArray("breeds")[0].asJsonObject.getAsJsonArray("values")[0].asJsonObject.addProperty("ageMinimumDays", -1) },
            { it.getAsJsonArray("breeds")[0].asJsonObject.getAsJsonArray("values")[0].asJsonObject.addProperty("unit", "lb") },
            { it.getAsJsonArray("breeds")[0].asJsonObject.getAsJsonArray("values")[0].asJsonObject.addProperty("statistic", "median") },
            { it.getAsJsonArray("breeds")[1].asJsonObject.getAsJsonArray("values")[4].asJsonObject.addProperty("center", 1) },
        )
        mutations.forEach { mutate ->
            val root = bundledJson()
            mutate(root)
            refreshChecksum(root)
            assertIs<BreedReferenceSnapshotLoadResult.Unavailable>(load(root))
        }
    }

    @Test
    fun `published source metadata and documented limitations remain accessible`() {
        val snapshot = BreedReferenceSnapshot.bundled()
        val dogslife = assertNotNull(snapshot.manifest.sources.find { it.id == "dogslife" })
        val labrador = assertNotNull(snapshot.breed("VBO:0200800"))

        assertEquals("UK", dogslife.geography)
        assertTrue(dogslife.method!!.contains("Owner-reported"))
        assertEquals(358, labrador.values.single { it.id == "lab-f24" }.sampleSize)
        assertTrue(labrador.values.any { it.statistic == BreedReferenceStatisticKind.DOCUMENTED_GAP })
    }

    private fun load(root: JsonObject) = BreedReferenceSnapshot.loadOrUnavailable(
        streamProvider = { ByteArrayInputStream(Gson().toJson(root).toByteArray()) },
    )

    private fun bundledJson() = javaClass.classLoader.getResourceAsStream(BreedReferenceSnapshot.RESOURCE_PATH)!!
        .bufferedReader().use { JsonParser.parseString(it.readText()).asJsonObject }

    private fun refreshChecksum(root: JsonObject) {
        val checksum = MessageDigest.getInstance("SHA-256")
            .digest(root.getAsJsonArray("breeds").toString().toByteArray())
            .joinToString("") { "%02x".format(it) }
        root.getAsJsonObject("manifest").addProperty("numericalDataSha256", checksum)
    }
}
