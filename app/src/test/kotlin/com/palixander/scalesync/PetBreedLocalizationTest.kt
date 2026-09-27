package com.palixander.scalesync

import com.google.gson.JsonParser
import com.palixander.scalesync.domain.BreedId
import com.palixander.scalesync.domain.PetSpecies
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PetBreedLocalizationTest {
    private val catalog = PetBreedCatalog()
    private val locales = listOf("en", "ru", "de", "fr", "it", "ja", "zh", "be", "uk")
        .map(Locale::forLanguageTag)

    @Test
    fun everySupportedLocalePresentsEverySupportedBreedWithoutChangingIds() {
        PetSpecies.entries.filter { it != PetSpecies.UNSPECIFIED }.forEach { species ->
            val english = catalog.search("", species, Locale.ENGLISH)
            assertTrue(english.isNotEmpty())

            locales.forEach { locale ->
                val localized = catalog.search("", species, locale)
                assertEquals(locale.language, english.map { it.id }.toSet(), localized.map { it.id }.toSet())
                assertTrue(locale.language, localized.all { it.displayName.isNotBlank() })
                assertEquals(english.associate { it.id to it.canonicalName }, localized.associate { it.id to it.canonicalName })
            }
        }
    }

    @Test
    fun englishAndRussianUseCatalogSourceNamesAndUnknownLocaleFallsBackToEnglish() {
        val id = BreedId("VBO:0200800")
        assertEquals("Labrador Retriever", option(id, PetSpecies.DOG, "en").displayName)
        assertEquals("Лабрадор-ретривер", option(id, PetSpecies.DOG, "ru").displayName)
        assertEquals("Labrador Retriever", option(id, PetSpecies.DOG, "ar").displayName)
    }

    @Test
    fun eachConfiguredLocaleCanSearchCurrentLabelAndEnglishCanonicalName() {
        locales.forEach { locale ->
            val localized = option(BreedId("VBO:0200577"), PetSpecies.DOG, locale.language)
            assertEquals(localized.id, catalog.search(localized.displayName, PetSpecies.DOG, locale).single().id)
            assertEquals(localized.id, catalog.search("German Shepherd Dog", PetSpecies.DOG, locale).single().id)
        }
    }

    @Test
    fun aliasesRemainSearchableAndSpeciesSafeInEveryLocale() {
        locales.forEach { locale ->
            val dog = catalog.search("Labrador Retriever", PetSpecies.DOG, locale).single()
            assertTrue(catalog.search("Labrador", PetSpecies.DOG, locale).any { it.id == dog.id })
            assertTrue(catalog.search("Labrador Retriever", PetSpecies.CAT, locale).isEmpty())

            val cat = catalog.search("Maine Coon Cat", PetSpecies.CAT, locale).single()
            assertTrue(catalog.search("Maine Coon Cat", PetSpecies.DOG, locale).isEmpty())
            assertEquals(BreedId("VBO:0100154"), cat.id)
        }
    }

    @Test
    fun resolvingLocalizedOptionsPreservesCanonicalAndDuplicateIds() {
        locales.forEach { locale ->
            val sphynx = catalog.resolve(BreedId("VBO:0100061"), PetSpecies.CAT, locale)
                as PetBreedSelection.Available
            assertEquals(BreedId("VBO:0100230"), sphynx.id)
            assertEquals("Sphynx", sphynx.option.canonicalName)

            val blackRussianTerrier = catalog.resolve(BreedId("VBO:0201146"), PetSpecies.DOG, locale)
                as PetBreedSelection.Available
            assertEquals(BreedId("VBO:0200174"), blackRussianTerrier.id)
            assertEquals("Russkiy Tchiorny Terrier", blackRussianTerrier.option.canonicalName)
        }
    }

    @Test
    fun newlyLocalizedDogNamesAreExactAndSearchableInEveryTranslatedLocale() {
        val expected = mapOf(
            "be" to listOf("Вельш-коргі-пемброк", "Малая італьянская хорт"),
            "de" to listOf("Welsh Corgi Pembroke", "Italienisches Windspiel"),
            "fr" to listOf("Welsh Corgi Pembroke", "Petit lévrier italien"),
            "it" to listOf("Welsh Corgi Pembroke", "Piccolo levriero italiano"),
            "ja" to listOf("ウェルシュ・コーギー・ペンブローク", "イタリアン・グレーハウンド"),
            "uk" to listOf("Вельш-коргі-пемброк", "Мала італійська хорт"),
            "zh" to listOf("彭布罗克威尔士柯基犬", "意大利灵缇犬"),
        )
        val ids = listOf(BreedId("VBO:0200995"), BreedId("VBO:0200713"))

        expected.forEach { (language, names) ->
            val locale = Locale.forLanguageTag(language)
            ids.zip(names).forEach { (id, name) ->
                assertEquals(language, name, option(id, PetSpecies.DOG, language).displayName)
                assertTrue(catalog.search(name, PetSpecies.DOG, locale).any { it.id == id })
            }
        }
    }

    @Test
    fun representativeDogBreedsHaveExactNaturalNamesAndRemainSearchableByLocalizedAndEnglishNames() {
        val expected = mapOf(
            "be" to listOf("Амерыканская акіта", "Бернскі зененхунд", "Нямецкая аўчарка"),
            "de" to listOf("Amerikanischer Akita", "Berner Sennenhund", "Deutscher Schäferhund"),
            "fr" to listOf("Akita américain", "Bouvier bernois", "Berger allemand"),
            "it" to listOf("Akita americano", "Bovaro del Bernese", "Pastore tedesco"),
            "ja" to listOf("アメリカン・アキタ", "バーニーズ・マウンテン・ドッグ", "ジャーマン・シェパード・ドッグ"),
            "uk" to listOf("Американська акіта", "Бернський зенненхунд", "Німецька вівчарка"),
            "zh" to listOf("美国秋田犬", "伯恩山犬", "德国牧羊犬"),
        )
        val breeds = listOf(
            BreedId("VBO:0200027") to "American Akita",
            BreedId("VBO:0200161") to "Bernese Mountain Dog",
            BreedId("VBO:0200577") to "German Shepherd Dog",
        )

        expected.forEach { (language, names) ->
            val locale = Locale.forLanguageTag(language)
            breeds.zip(names).forEach { (breed, localizedName) ->
                val (id, englishName) = breed
                assertEquals(language, localizedName, option(id, PetSpecies.DOG, language).displayName)
                assertTrue(catalog.search(localizedName, PetSpecies.DOG, locale).any { it.id == id })
                assertTrue(catalog.search(englishName, PetSpecies.DOG, locale).any { it.id == id })
            }
        }
    }

    @Test
    fun translatedDogNamesContainNeitherGenerationMarkersNorDuplicatedWords() {
        val marker = Regex("(?i)(translated|translation|locale|language|\\[.{0,12}])")
        PetBreedLocalization.TRANSLATED_LOCALES.forEach { language ->
            catalog.search("", PetSpecies.DOG, Locale.forLanguageTag(language))
                .forEach { option ->
                    assertTrue("$language: ${option.displayName}", !marker.containsMatchIn(option.displayName))
                    val words = option.displayName.lowercase(Locale.ROOT)
                        .split(Regex("[\\s-]+"))
                        .filter(String::isNotBlank)
                    assertTrue("$language: ${option.displayName}", words.zipWithNext().none { (a, b) -> a == b })
                }
        }
    }

    @Test
    fun representativeCatBreedsHaveExactNaturalNamesAndRemainSearchableByLocalizedAndEnglishNames() {
        val expected = mapOf(
            "en" to listOf("British Shorthair", "Maine Coon", "Siamese"),
            "ru" to listOf("Британская короткошёрстная", "Мейн-кун", "Сиамская"),
            "be" to listOf("Брытанская кароткашэрсная", "Мэйн-кун", "Сіямская"),
            "de" to listOf("Britisch Kurzhaar", "Maine-Coon-Katze", "Siamkatze"),
            "fr" to listOf("British Shorthair", "Maine Coon", "Siamois"),
            "it" to listOf("British Shorthair", "Maine Coon", "Siamese"),
            "ja" to listOf("ブリティッシュショートヘア", "メインクーン", "シャム"),
            "uk" to listOf("Британська короткошерста", "Мейн-кун", "Сіамська"),
            "zh" to listOf("英国短毛猫", "缅因猫", "暹罗猫"),
        )
        val breeds = listOf(
            BreedId("VBO:0100052") to "British Shorthair",
            BreedId("VBO:0100154") to "Maine Coon",
            BreedId("VBO:0100221") to "Siamese",
        )

        expected.forEach { (language, names) ->
            val locale = Locale.forLanguageTag(language)
            breeds.zip(names).forEach { (breed, localizedName) ->
                val (id, englishName) = breed
                assertEquals(language, localizedName, option(id, PetSpecies.CAT, language).displayName)
                assertTrue(catalog.search(localizedName, PetSpecies.CAT, locale).any { it.id == id })
                assertTrue(catalog.search(englishName, PetSpecies.CAT, locale).any { it.id == id })
            }
        }
    }

    @Test
    fun translatedCatNamesContainNeitherGenerationMarkersNorDuplicatedWords() {
        val marker = Regex("(?i)(translated|translation|locale|language|\\[.{0,12}])")
        PetBreedLocalization.TRANSLATED_LOCALES.forEach { language ->
            catalog.search("", PetSpecies.CAT, Locale.forLanguageTag(language)).forEach { option ->
                assertTrue("$language: ${option.displayName}", !marker.containsMatchIn(option.displayName))
                val words = option.displayName.lowercase(Locale.ROOT)
                    .split(Regex("[\\s-]+"))
                    .filter(String::isNotBlank)
                assertTrue("$language: ${option.displayName}", words.zipWithNext().none { (a, b) -> a == b })
            }
        }
    }

    @Test
    fun explicitLocalizedNameIsResolvedByCanonicalId() {
        val localization = load(singleBreedJson(names = "\"de\":\"Deutscher Name\""))
        assertEquals(
            "Deutscher Name",
            localization.displayName(TEST_ID, "German Shepherd Dog", "Немецкая овчарка", Locale.GERMAN),
        )
        assertEquals(
            "German Shepherd Dog",
            localization.displayName(TEST_ID, "German Shepherd Dog", "Немецкая овчарка", Locale.FRENCH),
        )
    }

    @Test
    fun loaderRejectsDuplicateUnknownAndNoncanonicalIds() {
        assertInvalid(singleBreedJson(extraBreed = "{\"id\":\"$TEST_ID\",\"species\":\"dog\"}"))
        assertInvalid(singleBreedJson(id = "VBO:9999999"))
        assertInvalid(singleBreedJson(id = "VBO:0100061", species = "cat"))
    }

    @Test
    fun loaderRejectsUnsupportedKeysBlankNamesAndSpeciesMismatch() {
        assertInvalid(singleBreedJson(extraRoot = ",\"unexpected\":true"))
        assertInvalid(singleBreedJson(names = "\"es\":\"Nombre\""))
        assertInvalid(singleBreedJson(names = "\"de\":\"   \""))
        assertInvalid(singleBreedJson(species = "cat"))
    }

    @Test
    fun bundledSchemaCoversExactlyAllSelectableCanonicalIds() {
        val options = listOf(PetSpecies.CAT, PetSpecies.DOG).flatMap { catalog.search("", it, Locale.ENGLISH) }
        assertEquals(82, options.size)
        assertEquals(82, options.map { it.id }.toSet().size)
        assertTrue(options.all { com.palixander.scalesync.core.breed.canonicalBreedId(it.id.value) == it.id.value })

        val root = PetBreedLocalizationTest::class.java
            .getResourceAsStream("/${PetBreedLocalization.RESOURCE_PATH}")!!.reader().use(JsonParser::parseReader)
            .asJsonObject
        val entries = root.getAsJsonArray("breeds")
        assertEquals(82, entries.size())
        entries.forEach { element ->
            val names = element.asJsonObject.getAsJsonObject("names")
            assertEquals(
                element.asJsonObject.get("id").asString,
                PetBreedLocalization.TRANSLATED_LOCALES,
                names.keySet(),
            )
            assertTrue(names.entrySet().all { (_, value) -> value.asString.isNotBlank() })
        }
    }

    @Test
    fun sortingIsDeterministicForEveryLocale() {
        locales.forEach { locale ->
            PetSpecies.entries.filter { it != PetSpecies.UNSPECIFIED }.forEach { species ->
                val first = catalog.search("", species, locale).map { it.id }
                assertEquals(first, catalog.search("", species, locale).map { it.id })
            }
        }
    }

    private fun option(id: BreedId, species: PetSpecies, language: String): PetBreedOption =
        (catalog.resolve(id, species, Locale.forLanguageTag(language)) as PetBreedSelection.Available).option

    private fun load(json: String): PetBreedLocalization = PetBreedLocalization.load(
        streamProvider = { json.byteInputStream() },
        expectedBreeds = mapOf(TEST_ID to PetSpecies.DOG),
        catalog = com.palixander.scalesync.core.breed.BreedCatalog.bundled(),
    )

    private fun assertInvalid(json: String) {
        assertThrows(IllegalArgumentException::class.java) { load(json) }
    }

    private fun singleBreedJson(
        id: String = TEST_ID,
        species: String = "dog",
        names: String = "",
        extraBreed: String? = null,
        extraRoot: String = "",
    ): String {
        val breeds = buildList {
            add("{\"id\":\"$id\",\"species\":\"$species\",\"names\":{$names}}")
            extraBreed?.let(::add)
        }.joinToString(",")
        return """{"schemaVersion":1,"locales":["be","de","fr","it","ja","uk","zh"],"breeds":[$breeds]$extraRoot}"""
    }

    private companion object {
        const val TEST_ID = "VBO:0200577"
    }
}
