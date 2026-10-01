package com.palixander.weightogether.breedcatalog;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public final class Main {
    private Main() {}

    public static void main(String[] args) throws Exception {
        Map<String, String> values = parseArgs(args);
        CatalogGenerator.Request request = new CatalogGenerator.Request(
                required(values, "source"),
                requiredString(values, "source-version"),
                requiredString(values, "source-url"),
                requiredString(values, "source-sha256"),
                requiredString(values, "snapshot-date"),
                required(values, "overrides"),
                required(values, "exclusions"),
                required(values, "output"));
        new CatalogGenerator().generate(request);
    }

    private static Map<String, String> parseArgs(String[] args) {
        if (args.length % 2 != 0) {
            throw new IllegalArgumentException("Arguments must be --name value pairs");
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (int index = 0; index < args.length; index += 2) {
            if (!args[index].startsWith("--")) {
                throw new IllegalArgumentException("Expected option, got: " + args[index]);
            }
            String name = args[index].substring(2);
            if (result.put(name, args[index + 1]) != null) {
                throw new IllegalArgumentException("Duplicate option: --" + name);
            }
        }
        return result;
    }

    private static Path required(Map<String, String> values, String name) {
        return Path.of(requiredString(values, name));
    }

    private static String requiredString(Map<String, String> values, String name) {
        String value = values.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing --" + name);
        }
        return value;
    }
}
