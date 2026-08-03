package com.geyserextra.paper.pack;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Resolves a Java Edition {@code minecraft:&lt;item&gt;} identifier for Bedrock
 * custom-item inventory display.
 *
 * <p>Flat inventory paths come from build-time {@code vanilla_texture_paths.json}.
 * Blocks without a flat PNG are listed under {@code useBlockIcon}. Geyser
 * rejects {@code BLOCK_PLACER}/{@code useBlockIcon} on vanilla-based custom
 * items. Those mappings must therefore remain on Geyser's vanilla base-item
 * path unless an authored custom PNG exists; a terrain texture would render
 * only one block face rather than the normal inventory icon.</p>
 */
public final class BedrockVanillaTexturePaths {

    private static final String RESOURCE = "/bedrock/vanilla_texture_paths.json";
    private static final String MINECRAFT_PREFIX = "minecraft:";

    private static final Map<String, String> GENERATED_PATHS;
    /** Bare Java id → Bedrock block id (Geyser needs the Bedrock name). */
    private static final Map<String, String> USE_BLOCK_ICON;
    /** Bare Java id → the name Bedrock's own resource pack gives the item. */
    private static final Map<String, String> VANILLA_NAMES;

    static {
        Loaded loaded = loadGenerated();
        GENERATED_PATHS = loaded.paths;
        USE_BLOCK_ICON = loaded.useBlockIcon;
        VANILLA_NAMES = loaded.vanillaNames;
    }

    private BedrockVanillaTexturePaths() {}

    /**
     * @return flat Bedrock texture path without {@code .png}, or {@code null}
     *         when the id should use a 3D block icon / has no counterpart
     */
    public static String resolve(String javaBaseItem) {
        if (javaBaseItem == null || javaBaseItem.isBlank()) {
            return null;
        }
        String name = stripNamespace(javaBaseItem);
        if (name == null) {
            return null;
        }
        name = name.toLowerCase(Locale.ROOT);
        if (USE_BLOCK_ICON.containsKey(name)) {
            return null;
        }
        return GENERATED_PATHS.get(name);
    }

    /**
     * @return true when Bedrock has no flat inventory PNG for this Java base
     *         (block-base items that need a block texture fallback in the pack)
     */
    public static boolean usesBlockIcon(String javaBaseItem) {
        String name = stripNamespace(javaBaseItem);
        if (name == null) {
            return false;
        }
        return USE_BLOCK_ICON.containsKey(name.toLowerCase(Locale.ROOT));
    }

    /**
     * Unmodifiable map of bare Java ids that need {@code useBlockIcon} to the
     * Bedrock block id Geyser must register ({@code cobweb → web}); most
     * entries map to themselves.
     */
    public static Map<String, String> blockIconBases() {
        return USE_BLOCK_ICON;
    }

    /**
     * The name Bedrock itself shows for this vanilla item, or {@code null}
     * when the generated table has no entry.
     *
     * <p>Meant for the display name of a custom item whose stack carries no
     * name of its own. A registered custom item is named only from the pack's
     * {@code texts/*.lang}, so without this the client's own localised name is
     * replaced by whatever we invent — which is how Japanese clients ended up
     * showing "Wooden Sword".</p>
     */
    public static String vanillaName(String javaBaseItem) {
        if (javaBaseItem == null || javaBaseItem.isBlank()) {
            return null;
        }
        String name = stripNamespace(javaBaseItem);
        if (name == null) {
            return null;
        }
        return VANILLA_NAMES.get(name.toLowerCase(Locale.ROOT));
    }

    static String stripNamespace(String javaBaseItem) {
        if (javaBaseItem.startsWith(MINECRAFT_PREFIX)) {
            return javaBaseItem.substring(MINECRAFT_PREFIX.length());
        }
        if (javaBaseItem.indexOf(':') < 0) {
            return javaBaseItem;
        }
        return null;
    }

    private record Loaded(
        Map<String, String> paths,
        Map<String, String> useBlockIcon,
        Map<String, String> vanillaNames
    ) {}

    private static Loaded loadGenerated() {
        try (InputStream in = BedrockVanillaTexturePaths.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing classpath resource: " + RESOURCE
                    + " (run generateVanillaTexturePaths before compileJava)");
            }
            JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                .getAsJsonObject();
            JsonObject pathsObj = root.getAsJsonObject("paths");
            if (pathsObj == null) {
                throw new IllegalStateException(RESOURCE + " is missing a \"paths\" object");
            }
            Map<String, String> paths = new HashMap<>();
            for (String key : pathsObj.keySet()) {
                paths.put(key, pathsObj.get(key).getAsString());
            }
            Map<String, String> blockIcon = new HashMap<>();
            JsonObject blockIconObj = root.getAsJsonObject("useBlockIcon");
            if (blockIconObj != null) {
                for (String key : blockIconObj.keySet()) {
                    blockIcon.put(
                        key.toLowerCase(Locale.ROOT),
                        blockIconObj.get(key).getAsString().toLowerCase(Locale.ROOT));
                }
            }
            // Absent on a jar built before the names were generated: degrade to
            // the prettified fallback rather than failing to load the plugin.
            Map<String, String> names = new HashMap<>();
            JsonObject namesObj = root.getAsJsonObject("vanillaNames");
            if (namesObj != null) {
                for (String key : namesObj.keySet()) {
                    names.put(key.toLowerCase(Locale.ROOT), namesObj.get(key).getAsString());
                }
            }
            return new Loaded(
                Collections.unmodifiableMap(paths),
                Collections.unmodifiableMap(blockIcon),
                Collections.unmodifiableMap(names));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load " + RESOURCE, e);
        }
    }
}
