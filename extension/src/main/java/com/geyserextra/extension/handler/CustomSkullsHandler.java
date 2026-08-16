/*
 * GeyserExtra Extension - Custom Skulls Handler
 * Loads and registers custom skulls from shared configuration file.
 */
package com.geyserextra.extension.handler;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.geysermc.geyser.api.block.custom.CustomBlockData;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineCustomSkullsEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineCustomSkullsEvent.SkullTextureType;
import org.geysermc.geyser.api.extension.Extension;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Handler for loading and registering custom skulls from shared configuration.
 *
 * Why: This handler reads skull definitions from a shared JSON file (skulls.json)
 * and registers them with Geyser to enable custom skull blocks for Bedrock players.
 * Custom skulls require 'gameplay.enable-custom-content' to be true in Geyser config.
 */
public class CustomSkullsHandler {

    private static final String SKULLS_FILE_NAME = "skulls.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Extension extension;
    private final Path sharedFolder;
    private final List<SkullEntry> skullEntries;

    /**
     * True once skulls.json has been read and parsed without error, so an
     * empty registry can be told apart from an unreadable one.
     */
    private boolean registryLoaded;

    /**
     * Creates a new CustomSkullsHandler.
     *
     * @param extension the parent extension instance
     * @param sharedFolder the path to the shared data folder
     */
    public CustomSkullsHandler(Extension extension, Path sharedFolder) {
        this.extension = extension;
        this.sharedFolder = sharedFolder;
        this.skullEntries = new ArrayList<>();
        loadSkullRegistry();
    }

