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
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipInputStream;

public final class Main {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final int SUPPORTED_SCHEMA_VERSION = 3;

    private Main() {}

    public static void main(String[] args) throws Exception {
        if (args.length == 2 && args[0].equals("--validate-cat-breed-evidence")) {
            validateCatBreedEvidence(Path.of(args[1]));
            return;
        }
        if (args.length == 4 && args[0].equals("--snapshot")) {
            snapshot(Path.of(args[1]), Path.of(args[2]), Path.of(args[3]));
            return;
        }
        if (args.length == 5 && args[0].equals("--derive")) {
            derive(Path.of(args[1]), Path.of(args[2]), Path.of(args[3]), Path.of(args[4]));
            return;
        }
        if (args.length != 2) throw new IllegalArgumentException("Usage: <source-json> <output-json> | --snapshot <source-json> <bccg-curves.csv> <output-json> | --derive <source-json> <dog-zip> <kitten-csv> <output-json> | --validate-cat-breed-evidence <csv>");
        normalize(Path.of(args[0]), Path.of(args[1]));
    }

    private static final List<String> CAT_EVIDENCE_FIELDS = List.of(
        "schemaVersion", "vboId", "canonicalBreed", "batch", "evidenceTier", "sex",
        "adultLowerKg", "adultMedianKg", "adultUpperKg", "maturityAgeDays",
        "maturityDerivation", "medianDerivation", "sourceId", "sourceAuthorityClass",
        "sourceUrl", "claim", "limitations", "deprecatedAliases"
    );
    private static final Set<String> CAT_BATCH_1_IDS = Set.of(
        "0100000", "0100036", "0100040", "0100053", "0100077", "0100084",
        "0100169", "0100170", "0100178", "0100183", "0100184", "0100189",
        "0100196", "0100200", "0100230", "0100235", "0100245", "0100303"
    );
    private static final Set<String> CAT_BATCH_2_IDS = Set.of(
        "0100018", "0100045", "0100056", "0100090", "0100173", "0100188",
        "0100216", "0100249"
    );

    /**
     * Returns the reviewed adult evidence rows for one production batch.
     *
     * Keeping selection behind an exact allowlist prevents a newly researched or explicitly
     * excluded breed from entering a generated snapshot merely because a CSV row was added.
     */
    static List<CatBreedEvidence> catBreedEvidenceForBatch(Path path, int batch) throws Exception {
        validateCatBreedEvidence(path);
        require(batch == 1 || batch == 2, 0, "batch");
        List<CatBreedEvidence> evidence = new ArrayList<>();
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        for (int lineNumber = 2; lineNumber <= lines.size(); lineNumber++) {
            List<String> values = csvFields(lines.get(lineNumber - 1));
            if (Integer.parseInt(values.get(CAT_EVIDENCE_FIELDS.indexOf("batch"))) != batch) continue;
            evidence.add(new CatBreedEvidence(
                values.get(CAT_EVIDENCE_FIELDS.indexOf("vboId")),
                values.get(CAT_EVIDENCE_FIELDS.indexOf("canonicalBreed")),
                values.get(CAT_EVIDENCE_FIELDS.indexOf("sex")),
                Double.parseDouble(values.get(CAT_EVIDENCE_FIELDS.indexOf("adultLowerKg"))),
                Double.parseDouble(values.get(CAT_EVIDENCE_FIELDS.indexOf("adultMedianKg"))),
                Double.parseDouble(values.get(CAT_EVIDENCE_FIELDS.indexOf("adultUpperKg"))),
                Integer.parseInt(values.get(CAT_EVIDENCE_FIELDS.indexOf("maturityAgeDays"))),
                values.get(CAT_EVIDENCE_FIELDS.indexOf("maturityDerivation")),
                values.get(CAT_EVIDENCE_FIELDS.indexOf("evidenceTier")),
                values.get(CAT_EVIDENCE_FIELDS.indexOf("sourceId")),
                values.get(CAT_EVIDENCE_FIELDS.indexOf("sourceAuthorityClass")),
                values.get(CAT_EVIDENCE_FIELDS.indexOf("sourceUrl")),
                values.get(CAT_EVIDENCE_FIELDS.indexOf("claim")),
                values.get(CAT_EVIDENCE_FIELDS.indexOf("limitations"))
            ));
        }
        return List.copyOf(evidence);
    }

    record CatBreedEvidence(
        String vboId,
        String canonicalBreed,
        String sex,
        double adultLowerKg,
        double adultMedianKg,
        double adultUpperKg,
        int maturityAgeDays,
        String maturityDerivation,
        String evidenceTier,
        String sourceId,
        String sourceAuthorityClass,
        String sourceUrl,
        String claim,
        String limitations
    ) {}

