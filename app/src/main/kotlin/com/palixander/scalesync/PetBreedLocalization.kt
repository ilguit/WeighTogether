package com.palixander.scalesync

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.palixander.scalesync.core.breed.BreedCatalog
import com.palixander.scalesync.core.breed.BreedSpecies
import com.palixander.scalesync.core.breed.canonicalBreedId
import com.palixander.scalesync.domain.PetSpecies
import java.io.InputStream
import java.text.Collator
import java.text.Normalizer
import java.util.Locale

/** Deterministic, presentation-only breed names keyed by stable canonical breed ID. */
internal class PetBreedLocalization private constructor(
    private val namesById: Map<String, Map<String, String>>,
) {
    fun displayName(id: String, englishName: String, russianName: String, locale: Locale): String {
        require(englishName.isNotBlank()) { "Canonical English breed name must not be blank" }
        require(russianName.isNotBlank()) { "Russian breed name must not be blank" }
        return when (val language = locale.language) {
            "ru" -> russianName
            "en" -> englishName
            in TRANSLATED_LOCALES -> namesById[id]?.get(language) ?: englishName
            else -> englishName
        }
    }

    companion object {
        const val RESOURCE_PATH = "pet_breed_localizations.json"
        const val SCHEMA_VERSION = 1
        val TRANSLATED_LOCALES = setOf("be", "de", "fr", "it", "ja", "uk", "zh")

        fun bundled(expectedBreeds: Map<String, PetSpecies>, catalog: BreedCatalog): PetBreedLocalization = load(
            streamProvider = {
                PetBreedLocalization::class.java.getResourceAsStream("/$RESOURCE_PATH")
                    ?: error("Bundled breed localization resource is missing: $RESOURCE_PATH")
            },
            expectedBreeds = expectedBreeds,
            catalog = catalog,
            requireCompleteSchema = true,
        )

        fun load(
            streamProvider: () -> InputStream,
            expectedBreeds: Map<String, PetSpecies>,
            catalog: BreedCatalog,
            requireCompleteSchema: Boolean = false,
        ): PetBreedLocalization = streamProvider().use { stream ->
            val root = JsonParser.parseReader(stream.reader(Charsets.UTF_8)).asJsonObject
            root.requireOnlyKeys(ROOT_KEYS, "root")
            require(root.requiredInt("schemaVersion") == SCHEMA_VERSION) { "Unsupported breed localization schema" }
            val locales = root.requiredArray("locales").map { element ->
                require(element.isJsonPrimitive && element.asJsonPrimitive.isString) { "Locale keys must be strings" }
                element.asString
            }
            require(locales.size == locales.toSet().size) { "Duplicate locale key" }
            require(locales.toSet() == TRANSLATED_LOCALES) { "Unsupported or missing locale keys" }

            val entries = root.requiredArray("breeds").map { element ->
                require(element.isJsonObject) { "Breed localization entries must be objects" }
                val entry = element.asJsonObject
                entry.requireOnlyKeys(BREED_KEYS, "breed")
                val id = entry.requiredString("id")
                require(id.isNotBlank()) { "Breed localization ID must not be blank" }
                require(canonicalBreedId(id) == id) { "Noncanonical breed ID $id" }
                val species = when (entry.requiredString("species")) {
                    "cat" -> PetSpecies.CAT
                    "dog" -> PetSpecies.DOG
                    else -> throw IllegalArgumentException("Unsupported breed species")
                }
                val names = entry.getAsJsonObject("names") ?: JsonObject()
                names.requireOnlyKeys(TRANSLATED_LOCALES, "names for $id")
                val localizedNames = names.entrySet().associate { (locale, value) ->
                    require(value.isJsonPrimitive && value.asJsonPrimitive.isString && value.asString.isNotBlank()) {
                        "Blank breed name for $id/$locale"
                    }
                    locale to value.asString
                }
                Triple(id, species, localizedNames)
            }

            require(entries.map { it.first }.toSet().size == entries.size) { "Duplicate breed localization ID" }
            val actualSpecies = entries.associate { it.first to it.second }
            if (requireCompleteSchema) {
                require(actualSpecies.values.count { it == PetSpecies.DOG } == 51) { "Expected 51 dog breed IDs" }
                require(actualSpecies.values.count { it == PetSpecies.CAT } == 31) { "Expected 31 cat breed IDs" }
                entries.forEach { (id, _, names) ->
                    require(names.keys == TRANSLATED_LOCALES && names.values.all(String::isNotBlank)) {
                        "Breed localization schema must contain every translated locale for $id"
                    }
                }
            }
            require(expectedBreeds.keys.all { it in actualSpecies }) {
                val missing = expectedBreeds.keys - actualSpecies.keys
                "Breed localization schema is missing selectable IDs: $missing"
            }
            expectedBreeds.forEach { (id, species) ->
                require(actualSpecies.getValue(id) == species) { "Species mismatch for $id" }
            }
            entries.forEach { (id, species) ->
                val record = catalog.findById(id) ?: throw IllegalArgumentException("Unknown breed ID $id")
                val catalogSpecies = if (record.species == BreedSpecies.CAT) PetSpecies.CAT else PetSpecies.DOG
                require(catalogSpecies == species) { "Catalog species mismatch for $id" }
            }
            PetBreedLocalization(entries.associate { it.first to it.third })
        }

        fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
            .trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)

        fun comparator(locale: Locale): Comparator<PetBreedOption> {
            val collator = Collator.getInstance(locale).apply { strength = Collator.PRIMARY }
            return Comparator { left, right ->
                collator.compare(left.displayName, right.displayName).takeUnless { it == 0 }
                    ?: left.canonicalName.compareTo(right.canonicalName, ignoreCase = true).takeUnless { it == 0 }
                    ?: left.id.value.compareTo(right.id.value)
            }
        }

        private val ROOT_KEYS = setOf("schemaVersion", "locales", "breeds")
        private val BREED_KEYS = setOf("id", "species", "names")

        private fun JsonObject.requireOnlyKeys(supported: Set<String>, location: String) {
            val unsupported = keySet() - supported
            require(unsupported.isEmpty()) { "Unsupported keys in $location: $unsupported" }
        }

        private fun JsonObject.requiredString(key: String): String {
            val value = get(key)
            require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString) { "Missing string $key" }
            return value.asString
        }

        private fun JsonObject.requiredInt(key: String): Int {
            val value = get(key)
            require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isNumber) { "Missing integer $key" }
            return value.asInt
        }

        private fun JsonObject.requiredArray(key: String) = get(key).also { value ->
            require(value != null && value.isJsonArray) { "Missing array $key" }
        }.asJsonArray
    }
}