    /**
     * Loads skull entries from the shared skulls.json file.
     * Creates the shared folder if it does not exist.
     */
    private void loadSkullRegistry() {
        extension.logger().debug("=== Loading Skull Registry ===");
        Path skullsFile = sharedFolder.resolve(SKULLS_FILE_NAME);
        extension.logger().debug("Skulls file path: " + skullsFile.toAbsolutePath());

        if (!Files.exists(sharedFolder)) {
            try {
                Files.createDirectories(sharedFolder);
                extension.logger().debug("Created shared folder: " + sharedFolder);
            } catch (IOException e) {
                extension.logger().error("Failed to create shared folder: " + e.getMessage());
                return;
            }
        }

        if (!Files.exists(skullsFile)) {
            // Why: previously this branch auto-created a sample skulls.json, which
            // pinned placeholder data into the live data folder and hid the real
            // recovery flow. The extension now logs the actionable steps and waits
            // for the Paper plugin to write the real file. No file is created.
            extension.logger().warning("No skulls.json found at " + skullsFile);
            extension.logger().warning("Recovery steps:");
            extension.logger().warning("  1. Place some custom player heads in the world so Paper can scan them.");
            extension.logger().warning("  2. Restart the server so the Paper plugin writes skulls.json before Geyser loads.");
            extension.logger().warning("  3. The extension picks up the file on subsequent startups.");
            return;
        }

        try {
            String content = Files.readString(skullsFile, StandardCharsets.UTF_8);
            extension.logger().debug("Read skulls.json (" + content.length() + " bytes)");

            JsonObject root = JsonParser.parseString(content).getAsJsonObject();
            parseSkullEntries(root);
            registryLoaded = true;
            extension.logger().debug("Successfully loaded " + skullEntries.size() + " custom skull entries.");
        } catch (IOException e) {
            extension.logger().error("Failed to read skulls.json: " + e.getMessage());
        } catch (Exception e) {
            extension.logger().error("Failed to parse skulls.json: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Parses skull entries from the JSON configuration.
     *
     * @param root the root JSON object containing skull definitions
     */
    private void parseSkullEntries(JsonObject root) {
        // Format: { "skulls": [ { "texture": "...", "type": "PROFILE|SKIN_HASH|USERNAME|UUID" }, ... ] }
        if (!root.has("skulls")) {
            extension.logger().warning("skulls.json missing 'skulls' array.");
            return;
        }

        JsonArray skulls = root.getAsJsonArray("skulls");
        for (JsonElement element : skulls) {
            JsonObject skullDef = element.getAsJsonObject();
            SkullEntry entry = parseSkullDefinition(skullDef);
            if (entry != null) {
                skullEntries.add(entry);
            }
        }
    }

    /**
     * Parses a single skull definition from JSON.
     *
     * @param skullDef the JSON object containing skull properties
     * @return the parsed SkullEntry or null if parsing fails
     */
    private SkullEntry parseSkullDefinition(JsonObject skullDef) {
        try {
            String texture = getStringOrDefault(skullDef, "texture", null);
            String typeStr = getStringOrDefault(skullDef, "type", "PROFILE");

            if (texture == null || texture.isBlank()) {
                extension.logger().warning("Skull definition missing 'texture' field, skipping.");
                return null;
            }

            SkullTextureType textureType = parseSkullTextureType(typeStr);
            if (textureType == null) {
                extension.logger().warning("Invalid skull texture type '" + typeStr + "', skipping.");
                return null;
            }

            return new SkullEntry(texture, textureType);
        } catch (Exception e) {
            extension.logger().warning("Failed to parse skull definition: " + e.getMessage());
            return null;
        }
    }

    /**
     * Parses the skull texture type from string.
     *
     * @param typeStr the type string (PROFILE, SKIN_HASH, USERNAME, UUID)
     * @return the corresponding SkullTextureType or null if invalid
     */
    private SkullTextureType parseSkullTextureType(String typeStr) {
        return switch (typeStr.toUpperCase()) {
            case "PROFILE" -> SkullTextureType.PROFILE;
            case "SKIN_HASH" -> SkullTextureType.SKIN_HASH;
            case "USERNAME" -> SkullTextureType.USERNAME;
            case "UUID" -> SkullTextureType.UUID;
            default -> null;
        };
    }

    /**
     * Registers all loaded custom skulls with the Geyser event.
     *
     * @param event the custom skulls definition event from Geyser
     */
    public void registerSkulls(GeyserDefineCustomSkullsEvent event) {
        extension.logger().debug("=== Custom Skulls Registration ===");
        extension.logger().debug("Loaded skull entries: " + skullEntries.size());
        extension.logger().debug("NOTE: Geyser requires 'enable-custom-content: true' in config.yml");

        int registeredCount = 0;

        for (SkullEntry entry : skullEntries) {
            try {
                extension.logger().debug("Registering skull - Type: " + entry.textureType()
                    + ", Texture: " + truncateForLogging(entry.texture()));
                event.register(entry.texture(), entry.textureType());
                registeredCount++;
                extension.logger().debug("Successfully registered skull: " + truncateForLogging(entry.texture()));
            } catch (Exception e) {
                extension.logger().error("Failed to register skull with texture '"
                    + truncateForLogging(entry.texture()) + "': " + e.getMessage());
                e.printStackTrace();
            }
        }

        extension.logger().debug("=== Skull Registration Complete: " + registeredCount + " skull(s) ===");
        if (registeredCount > 0) {
            return;
        }
        // A server with no custom-textured heads is a perfectly healthy state,
        // and this branch used to shout four WARN lines at it on every boot —
        // two of which ("the file exists", "restart after the scan") are
        // provably false when we just read and parsed the file ourselves. The
        // genuinely broken cases already log their own recovery steps in
        // loadSkullRegistry(), so repeating them here only trains operators
        // to ignore the warning that matters.
        if (registryLoaded) {
            extension.logger().debug(
                "No custom skulls to register: skulls.json parsed cleanly and lists none.");
        } else {
            extension.logger().warning(
                "No skulls registered because skulls.json could not be read;"
                    + " see the earlier recovery steps in this log.");
        }
    }

    /**
     * Creates a sample skulls.json file for reference.
     *
     * @param skullsFile the path to create the sample file
     */
    private void createSampleSkullsFile(Path skullsFile) {
        JsonObject sample = new JsonObject();
        JsonArray skulls = new JsonArray();

        // Sample profile-based skull (Base64 encoded profile JSON)
        JsonObject profileSkull = new JsonObject();
        profileSkull.addProperty("texture", "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZXhhbXBsZSJ9fX0=");
        profileSkull.addProperty("type", "PROFILE");
        // Note: Base64 encoded profile JSON for custom skull texture
        skulls.add(profileSkull);

        // Sample skin hash skull
        JsonObject skinHashSkull = new JsonObject();
        skinHashSkull.addProperty("texture", "a1b2c3d4e5f6...");
        skinHashSkull.addProperty("type", "SKIN_HASH");
        skulls.add(skinHashSkull);

        // Sample username-based skull
        JsonObject usernameSkull = new JsonObject();
        usernameSkull.addProperty("texture", "Notch");
        usernameSkull.addProperty("type", "USERNAME");
        skulls.add(usernameSkull);

        // Sample UUID-based skull
        JsonObject uuidSkull = new JsonObject();
        uuidSkull.addProperty("texture", "069a79f4-44e9-4726-a5be-fca90e38aaf5");
        uuidSkull.addProperty("type", "UUID");
        skulls.add(uuidSkull);

        sample.add("skulls", skulls);

        try {
            Files.writeString(skullsFile, GSON.toJson(sample), StandardCharsets.UTF_8);
            extension.logger().debug("Created sample skulls.json at " + skullsFile);
        } catch (IOException e) {
            extension.logger().warning("Failed to create sample skulls.json: " + e.getMessage());
        }
    }

    /**
     * Truncates a string for safe logging (to avoid logging very long base64 strings).
     *
     * @param str the string to truncate
     * @return the truncated string with ellipsis if longer than 50 characters
     */
    private String truncateForLogging(String str) {
        if (str == null) {
            return "null";
        }
        if (str.length() <= 50) {
            return str;
        }
        return str.substring(0, 47) + "...";
    }

    /**
     * Gets a string value from JSON or returns default.
     */
    private String getStringOrDefault(JsonObject obj, String key, String defaultValue) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            return obj.get(key).getAsString();
        }
        return defaultValue;
    }

    /**
     * Returns an unmodifiable list of loaded skull entries.
     *
     * @return the list of skull entries
     */
    public List<SkullEntry> getSkullEntries() {
        return Collections.unmodifiableList(skullEntries);
    }

    /**
     * Internal record for skull entry data.
     * Why: This encapsulates skull texture and type information for registration.
     */
    public record SkullEntry(
        String texture,
        SkullTextureType textureType
    ) {}
}