    static void validateCatBreedEvidence(Path path) throws Exception {
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        if (lines.isEmpty() || !csvFields(lines.get(0)).equals(CAT_EVIDENCE_FIELDS)) {
            throw new IllegalArgumentException("Unexpected cat breed evidence header");
        }
        Map<String, Set<String>> sexesByBreed = new HashMap<>();
        Map<String, List<String>> firstRowByBreed = new HashMap<>();
        Set<String> aliases = new HashSet<>();
        int batch1 = 0;
        int batch2 = 0;
        for (int lineNumber = 2; lineNumber <= lines.size(); lineNumber++) {
            List<String> values = csvFields(lines.get(lineNumber - 1));
            if (values.size() != CAT_EVIDENCE_FIELDS.size()) {
                throw new IllegalArgumentException("Invalid cat breed evidence row " + lineNumber);
            }
            Map<String, String> row = new HashMap<>();
            for (int index = 0; index < values.size(); index++) row.put(CAT_EVIDENCE_FIELDS.get(index), values.get(index));
            require(row.get("schemaVersion").equals("1"), lineNumber, "schemaVersion");
            require(row.get("vboId").matches("01[0-9]{5}"), lineNumber, "vboId");
            require(!row.get("canonicalBreed").isBlank(), lineNumber, "canonicalBreed");
            require(Set.of("1", "2").contains(row.get("batch")), lineNumber, "batch");
            String vboId = row.get("vboId");
            require(CAT_BATCH_1_IDS.contains(vboId) || CAT_BATCH_2_IDS.contains(vboId), lineNumber, "approved breed");
            require(row.get("batch").equals(CAT_BATCH_1_IDS.contains(vboId) ? "1" : "2"), lineNumber, "approved batch");
            require(Set.of("official", "professional_fallback").contains(row.get("evidenceTier")), lineNumber, "evidenceTier");
            require(Set.of("female", "male").contains(row.get("sex")), lineNumber, "sex");
            double lower = positive(row, "adultLowerKg", lineNumber);
            double median = positive(row, "adultMedianKg", lineNumber);
            double upper = positive(row, "adultUpperKg", lineNumber);
            require(lower < upper, lineNumber, "adult bounds");
            // The approved evidence package preserves source conversions at 3–6 decimals.
            require(Math.abs(median - (lower + upper) / 2.0) <= 0.0001, lineNumber, "adultMedianKg");
            require(row.get("medianDerivation").equals("arithmetic_midpoint"), lineNumber, "medianDerivation");
            int maturity = Integer.parseInt(row.get("maturityAgeDays"));
            require(maturity > 0, lineNumber, "maturityAgeDays");
            require(Set.of("published", "model_fallback").contains(row.get("maturityDerivation")), lineNumber, "maturityDerivation");
            require(!row.get("sourceId").isBlank() && !row.get("sourceAuthorityClass").isBlank(), lineNumber, "source provenance");
            require(row.get("sourceUrl").startsWith("https://") && !row.get("claim").isBlank() && !row.get("limitations").isBlank(), lineNumber, "claim provenance");
            require(!row.get("evidenceTier").equals("official") || row.get("sourceAuthorityClass").startsWith("official_"), lineNumber, "official source class");
            require(!row.get("evidenceTier").equals("professional_fallback") || !row.get("sourceAuthorityClass").startsWith("official_"), lineNumber, "fallback source class");
            if (row.get("maturityDerivation").equals("model_fallback")) require(maturity == 730, lineNumber, "fallback maturity");
            String breed = row.get("vboId");
            require(!breed.equals("0100061"), lineNumber, "deprecated Sphynx ID");
            require(sexesByBreed.computeIfAbsent(breed, unused -> new HashSet<>()).add(row.get("sex")), lineNumber, "duplicate sex");
            List<String> first = firstRowByBreed.putIfAbsent(breed, values);
            if (first != null) {
                for (String field : List.of("canonicalBreed", "batch", "evidenceTier", "maturityAgeDays", "maturityDerivation", "sourceId", "sourceAuthorityClass", "sourceUrl", "limitations", "deprecatedAliases")) {
                    require(first.get(CAT_EVIDENCE_FIELDS.indexOf(field)).equals(row.get(field)), lineNumber, "inconsistent " + field);
                }
            } else if (row.get("batch").equals("1")) batch1++; else batch2++;
            if (!row.get("deprecatedAliases").isBlank()) {
                require(row.get("deprecatedAliases").matches("01[0-9]{5}"), lineNumber, "deprecatedAliases");
                aliases.add(row.get("deprecatedAliases") + "->" + breed);
            }
        }
        require(sexesByBreed.size() == 26, 0, "breed count");
        require(batch1 == 18 && batch2 == 8, 0, "batch counts");
        require(firstRowByBreed.keySet().containsAll(CAT_BATCH_1_IDS), 0, "Batch 1 IDs");
        require(firstRowByBreed.keySet().containsAll(CAT_BATCH_2_IDS), 0, "Batch 2 IDs");
        require(firstRowByBreed.get("0100200").get(CAT_EVIDENCE_FIELDS.indexOf("evidenceTier")).equals("professional_fallback"), 0, "Russian Blue exception");
        require(sexesByBreed.values().stream().allMatch(value -> value.equals(Set.of("female", "male"))), 0, "sex coverage");
        require(aliases.equals(Set.of("0100061->0100230")), 0, "canonical aliases");
    }

