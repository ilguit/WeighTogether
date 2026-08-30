package com.palixander.scalesync.breedcatalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CatalogGeneratorTest {
    @TempDir Path tempDir;

    @Test
    void generatesCanonicalBreedConceptsWithAliasesOverridesAndSpecials() throws Exception {
        Path source = resource("vbo-fixture.obo");
        Path output = tempDir.resolve("catalog.json");

        new CatalogGenerator().generate(request(source, output, CatalogGenerator.sha256(Files.readAllBytes(source))));

        String json = Files.readString(output);
        assertTrue(json.contains("\"id\": \"VBO:0100002\""));
        assertTrue(json.contains("\"displayNameRu\": \"Листовая кошка\""));
        assertTrue(json.contains("\"aliases\": [\"Alternate Cat\", \"Cat \\\"Quoted\\\"\"]"));
        assertTrue(json.contains("\"id\": \"VBO:0200001\""));
        assertTrue(json.contains("\"displayNameRu\": \"Leaf Dog\""));
        assertTrue(json.contains("\"id\": \"scalesync:cat:mixed-breed\""));
        assertTrue(json.contains("\"id\": \"scalesync:dog:breed-unknown\""));
        assertTrue(json.contains("\"id\": \"VBO:0100001\""));
        assertFalse(json.contains("VBO:0100003"));
        assertFalse(json.contains("VBO:123"));
        assertFalse(json.contains("Broad Cat"));
        assertFalse(json.contains("VBO:0100004"));
        assertTrue(json.contains("VBO:0200002"));
    }

    @Test
    void outputIsByteForByteDeterministicAndContainsVerifiableCatalogHash() throws Exception {
        Path source = resource("vbo-fixture.obo");
        String sourceSha = CatalogGenerator.sha256(Files.readAllBytes(source));
        Path first = tempDir.resolve("first.json");
        Path second = tempDir.resolve("second.json");

        new CatalogGenerator().generate(request(source, first, sourceSha));
        new CatalogGenerator().generate(request(source, second, sourceSha));

        assertEquals(Files.readString(first), Files.readString(second));
        String json = Files.readString(first);
        String breeds = json.substring(json.indexOf("[\n", json.indexOf("\"breeds\"")), json.length() - 3);
        String expectedHash = CatalogGenerator.sha256(breeds.getBytes(StandardCharsets.UTF_8));
        assertTrue(json.contains("\"catalogSha256\": \"" + expectedHash + "\""));
    }

    @Test
    void rejectsUnexpectedSourceChecksumBeforeParsing() throws Exception {
        Path source = resource("vbo-fixture.obo");
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                new CatalogGenerator().generate(request(source, tempDir.resolve("catalog.json"), "0".repeat(64))));
        assertTrue(error.getMessage().contains("Source SHA-256 mismatch"));
    }

    @Test
    void readsVendoredGzipWhileCheckingTheUncompressedSourceChecksum() throws Exception {
        Path source = resource("vbo-fixture.obo");
        byte[] sourceBytes = Files.readAllBytes(source);
        Path compressed = tempDir.resolve("fixture.obo.gz");
        try (GZIPOutputStream output = new GZIPOutputStream(Files.newOutputStream(compressed))) {
            output.write(sourceBytes);
        }

        Path catalog = tempDir.resolve("catalog.json");
        new CatalogGenerator().generate(request(compressed, catalog, CatalogGenerator.sha256(sourceBytes)));

        assertTrue(Files.readString(catalog).contains("\"id\": \"VBO:0100002\""));
    }

    @Test
    void rejectsExclusionWhenThePinnedVboLabelDrifts() throws Exception {
        Path source = resource("vbo-fixture.obo");
        Path exclusions = tempDir.resolve("exclusions.tsv");
        Files.writeString(exclusions, "VBO:0100004\tRenamed Mixed Breed (Cat)\tFixture rationale\n");
        CatalogGenerator.Request base = request(
                source,
                tempDir.resolve("catalog.json"),
                CatalogGenerator.sha256(Files.readAllBytes(source)));
        CatalogGenerator.Request request = new CatalogGenerator.Request(
                base.source(), base.sourceVersion(), base.sourceUrl(), base.sourceSha256(), base.snapshotDate(),
                base.overrides(), exclusions, base.output());

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> new CatalogGenerator().generate(request));

        assertTrue(error.getMessage().contains("Excluded VBO term label changed: VBO:0100004"));
    }

    private CatalogGenerator.Request request(Path source, Path output, String sha256) {
        return new CatalogGenerator.Request(
                source,
                "2099-01-02",
                "https://purl.obolibrary.org/obo/vbo/releases/2099-01-02/vbo.obo",
                sha256,
                "2099-01-03",
                resource("overrides-fixture.tsv"),
                resource("exclusions-fixture.tsv"),
                output);
    }

    private static Path resource(String name) {
        try {
            return Path.of(CatalogGeneratorTest.class.getResource("/" + name).toURI());
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
