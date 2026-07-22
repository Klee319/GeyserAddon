package com.geyserextra.paper.pack.generate;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Indexes every texture path declared in Mojang {@code bedrock-samples}
 * {@code item_texture.json} and {@code terrain_texture.json}.
 */
final class BedrockTextureCatalog {

    private final Set<String> paths;

    private BedrockTextureCatalog(Set<String> paths) {
        this.paths = paths;
    }

    static BedrockTextureCatalog load(Path itemTextureJson, Path terrainTextureJson) throws IOException {
        Set<String> out = new HashSet<>();
        collectFromAtlas(stripLeadingComments(Files.readString(itemTextureJson, StandardCharsets.UTF_8)), out);
        collectFromAtlas(stripLeadingComments(Files.readString(terrainTextureJson, StandardCharsets.UTF_8)), out);
        return new BedrockTextureCatalog(Set.copyOf(out));
    }

    boolean contains(String path) {
        return paths.contains(path);
    }

    String firstExisting(Iterable<String> candidates) {
        for (String candidate : candidates) {
            if (contains(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    int size() {
        return paths.size();
    }

    private static void collectFromAtlas(String json, Set<String> out) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonObject textureData = root.getAsJsonObject("texture_data");
        if (textureData == null) {
            return;
        }
        for (String key : textureData.keySet()) {
            collectTextureValue(textureData.get(key), out);
        }
    }

    private static void collectTextureValue(JsonElement element, Set<String> out) {
        if (element == null || element.isJsonNull()) {
            return;
        }
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            out.add(normalizePath(element.getAsString()));
            return;
        }
        if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            for (JsonElement child : array) {
                collectTextureValue(child, out);
            }
            return;
        }
        if (element.isJsonObject()) {
            JsonObject obj = element.getAsJsonObject();
            if (obj.has("path")) {
                collectTextureValue(obj.get("path"), out);
            }
            if (obj.has("textures")) {
                collectTextureValue(obj.get("textures"), out);
            }
        }
    }

    private static String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return path;
        }
        return path.toLowerCase(Locale.ROOT);
    }

    /** bedrock-samples ships // comment lines above the JSON root. */
    static String stripLeadingComments(String raw) {
        StringBuilder sb = new StringBuilder();
        for (String line : raw.split("\n")) {
            if (line.stripLeading().startsWith("//")) {
                continue;
            }
            sb.append(line).append('\n');
        }
        return sb.toString();
    }
}
