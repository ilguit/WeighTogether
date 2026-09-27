package com.palixander.scalesync

import com.palixander.scalesync.domain.BreedId
import com.palixander.scalesync.domain.PetSpecies
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
        }
    }

    @Test
    fun nonEnglishLocalesHaveLocaleSpecificPresentation() {
        val englishDog = option(BreedId("VBO:0200577"), PetSpecies.DOG, "en").displayName
        val englishCat = option(BreedId("VBO:0100052"), PetSpecies.CAT, "en").displayName
        locales.filterNot { it.language == "en" }.forEach { locale ->
            assertNotEquals(englishDog, option(BreedId("VBO:0200577"), PetSpecies.DOG, locale.language).displayName)
            assertNotEquals(englishCat, option(BreedId("VBO:0100052"), PetSpecies.CAT, locale.language).displayName)
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
}
