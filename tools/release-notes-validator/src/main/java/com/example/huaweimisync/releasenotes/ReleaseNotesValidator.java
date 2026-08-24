package com.example.huaweimisync.releasenotes;

import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.api.lowlevel.Parse;
import org.snakeyaml.engine.v2.events.AliasEvent;
import org.snakeyaml.engine.v2.exceptions.YamlEngineException;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class ReleaseNotesValidator {
    private static final String DIRECTORY_NAME = ".release-notes";
    private static final Pattern FILE_NAME = Pattern.compile("([1-9][0-9]*)-([a-z0-9]+(?:-[a-z0-9]+)*)\\.yaml");
    private static final Pattern CYRILLIC = Pattern.compile(".*\\p{IsCyrillic}.*", Pattern.DOTALL);
    private static final Set<String> ALLOWED_KEYS = Set.of("issue", "userVisible", "text", "reason", "flavors");
    private static final Set<String> ALLOWED_FLAVORS = Set.of("personal", "huaweiEnterprise");
    private static final Set<String> SUPPORT_FILES = Set.of("README.md", "template.yaml.example");

    private final Load yaml = new Load(LoadSettings.builder()
            .setLabel("release-note fragment")
            .setAllowDuplicateKeys(false)
            .setMaxAliasesForCollections(0)
            .build());
    private final Parse yamlParser = new Parse(LoadSettings.builder()
            .setLabel("release-note fragment")
            .build());

    public int validate(Path repositoryRoot) throws ValidationException {
        Path directory = repositoryRoot.resolve(DIRECTORY_NAME);
        if (!Files.isDirectory(directory)) {
            throw new ValidationException("required directory '" + directory + "' does not exist or is not a directory");
        }

        List<Path> fragments;
        try (Stream<Path> entries = Files.list(directory)) {
            fragments = entries.sorted().toList();
        } catch (IOException exception) {
            throw new ValidationException("cannot read directory '" + directory + "': " + exception.getMessage(), exception);
        }

        Set<String> caseInsensitiveNames = new HashSet<>();
        List<String> diagnostics = new ArrayList<>();
        int fragmentCount = 0;
        for (Path fragment : fragments) {
            if (!Files.isRegularFile(fragment)) {
                diagnostics.add(error(fragment, "only regular fragment files are allowed").getMessage());
                continue;
            }
            if (SUPPORT_FILES.contains(fragment.getFileName().toString())) {
                continue;
            }
            fragmentCount++;
            String lowerCaseName = fragment.getFileName().toString().toLowerCase(Locale.ROOT);
            if (!caseInsensitiveNames.add(lowerCaseName)) {
                diagnostics.add(error(fragment, "filename collides case-insensitively with another fragment").getMessage());
                continue;
            }
            try {
                validateFragment(fragment);
            } catch (ValidationException exception) {
                diagnostics.add(exception.getMessage());
            }
        }
        if (!diagnostics.isEmpty()) {
            throw new ValidationException(String.join(System.lineSeparator(), diagnostics));
        }
        return fragmentCount;
    }

    private void validateFragment(Path fragment) throws ValidationException {
        String fileName = fragment.getFileName().toString();
        Matcher fileNameMatcher = FILE_NAME.matcher(fileName);
        if (!fileNameMatcher.matches()) {
            throw error(fragment, "filename must match <positive-issue>-<lowercase-slug>.yaml");
        }

        String input;
        try {
            input = Files.readString(fragment, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw error(fragment, "cannot read file: " + exception.getMessage(), exception);
        }

        List<Object> documents;
        try {
            yamlParser.parseString(input).forEach(event -> {
                if (event instanceof AliasEvent) {
                    throw new AliasRejectedException();
                }
            });
            documents = new ArrayList<>();
            yaml.loadAllFromString(input).forEach(documents::add);
        } catch (AliasRejectedException exception) {
            throw error(fragment, "YAML aliases are not allowed");
        } catch (YamlEngineException exception) {
            throw error(fragment, "invalid YAML: " + conciseMessage(exception), exception);
        }
        if (documents.size() != 1) {
            throw error(fragment, "must contain exactly one YAML document");
        }

        Map<String, Object> values = requireStringKeyedMap(fragment, documents.get(0));
        rejectUnknownKeys(fragment, values);

        BigInteger issue = requirePositiveIssue(fragment, values.get("issue"));
        BigInteger filenameIssue = new BigInteger(fileNameMatcher.group(1));
        if (!issue.equals(filenameIssue)) {
            throw error(fragment, "field 'issue' (" + issue + ") must match filename issue (" + filenameIssue + ")");
        }

        Object visibleValue = values.get("userVisible");
        if (!(visibleValue instanceof Boolean userVisible)) {
            throw error(fragment, "required field 'userVisible' must be a boolean");
        }

        if (userVisible) {
            String text = requireNonBlankString(fragment, values, "text");
            if (!CYRILLIC.matcher(text).matches()) {
                throw error(fragment, "field 'text' must contain Russian (Cyrillic) text");
            }
            if (values.containsKey("reason")) {
                throw error(fragment, "field 'reason' is forbidden when 'userVisible' is true");
            }
        } else {
            requireNonBlankString(fragment, values, "reason");
            if (values.containsKey("text")) {
                throw error(fragment, "field 'text' is forbidden when 'userVisible' is false");
            }
        }

        if (values.containsKey("flavors")) {
            validateFlavors(fragment, values.get("flavors"));
        }
    }

    private Map<String, Object> requireStringKeyedMap(Path fragment, Object document) throws ValidationException {
        if (!(document instanceof Map<?, ?> map)) {
            throw error(fragment, "YAML document must be a mapping");
        }
        Map<String, Object> values = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw error(fragment, "all field names must be strings");
            }
            values.put(key, entry.getValue());
        }
        return values;
    }

    private void rejectUnknownKeys(Path fragment, Map<String, Object> values) throws ValidationException {
        for (String key : values.keySet()) {
            if (!ALLOWED_KEYS.contains(key)) {
                throw error(fragment, "unknown field '" + key + "'");
            }
        }
        if (!values.containsKey("issue")) {
            throw error(fragment, "required field 'issue' is missing");
        }
    }

    private BigInteger requirePositiveIssue(Path fragment, Object value) throws ValidationException {
        if (!(value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long
                || value instanceof BigInteger)) {
            throw error(fragment, "required field 'issue' must be an integer");
        }
        BigInteger issue = value instanceof BigInteger bigInteger
                ? bigInteger
                : BigInteger.valueOf(((Number) value).longValue());
        if (issue.signum() <= 0) {
            throw error(fragment, "field 'issue' must be greater than zero");
        }
        return issue;
    }

    private String requireNonBlankString(Path fragment, Map<String, Object> values, String field) throws ValidationException {
        Object value = values.get(field);
        if (!(value instanceof String stringValue) || stringValue.isBlank()) {
            throw error(fragment, "required field '" + field + "' must be a non-blank string");
        }
        return stringValue;
    }

    private void validateFlavors(Path fragment, Object value) throws ValidationException {
        if (!(value instanceof List<?> flavors) || flavors.isEmpty()) {
            throw error(fragment, "optional field 'flavors' must be a non-empty list");
        }
        Set<String> unique = new HashSet<>();
        for (Object flavorValue : flavors) {
            if (!(flavorValue instanceof String flavor)) {
                throw error(fragment, "every 'flavors' item must be a string");
            }
            if (!ALLOWED_FLAVORS.contains(flavor)) {
                throw error(fragment, "unknown flavor '" + flavor + "'; allowed: personal, huaweiEnterprise");
            }
            if (!unique.add(flavor)) {
                throw error(fragment, "duplicate flavor '" + flavor + "'");
            }
        }
    }

    private static String conciseMessage(Throwable exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return message.lines().findFirst().orElse(message);
    }

    private static ValidationException error(Path fragment, String message) {
        return new ValidationException(fragment + ": " + message);
    }

    private static ValidationException error(Path fragment, String message, Throwable cause) {
        return new ValidationException(fragment + ": " + message, cause);
    }

    private static final class AliasRejectedException extends RuntimeException {
    }
}
