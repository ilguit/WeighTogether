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
import java.util.HexFormat;

public final class Main {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private Main() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Usage: <source-json> <output-json>");
        JsonObject root = JsonParser.parseString(Files.readString(Path.of(args[0]), StandardCharsets.UTF_8)).getAsJsonObject();
        JsonArray profiles = root.getAsJsonArray("profiles");
        String checksum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(profiles.toString().getBytes(StandardCharsets.UTF_8)));
        root.getAsJsonObject("manifest").addProperty("numericalDataSha256", checksum);
        String normalized = GSON.toJson(root) + "\n";
        Path output = Path.of(args[1]);
        Files.createDirectories(output.getParent());
        Files.writeString(output, normalized, StandardCharsets.UTF_8);
    }
}
