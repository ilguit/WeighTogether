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
        assertNull(catalog.findById("not-present"))
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

        assertEquals(listOf("alias", "russian", "canonical"), catalog.search("").map { it.id })
        assertEquals(listOf("alias", "canonical"), catalog.search(" \n ", BreedSpecies.DOG).map { it.id })
        assertEquals(catalog.all(), catalog.search(""))
        assertEquals(catalog.all(BreedSpecies.CAT), catalog.search("", BreedSpecies.CAT))
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
                {"id":"alias","species":"dog","canonicalName":"First","displayNameRu":"А-порода","aliases":["Alias name"],"kind":"mixed"}
              ]
            }
        """.trimIndent()
    }
}
