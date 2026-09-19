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
    fun `bundled snapshot contains all thirty researched breeds and Russian Black Terrier alias`() {
        val snapshot = BreedReferenceSnapshot.bundled()

        assertEquals(1, snapshot.manifest.schemaVersion)
        assertEquals(30, snapshot.breeds.size)
        assertEquals((1..30).toList(), snapshot.breeds.map { it.popularityRank }.sorted())
        assertSame(snapshot.breed("VBO:0200174"), snapshot.breed("VBO:0201146"))
        snapshot.breeds.filter { it.popularityRank <= 10 }.forEach { breed ->
            assertTrue(breed.values.any { it.adult && it.measure == BreedReferenceMeasure.WEIGHT })
        }
    }

    @Test
    fun `ranks 21 through 30 preserve adult weights and explicit growth gaps`() {
        val snapshot = BreedReferenceSnapshot.bundled()
        val packageBreeds = snapshot.breeds.filter { it.popularityRank in 21..30 }

        assertEquals((21..30).toList(), packageBreeds.map { it.popularityRank })
        packageBreeds.forEach { breed ->
            assertTrue(breed.values.none { it.measure == BreedReferenceMeasure.HEIGHT })
            assertTrue(breed.values.any {
                !it.adult && it.measure == BreedReferenceMeasure.WEIGHT &&
                    it.statistic == BreedReferenceStatisticKind.DOCUMENTED_GAP &&
                    it.ageMinimumDays == 0 && it.ageMaximumDays == 730
            })
        }
        assertTrue(snapshot.breed("VBO:0200734")!!.values.filter { it.adult }.all { !it.activeForProduct })
        assertTrue(snapshot.breed("VBO:0200880")!!.values.all {
            it.statistic == BreedReferenceStatisticKind.DOCUMENTED_GAP
        })
        assertTrue(snapshot.breed("VBO:0200724")!!.values.any {
            it.adult && it.lower == 5.0 && it.upper == 6.0 && it.sourceId == "fci345"
        })
        assertTrue(snapshot.breed("VBO:0200193")!!.values.any {
            it.adult && it.sex == BreedReferenceSex.FEMALE &&
                it.lower == 12.0 && it.upper == 19.0 && it.sourceId == "wiki-border-it"
        })
        assertTrue(snapshot.breed("VBO:0201174")!!.values.any {
            it.adult && it.sex == BreedReferenceSex.MALE &&
                it.lower == 20.0 && it.upper == 30.0 && it.sourceId == "wiki-samoyed"
        })
        assertEquals("p.4", snapshot.manifest.sources.single { it.id == "fci345" }.pageOrTable)
        assertTrue(snapshot.manifest.sources.single { it.id == "wiki-akita" }.method!!.contains("ambiguity"))
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
            { it.getAsJsonArray("breeds").remove(29) },
            { it.getAsJsonArray("breeds")[29].asJsonObject.addProperty("popularityRank", 29) },
            {
                val sources = it.getAsJsonObject("manifest").getAsJsonArray("sources")
                sources[1].asJsonObject.addProperty("id", sources[0].asJsonObject.get("id").asString)
            },
            { it.getAsJsonArray("breeds")[0].asJsonObject.getAsJsonArray("values")[0].asJsonObject.addProperty("sourceId", "missing") },
            { it.getAsJsonArray("breeds")[0].asJsonObject.addProperty("breedId", "VBO:missing") },
            {
                val breeds = it.getAsJsonArray("breeds")
                breeds[1].asJsonObject.addProperty("breedId", breeds[0].asJsonObject.get("breedId").asString)
            },
            {
                val breeds = it.getAsJsonArray("breeds")
                val duplicateId = breeds[0].asJsonObject.getAsJsonArray("values")[0].asJsonObject.get("id").asString
                breeds[1].asJsonObject.getAsJsonArray("values")[0].asJsonObject.addProperty("id", duplicateId)
            },
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

    @Test
    fun `ranks eleven through twenty preserve safe adult mappings and exact statistic semantics`() {
        val snapshot = BreedReferenceSnapshot.bundled()

        assertEquals("Papillon", snapshot.breed("VBO:0200985")!!.englishName)
        assertEquals(34.0, snapshot.breed("VBO:0200610")!!.values.single { it.id == "gold-adult" }.upper)
        assertEquals(16.0, snapshot.breed("VBO:0200095")!!.values.single { it.id == "aussie-adult" }.lower)
        assertEquals(BreedReferenceStatisticKind.IDEAL, snapshot.breed("VBO:0200120")!!.values.single { it.id == "bas-mw" }.statistic)
        assertEquals(BreedReferenceStatisticKind.STANDARD_POINT, snapshot.breed("VBO:0201135")!!.values.single { it.id == "ridge-mw" }.statistic)
        val centralAsianMale = snapshot.breed("VBO:0200321")!!.values.single { it.id == "cas-mw" }
        assertEquals(BreedReferenceStatisticKind.MINIMUM, centralAsianMale.statistic)
        assertEquals(50.0, centralAsianMale.lower)
        assertEquals(null, centralAsianMale.upper)
        assertEquals(6.0, snapshot.breed("VBO:0200893")!!.values.single { it.id == "minpin-adult" }.upper)
    }

    @Test
    fun `new neonatal observations gaps and provenance remain source faithful`() {
        val snapshot = BreedReferenceSnapshot.bundled()
        val pomeranian = snapshot.breed("VBO:0200599")!!.values
        val ridgeback = snapshot.breed("VBO:0201135")!!.values

        assertEquals(117, pomeranian.single { it.id == "pom-birth-mugnier" }.sampleSize)
        assertEquals(0.124, pomeranian.single { it.id == "pom-birth-groppetti" }.center)
        assertEquals(76, ridgeback.single { it.id == "ridge-birth-q" }.sampleSize)
        assertEquals(0.55, ridgeback.single { it.id == "ridge-birth-r" }.upper)
        assertTrue(snapshot.breeds.filter { it.popularityRank in 11..20 }.all { breed ->
            breed.values.any { it.statistic == BreedReferenceStatisticKind.DOCUMENTED_GAP }
        })
        assertTrue(snapshot.breeds.filter { it.popularityRank in 11..20 }.flatMap { it.values }.all {
            it.measure == BreedReferenceMeasure.WEIGHT
        })
        assertEquals("p.5", snapshot.manifest.sources.single { it.id == "fci077" }.pageOrTable)
        assertTrue(snapshot.manifest.sources.single { it.id == "komarova2020" }.method!!.contains("sample sizes not exposed"))
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
