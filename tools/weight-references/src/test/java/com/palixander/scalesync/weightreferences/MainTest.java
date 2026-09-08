package com.palixander.scalesync.weightreferences;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MainTest {
    @TempDir Path temporaryDirectory;

    @Test
    void normalizeAcceptsSchemaV3AndRefreshesProfileChecksum() throws Exception {
        Path source = temporaryDirectory.resolve("source.json");
        Path output = temporaryDirectory.resolve("output.json");
        Files.writeString(source, document(3), StandardCharsets.UTF_8);

        Main.main(new String[] {source.toString(), output.toString()});

        JsonObject root = JsonParser.parseString(Files.readString(output)).getAsJsonObject();
        String profiles = root.getAsJsonArray("profiles").toString();
        String expected = HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(profiles.getBytes(StandardCharsets.UTF_8))
        );
        assertEquals(expected, root.getAsJsonObject("manifest").get("numericalDataSha256").getAsString());
    }

    @Test
    void normalizeRejectsLegacySchema() throws Exception {
        Path source = temporaryDirectory.resolve("legacy.json");
        Path output = temporaryDirectory.resolve("output.json");
        Files.writeString(source, document(1), StandardCharsets.UTF_8);

        IllegalArgumentException error = assertThrows(
            IllegalArgumentException.class,
            () -> Main.main(new String[] {source.toString(), output.toString()})
        );

        assertEquals("Unsupported weight reference schema: 1", error.getMessage());
    }

    private static String document(int schemaVersion) {
        return "{\"manifest\":{\"schemaVersion\":" + schemaVersion
            + ",\"numericalDataSha256\":\"generated\"},\"profiles\":[{\"id\":\"observed\"}]}";
    }
}
