/*
 * GeyserExtra Extension - Custom Items Handler
 * Loads and registers custom items from shared configuration file.
 */
package com.geyserextra.extension.handler;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineCustomItemsEvent;
import org.geysermc.geyser.api.extension.Extension;
import org.geysermc.geyser.api.item.custom.CustomItemData;
import org.geysermc.geyser.api.item.custom.CustomItemOptions;
import org.geysermc.geyser.api.item.custom.NonVanillaCustomItemData;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Handler for loading and registering custom items from shared configuration.
 *
 * Why: This handler reads item definitions from a shared JSON file (custom_items.json)
 * and registers them with Geyser to enable custom items for Bedrock players.
 * The shared file approach allows synchronization with Paper plugin.
 *
 * Texture resolution: Items are registered with their generated name as both the
 * Geyser item name and the texture key. The companion {@code AutoBedrockPackBuilder}
 * (Paper module) generates a textureless BE pack whose {@code item_texture.json}
 * points each generated key at the matching vanilla BE texture path, so BE clients
 * fall back to bundled vanilla textures without operators authoring a custom pack.
 */
public class CustomItemsHandler {

    private static final String ITEMS_FILE_NAME = "custom_items.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Extension extension;
    private final Path sharedFolder;
    private final List<ItemMapping> itemMappings;

    /**
     * Creates a new CustomItemsHandler.
     *
     * @param extension    the parent extension instance
     * @param sharedFolder the path to the shared data folder
     */
    public CustomItemsHandler(Extension extension, Path sharedFolder) {
        this.extension = extension;
        this.sharedFolder = sharedFolder;
        this.itemMappings = new ArrayList<>();
        loadItemMappings();
    }

