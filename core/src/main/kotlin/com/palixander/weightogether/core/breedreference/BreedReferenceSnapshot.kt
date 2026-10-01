package com.palixander.weightogether.core.breedreference

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.palixander.weightogether.core.breed.BreedCatalog
import com.palixander.weightogether.core.breed.BreedSpecies
import java.io.InputStream
import java.security.MessageDigest

enum class BreedReferenceSex { MALE, FEMALE, COMBINED }
enum class BreedReferenceSourceKind { INTERNATIONAL_STANDARD, NATIONAL_STANDARD, BREED_CLUB, PROFESSIONAL_REFERENCE, OBSERVATIONAL, MODELLED }
enum class BreedReferenceStatisticKind {
    RANGE,
    QUANTILES,
    MEAN,
    MEDIAN,
    APPROXIMATE_AVERAGE,
    APPROXIMATE_RANGE,
    MEAN_SD,
    IDEAL,
    IDEAL_RANGE,
    STANDARD_POINT,
    MINIMUM,
    MAXIMUM,
    DOCUMENTED_GAP,
}
enum class BreedReferenceMeasure { WEIGHT, HEIGHT }

data class BreedReferenceSource(
    val id: String,
    val title: String,
    val url: String,
    val year: Int?,
    val kind: BreedReferenceSourceKind,
    val geography: String?,
    val method: String?,
    val pageOrTable: String?,
)

data class BreedReferenceValue(
    val id: String,
    val measure: BreedReferenceMeasure,
    val sex: BreedReferenceSex,
    val ageMinimumDays: Int?,
    val ageMaximumDays: Int?,
    val ageLabel: String,
    val adult: Boolean,
    val statistic: BreedReferenceStatisticKind,
    val unit: String,
    val lower: Double?,
    val center: Double?,
    val upper: Double?,
    val spread: Double?,
    val sampleSize: Int?,
    val sampleUnit: String?,
    val sourceId: String?,
    val limitations: List<String>,
    val gap: String?,
    val activeForProduct: Boolean,
)

data class BreedReferenceBreed(
    val breedId: String,
    val aliases: List<String>,
    val russianName: String,
    val englishName: String,
    val popularityRank: Int,
    val registrations: Int,
    val values: List<BreedReferenceValue>,
)

data class BreedReferenceManifest(
    val schemaVersion: Int,
    val snapshotVersion: String,
    val snapshotDate: String,
    val numericalDataSha256: String,
    val sources: List<BreedReferenceSource>,
)

sealed interface BreedReferenceSnapshotLoadResult {
    data class Available(val snapshot: BreedReferenceSnapshot) : BreedReferenceSnapshotLoadResult
    data class Unavailable(val reason: String) : BreedReferenceSnapshotLoadResult
}

