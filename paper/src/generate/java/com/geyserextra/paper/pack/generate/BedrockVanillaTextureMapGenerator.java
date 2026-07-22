package com.geyserextra.paper.pack.generate;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Build-time entry point: reads {@code bedrock-samples} atlases, resolves every
 * vanilla Java item/block id, and writes {@code bedrock/vanilla_texture_paths.json}.
 *
 * <p>Blocks without a flat inventory PNG are listed under {@code useBlockIcon}
 * so runtime can register Geyser {@code useBlockIcon=true} instead of a face
 * texture path.</p>
 */
public final class BedrockVanillaTextureMapGenerator {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static void main(String[] args) throws IOException {
        if (args.length < 7) {
            System.err.println("Usage: BedrockVanillaTextureMapGenerator "
                + "<item_texture.json> <terrain_texture.json> <manifest.json> "
                + "<items.json> <blocks.json> <blocksJ2B.json> <output.json>");
            System.exit(1);
        }
        Path itemJson = Path.of(args[0]);
        Path terrainJson = Path.of(args[1]);
        Path manifestJson = Path.of(args[2]);
        Path javaItemsJson = Path.of(args[3]);
        Path javaBlocksJson = Path.of(args[4]);
        Path blocksJ2BJson = Path.of(args[5]);
        Path outputJson = Path.of(args[6]);

        BedrockTextureCatalog catalog = BedrockTextureCatalog.load(itemJson, terrainJson);
        BedrockInventoryIconIndex iconIndex = BedrockInventoryIconIndex.load(itemJson, terrainJson);
        VanillaTextureResolver resolver = new VanillaTextureResolver(catalog, iconIndex);
        Set<String> blockIds = readRegistryKeys(javaBlocksJson);
        Set<String> itemIds = readRegistryKeys(javaItemsJson);
        Map<String, String> javaToBedrockBlockIds = readBlockRenames(blocksJ2BJson);

        Set<String> allIds = new TreeSet<>();
        allIds.addAll(itemIds);
        allIds.addAll(blockIds);

        Map<String, String> paths = new LinkedHashMap<>();
        // Java bare id -> Bedrock bare block id. Geyser sends the value
        // verbatim to Bedrock clients in minecraft:block_placer, so renamed
        // blocks (cobweb -> web) must carry the Bedrock name here — an
        // unknown id breaks both the 3D icon and placement prediction.
        Map<String, String> useBlockIcon = new TreeMap<>();
        for (String javaName : allIds) {
            if ("air".equals(javaName)) {
                continue;
            }
            boolean isBlock = blockIds.contains(javaName);
            VanillaTextureResolver.Result result = resolver.resolveResult(javaName, isBlock);
            if (result.useBlockIcon()) {
                // Blocks newer than the blocksJ2B snapshot keep their Java
                // name — post-flattening additions share ids across editions.
                useBlockIcon.put(javaName,
                    javaToBedrockBlockIds.getOrDefault(javaName, javaName));
            } else if (result.path() != null) {
                paths.put(javaName, result.path());
            }
        }

        JsonObject root = new JsonObject();
        root.addProperty("source", "https://github.com/Mojang/bedrock-samples");
        root.addProperty("javaRegistrySource", "https://github.com/PrismarineJS/minecraft-data");
        root.addProperty("generatedAt", Instant.now().toString());
        root.addProperty("catalogPathCount", catalog.size());
        root.addProperty("mappedMaterialCount", paths.size());
        root.addProperty("useBlockIconCount", useBlockIcon.size());
        root.add("minEngineVersion", readMinEngineVersion(manifestJson));
        JsonObject pathObject = new JsonObject();
        for (Map.Entry<String, String> entry : paths.entrySet()) {
            pathObject.addProperty(entry.getKey(), entry.getValue());
        }
        root.add("paths", pathObject);
        JsonObject blockIconObject = new JsonObject();
        for (Map.Entry<String, String> entry : useBlockIcon.entrySet()) {
            blockIconObject.addProperty(entry.getKey(), entry.getValue());
        }
        root.add("useBlockIcon", blockIconObject);

        Files.createDirectories(outputJson.getParent());
        Files.writeString(outputJson, GSON.toJson(root), StandardCharsets.UTF_8);
        System.out.println("[BedrockVanillaTextureMapGenerator] wrote " + paths.size()
            + " flat paths + " + useBlockIcon.size() + " useBlockIcon entries to " + outputJson);
    }

    /**
     * Reads minecraft-data {@code blocksJ2B.json} ("minecraft:cobweb[props]"
     * → "minecraft:web[props]") into a bare-name rename map. Only the first
     * (default-state) entry per Java block is kept.
     */
    private static Map<String, String> readBlockRenames(Path blocksJ2BJson) throws IOException {
        String raw = Files.readString(blocksJ2BJson, StandardCharsets.UTF_8);
        JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
        Map<String, String> renames = new LinkedHashMap<>();
        for (String key : root.keySet()) {
            String javaName = bareBlockName(key);
            String bedrockName = bareBlockName(root.get(key).getAsString());
            renames.putIfAbsent(javaName, bedrockName);
        }
        return renames;
    }

    /** Strips the {@code minecraft:} namespace and {@code [state=...]} suffix. */
    private static String bareBlockName(String stateId) {
        String name = stateId;
        int bracket = name.indexOf('[');
        if (bracket >= 0) {
            name = name.substring(0, bracket);
        }
        int colon = name.indexOf(':');
        if (colon >= 0) {
            name = name.substring(colon + 1);
        }
        return name.toLowerCase(Locale.ROOT);
    }

    private static Set<String> readRegistryKeys(Path jsonFile) throws IOException {
        String raw = Files.readString(jsonFile, StandardCharsets.UTF_8);
        var element = JsonParser.parseString(raw);
        Set<String> keys = new TreeSet<>();
        if (element.isJsonArray()) {
            for (var entry : element.getAsJsonArray()) {
                if (entry.isJsonObject() && entry.getAsJsonObject().has("name")) {
                    keys.add(entry.getAsJsonObject().get("name").getAsString()
                        .toLowerCase(Locale.ROOT));
                }
            }
        } else if (element.isJsonObject()) {
            for (String key : element.getAsJsonObject().keySet()) {
                keys.add(key.toLowerCase(Locale.ROOT));
            }
        }
        return Set.copyOf(keys);
    }

    private static JsonArray readMinEngineVersion(Path manifestJson) throws IOException {
        String raw = Files.readString(manifestJson, StandardCharsets.UTF_8);
        JsonObject manifest = JsonParser.parseString(raw).getAsJsonObject();
        JsonObject header = manifest.getAsJsonObject("header");
        if (header != null && header.has("min_engine_version")) {
            return header.getAsJsonArray("min_engine_version");
        }
        return new JsonArray();
    }
}
