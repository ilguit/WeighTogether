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
    fun `maximum accepts only one published upper boundary`() {
        val valid = bundledJson()
        val value = valid.getAsJsonArray("breeds")[0].asJsonObject
            .getAsJsonArray("values")[0].asJsonObject
        value.addProperty("statistic", "maximum")
        value.remove("lower")
        refreshChecksum(valid)

        val snapshot = assertIs<BreedReferenceSnapshotLoadResult.Available>(load(valid)).snapshot
        val maximum = snapshot.breeds[0].values[0]
        assertEquals(BreedReferenceStatisticKind.MAXIMUM, maximum.statistic)
        assertEquals(null, maximum.lower)
        assertEquals(12.0, maximum.upper)
        assertEquals(null, maximum.center)

        listOf("lower", "center", "spread").forEach { extraField ->
            val invalid = valid.deepCopy()
            invalid.getAsJsonArray("breeds")[0].asJsonObject
                .getAsJsonArray("values")[0].asJsonObject.addProperty(extraField, 1.0)
            refreshChecksum(invalid)
            assertIs<BreedReferenceSnapshotLoadResult.Unavailable>(load(invalid))
        }
        val missingUpper = valid.deepCopy()
        missingUpper.getAsJsonArray("breeds")[0].asJsonObject
            .getAsJsonArray("values")[0].asJsonObject.remove("upper")
        refreshChecksum(missingUpper)
        assertIs<BreedReferenceSnapshotLoadResult.Unavailable>(load(missingUpper))
    }

    @Test
    fun `bundled snapshot contains all fifty researched breeds and Russian Black Terrier alias`() {
        val snapshot = BreedReferenceSnapshot.bundled()

        assertEquals(1, snapshot.manifest.schemaVersion)
        assertEquals(50, snapshot.breeds.size)
        assertEquals((1..50).toList(), snapshot.breeds.map { it.popularityRank }.sorted())
        assertSame(snapshot.breed("VBO:0200174"), snapshot.breed("VBO:0201146"))
        snapshot.breeds.filter { it.popularityRank <= 10 }.forEach { breed ->
            assertTrue(breed.values.any { it.adult && it.measure == BreedReferenceMeasure.WEIGHT })
        }
    }

    @Test
    fun `ranks 31 through 50 preserve approved adult semantics provenance and growth gaps`() {
        val snapshot = BreedReferenceSnapshot.bundled()
        val packageBreeds = snapshot.breeds.filter { it.popularityRank in 31..50 }

        assertEquals((31..50).toList(), packageBreeds.map { it.popularityRank })
        packageBreeds.forEach { breed ->
            assertTrue(breed.values.any {
                !it.adult && it.measure == BreedReferenceMeasure.WEIGHT &&
                    it.statistic == BreedReferenceStatisticKind.DOCUMENTED_GAP &&
                    it.ageMinimumDays == 0 && it.ageMaximumDays == 730 &&
                    it.gap!!.contains("size-category curves were not substituted")
            })
        }

        val expectedAdultStatistics = mapOf(
            "VBO:0201415" to setOf(BreedReferenceStatisticKind.RANGE),
            "VBO:0201448" to setOf(BreedReferenceStatisticKind.MAXIMUM),
            "VBO:0200161" to setOf(BreedReferenceStatisticKind.RANGE),
            "VBO:0200339" to setOf(BreedReferenceStatisticKind.RANGE, BreedReferenceStatisticKind.IDEAL_RANGE),
            "VBO:0200962" to setOf(BreedReferenceStatisticKind.RANGE),
            "VBO:0200485" to setOf(BreedReferenceStatisticKind.STANDARD_POINT),
            "VBO:0200163" to setOf(BreedReferenceStatisticKind.APPROXIMATE_AVERAGE),
            "VBO:0201198" to setOf(BreedReferenceStatisticKind.RANGE),
            "VBO:0200713" to setOf(BreedReferenceStatisticKind.MAXIMUM),
            "VBO:0201403" to setOf(BreedReferenceStatisticKind.APPROXIMATE_RANGE),
            "VBO:0200340" to setOf(BreedReferenceStatisticKind.RANGE, BreedReferenceStatisticKind.IDEAL_RANGE),
            "VBO:0200345" to setOf(BreedReferenceStatisticKind.RANGE),
            "VBO:0201348" to setOf(BreedReferenceStatisticKind.RANGE),
            "VBO:0200410" to setOf(BreedReferenceStatisticKind.MAXIMUM),
            "VBO:0200027" to setOf(BreedReferenceStatisticKind.DOCUMENTED_GAP),
            "VBO:0201217" to setOf(BreedReferenceStatisticKind.DOCUMENTED_GAP),
            "VBO:0200882" to setOf(BreedReferenceStatisticKind.RANGE),
            "VBO:0200764" to setOf(BreedReferenceStatisticKind.RANGE),
            "VBO:0200375" to setOf(BreedReferenceStatisticKind.RANGE, BreedReferenceStatisticKind.DOCUMENTED_GAP),
            "VBO:0201143" to setOf(BreedReferenceStatisticKind.STANDARD_POINT),
        )
        expectedAdultStatistics.forEach { (breedId, statistics) ->
            assertEquals(statistics, snapshot.breed(breedId)!!.values.filter { it.adult }.map { it.statistic }.toSet())
        }

        val dachshund = snapshot.breed("VBO:0200410")!!.values.single { it.id == "dms-adult" }
        assertEquals(5.0, dachshund.upper)
        assertEquals(null, dachshund.lower)
        assertTrue(dachshund.limitations.any { it.contains("5.5 kg") })
        assertTrue(snapshot.breed("VBO:0200027")!!.values.none { it.lower != null || it.center != null || it.upper != null })
        assertTrue(snapshot.breed("VBO:0201217")!!.values.all { it.statistic == BreedReferenceStatisticKind.DOCUMENTED_GAP })
        assertTrue(snapshot.breed("VBO:0200375")!!.values.any {
            it.id == "col-female-gap" && it.sex == BreedReferenceSex.FEMALE &&
                it.gap!!.contains("no numeric female interval was synthesized")
        })
        assertTrue(snapshot.manifest.sources.single { it.id == "wiki-westie" }.method!!.contains("CC BY-SA"))
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
        assertTrue(snapshot.breed("VBO:0200734")!!.values.filter {
            it.adult && it.statistic != BreedReferenceStatisticKind.DOCUMENTED_GAP
        }.all { it.activeForProduct })
        assertTrue(snapshot.breed("VBO:0200880")!!.values.all {
            it.statistic == BreedReferenceStatisticKind.DOCUMENTED_GAP
        })
        assertTrue(snapshot.breed("VBO:0200724")!!.values.any {
            it.adult && it.lower == 5.0 && it.upper == 6.0 && it.sourceId == "fci345" &&
                it.statistic == BreedReferenceStatisticKind.IDEAL_RANGE
        })
        assertEquals(
            BreedReferenceStatisticKind.IDEAL_RANGE,
            snapshot.breed("VBO:0201089")!!.values.single { it.id == "pug-w" }.statistic,
        )
        listOf("VBO:0200898", "VBO:0200899", "VBO:0200897").forEach { breedId ->
            assertEquals(
                BreedReferenceStatisticKind.APPROXIMATE_RANGE,
                snapshot.breed(breedId)!!.values.single { it.adult }.statistic,
            )
        }
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
    fun `AmStaff uses one Wikipedia fallback and keeps other numeric weights inactive`() {
        val values = BreedReferenceSnapshot.bundled().breed("VBO:0200055")!!.values

        val active = values.filter {
            it.measure == BreedReferenceMeasure.WEIGHT && it.adult && it.activeForProduct
        }
        assertEquals(1, active.size)
        assertTrue(active.single().let {
            it.id == "ams-wiki-adult" && it.sex == BreedReferenceSex.COMBINED &&
                it.statistic == BreedReferenceStatisticKind.APPROXIMATE_RANGE &&
                it.lower == 23.0 && it.upper == 36.0 && it.sourceId == "wiki-amstaff" &&
                it.limitations.any { limitation -> limitation.contains("40–70 lb") }
        })
        assertTrue(values.any { it.sex == BreedReferenceSex.MALE && it.lower == 28.0 && it.upper == 33.0 && !it.activeForProduct })
        assertTrue(values.any { it.sex == BreedReferenceSex.FEMALE && it.lower == 19.0 && it.upper == 25.0 && !it.activeForProduct })
        assertTrue(values.any { it.sex == BreedReferenceSex.MALE && it.center == 28.3 && it.sampleSize == 570 && !it.activeForProduct })
        assertTrue(values.any { it.sex == BreedReferenceSex.FEMALE && it.center == 23.5 && it.sampleSize == 637 && !it.activeForProduct })
        assertTrue(values.any { it.sex == BreedReferenceSex.COMBINED && it.lower == 18.1 && !it.activeForProduct })
        assertTrue(values.any {
            it.id == "ams-adult-weight-gap" && it.sourceId == "fci286" &&
                it.statistic == BreedReferenceStatisticKind.DOCUMENTED_GAP && !it.activeForProduct
        })
    }

    @Test
    fun `Akita uses sex specific Wikipedia fallback and keeps FCI gap inactive`() {
        val values = BreedReferenceSnapshot.bundled().breed("VBO:0200734")!!.values

        val adult = values.filter { it.adult && it.statistic != BreedReferenceStatisticKind.DOCUMENTED_GAP }
        assertEquals(2, adult.size)
        assertTrue(adult.all { it.activeForProduct && it.sourceId == "wiki-akita" })
        assertTrue(adult.any { it.sex == BreedReferenceSex.MALE && it.lower == 27.0 && it.upper == 59.0 })
        assertTrue(adult.any { it.sex == BreedReferenceSex.FEMALE && it.lower == 25.0 && it.upper == 45.0 })
        assertTrue(values.any {
            it.id == "aki-adult-weight-gap" && it.sourceId == "fci255" &&
                it.statistic == BreedReferenceStatisticKind.DOCUMENTED_GAP && !it.activeForProduct
        })
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