    /**
     * Loads item mappings from the custom_items.json file.
     * Creates the data folder if it does not exist.
     */
    private void loadItemMappings() {
        extension.logger().info("=== Loading Custom Items ===");
        extension.logger().info("Data folder: " + sharedFolder.toAbsolutePath());
        extension.logger().info("Data folder exists: " + Files.exists(sharedFolder));
        Path itemsFile = sharedFolder.resolve(ITEMS_FILE_NAME);
        extension.logger().info("Items file path: " + itemsFile.toAbsolutePath());
        extension.logger().info("Items file exists: " + Files.exists(itemsFile));

        if (!Files.exists(sharedFolder)) {
            try {
                Files.createDirectories(sharedFolder);
                extension.logger().info("Created shared folder: " + sharedFolder);
            } catch (IOException e) {
                extension.logger().error("Failed to create shared folder: " + e.getMessage());
                return;
            }
        }

        if (!Files.exists(itemsFile)) {
            extension.logger().warning("No custom_items.json found at " + itemsFile);
            extension.logger().warning("Please ensure Paper plugin has run and scanned recipes first.");
            extension.logger().warning("Then restart the server for Extension to load the items.");
            createSampleItemsFile(itemsFile);
            return;
        }

        try (Reader reader = Files.newBufferedReader(itemsFile, StandardCharsets.UTF_8)) {
            String content = Files.readString(itemsFile, StandardCharsets.UTF_8);
            extension.logger().info("File content length: " + content.length() + " bytes");
            extension.logger().info("File content preview: " + content.substring(0, Math.min(200, content.length())) + "...");

            JsonObject root = JsonParser.parseString(content).getAsJsonObject();
            parseItemMappings(root);
            extension.logger().info("Loaded " + itemMappings.size() + " custom item mappings.");
        } catch (IOException e) {
            extension.logger().error("Failed to read custom_items.json: " + e.getMessage());
            e.printStackTrace();
        } catch (Exception e) {
            extension.logger().error("Failed to parse custom_items.json: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Parses item mappings from the JSON configuration.
     *
     * @param root the root JSON object containing item definitions
     */
    private void parseItemMappings(JsonObject root) {
        // Support format: { "items": { "minecraft:base_item": [ { ... }, ... ] } }
        if (root.has("items")) {
            JsonObject items = root.getAsJsonObject("items");
            for (Map.Entry<String, JsonElement> entry : items.entrySet()) {
                String baseItem = entry.getKey();
                JsonArray itemArray = entry.getValue().getAsJsonArray();

                for (JsonElement element : itemArray) {
                    JsonObject itemDef = element.getAsJsonObject();
                    ItemMapping mapping = parseItemDefinition(baseItem, itemDef);
                    if (mapping != null) {
                        itemMappings.add(mapping);
                    }
                }
            }
        }

        // Support format: { "non_vanilla_items": [ { ... }, ... ] }
        if (root.has("non_vanilla_items")) {
            JsonArray nonVanillaItems = root.getAsJsonArray("non_vanilla_items");
            for (JsonElement element : nonVanillaItems) {
                JsonObject itemDef = element.getAsJsonObject();
                ItemMapping mapping = parseNonVanillaItemDefinition(itemDef);
                if (mapping != null) {
                    itemMappings.add(mapping);
                }
            }
        }
    }

    /**
     * Parses a single item definition for vanilla item extension.
     *
     * @param baseItem the base Minecraft item identifier
     * @param itemDef the JSON object containing item properties
     * @return the parsed ItemMapping or null if parsing fails
     */
    private ItemMapping parseItemDefinition(String baseItem, JsonObject itemDef) {
        try {
            String rawName = getStringOrDefault(itemDef, "name", null);
            if (rawName == null) {
                extension.logger().warning("Item definition missing 'name' field, skipping.");
                return null;
            }
            String name = sanitizeIdentifierValue(rawName, "name");

            // Honour explicit opt-out from BE registration (e.g. operator manually disabled an item).
            boolean register = getBooleanOrDefault(itemDef, "register", true);
            if (!register) {
                extension.logger().info("Skipping item '" + name + "': register=false");
                return null;
            }

            int customModelData = getIntOrDefault(itemDef, "custom_model_data", 0);
            boolean unbreakable = getBooleanOrDefault(itemDef, "unbreakable", false);
            int damagePredicate = getIntOrDefault(itemDef, "damage_predicate", -1);
            String displayName = getStringOrDefault(itemDef, "display_name", name);
            String icon = getStringOrDefault(itemDef, "icon", name);
            boolean allowOffhand = getBooleanOrDefault(itemDef, "allow_offhand", true);
            int textureSize = getIntOrDefault(itemDef, "texture_size", 16);
            int creativeCategory = getIntOrDefault(itemDef, "creative_category", 0);
            String creativeGroup = getStringOrDefault(itemDef, "creative_group", null);

            return new ItemMapping(
                baseItem,
                name,
                customModelData,
                unbreakable,
                damagePredicate,
                displayName,
                icon,
                allowOffhand,
                textureSize,
                false,
                null,
                0,
                creativeCategory,
                creativeGroup
            );
        } catch (Exception e) {
            extension.logger().warning("Failed to parse item definition: " + e.getMessage());
            return null;
        }
    }

    /**
     * Parses a non-vanilla item definition.
     *
     * @param itemDef the JSON object containing non-vanilla item properties
     * @return the parsed ItemMapping or null if parsing fails
     */
    private ItemMapping parseNonVanillaItemDefinition(JsonObject itemDef) {
        try {
            String rawName = getStringOrDefault(itemDef, "name", null);
            String identifier = getStringOrDefault(itemDef, "identifier", null);
            int javaId = getIntOrDefault(itemDef, "java_id", -1);

            if (rawName == null || identifier == null || javaId < 0) {
                extension.logger().warning("Non-vanilla item definition missing required fields, skipping.");
                return null;
            }
            String name = sanitizeIdentifierValue(rawName, "name");

            String displayName = getStringOrDefault(itemDef, "display_name", name);
            String icon = getStringOrDefault(itemDef, "icon", name);
            boolean allowOffhand = getBooleanOrDefault(itemDef, "allow_offhand", true);
            int textureSize = getIntOrDefault(itemDef, "texture_size", 16);
            int creativeCategory = getIntOrDefault(itemDef, "creative_category", 0);
            String creativeGroup = getStringOrDefault(itemDef, "creative_group", null);

            return new ItemMapping(
                null,
                name,
                0,
                false,
                -1,
                displayName,
                icon,
                allowOffhand,
                textureSize,
                true,
                identifier,
                javaId,
                creativeCategory,
                creativeGroup
            );
        } catch (Exception e) {
            extension.logger().warning("Failed to parse non-vanilla item definition: " + e.getMessage());
            return null;
        }
    }

    /**
     * Registers all loaded custom items with the Geyser event.
     *
     * @param event the custom items definition event from Geyser
     */
    public void registerItems(GeyserDefineCustomItemsEvent event) {
        extension.logger().info("=== Custom Items Registration ===");
        extension.logger().info("Loaded item mappings: " + itemMappings.size());
        extension.logger().info("Shared folder path: " + sharedFolder.toAbsolutePath());
        int registeredCount = 0;

        for (ItemMapping mapping : itemMappings) {
            try {
                // Why: non_vanilla items have their own identifiers and don't consume
                // model IDs that would affect vanilla item textures
                if (mapping.isNonVanilla) {
                    registeredCount += registerNonVanillaItem(event, mapping);
                    continue;
                }

                registeredCount += registerVanillaItem(event, mapping);
            } catch (Exception e) {
                extension.logger().warning("Failed to register item '"
                    + mapping.name() + "': " + e.getMessage());
                e.printStackTrace();
            }
        }

        extension.logger().info("=== Registration Complete: " + registeredCount + " registered ===");
    }

    /**
     * Registers a non-vanilla custom item.
     *
     * @return 1 if registration succeeded
     */
    private int registerNonVanillaItem(GeyserDefineCustomItemsEvent event, ItemMapping mapping) {
        NonVanillaCustomItemData data = buildNonVanillaItemData(mapping);
        extension.logger().info("Registering NonVanilla: " + mapping.name()
            + " (identifier=" + mapping.identifier()
            + ", javaId=" + mapping.javaId() + ")");
        event.register(data);
        extension.logger().info("Successfully registered: " + mapping.name());
        return 1;
    }

    /**
     * Registers a vanilla custom item extension.
     *
     * @return 1 if registration succeeded
     */
    private int registerVanillaItem(GeyserDefineCustomItemsEvent event, ItemMapping mapping) {
        CustomItemData data = buildCustomItemData(mapping);
        extension.logger().info("Registering: " + mapping.name()
            + " (base: " + mapping.baseItem()
            + ", CMD: " + mapping.customModelData() + ")");
        event.register(mapping.baseItem, data);
        extension.logger().info("Successfully registered: " + mapping.name());
        return 1;
    }

    /**
     * Builds CustomItemData for a vanilla item extension.
     *
     * @param mapping the item mapping configuration
     * @return the built CustomItemData
     */
    private CustomItemData buildCustomItemData(ItemMapping mapping) {
        CustomItemOptions.Builder optionsBuilder = CustomItemOptions.builder();

        if (mapping.customModelData > 0) {
            optionsBuilder.customModelData(mapping.customModelData);
        }
        if (mapping.unbreakable) {
            optionsBuilder.unbreakable(true);
        }
        if (mapping.damagePredicate >= 0) {
            optionsBuilder.damagePredicate(mapping.damagePredicate);
        }

        CustomItemOptions options = optionsBuilder.build();

        CustomItemData.Builder builder = CustomItemData.builder()
            .name(mapping.name)
            .customItemOptions(options);

        if (mapping.displayName != null) {
            builder.displayName(mapping.displayName);
        }
        if (mapping.icon != null) {
            builder.icon(mapping.icon);
        }
        builder.allowOffhand(mapping.allowOffhand);
        builder.textureSize(mapping.textureSize);

        // Set creative category for recipe book visibility
        if (mapping.creativeCategory > 0 && mapping.creativeCategory <= 5) {
            builder.creativeCategory(mapping.creativeCategory);
            if (mapping.creativeGroup != null && !mapping.creativeGroup.isBlank()) {
                builder.creativeGroup(mapping.creativeGroup);
            }
        }

        return builder.build();
    }

    /**
     * Builds NonVanillaCustomItemData for a modded/custom item.
     *
     * @param mapping the item mapping configuration
     * @return the built NonVanillaCustomItemData
     */
    private NonVanillaCustomItemData buildNonVanillaItemData(ItemMapping mapping) {
        NonVanillaCustomItemData.Builder builder = NonVanillaCustomItemData.builder()
            .name(mapping.name)
            .identifier(mapping.identifier)
            .javaId(mapping.javaId);

        if (mapping.displayName != null) {
            builder.displayName(mapping.displayName);
        }
        if (mapping.icon != null) {
            builder.icon(mapping.icon);
        }
        builder.allowOffhand(mapping.allowOffhand);
        builder.textureSize(mapping.textureSize);

        // Set creative category for recipe book visibility
        if (mapping.creativeCategory > 0 && mapping.creativeCategory <= 5) {
            builder.creativeCategory(mapping.creativeCategory);
            if (mapping.creativeGroup != null && !mapping.creativeGroup.isBlank()) {
                builder.creativeGroup(mapping.creativeGroup);
            }
        }

        return builder.build();
    }

    /**
     * Creates a sample custom_items.json file for reference.
     *
     * @param itemsFile the path to create the sample file
     */
    private void createSampleItemsFile(Path itemsFile) {
        JsonObject sample = new JsonObject();

        // Sample vanilla item extension
        JsonObject items = new JsonObject();
        JsonArray ironSwordItems = new JsonArray();
        JsonObject sampleItem = new JsonObject();
        sampleItem.addProperty("name", "custom_sword");
        sampleItem.addProperty("custom_model_data", 1);
        sampleItem.addProperty("display_name", "Custom Sword");
        sampleItem.addProperty("icon", "custom_sword");
        sampleItem.addProperty("allow_offhand", false);
        ironSwordItems.add(sampleItem);
        items.add("minecraft:iron_sword", ironSwordItems);
        sample.add("items", items);

        // Sample non-vanilla items
        JsonArray nonVanillaItems = new JsonArray();
        JsonObject nonVanillaSample = new JsonObject();
        nonVanillaSample.addProperty("name", "modded_item");
        nonVanillaSample.addProperty("identifier", "mymod:modded_item");
        nonVanillaSample.addProperty("java_id", 10000);
        nonVanillaSample.addProperty("display_name", "Modded Item");
        nonVanillaSample.addProperty("icon", "modded_item");
        nonVanillaItems.add(nonVanillaSample);
        sample.add("non_vanilla_items", nonVanillaItems);

        try {
            Files.writeString(itemsFile, GSON.toJson(sample), StandardCharsets.UTF_8);
            extension.logger().info("Created sample custom_items.json at " + itemsFile);
        } catch (IOException e) {
            extension.logger().warning("Failed to create sample custom_items.json: " + e.getMessage());
        }
    }

    /**
     * Sanitizes a name/identifier value for Geyser compatibility.
     * Geyser only allows [a-z0-9_\-./]+ in identifier values.
     * Replaces invalid characters (e.g. ':') with '_' and logs a warning.
     */
    private String sanitizeIdentifierValue(String value, String fieldName) {
        if (value == null) {
            return null;
        }
        String sanitized = value.toLowerCase().replaceAll("[^a-z0-9_\\-./]", "_");
        if (!sanitized.equals(value)) {
            extension.logger().warning("Sanitized " + fieldName + " '" + value + "' -> '" + sanitized
                + "' (invalid characters replaced with '_')");
        }
        return sanitized;
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
     * Gets an int value from JSON or returns default.
     */
    private int getIntOrDefault(JsonObject obj, String key, int defaultValue) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            return obj.get(key).getAsInt();
        }
        return defaultValue;
    }

    /**
     * Gets a boolean value from JSON or returns default.
     */
    private boolean getBooleanOrDefault(JsonObject obj, String key, boolean defaultValue) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            return obj.get(key).getAsBoolean();
        }
        return defaultValue;
    }

    /**
     * Returns an unmodifiable list of loaded item mappings.
     *
     * @return the list of item mappings
     */
    public List<ItemMapping> getItemMappings() {
        return Collections.unmodifiableList(itemMappings);
    }

    /**
     * Internal record for item mapping data.
     * Why: This encapsulates all item configuration properties for both vanilla and non-vanilla items.
     *
     * @param creativeCategory Bedrock creative inventory category (1-5), 0 = none
     * @param creativeGroup Bedrock creative group for sub-categorization
     */
    public record ItemMapping(
        String baseItem,
        String name,
        int customModelData,
        boolean unbreakable,
        int damagePredicate,
        String displayName,
        String icon,
        boolean allowOffhand,
        int textureSize,
        boolean isNonVanilla,
        String identifier,
        int javaId,
        int creativeCategory,
        String creativeGroup
    ) {}
}
