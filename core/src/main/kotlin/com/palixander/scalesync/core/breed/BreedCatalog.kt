package com.palixander.scalesync.core.breed

import com.google.gson.Gson
import java.io.InputStream
import java.text.Normalizer
import java.util.Locale

enum class BreedSpecies {
    CAT,
    DOG,
}

enum class BreedKind {
    VBO,
    MIXED,
    UNKNOWN,
}

data class BreedRecord(
    val id: String,
    val species: BreedSpecies,
    val canonicalName: String,
    val displayNameRu: String,
    val aliases: List<String>,
    val kind: BreedKind,
)

data class BreedCatalogManifest(
    val schemaVersion: Int,
    val sourceName: String,
    val sourceVersion: String,
    val sourceUrl: String,
    val license: String,
    val licenseUrl: String,
    val snapshotDate: String,
    val sourceSha256: String,
    val catalogSha256: String,
)

class BreedCatalog private constructor(
    val manifest: BreedCatalogManifest,
    records: List<BreedRecord>,
) {
    private val records = records.sortedWith(recordComparator)
    private val recordsById = this.records.associateBy(BreedRecord::id)
    private val searchableRecords = this.records.associateWith { record ->
        sequenceOf(record.displayNameRu, record.canonicalName)
            .plus(record.aliases.asSequence())
            .map(::normalizeSearchText)
            .toList()
    }

    init {
        require(manifest.schemaVersion == SUPPORTED_SCHEMA_VERSION) {
            "Unsupported breed catalog schema: ${manifest.schemaVersion}"
        }
        require(recordsById.size == this.records.size) { "Breed catalog contains duplicate IDs" }
    }

    fun all(): List<BreedRecord> = records

    fun all(species: BreedSpecies): List<BreedRecord> = records.filter { it.species == species }

    fun findById(id: String): BreedRecord? = recordsById[id]

    fun search(query: String, species: BreedSpecies? = null): List<BreedRecord> {
        val normalizedQuery = normalizeSearchText(query)
        return records.filter { record ->
            (species == null || record.species == species) &&
                (normalizedQuery.isEmpty() || searchableRecords.getValue(record).any { normalizedQuery in it })
        }
    }

    companion object {
        const val RESOURCE_PATH = "breed_catalog.json"
        const val SUPPORTED_SCHEMA_VERSION = 1

        private val whitespace = Regex("\\s+")
        private val recordComparator = compareBy<BreedRecord>(
            { if (it.kind == BreedKind.UNKNOWN) 0 else 1 },
            { normalizeSearchText(it.displayNameRu) },
            { normalizeSearchText(it.canonicalName) },
            BreedRecord::id,
        )

        fun bundled(): BreedCatalog = load {
            BreedCatalog::class.java.classLoader.getResourceAsStream(RESOURCE_PATH)
                ?: error("Bundled breed catalog resource is missing: $RESOURCE_PATH")
        }

        fun load(streamProvider: () -> InputStream): BreedCatalog = streamProvider().use { stream ->
            val document = Gson().fromJson(stream.reader(Charsets.UTF_8), CatalogDocument::class.java)
                ?: throw IllegalArgumentException("Breed catalog is empty")
            BreedCatalog(
                manifest = document.manifest.toPublicModel(),
                records = document.breeds.map(BreedJson::toPublicModel),
            )
        }

        private fun normalizeSearchText(value: String): String = Normalizer
            .normalize(value, Normalizer.Form.NFKC)
            .trim()
            .replace(whitespace, " ")
            .lowercase(Locale.ROOT)
    }
}

private data class CatalogDocument(
    val manifest: ManifestJson,
    val breeds: List<BreedJson>,
)

private data class ManifestJson(
    val schemaVersion: Int,
    val sourceName: String,
    val sourceVersion: String,
    val sourceUrl: String,
    val license: String,
    val licenseUrl: String,
    val snapshotDate: String,
    val sourceSha256: String,
    val catalogSha256: String,
) {
    fun toPublicModel() = BreedCatalogManifest(
        schemaVersion,
        sourceName,
        sourceVersion,
        sourceUrl,
        license,
        licenseUrl,
        snapshotDate,
        sourceSha256,
        catalogSha256,
    )
}

private data class BreedJson(
    val id: String,
    val species: String,
    val canonicalName: String,
    val displayNameRu: String,
    val aliases: List<String>,
    val kind: String,
) {
    fun toPublicModel() = BreedRecord(
        id = id,
        species = BreedSpecies.valueOf(species.uppercase(Locale.ROOT)),
        canonicalName = canonicalName,
        displayNameRu = displayNameRu,
        aliases = aliases.toList(),
        kind = BreedKind.valueOf(kind.uppercase(Locale.ROOT)),
    )
}
