package com.palixander.scalesync.weightreferences;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipInputStream;

public final class Main {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final int SUPPORTED_SCHEMA_VERSION = 2;

    private Main() {}

    public static void main(String[] args) throws Exception {
        if (args.length == 4 && args[0].equals("--snapshot")) {
            snapshot(Path.of(args[1]), Path.of(args[2]), Path.of(args[3]));
            return;
        }
        if (args.length == 5 && args[0].equals("--derive")) {
            derive(Path.of(args[1]), Path.of(args[2]), Path.of(args[3]), Path.of(args[4]));
            return;
        }
        if (args.length != 2) throw new IllegalArgumentException("Usage: <source-json> <output-json> | --snapshot <source-json> <bccg-curves.csv> <output-json> | --derive <source-json> <dog-zip> <kitten-csv> <output-json>");
        normalize(Path.of(args[0]), Path.of(args[1]));
    }

    private static void snapshot(Path source, Path fittedCatCurves, Path output) throws Exception {
        JsonObject root = JsonParser.parseString(Files.readString(source, StandardCharsets.UTF_8)).getAsJsonObject();
        validateSchema(root);
        JsonObject catSource = root.getAsJsonObject("manifest").getAsJsonArray("sources").asList().stream()
            .map(value -> value.getAsJsonObject())
            .filter(value -> value.get("id").getAsString().equals("salt-dsh-kitten-2022"))
            .findFirst().orElseThrow();
        verify(fittedCatCurves, catSource.get("derivedArtifactSha256").getAsString());
        JsonArray profiles = root.getAsJsonArray("profiles");
        for (int index = profiles.size() - 1; index >= 0; index--) {
            if (profiles.get(index).getAsJsonObject().get("id").getAsString().startsWith("cat-population-")) profiles.remove(index);
        }
        addFittedCatProfiles(root, profiles, fittedCatCurves);
        Path assembled = Files.createTempFile("weight-reference-snapshot", ".json");
        try {
            Files.writeString(assembled, GSON.toJson(root) + "\n", StandardCharsets.UTF_8);
            normalize(assembled, output);
        } finally {
            Files.deleteIfExists(assembled);
        }
    }

    private static void addFittedCatProfiles(JsonObject root, JsonArray profiles, Path curves) throws Exception {
        Map<String, JsonObject> scopes = new TreeMap<>();
        root.getAsJsonObject("manifest").getAsJsonArray("scopes").forEach(value -> scopes.put(value.getAsJsonObject().get("id").getAsString(), value.getAsJsonObject()));
        Map<String, JsonArray> pointsById = new TreeMap<>();
        try (BufferedReader reader = Files.newBufferedReader(curves, StandardCharsets.UTF_8)) {
            String header = reader.readLine();
            if (!"sex,week,mu,sigma,nu,p02,p09,p50,p91,p98".equals(header)) throw new IllegalArgumentException("Unexpected fitted-curve header");
            for (String line; (line = reader.readLine()) != null;) {
                String[] value = line.split(",", -1);
                if (value.length != 10) throw new IllegalArgumentException("Invalid fitted-curve row: " + line);
                String sex = value[0].equals("F") ? "female" : value[0].equals("M") ? "male" : null;
                if (sex == null) throw new IllegalArgumentException("Unknown fitted-curve sex: " + value[0]);
                JsonObject point = new JsonObject();
                point.addProperty("ageDays", Integer.parseInt(value[1]) * 7);
                point.addProperty("lowerKg", Double.parseDouble(value[6]));
                point.addProperty("medianKg", Double.parseDouble(value[7]));
                point.addProperty("upperKg", Double.parseDouble(value[8]));
                pointsById.computeIfAbsent("cat-population-" + sex, unused -> new JsonArray()).add(point);
            }
        }
        pointsById.forEach((id, points) -> {
            JsonObject scope = scopes.get(id);
            if (scope == null) throw new IllegalArgumentException("Fitted profile has no declared scope: " + id);
            JsonObject source = root.getAsJsonObject("manifest").getAsJsonArray("sources").asList().stream().map(e -> e.getAsJsonObject()).filter(s -> s.get("id").equals(scope.get("sourceId"))).findFirst().orElseThrow();
            JsonObject profile = new JsonObject();
            for (String field : List.of("id", "species", "sex", "basis", "weightCategory", "breedId", "sourceId", "constraints")) profile.add(field, scope.get(field));
            profile.addProperty("citation", source.get("citation").getAsString());
            profile.addProperty("license", source.get("license").getAsString());
            profile.addProperty("referenceKind", "fitted_bccg_percentiles");
            profile.addProperty("minimumBinN", 0);
            profile.add("points", points);
            profiles.add(profile);
        });
    }

