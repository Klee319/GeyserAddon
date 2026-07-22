package com.geyserextra.paper.pack.generate;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Indexes Bedrock inventory icon paths from {@code item_texture.json} keys and
 * terrain {@code *_carried} entries. Used to avoid mapping Java block ids to
 * block-face UV paths ({@code *_side}, {@code *_top}) in custom
 * {@code item_texture.json} entries.
 */
final class BedrockInventoryIconIndex {

    private final Map<String, String> itemAtlasByKey;
    private final Map<String, String> carriedByBaseName;

    private BedrockInventoryIconIndex(Map<String, String> itemAtlasByKey, Map<String, String> carriedByBaseName) {
        this.itemAtlasByKey = itemAtlasByKey;
        this.carriedByBaseName = carriedByBaseName;
    }

    static BedrockInventoryIconIndex load(Path itemTextureJson, Path terrainTextureJson) throws IOException {
        Map<String, String> atlas = new HashMap<>();
        collectItemAtlasKeys(
            BedrockTextureCatalog.stripLeadingComments(
                Files.readString(itemTextureJson, StandardCharsets.UTF_8)),
            atlas);
        Map<String, String> carried = new HashMap<>();
        collectCarriedIcons(
            BedrockTextureCatalog.stripLeadingComments(
                Files.readString(terrainTextureJson, StandardCharsets.UTF_8)),
            carried);
        return new BedrockInventoryIconIndex(Map.copyOf(atlas), Map.copyOf(carried));
    }

    String resolve(String javaName) {
        if (javaName == null || javaName.isBlank()) {
            return null;
        }
        String name = javaName.toLowerCase(Locale.ROOT);

        String fromAtlas = itemAtlasByKey.get(name);
        if (isInventorySafe(fromAtlas)) {
            return fromAtlas;
        }
        for (String legacyKey : legacyAtlasKeys(name)) {
            fromAtlas = itemAtlasByKey.get(legacyKey);
            if (isInventorySafe(fromAtlas)) {
                return fromAtlas;
            }
        }

        String carried = carriedByBaseName.get(name);
        if (isInventorySafe(carried)) {
            return carried;
        }
        return null;
    }

    static boolean isInventorySafe(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        if (!path.startsWith("textures/blocks/")) {
            return true;
        }
        String file = path.substring("textures/blocks/".length());
        return !file.endsWith("_side")
            && !file.endsWith("_top")
            && !file.endsWith("_bottom")
            && !file.endsWith("_front");
    }

    private static void collectItemAtlasKeys(String json, Map<String, String> out) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonObject textureData = root.getAsJsonObject("texture_data");
        if (textureData == null) {
            return;
        }
        for (String key : textureData.keySet()) {
            String path = firstTexturePath(textureData.get(key));
            if (path != null) {
                out.put(key.toLowerCase(Locale.ROOT), path.toLowerCase(Locale.ROOT));
            }
        }
    }

    private static void collectCarriedIcons(String json, Map<String, String> out) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonObject textureData = root.getAsJsonObject("texture_data");
        if (textureData == null) {
            return;
        }
        for (String key : textureData.keySet()) {
            if (!key.endsWith("_carried")) {
                continue;
            }
            String baseName = key.substring(0, key.length() - "_carried".length());
            String path = firstTexturePath(textureData.get(key));
            if (path != null) {
                out.put(baseName.toLowerCase(Locale.ROOT), path.toLowerCase(Locale.ROOT));
            }
        }
    }

    private static String firstTexturePath(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            return element.getAsString();
        }
        if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            if (array.isEmpty()) {
                return null;
            }
            return firstTexturePath(array.get(0));
        }
        if (element.isJsonObject()) {
            JsonObject obj = element.getAsJsonObject();
            if (obj.has("textures")) {
                return firstTexturePath(obj.get("textures"));
            }
            if (obj.has("path")) {
                return firstTexturePath(obj.get("path"));
            }
        }
        return null;
    }

    private static List<String> legacyAtlasKeys(String javaName) {
        return switch (javaName) {
            case "book" -> List.of("book_normal");
            case "enchanted_book" -> List.of("book_enchanted");
            case "writable_book" -> List.of("book_writable");
            case "written_book" -> List.of("book_written");
            case "golden_apple" -> List.of("apple_golden");
            case "golden_carrot" -> List.of("carrot_golden");
            case "bow" -> List.of("bow_standby");
            case "bucket" -> List.of("bucket_empty");
            case "redstone" -> List.of("redstone_dust");
            case "totem_of_undying" -> List.of("totem");
            default -> List.of();
        };
    }
}