class BreedReferenceSnapshot private constructor(
    val manifest: BreedReferenceManifest,
    val breeds: List<BreedReferenceBreed>,
) {
    fun breed(id: String): BreedReferenceBreed? = breeds.firstOrNull { it.breedId == id || id in it.aliases }

    companion object {
        const val RESOURCE_PATH = "breed_references.json"
        const val SCHEMA_VERSION = 1

        fun bundled(breedCatalog: BreedCatalog = BreedCatalog.bundled()): BreedReferenceSnapshot =
            when (val result = bundledOrUnavailable(breedCatalog)) {
                is BreedReferenceSnapshotLoadResult.Available -> result.snapshot
                is BreedReferenceSnapshotLoadResult.Unavailable -> error(result.reason)
            }

        fun bundledOrUnavailable(breedCatalog: BreedCatalog = BreedCatalog.bundled()): BreedReferenceSnapshotLoadResult =
            loadOrUnavailable(
                streamProvider = { BreedReferenceSnapshot::class.java.classLoader.getResourceAsStream(RESOURCE_PATH) },
                breedCatalog = breedCatalog,
            )

        fun load(streamProvider: () -> InputStream?, breedCatalog: BreedCatalog = BreedCatalog.bundled()): BreedReferenceSnapshot =
            when (val result = loadOrUnavailable(streamProvider, breedCatalog)) {
                is BreedReferenceSnapshotLoadResult.Available -> result.snapshot
                is BreedReferenceSnapshotLoadResult.Unavailable -> throw IllegalArgumentException(result.reason)
            }

        fun loadOrUnavailable(
            streamProvider: () -> InputStream?,
            breedCatalog: BreedCatalog = BreedCatalog.bundled(),
        ): BreedReferenceSnapshotLoadResult = runCatching {
            val json = streamProvider()?.bufferedReader()?.use { it.readText() } ?: error("Missing $RESOURCE_PATH")
            val root = JsonParser.parseString(json).asJsonObject
            val document = Gson().fromJson(root, SnapshotJson::class.java)
            require(document.manifest.schemaVersion == SCHEMA_VERSION) { "Unsupported breed reference schema" }
            val checksum = sha256(root.getAsJsonArray("breeds").toString().toByteArray(Charsets.UTF_8))
            require(checksum == document.manifest.numericalDataSha256) {
                "Breed reference checksum mismatch: expected ${document.manifest.numericalDataSha256}, actual $checksum"
            }
            val snapshot = BreedReferenceSnapshot(document.manifest.toModel(), document.breeds.map(BreedJson::toModel))
            snapshot.validate(breedCatalog)
            snapshot
        }.fold(
            onSuccess = { BreedReferenceSnapshotLoadResult.Available(it) },
            onFailure = { BreedReferenceSnapshotLoadResult.Unavailable(it.message ?: "Invalid breed reference snapshot") },
        )

        private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private fun validate(catalog: BreedCatalog) {
        require(manifest.snapshotVersion.isNotBlank() && manifest.snapshotDate.matches(Regex("\\d{4}-\\d{2}-\\d{2}")))
        require(manifest.sources.map { it.id }.toSet().size == manifest.sources.size) { "Duplicate source ID" }
        require(breeds.size == 51) { "Expected exactly fifty-one supported breeds" }
        require(breeds.map { it.popularityRank }.sorted() == (1..50).toList() + 61) {
            "Breed popularity ranks must contain the researched top 50 and German Shepherd at rank 61"
        }
        require(breeds.map { it.breedId }.toSet().size == breeds.size) { "Duplicate breed ID" }
        val dogCatalogIds = catalog.all(BreedSpecies.DOG).map { it.id }.toSet()
        val sourceIds = manifest.sources.map { it.id }.toSet()
        val allIds = mutableSetOf<String>()
        val allAliases = mutableSetOf<String>()
        val primaryIds = breeds.map { it.breedId }.toSet()
        breeds.forEach { breed ->
            require(breed.breedId in dogCatalogIds) { "Unknown dog breed ${breed.breedId}" }
            require(breed.aliases.all { it in dogCatalogIds }) { "Unknown breed alias" }
            require(breed.aliases.all { it !in primaryIds && allAliases.add(it) }) { "Conflicting breed alias" }
            breed.values.forEach { value ->
                require(allIds.add(value.id)) { "Duplicate value ID ${value.id}" }
                require(value.unit == if (value.measure == BreedReferenceMeasure.WEIGHT) "kg" else "cm") { "Invalid unit" }
                require(value.sourceId == null || value.sourceId in sourceIds) { "Unknown source" }
                require(value.ageMinimumDays == null || value.ageMinimumDays >= 0)
                require(value.ageMaximumDays == null || value.ageMaximumDays >= (value.ageMinimumDays ?: 0))
                require(value.adult.xor(value.ageMinimumDays != null)) { "Exactly one age scope is required" }
                val numbers = listOfNotNull(value.lower, value.center, value.upper, value.spread)
                require(numbers.all { it > 0.0 }) { "Values must be positive" }
                require(value.lower == null || value.upper == null || value.lower <= value.upper) { "Reversed bounds" }
                if (value.statistic == BreedReferenceStatisticKind.DOCUMENTED_GAP) {
                    require(numbers.isEmpty() && !value.gap.isNullOrBlank()) { "Invalid documented gap" }
                } else {
                    require(value.sourceId != null && numbers.isNotEmpty() && value.gap == null) { "Incomplete numeric record" }
                    when (value.statistic) {
                        BreedReferenceStatisticKind.RANGE,
                        BreedReferenceStatisticKind.QUANTILES,
                        BreedReferenceStatisticKind.APPROXIMATE_RANGE,
                        BreedReferenceStatisticKind.IDEAL_RANGE,
                        -> require(value.lower != null && value.upper != null)
                        BreedReferenceStatisticKind.MEAN, BreedReferenceStatisticKind.MEDIAN, BreedReferenceStatisticKind.APPROXIMATE_AVERAGE -> require(value.center != null)
                        BreedReferenceStatisticKind.MEAN_SD -> require(value.center != null && value.spread != null)
                        BreedReferenceStatisticKind.IDEAL, BreedReferenceStatisticKind.STANDARD_POINT -> require(value.center != null)
                        BreedReferenceStatisticKind.MINIMUM -> require(value.lower != null && value.center == null && value.upper == null)
                        BreedReferenceStatisticKind.MAXIMUM -> require(value.lower == null && value.center == null && value.upper != null && value.spread == null)
                        BreedReferenceStatisticKind.DOCUMENTED_GAP -> Unit
                    }
                }
            }
        }
        require(breed("VBO:0200174") === breed("VBO:0201146")) { "Russian Black Terrier alias is not canonical" }
    }
}

private data class SnapshotJson(val manifest: ManifestJson, val breeds: List<BreedJson>)
private data class ManifestJson(val schemaVersion: Int, val snapshotVersion: String, val snapshotDate: String, val numericalDataSha256: String, val sources: List<SourceJson>) {
    fun toModel() = BreedReferenceManifest(schemaVersion, snapshotVersion, snapshotDate, numericalDataSha256, sources.map(SourceJson::toModel))
}
private data class SourceJson(val id: String, val title: String, val url: String, val year: Int?, val kind: String, val geography: String?, val method: String?, val pageOrTable: String?) {
    fun toModel() = BreedReferenceSource(id, title, url, year, enumValue(kind), geography, method, pageOrTable)
}
private data class BreedJson(val breedId: String, val aliases: List<String>?, val russianName: String, val englishName: String, val popularityRank: Int, val registrations: Int, val values: List<ValueJson>) {
    fun toModel() = BreedReferenceBreed(breedId, aliases.orEmpty(), russianName, englishName, popularityRank, registrations, values.map(ValueJson::toModel))
}
private data class ValueJson(val id: String, val measure: String, val sex: String, val ageMinimumDays: Int?, val ageMaximumDays: Int?, val ageLabel: String, val adult: Boolean, val statistic: String, val unit: String, val lower: Double?, val center: Double?, val upper: Double?, val spread: Double?, val sampleSize: Int?, val sampleUnit: String?, val sourceId: String?, val limitations: List<String>?, val gap: String?, val activeForProduct: Boolean?) {
    fun toModel() = BreedReferenceValue(id, enumValue(measure), enumValue(sex), ageMinimumDays, ageMaximumDays, ageLabel, adult, enumValue(statistic), unit, lower, center, upper, spread, sampleSize, sampleUnit, sourceId, limitations.orEmpty(), gap, activeForProduct ?: true)
}
private inline fun <reified T : Enum<T>> enumValue(value: String): T = enumValueOf(value.uppercase())
