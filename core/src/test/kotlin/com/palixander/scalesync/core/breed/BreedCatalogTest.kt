package com.palixander.scalesync.core.breed

import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BreedCatalogTest {
    @Test
    fun bundledCatalogExposesManifestAndBothSpecies() {
        val catalog = BreedCatalog.bundled()

        assertEquals(1, catalog.manifest.schemaVersion)
        assertEquals("2026-04-15", catalog.manifest.sourceVersion)
        assertTrue(catalog.all(BreedSpecies.CAT).isNotEmpty())
        assertTrue(catalog.all(BreedSpecies.DOG).isNotEmpty())
        assertTrue(catalog.all().any { it.kind == BreedKind.MIXED })
        assertTrue(catalog.all().any { it.kind == BreedKind.UNKNOWN })
    }

    @Test
    fun findsKnownIdAndReturnsNullForUnknownId() {
        val catalog = BreedCatalog.bundled()

        assertEquals("Абиссинская", catalog.findById("VBO:0100000")?.displayNameRu)
        assertEquals("Домашняя короткошёрстная", catalog.findById("VBO:0100119")?.displayNameRu)
        assertNull(catalog.findById("not-present"))
    }

    @Test
    fun canadianSphynxAliasResolvesToSingleCanonicalCatalogRecord() {
        val catalog = BreedCatalog.bundled()

        assertEquals("VBO:0100230", canonicalBreedId("VBO:0100061"))
        assertEquals("VBO:0100230", catalog.findById("VBO:0100061")?.id)
        assertEquals(listOf("VBO:0100230"), catalog.search("Canadian Sphynx").map { it.id })
        assertEquals(1, catalog.all(BreedSpecies.CAT).count { "Canadian Sphynx" in it.aliases })
    }

    @Test
    fun bundledCatalogHasExactApplicationOwnedChoicesAndExcludesRandomBredDuplicates() {
        val catalog = BreedCatalog.bundled()
        val specials = catalog.all().filter { it.kind != BreedKind.VBO }

        assertEquals(
            listOf(
                SpecialChoice("scalesync:cat:breed-unknown", BreedSpecies.CAT, BreedKind.UNKNOWN, "Без породы"),
                SpecialChoice("scalesync:dog:breed-unknown", BreedSpecies.DOG, BreedKind.UNKNOWN, "Без породы"),
                SpecialChoice("scalesync:cat:mixed-breed", BreedSpecies.CAT, BreedKind.MIXED, "Метис"),
                SpecialChoice("scalesync:dog:mixed-breed", BreedSpecies.DOG, BreedKind.MIXED, "Метис"),
            ),
            specials.map { SpecialChoice(it.id, it.species, it.kind, it.displayNameRu) },
        )
        assertEquals(
            listOf("scalesync:cat:breed-unknown", "scalesync:dog:breed-unknown"),
            catalog.search("Без породы").map { it.id },
        )
        assertNull(catalog.findById("VBO:0201489"))
        assertNull(catalog.findById("VBO:0200986"))
    }

    @Test
    fun searchesRussianCanonicalAndAliasesIgnoringCaseUnicodeAndWhitespace() {
        val catalog = loadFixture()

        assertEquals(listOf("russian"), catalog.search("  РУССКАЯ\tПОРОДА ").map { it.id })
        assertEquals(listOf("canonical"), catalog.search("STRASSE").map { it.id })
        assertEquals(listOf("alias"), catalog.search("ＡＬＩＡＳ   name").map { it.id })
    }

    @Test
    fun speciesFilterAndEmptyQueryReturnDeterministicallySortedRecords() {
        val catalog = loadFixture()

        assertEquals(
            listOf("unknown-cat", "unknown-dog", "alias", "russian", "canonical"),
            catalog.search("").map { it.id },
        )
        assertEquals(
            listOf("unknown-dog", "alias", "canonical"),
            catalog.search(" \n ", BreedSpecies.DOG).map { it.id },
        )
        assertEquals(catalog.all(), catalog.search(""))
        assertEquals(catalog.all(BreedSpecies.CAT), catalog.search("", BreedSpecies.CAT))
    }

    @Test
    fun emptySearchPlacesUnknownFirstForEachSpeciesAndKeepsOtherRecordsAlphabetical() {
        val catalog = loadFixture()

        assertEquals(
            listOf("unknown-cat", "russian"),
            catalog.search("", BreedSpecies.CAT).map { it.id },
        )
        assertEquals(
            listOf("unknown-dog", "alias", "canonical"),
            catalog.search("", BreedSpecies.DOG).map { it.id },
        )
    }

    @Test
    fun loaderUsesInjectedStreamAndClosesIt() {
        var closed = false
        val stream = object : ByteArrayInputStream(fixture.toByteArray()) {
            override fun close() {
                closed = true
                super.close()
            }
        }

        val catalog = BreedCatalog.load { stream }

        assertNotNull(catalog.findById("russian"))
        assertTrue(closed)
    }

    private fun loadFixture() = BreedCatalog.load { ByteArrayInputStream(fixture.toByteArray()) }

    private data class SpecialChoice(
        val id: String,
        val species: BreedSpecies,
        val kind: BreedKind,
        val displayNameRu: String,
    )

    private companion object {
        val fixture = """
            {
              "manifest": {
                "schemaVersion": 1,
                "sourceName": "fixture",
                "sourceVersion": "1",
                "sourceUrl": "https://example.test/source",
                "license": "test",
                "licenseUrl": "https://example.test/license",
                "snapshotDate": "2026-01-01",
                "sourceSha256": "source",
                "catalogSha256": "catalog"
              },
              "breeds": [
                {"id":"canonical","species":"dog","canonicalName":"Strasse Hound","displayNameRu":"Я-порода","aliases":[],"kind":"vbo"},
                {"id":"russian","species":"cat","canonicalName":"Second","displayNameRu":"Русская порода","aliases":[],"kind":"vbo"},
                {"id":"alias","species":"dog","canonicalName":"First","displayNameRu":"А-порода","aliases":["Alias name"],"kind":"mixed"},
                {"id":"unknown-cat","species":"cat","canonicalName":"No breed","displayNameRu":"Без породы","aliases":[],"kind":"unknown"},
                {"id":"unknown-dog","species":"dog","canonicalName":"No breed","displayNameRu":"Без породы","aliases":[],"kind":"unknown"}
              ]
            }
        """.trimIndent()
    }
}
