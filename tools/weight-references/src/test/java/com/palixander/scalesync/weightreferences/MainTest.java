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
    void normalizeAcceptsSchemaV4AndRefreshesFullPayloadChecksum() throws Exception {
        Path source = temporaryDirectory.resolve("source.json");
        Path output = temporaryDirectory.resolve("output.json");
        Files.writeString(source, document(4), StandardCharsets.UTF_8);

        Main.main(new String[] {source.toString(), output.toString()});

        JsonObject root = JsonParser.parseString(Files.readString(output)).getAsJsonObject();
        String actual = root.getAsJsonObject("manifest").get("numericalDataSha256").getAsString();
        root.getAsJsonObject("manifest").addProperty("numericalDataSha256", "");
        String profiles = root.toString();
        String expected = HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(profiles.getBytes(StandardCharsets.UTF_8))
        );
        assertEquals(expected, actual);
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

    @Test
    void approvedCatBreedEvidenceSatisfiesProductionContract() throws Exception {
        Main.validateCatBreedEvidence(Path.of("cat_breed_evidence.csv"));
    }

    @Test
    void catBreedEvidenceRejectsNonStandardFallbackMaturity() throws Exception {
        String approved = Files.readString(Path.of("cat_breed_evidence.csv"), StandardCharsets.UTF_8);
        Path invalid = temporaryDirectory.resolve("invalid.csv");
        Files.writeString(
            invalid,
            approved.replaceFirst(",730,model_fallback,", ",731,model_fallback,"),
            StandardCharsets.UTF_8
        );

        IllegalArgumentException error = assertThrows(
            IllegalArgumentException.class,
            () -> Main.validateCatBreedEvidence(invalid)
        );

        assertEquals("Invalid fallback maturity at row 4", error.getMessage());
    }

    @Test
    void catBreedEvidenceRejectsTamperedSexBoundsMidpointAndProvenance() throws Exception {
        String approved = Files.readString(Path.of("cat_breed_evidence.csv"), StandardCharsets.UTF_8);
        for (String invalid : new String[] {
            approved.replaceFirst(",female,", ",combined,"),
            approved.replaceFirst(",2.721554,3.401942,4.082331,", ",-1,3.401942,4.082331,"),
            approved.replaceFirst(",2.721554,3.401942,4.082331,", ",2.721554,3.5,4.082331,"),
            approved.replaceFirst(",https://tica.org/breed/abyssinian/,", ",http://invalid.example/,"),
            approved.replaceFirst(",official_breed_organization,", ",professional_pet_reference,")
        }) {
            Path path = temporaryDirectory.resolve("invalid-" + invalid.hashCode() + ".csv");
            Files.writeString(path, invalid, StandardCharsets.UTF_8);
            assertThrows(IllegalArgumentException.class, () -> Main.validateCatBreedEvidence(path));
        }
    }

    private static String document(int schemaVersion) {
        return "{\"manifest\":{\"schemaVersion\":" + schemaVersion
            + ",\"numericalDataSha256\":\"generated\"},\"profiles\":[{\"id\":\"observed\"}]}";
    }
}
