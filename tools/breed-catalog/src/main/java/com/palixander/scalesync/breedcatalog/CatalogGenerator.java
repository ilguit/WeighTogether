package com.palixander.scalesync.breedcatalog;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

public final class CatalogGenerator {
    static final String CAT_ROOT = "VBO:0400018";
    static final String DOG_ROOT = "VBO:0400024";
    private static final Pattern CANONICAL_ID = Pattern.compile("VBO:[0-9]{7}");
    private static final Pattern SYNONYM = Pattern.compile("^synonym: \"((?:\\\\.|[^\"\\\\])*)\" (EXACT|RELATED)(?: |$).*$");
    private static final String LICENSE_URL = "https://creativecommons.org/licenses/by/4.0/";

    public record Request(
            Path source,
            String sourceVersion,
            String sourceUrl,
            String sourceSha256,
            String snapshotDate,
            Path overrides,
            Path exclusions,
            Path output) {}

    record Term(String id, String label, List<String> synonyms, List<String> parents, boolean obsolete) {}

    record Breed(String id, String species, String canonicalName, String displayNameRu, List<String> aliases, String kind) {}

    public void generate(Request request) throws IOException {
        validateRequest(request);
        byte[] sourceBytes = readSourceBytes(request.source());
        String actualSourceSha = sha256(sourceBytes);
        if (!actualSourceSha.equalsIgnoreCase(request.sourceSha256())) {
            throw new IllegalArgumentException("Source SHA-256 mismatch: expected " + request.sourceSha256()
                    + ", got " + actualSourceSha);
        }

        ParsedObo parsed = parseObo(sourceBytes);
        if (!parsed.dataVersion.equals("releases/" + request.sourceVersion())) {
            throw new IllegalArgumentException("VBO data-version mismatch: expected releases/"
                    + request.sourceVersion() + ", got " + parsed.dataVersion);
        }
        Map<String, String> overrides = parseOverrides(request.overrides());
        Map<String, Exclusion> exclusions = parseExclusions(request.exclusions());
        List<Breed> breeds = buildBreeds(parsed.terms, overrides, exclusions);
        validateBreeds(breeds);
        String breedsJson = renderBreeds(breeds);
        String catalogSha = sha256(breedsJson.getBytes(StandardCharsets.UTF_8));
        String document = renderDocument(request, actualSourceSha, catalogSha, breedsJson);

        Path parent = request.output().toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(request.output(), document, StandardCharsets.UTF_8);
    }

    private static byte[] readSourceBytes(Path source) throws IOException {
        try (InputStream file = Files.newInputStream(source);
             InputStream input = source.getFileName().toString().endsWith(".gz")
                     ? new GZIPInputStream(file)
                     : file) {
            return input.readAllBytes();
        }
    }

    private static void validateRequest(Request request) {
        try {
            LocalDate.parse(request.snapshotDate());
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("snapshot-date must be ISO-8601 (YYYY-MM-DD)", exception);
        }
        if (!request.sourceUrl().contains("/releases/" + request.sourceVersion() + "/")) {
            throw new IllegalArgumentException("source-url must be an immutable URL for source-version");
        }
        if (!request.sourceSha256().matches("(?i)[0-9a-f]{64}")) {
            throw new IllegalArgumentException("source-sha256 must contain 64 hexadecimal characters");
        }
    }

    private record ParsedObo(String dataVersion, Map<String, Term> terms) {}

    private static ParsedObo parseObo(byte[] sourceBytes) throws IOException {
        String dataVersion = null;
        Map<String, Term> terms = new LinkedHashMap<>();
        MutableTerm current = null;
        try (BufferedReader reader = new BufferedReader(new java.io.InputStreamReader(
                new java.io.ByteArrayInputStream(sourceBytes), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("data-version: ")) {
                    dataVersion = line.substring("data-version: ".length()).trim();
                } else if (line.equals("[Term]")) {
                    if (current != null) addTerm(terms, current);
                    current = new MutableTerm();
                } else if (line.startsWith("[") && current != null) {
                    addTerm(terms, current);
                    current = null;
                } else if (current != null) {
                    current.accept(line);
                }
            }
        }
        if (current != null) addTerm(terms, current);
        if (dataVersion == null) throw new IllegalArgumentException("OBO data-version is missing");
        return new ParsedObo(dataVersion, terms);
    }