    private static void normalize(Path source, Path output) throws Exception {
        JsonObject root = JsonParser.parseString(Files.readString(source, StandardCharsets.UTF_8)).getAsJsonObject();
        validateSchema(root);
        JsonArray profiles = root.getAsJsonArray("profiles");
        String checksum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(profiles.toString().getBytes(StandardCharsets.UTF_8)));
        root.getAsJsonObject("manifest").addProperty("numericalDataSha256", checksum);
        String normalized = GSON.toJson(root) + "\n";
        Files.createDirectories(output.getParent());
        Files.writeString(output, normalized, StandardCharsets.UTF_8);
    }

    private static void derive(Path source, Path dogZip, Path kittenCsv, Path output) throws Exception {
        JsonObject root = JsonParser.parseString(Files.readString(source, StandardCharsets.UTF_8)).getAsJsonObject();
        validateSchema(root);
        verify(dogZip, "a494c1c1bc6841ab3840d5757f34a20892e025a8f9da7e9cbb3b9d6086b07ff7");
        verify(kittenCsv, "76be97fd71d5139fb648e58c69db58945c221df33f1b7f15fc12e244db90e094");
        JsonArray profiles = new JsonArray();
        try (InputStream file = Files.newInputStream(dogZip); ZipInputStream zip = new ZipInputStream(file)) {
            if (zip.getNextEntry() == null) throw new IllegalArgumentException("Dog ZIP is empty");
            addProfiles(root, profiles, readDog(zip));
        }
        try (InputStream input = Files.newInputStream(kittenCsv)) {
            addProfiles(root, profiles, readKitten(input));
        }
        root.add("profiles", profiles);
        var generatedIds = new java.util.HashSet<String>();
        profiles.forEach(value -> generatedIds.add(value.getAsJsonObject().get("id").getAsString()));
        for (var scope : root.getAsJsonObject("manifest").getAsJsonArray("scopes")) {
            scope.getAsJsonObject().addProperty(
                "numericalAvailability",
                generatedIds.contains(scope.getAsJsonObject().get("id").getAsString()) ? "available" : "not_reproducible_from_published_artifacts"
            );
        }
        Path derived = Files.createTempFile("weight-reference-derived", ".json");
        Files.writeString(derived, GSON.toJson(root) + "\n", StandardCharsets.UTF_8);
        normalize(derived, output);
        Files.delete(derived);
    }

    private static Map<String, TreeMap<Integer, List<Double>>> readDog(InputStream input) throws Exception {
        Map<String, TreeMap<Integer, List<Double>>> groups = new TreeMap<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8), 1 << 20)) {
            reader.readLine();
            for (String line; (line = reader.readLine()) != null;) {
                String[] v = line.split(",", -1);
                if (v.length != 12) continue;
                double years = number(v[5]), weight = number(v[6]), adult = number(v[11]);
                double days = years * 365.25;
                if (!(days >= 84 && days <= 730 && weight > 0 && adult > 0 && adult <= 40 && (v[9].equals("Y") || v[10].equals("Y")))) continue;
                String sex = v[2].equals("F") ? "female" : v[2].equals("M") ? "male" : null;
                if (sex == null) continue;
                String category = adult < 6.5 ? "I" : adult < 9 ? "II" : adult < 15 ? "III" : adult < 30 ? "IV" : "V";
                add(groups, "dog-" + sex + "-" + category, week(days), weight);
            }
        }
        return groups;
    }

    private static Map<String, TreeMap<Integer, List<Double>>> readKitten(InputStream input) throws Exception {
        Map<String, TreeMap<Integer, List<Double>>> groups = new TreeMap<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            reader.readLine();
            for (String line; (line = reader.readLine()) != null;) {
                String[] v = line.split(",", -1);
                if (v.length != 5 || !v[0].equals("Modelling")) continue;
                double days = number(v[3]) * 365.25, weight = number(v[4]);
                int age = week(days);
                if (age < 56 || age > 546 || weight <= 0) continue;
                String sex = v[2].equals("F") ? "female" : v[2].equals("M") ? "male" : null;
                if (sex != null) add(groups, "cat-dsh-" + sex, age, weight);
            }
        }
        return groups;
    }

    private static void add(Map<String, TreeMap<Integer, List<Double>>> groups, String id, int age, double weight) {
        groups.computeIfAbsent(id, unused -> new TreeMap<>()).computeIfAbsent(age, unused -> new ArrayList<>()).add(weight);
    }

    private static void addProfiles(JsonObject root, JsonArray output, Map<String, TreeMap<Integer, List<Double>>> groups) {
        Map<String, JsonObject> scopes = new TreeMap<>();
        root.getAsJsonObject("manifest").getAsJsonArray("scopes").forEach(value -> scopes.put(value.getAsJsonObject().get("id").getAsString(), value.getAsJsonObject()));
        groups.forEach((id, bins) -> {
            JsonObject scope = scopes.get(id);
            if (scope == null) throw new IllegalArgumentException("Generated profile has no declared scope: " + id);
            int minimumN = id.startsWith("dog-") ? 100 : 30;
            JsonArray points = new JsonArray();
            bins.forEach((age, values) -> {
                if (values.size() < minimumN) return;
                values.sort(Comparator.naturalOrder());
                JsonObject point = new JsonObject();
                point.addProperty("ageDays", age);
                point.addProperty("lowerKg", round(quantile(values, .25)));
                point.addProperty("medianKg", round(quantile(values, .50)));
                point.addProperty("upperKg", round(quantile(values, .75)));
                points.add(point);
            });
            if (points.size() == 0) return;
            JsonObject source = root.getAsJsonObject("manifest").getAsJsonArray("sources").asList().stream().map(e -> e.getAsJsonObject()).filter(s -> s.get("id").equals(scope.get("sourceId"))).findFirst().orElseThrow();
            JsonObject profile = new JsonObject();
            for (String field : List.of("id", "species", "sex", "basis", "weightCategory", "breedId", "sourceId", "constraints")) profile.add(field, scope.get(field));
            profile.addProperty("citation", source.get("citation").getAsString());
            profile.addProperty("license", source.get("license").getAsString());
            profile.addProperty("referenceKind", "empirical_observation_quartiles");
            profile.addProperty("minimumBinN", minimumN);
            profile.add("points", points);
            output.add(profile);
        });
    }

    private static int week(double days) { return (int) Math.round(days / 7.0) * 7; }
    private static double number(String value) { try { return Double.parseDouble(value); } catch (NumberFormatException ignored) { return Double.NaN; } }
    private static double quantile(List<Double> values, double p) {
        double index = (values.size() - 1) * p;
        int low = (int) Math.floor(index), high = (int) Math.ceil(index);
        return values.get(low) + (values.get(high) - values.get(low)) * (index - low);
    }
    private static double round(double value) { return Math.round(value * 1000.0) / 1000.0; }
    private static void validateSchema(JsonObject root) {
        int schemaVersion = root.getAsJsonObject("manifest").get("schemaVersion").getAsInt();
        if (schemaVersion != SUPPORTED_SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported weight reference schema: " + schemaVersion);
        }
    }
    private static void verify(Path path, String expected) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[1 << 20];
            for (int read; (read = input.read(buffer)) >= 0;) digest.update(buffer, 0, read);
        }
        String actual = HexFormat.of().formatHex(digest.digest());
        if (!actual.equals(expected)) throw new IllegalArgumentException("Checksum mismatch for " + path);
    }
}