    private static double positive(Map<String, String> row, String field, int lineNumber) {
        double value;
        try { value = Double.parseDouble(row.get(field)); }
        catch (NumberFormatException error) { throw new IllegalArgumentException("Invalid " + field + " at row " + lineNumber); }
        require(Double.isFinite(value) && value > 0, lineNumber, field);
        return value;
    }

    private static void require(boolean condition, int row, String field) {
        if (!condition) throw new IllegalArgumentException("Invalid " + field + (row > 0 ? " at row " + row : ""));
    }

    private static List<String> csvFields(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < line.length(); index++) {
            char character = line.charAt(index);
            if (character == '"') {
                if (quoted && index + 1 < line.length() && line.charAt(index + 1) == '"') { field.append('"'); index++; }
                else quoted = !quoted;
            } else if (character == ',' && !quoted) { fields.add(field.toString()); field.setLength(0); }
            else field.append(character);
        }
        if (quoted) throw new IllegalArgumentException("Unterminated quoted CSV field");
        fields.add(field.toString());
        return fields;
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
        addModelledBreedProfiles(root, profiles);
        Path assembled = Files.createTempFile("weight-reference-snapshot", ".json");
        try {
            Files.writeString(assembled, GSON.toJson(root) + "\n", StandardCharsets.UTF_8);
            normalize(assembled, output);
        } finally {
            Files.deleteIfExists(assembled);
        }
    }

    /**
     * Scales the normalized sex-specific DSH P50 growth shape to audited adult breed ranges.
     * These are explicitly product models, not observed breed growth percentiles.
     */
    private static void addModelledBreedProfiles(JsonObject root, JsonArray profiles) {
        Map<String, JsonObject> scopes = new TreeMap<>();
        root.getAsJsonObject("manifest").getAsJsonArray("scopes").forEach(value ->
            scopes.put(value.getAsJsonObject().get("id").getAsString(), value.getAsJsonObject()));
        Map<String, JsonObject> population = new TreeMap<>();
        profiles.forEach(value -> {
            JsonObject profile = value.getAsJsonObject();
            if (profile.get("id").getAsString().startsWith("cat-population-")) {
                population.put(profile.get("sex").getAsString(), profile);
            }
        });
        for (var value : root.getAsJsonArray("modelledBreedRanges")) {
            JsonObject range = value.getAsJsonObject();
            String id = range.get("id").getAsString();
            for (int index = profiles.size() - 1; index >= 0; index--) {
                if (profiles.get(index).getAsJsonObject().get("id").getAsString().equals(id)) profiles.remove(index);
            }
            JsonObject scope = scopes.get(id);
            JsonObject shape = population.get(scope.get("sex").getAsString());
            JsonArray shapePoints = shape.getAsJsonArray("points");
            double finalMedian = shapePoints.get(shapePoints.size() - 1).getAsJsonObject().get("medianKg").getAsDouble();
            double adultLower = range.get("adultLowerKg").getAsDouble();
            double adultUpper = range.get("adultUpperKg").getAsDouble();
            double adultMedian = (adultLower + adultUpper) / 2.0;
            JsonArray points = new JsonArray();
            if (range.has("birthObservation")) points.add(range.getAsJsonObject("birthObservation").deepCopy());
            shapePoints.forEach(shapeValue -> {
                JsonObject input = shapeValue.getAsJsonObject();
                double ratio = input.get("medianKg").getAsDouble() / finalMedian;
                JsonObject point = new JsonObject();
                point.addProperty("ageDays", input.get("ageDays").getAsInt());
                point.addProperty("lowerKg", round(adultLower * ratio));
                point.addProperty("medianKg", round(adultMedian * ratio));
                point.addProperty("upperKg", round(adultUpper * ratio));
                point.addProperty("sourceId", range.get("sourceId").getAsString());
                points.add(point);
            });
            JsonObject adult = new JsonObject();
            adult.addProperty("ageDays", 730);
            adult.addProperty("lowerKg", adultLower);
            adult.addProperty("medianKg", adultMedian);
            adult.addProperty("upperKg", adultUpper);
            adult.addProperty("sourceId", range.get("sourceId").getAsString());
            points.add(adult);

            JsonObject source = root.getAsJsonObject("manifest").getAsJsonArray("sources").asList().stream()
                .map(e -> e.getAsJsonObject()).filter(s -> s.get("id").equals(range.get("sourceId"))).findFirst().orElseThrow();
            JsonObject profile = new JsonObject();
            for (String field : List.of("id", "species", "sex", "basis", "weightCategory", "breedId", "sourceId", "constraints", "ageAvailability")) {
                if (scope.has(field)) profile.add(field, scope.get(field));
            }
            profile.addProperty("citation", source.get("citation").getAsString());
            profile.addProperty("license", source.get("license").getAsString());
            profile.addProperty("referenceKind", "modelled_breed_adult_range");
            profile.addProperty("minimumBinN", 0);
            profile.addProperty("centerStatistic", "median");
            profile.addProperty("boundsStatistic", "adult_typical_range");
            profile.add("points", points);
            profiles.add(profile);
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