    private static void validateBreeds(List<Breed> breeds) {
        Set<String> ids = new HashSet<>();
        Comparator<Breed> order = Comparator.comparing(Breed::species).thenComparing(Breed::id);
        for (int index = 0; index < breeds.size(); index++) {
            Breed breed = breeds.get(index);
            if (!ids.add(breed.id())) {
                throw new IllegalArgumentException("Duplicate breed ID: " + breed.id());
            }
            if (!breed.species().equals("cat") && !breed.species().equals("dog")) {
                throw new IllegalArgumentException("Unsupported breed species: " + breed.species());
            }
            if (index > 0 && order.compare(breeds.get(index - 1), breed) >= 0) {
                throw new IllegalArgumentException("Breed records are not uniquely sorted by species and ID");
            }
            String previousAlias = null;
            Set<String> normalizedAliases = new HashSet<>();
            for (String alias : breed.aliases()) {
                String normalized = normalize(alias);
                if (alias.isBlank() || !normalizedAliases.add(normalized)) {
                    throw new IllegalArgumentException("Invalid or duplicate alias for " + breed.id());
                }
                if (normalized.equals(normalize(breed.canonicalName()))
                        || normalized.equals(normalize(breed.displayNameRu()))) {
                    throw new IllegalArgumentException("Redundant alias for " + breed.id());
                }
                if (previousAlias != null && normalize(previousAlias).compareTo(normalized) >= 0) {
                    throw new IllegalArgumentException("Aliases are not uniquely sorted for " + breed.id());
                }
                previousAlias = alias;
            }
        }
        for (String species : List.of("cat", "dog")) {
            for (String kind : List.of("mixed", "unknown")) {
                long count = breeds.stream()
                        .filter(breed -> breed.species().equals(species) && breed.kind().equals(kind))
                        .count();
                if (count != 1) {
                    throw new IllegalArgumentException("Expected exactly one " + species + " " + kind + " record");
                }
            }
        }
    }

    private static void addTerm(Map<String, Term> terms, MutableTerm mutable) {
        if (mutable.id == null) return;
        Term term = new Term(mutable.id, mutable.label, List.copyOf(mutable.synonyms),
                List.copyOf(mutable.parents), mutable.obsolete);
        if (terms.put(term.id(), term) != null) {
            throw new IllegalArgumentException("Duplicate OBO term: " + term.id());
        }
    }

    private static final class MutableTerm {
        String id;
        String label;
        boolean obsolete;
        final List<String> synonyms = new ArrayList<>();
        final List<String> parents = new ArrayList<>();

        void accept(String line) {
            if (line.startsWith("id: ")) {
                id = line.substring(4).trim();
            } else if (line.startsWith("name: ")) {
                label = line.substring(6).trim();
            } else if (line.startsWith("is_a: ")) {
                parents.add(firstToken(line.substring(6)));
            } else if (line.equals("is_obsolete: true")) {
                obsolete = true;
            } else {
                Matcher matcher = SYNONYM.matcher(line);
                if (matcher.matches()) synonyms.add(unescapeObo(matcher.group(1)));
            }
        }
    }

    private static String firstToken(String value) {
        int space = value.indexOf(' ');
        return space < 0 ? value.trim() : value.substring(0, space).trim();
    }

    private static String unescapeObo(String value) {
        StringBuilder result = new StringBuilder();
        boolean escaped = false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (escaped) {
                result.append(switch (character) {
                    case 'n' -> '\n';
                    case 't' -> '\t';
                    case 'r' -> '\r';
                    default -> character;
                });
                escaped = false;
            } else if (character == '\\') {
                escaped = true;
            } else {
                result.append(character);
            }
        }
        if (escaped) result.append('\\');
        return result.toString();
    }

    private static Map<String, String> parseOverrides(Path path) throws IOException {
        Map<String, String> result = new TreeMap<>();
        int lineNumber = 0;
        for (String rawLine : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            lineNumber++;
            String line = rawLine.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] columns = line.split("\\t", -1);
            if (columns.length != 2 || !CANONICAL_ID.matcher(columns[0]).matches() || columns[1].isBlank()) {
                throw new IllegalArgumentException("Invalid Russian override at " + path + ":" + lineNumber);
            }
            if (result.put(columns[0], columns[1].strip()) != null) {
                throw new IllegalArgumentException("Duplicate Russian override for " + columns[0]);
            }
        }
        return result;
    }

    private record Exclusion(String expectedSpecies, String expectedLabel, String rationale) {}

    private static Map<String, Exclusion> parseExclusions(Path path) throws IOException {
        Map<String, Exclusion> result = new TreeMap<>();
        int lineNumber = 0;
        for (String rawLine : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            lineNumber++;
            String line = rawLine.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] columns = line.split("\\t", -1);
            if (columns.length != 4 || !CANONICAL_ID.matcher(columns[0]).matches()
                    || (!columns[1].equals("cat") && !columns[1].equals("dog"))
                    || columns[2].isBlank() || columns[3].isBlank()) {
                throw new IllegalArgumentException("Invalid VBO exclusion at " + path + ":" + lineNumber);
            }
            if (result.put(columns[0], new Exclusion(columns[1], columns[2].strip(), columns[3].strip())) != null) {
                throw new IllegalArgumentException("Duplicate VBO exclusion for " + columns[0]);
            }
        }
        return result;
    }

    private static List<Breed> buildBreeds(
            Map<String, Term> terms,
            Map<String, String> overrides,
            Map<String, Exclusion> exclusions) {
        requireRoot(terms, CAT_ROOT, "Cat breed");
        requireRoot(terms, DOG_ROOT, "Dog breed");
        Map<String, List<String>> children = new HashMap<>();
        for (Term term : terms.values()) {
            if (term.obsolete()) continue;
            for (String parent : term.parents()) children.computeIfAbsent(parent, ignored -> new ArrayList<>()).add(term.id());
        }
        validateExclusions(terms, children, exclusions);

        List<Breed> result = new ArrayList<>();
        Set<String> included = new HashSet<>();
        addSpeciesBreeds("cat", CAT_ROOT, terms, children, overrides, exclusions.keySet(), result, included);
        addSpeciesBreeds("dog", DOG_ROOT, terms, children, overrides, exclusions.keySet(), result, included);
        for (String id : overrides.keySet()) {
            if (!included.contains(id)) throw new IllegalArgumentException("Override does not identify a cat/dog breed: " + id);
        }
        addSpecials(result);
        result.sort(Comparator.comparing(Breed::species).thenComparing(Breed::id));
        return result;
    }

    private static void validateExclusions(
            Map<String, Term> terms,
            Map<String, List<String>> children,
            Map<String, Exclusion> exclusions) {
        Set<String> catDescendants = descendants(CAT_ROOT, children);
        Set<String> dogDescendants = descendants(DOG_ROOT, children);
        for (Map.Entry<String, Exclusion> entry : exclusions.entrySet()) {
            Term term = terms.get(entry.getKey());
            if (term == null || term.obsolete()) {
                throw new IllegalArgumentException("Excluded VBO term is missing or obsolete: " + entry.getKey());
            }
            if (!entry.getValue().expectedLabel().equals(term.label())) {
                throw new IllegalArgumentException("Excluded VBO term label changed: " + entry.getKey()
                        + " (expected '" + entry.getValue().expectedLabel() + "', got '" + term.label() + "')");
            }
            boolean isCat = catDescendants.contains(entry.getKey());
            boolean isDog = dogDescendants.contains(entry.getKey());
            if (isCat == isDog) {
                throw new IllegalArgumentException("Excluded VBO term must belong to exactly one cat/dog hierarchy: "
                        + entry.getKey());
            }
            String actualSpecies = isCat ? "cat" : "dog";
            if (!entry.getValue().expectedSpecies().equals(actualSpecies)) {
                throw new IllegalArgumentException("Excluded VBO term species changed: " + entry.getKey()
                        + " (expected '" + entry.getValue().expectedSpecies() + "', got '" + actualSpecies + "')");
            }
        }
    }

    private static void requireRoot(Map<String, Term> terms, String id, String label) {
        Term root = terms.get(id);
        if (root == null || root.obsolete() || !label.equals(root.label())) {
            throw new IllegalArgumentException("Expected VBO root is missing or changed: " + id + " (" + label + ")");
        }
    }

    private static void addSpeciesBreeds(
            String species,
            String root,
            Map<String, Term> terms,
            Map<String, List<String>> children,
            Map<String, String> overrides,
            Set<String> exclusions,
            List<Breed> result,
            Set<String> included) {
        Set<String> descendants = descendants(root, children);
        for (String id : new TreeMap<String, Term>(terms).keySet()) {
            Term term = terms.get(id);
            if (!descendants.contains(id) || term.obsolete() || !CANONICAL_ID.matcher(id).matches()
                    || exclusions.contains(id)) continue;
            if (term.label() == null || term.label().isBlank()) continue;
            String canonical = cleanName(term.label(), species);
            if (canonical.isBlank()) continue;
            String display = overrides.getOrDefault(id, canonical);
            List<String> aliases = dedupeAliases(canonical, display, term.synonyms(), species);
            result.add(new Breed(id, species, canonical, display, aliases, "vbo"));
            included.add(id);
        }
    }

    private static Set<String> descendants(String root, Map<String, List<String>> children) {
        Set<String> result = new HashSet<>();
        ArrayDeque<String> queue = new ArrayDeque<>(children.getOrDefault(root, List.of()));
        while (!queue.isEmpty()) {
            String id = queue.removeFirst();
            if (result.add(id)) queue.addAll(children.getOrDefault(id, List.of()));
        }
        return result;
    }

    private static String cleanName(String value, String species) {
        String suffix = species.equals("cat") ? " (Cat)" : " (Dog)";
        String cleaned = value.strip();
        return cleaned.endsWith(suffix) ? cleaned.substring(0, cleaned.length() - suffix.length()).strip() : cleaned;
    }

    private static List<String> dedupeAliases(String canonical, String display, List<String> synonyms, String species) {
        Set<String> excluded = new HashSet<>();
        excluded.add(normalize(canonical));
        excluded.add(normalize(display));
        Map<String, String> byNormalizedValue = new TreeMap<>();
        for (String synonym : synonyms) {
            String cleaned = cleanName(synonym, species);
            String normalized = normalize(cleaned);
            if (!cleaned.isBlank() && !excluded.contains(normalized)) byNormalizedValue.putIfAbsent(normalized, cleaned);
        }
        return List.copyOf(byNormalizedValue.values());
    }

    private static String normalize(String value) {
        return value.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static void addSpecials(List<Breed> result) {
        result.add(new Breed("scalesync:cat:mixed-breed", "cat", "Mixed breed", "Метис", List.of(), "mixed"));
        result.add(new Breed("scalesync:cat:breed-unknown", "cat", "Breed unknown", "Порода неизвестна", List.of(), "unknown"));
        result.add(new Breed("scalesync:dog:mixed-breed", "dog", "Mixed breed", "Метис", List.of(), "mixed"));
        result.add(new Breed("scalesync:dog:breed-unknown", "dog", "Breed unknown", "Порода неизвестна", List.of(), "unknown"));
    }

    private static String renderBreeds(List<Breed> breeds) {
        StringBuilder json = new StringBuilder("[\n");
        for (int index = 0; index < breeds.size(); index++) {
            Breed breed = breeds.get(index);
            json.append("    {\n")
                    .append("      \"id\": ").append(quote(breed.id())).append(",\n")
                    .append("      \"species\": ").append(quote(breed.species())).append(",\n")
                    .append("      \"canonicalName\": ").append(quote(breed.canonicalName())).append(",\n")
                    .append("      \"displayNameRu\": ").append(quote(breed.displayNameRu())).append(",\n")
                    .append("      \"aliases\": [");
            for (int aliasIndex = 0; aliasIndex < breed.aliases().size(); aliasIndex++) {
                if (aliasIndex > 0) json.append(", ");
                json.append(quote(breed.aliases().get(aliasIndex)));
            }
            json.append("],\n      \"kind\": ").append(quote(breed.kind())).append("\n    }");
            if (index + 1 < breeds.size()) json.append(',');
            json.append('\n');
        }
        return json.append("  ]").toString();
    }

    private static String renderDocument(Request request, String sourceSha, String catalogSha, String breedsJson) {
        return "{\n  \"manifest\": {\n"
                + "    \"schemaVersion\": 1,\n"
                + "    \"sourceName\": \"Vertebrate Breed Ontology\",\n"
                + "    \"sourceVersion\": " + quote(request.sourceVersion()) + ",\n"
                + "    \"sourceUrl\": " + quote(request.sourceUrl()) + ",\n"
                + "    \"license\": \"CC BY 4.0\",\n"
                + "    \"licenseUrl\": \"" + LICENSE_URL + "\",\n"
                + "    \"snapshotDate\": " + quote(request.snapshotDate()) + ",\n"
                + "    \"sourceSha256\": \"" + sourceSha + "\",\n"
                + "    \"catalogSha256\": \"" + catalogSha + "\"\n"
                + "  },\n  \"breeds\": " + breedsJson + "\n}\n";
    }

    private static String quote(String value) {
        StringBuilder escaped = new StringBuilder("\"");
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '\"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) escaped.append(String.format("\\u%04x", (int) character));
                    else escaped.append(character);
                }
            }
        }
        return escaped.append('\"').toString();
    }

    static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest) result.append(String.format("%02x", value & 0xff));
            return result.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
