package com.geyserextra.core.registry;

import com.geyserextra.core.api.CustomItemMapping;
import com.geyserextra.core.util.JsonUtil;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Thread-safe registry for managing custom item mappings.
 *
 * This registry provides CRUD operations for CustomItemMapping objects
 * with thread-safe access using ConcurrentHashMap and ReadWriteLock
 * for batch operations.
 *
 * Mappings can be looked up by name or by customModelData value.
 */
public final class ItemMappingRegistry {

    private static final Logger LOGGER = Logger.getLogger(ItemMappingRegistry.class.getName());
    private static final Type MAPPING_LIST_TYPE = new TypeToken<List<CustomItemMapping>>() {}.getType();

    /**
     * Primary storage: name -> CustomItemMapping
     */
    private final Map<String, CustomItemMapping> mappingsByName;

    /**
     * Secondary index: baseItem:customModelData -> CustomItemMapping
     * Composite key format allows efficient lookup by item type and CMD value.
     */
    private final Map<String, CustomItemMapping> mappingsByModelData;

    /**
     * Lock for batch operations that require consistency across multiple maps.
     */
    private final ReadWriteLock batchLock;

    /**
     * Creates a new empty ItemMappingRegistry.
     */
    public ItemMappingRegistry() {
        this.mappingsByName = new ConcurrentHashMap<>();
        this.mappingsByModelData = new ConcurrentHashMap<>();
        this.batchLock = new ReentrantReadWriteLock();
    }

    /**
     * Registers a new custom item mapping.
     *
     * If a mapping with the same name already exists, it will be replaced.
     * The secondary index will also be updated accordingly.
     *
     * @param mapping The mapping to register
     * @return The previously registered mapping with the same name, or null if none existed
     * @throws NullPointerException if mapping is null
     */
    public CustomItemMapping register(CustomItemMapping mapping) {
        Objects.requireNonNull(mapping, "mapping must not be null");

        batchLock.writeLock().lock();
        try {
            // Remove old secondary index entry if updating existing mapping
            CustomItemMapping existing = mappingsByName.get(mapping.name());
            if (existing != null) {
                String oldKey = createModelDataKey(existing.baseItem(), existing.customModelData());
                mappingsByModelData.remove(oldKey);
            }

            // Register in primary storage
            CustomItemMapping previous = mappingsByName.put(mapping.name(), mapping);

            // Register in secondary index
            String newKey = createModelDataKey(mapping.baseItem(), mapping.customModelData());
            mappingsByModelData.put(newKey, mapping);

            LOGGER.fine(() -> "Registered item mapping: " + mapping.name());
            return previous;
        } finally {
            batchLock.writeLock().unlock();
        }
    }

    /**
     * Registers multiple mappings at once.
     *
     * This is more efficient than calling register() multiple times
     * as it acquires the write lock only once.
     *
     * @param mappings The collection of mappings to register
     * @throws NullPointerException if mappings is null or contains null elements
     */
    public void registerAll(Collection<CustomItemMapping> mappings) {
        Objects.requireNonNull(mappings, "mappings must not be null");
        mappings.forEach(m -> Objects.requireNonNull(m, "mapping in collection must not be null"));

        batchLock.writeLock().lock();
        try {
            for (CustomItemMapping mapping : mappings) {
                // Remove old secondary index entry if updating existing mapping
                CustomItemMapping existing = mappingsByName.get(mapping.name());
                if (existing != null) {
                    String oldKey = createModelDataKey(existing.baseItem(), existing.customModelData());
                    mappingsByModelData.remove(oldKey);
                }

                mappingsByName.put(mapping.name(), mapping);

                String newKey = createModelDataKey(mapping.baseItem(), mapping.customModelData());
                mappingsByModelData.put(newKey, mapping);
            }

            LOGGER.fine(() -> "Registered " + mappings.size() + " item mappings");
        } finally {
            batchLock.writeLock().unlock();
        }
    }

    /**
     * Unregisters a custom item mapping by name.
     *
     * @param name The name of the mapping to unregister
     * @return The unregistered mapping, or null if no mapping was found
     * @throws NullPointerException if name is null
     */
    public CustomItemMapping unregister(String name) {
        Objects.requireNonNull(name, "name must not be null");

        batchLock.writeLock().lock();
        try {
            CustomItemMapping removed = mappingsByName.remove(name);
            if (removed != null) {
                String key = createModelDataKey(removed.baseItem(), removed.customModelData());
                mappingsByModelData.remove(key);
                LOGGER.fine(() -> "Unregistered item mapping: " + name);
            }
            return removed;
        } finally {
            batchLock.writeLock().unlock();
        }
    }

    /**
     * Gets all registered mappings.
     *
     * @return An unmodifiable collection of all registered mappings
     */
    public Collection<CustomItemMapping> getMappings() {
        batchLock.readLock().lock();
        try {
            return Collections.unmodifiableCollection(new ArrayList<>(mappingsByName.values()));
        } finally {
            batchLock.readLock().unlock();
        }
    }

    /**
     * Gets a mapping by its name.
     *
     * @param name The name of the mapping to retrieve
     * @return An Optional containing the mapping if found, or empty if not found
     * @throws NullPointerException if name is null
     */
    public Optional<CustomItemMapping> getByName(String name) {
        Objects.requireNonNull(name, "name must not be null");
        return Optional.ofNullable(mappingsByName.get(name));
    }

    /**
     * Gets a mapping by base item and custom model data value.
     *
     * @param baseItem        The base item identifier (e.g., "minecraft:diamond_sword")
     * @param customModelData The CustomModelData value
     * @return An Optional containing the mapping if found, or empty if not found
     * @throws NullPointerException if baseItem is null
     */
    public Optional<CustomItemMapping> getByCustomModelData(String baseItem, int customModelData) {
        Objects.requireNonNull(baseItem, "baseItem must not be null");
        String key = createModelDataKey(baseItem, customModelData);
        return Optional.ofNullable(mappingsByModelData.get(key));
    }

    /**
     * Gets all mappings for a specific base item.
     *
     * @param baseItem The base item identifier
     * @return A list of all mappings for the specified base item
     * @throws NullPointerException if baseItem is null
     */
    public List<CustomItemMapping> getByBaseItem(String baseItem) {
        Objects.requireNonNull(baseItem, "baseItem must not be null");

        batchLock.readLock().lock();
        try {
            return mappingsByName.values().stream()
                .filter(m -> m.baseItem().equals(baseItem))
                .collect(Collectors.toUnmodifiableList());
        } finally {
            batchLock.readLock().unlock();
        }
    }

    /**
     * Checks if a mapping with the given name exists.
     *
     * @param name The name to check
     * @return true if a mapping with the given name exists
     * @throws NullPointerException if name is null
     */
    public boolean contains(String name) {
        Objects.requireNonNull(name, "name must not be null");
        return mappingsByName.containsKey(name);
    }

    /**
     * Gets the number of registered mappings.
     *
     * @return The number of registered mappings
     */
    public int size() {
        return mappingsByName.size();
    }

    /**
     * Checks if the registry is empty.
     *
     * @return true if no mappings are registered
     */
    public boolean isEmpty() {
        return mappingsByName.isEmpty();
    }

    /**
     * Clears all registered mappings.
     */
    public void clear() {
        batchLock.writeLock().lock();
        try {
            mappingsByName.clear();
            mappingsByModelData.clear();
            LOGGER.fine("Cleared all item mappings");
        } finally {
            batchLock.writeLock().unlock();
        }
    }

    /**
     * Saves all mappings to a JSON file in Extension-compatible format.
     *
     * Format:
     * {
     *   "items": {
     *     "minecraft:diamond_sword": [
     *       { "name": "...", "custom_model_data": 1, ... }
     *     ]
     *   }
     * }
     *
     * Items are sorted by customModelData value within each base item group
     * to ensure consistent ordering across saves. This is important because
     * Geyser may use registration order for texture mapping.
     *
     * @param path The path to save the mappings to
     * @throws IOException if an I/O error occurs
     * @throws NullPointerException if path is null
     */
    public void save(Path path) throws IOException {
        Objects.requireNonNull(path, "path must not be null");

        batchLock.readLock().lock();
        try {
            // Group mappings by base item for Extension format
            Map<String, List<CustomItemMapping>> itemsByBase = new java.util.LinkedHashMap<>();

            // First, group all mappings by base item
            for (CustomItemMapping mapping : mappingsByName.values()) {
                String baseItem = mapping.baseItem();
                itemsByBase.computeIfAbsent(baseItem, k -> new ArrayList<>())
                    .add(mapping);
            }

            // Sort base items alphabetically, then sort items within each group by CMD
            Map<String, List<Map<String, Object>>> sortedItemsByBase = new java.util.LinkedHashMap<>();
            itemsByBase.keySet().stream().sorted().forEach(baseItem -> {
                List<CustomItemMapping> items = itemsByBase.get(baseItem);
                // Sort by customModelData to ensure consistent order
                items.sort(java.util.Comparator.comparingInt(CustomItemMapping::customModelData));
                List<Map<String, Object>> jsonItems = items.stream()
                    .map(this::convertMappingToJson)
                    .collect(Collectors.toList());
                sortedItemsByBase.put(baseItem, jsonItems);
            });

            Map<String, Object> root = new java.util.LinkedHashMap<>();
            root.put("items", sortedItemsByBase);

            String json = JsonUtil.toPrettyJson(root);

            // Ensure parent directories exist
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            Files.writeString(path, json);
            LOGGER.info(() -> "Saved " + mappingsByName.size() + " item mappings to " + path);
        } finally {
            batchLock.readLock().unlock();
        }
    }

    /**
     * Converts a CustomItemMapping to a JSON-compatible map for Extension format.
     *
     * @param mapping The mapping to convert
     * @return A map representing the JSON structure
     */
    private Map<String, Object> convertMappingToJson(CustomItemMapping mapping) {
        Map<String, Object> json = new java.util.LinkedHashMap<>();
        json.put("name", mapping.name());
        json.put("custom_model_data", mapping.customModelData());

        if (mapping.unbreakable()) {
            json.put("unbreakable", true);
        }
        if (mapping.hasDisplayName()) {
            json.put("display_name", mapping.displayName());
        }
        if (mapping.hasIconPath()) {
            json.put("icon", mapping.iconPath());
        }
        if (mapping.hasCreativeCategory()) {
            json.put("creative_category", mapping.creativeCategory());
        }
        if (mapping.hasCreativeGroup()) {
            json.put("creative_group", mapping.creativeGroup());
        }

        // Why: register=false by default for auto-detected items.
        // Users must set register=true after preparing the BE texture.
        // This prevents items without textures from being registered with Geyser,
        // which would cause them to appear transparent on Bedrock clients.
        json.put("register", mapping.register());

        return json;
    }

    /**
     * Loads mappings from a JSON file.
     *
     * Supports both legacy array format and new Extension-compatible format:
     * - Legacy: [ { ... }, { ... } ]
     * - New: { "items": { "minecraft:item": [ { ... } ] } }
     *
     * Existing mappings are cleared before loading.
     *
     * @param path The path to load the mappings from
     * @throws IOException if an I/O error occurs or the file does not exist
     * @throws NullPointerException if path is null
     */
    public void load(Path path) throws IOException {
        Objects.requireNonNull(path, "path must not be null");

        if (!Files.exists(path)) {
            throw new IOException("File does not exist: " + path);
        }

        String json = Files.readString(path);
        List<CustomItemMapping> mappings = parseJsonToMappings(json);

        if (mappings == null || mappings.isEmpty()) {
            LOGGER.warning(() -> "Loaded empty or null mapping list from " + path);
            return;
        }

        batchLock.writeLock().lock();
        try {
            mappingsByName.clear();
            mappingsByModelData.clear();

            for (CustomItemMapping mapping : mappings) {
                if (mapping != null) {
                    mappingsByName.put(mapping.name(), mapping);
                    String key = createModelDataKey(mapping.baseItem(), mapping.customModelData());
                    mappingsByModelData.put(key, mapping);
                }
            }

            LOGGER.info(() -> "Loaded " + mappingsByName.size() + " item mappings from " + path);
        } finally {
            batchLock.writeLock().unlock();
        }
    }

    /**
     * Parses JSON to list of CustomItemMapping, supporting multiple formats.
     *
     * @param json The JSON string to parse
     * @return List of mappings, or empty list if parsing fails
     */
    private List<CustomItemMapping> parseJsonToMappings(String json) {
        List<CustomItemMapping> result = new ArrayList<>();

        try {
            // Try parsing as Extension format first: { "items": { ... } }
            com.google.gson.JsonElement element = com.google.gson.JsonParser.parseString(json);

            if (element.isJsonObject()) {
                com.google.gson.JsonObject root = element.getAsJsonObject();

                if (root.has("items")) {
                    com.google.gson.JsonObject items = root.getAsJsonObject("items");

                    for (Map.Entry<String, com.google.gson.JsonElement> entry : items.entrySet()) {
                        String baseItem = entry.getKey();
                        com.google.gson.JsonArray itemArray = entry.getValue().getAsJsonArray();

                        for (com.google.gson.JsonElement itemEl : itemArray) {
                            com.google.gson.JsonObject itemDef = itemEl.getAsJsonObject();
                            CustomItemMapping mapping = parseItemFromJson(baseItem, itemDef);
                            if (mapping != null) {
                                result.add(mapping);
                            }
                        }
                    }
                    return result;
                }
            }

            // Fallback: try legacy array format
            if (element.isJsonArray()) {
                return JsonUtil.fromJson(json, MAPPING_LIST_TYPE);
            }

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to parse mappings JSON", e);
        }

        return result;
    }

    /**
     * Parses a single item definition from JSON.
     *
     * @param baseItem The base item identifier
     * @param itemDef The JSON object containing item properties
     * @return The parsed CustomItemMapping, or null if parsing fails
     */
    private CustomItemMapping parseItemFromJson(String baseItem, com.google.gson.JsonObject itemDef) {
        try {
            String name = getStringOrNull(itemDef, "name");
            if (name == null) {
                return null;
            }

            int customModelData = getIntOrDefault(itemDef, "custom_model_data", 0);
            boolean unbreakable = getBooleanOrDefault(itemDef, "unbreakable", false);
            String displayName = getStringOrNull(itemDef, "display_name");
            String iconPath = getStringOrNull(itemDef, "icon");
            int creativeCategory = getIntOrDefault(itemDef, "creative_category", 0);
            String creativeGroup = getStringOrNull(itemDef, "creative_group");
            // register defaults to true: the auto-generated BE pack now ships an
            // item_texture.json entry for every mapping pointing at the base item's
            // vanilla texture, so registration is safe even without an authored pack.
            boolean register = getBooleanOrDefault(itemDef, "register", true);

            return new CustomItemMapping(
                name,
                baseItem,
                customModelData,
                unbreakable,
                displayName,
                iconPath,
                creativeCategory,
                creativeGroup,
                register
            );
        } catch (Exception e) {
            LOGGER.warning(() -> "Failed to parse item definition: " + e.getMessage());
            return null;
        }
    }

    private String getStringOrNull(com.google.gson.JsonObject obj, String key) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            return obj.get(key).getAsString();
        }
        return null;
    }

    private int getIntOrDefault(com.google.gson.JsonObject obj, String key, int defaultValue) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            return obj.get(key).getAsInt();
        }
        return defaultValue;
    }

    private boolean getBooleanOrDefault(com.google.gson.JsonObject obj, String key, boolean defaultValue) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            return obj.get(key).getAsBoolean();
        }
        return defaultValue;
    }

    /**
     * Loads mappings from a JSON file if it exists.
     *
     * If the file does not exist, this method does nothing and returns false.
     *
     * @param path The path to load the mappings from
     * @return true if mappings were loaded, false if the file does not exist
     * @throws NullPointerException if path is null
     */
    public boolean loadIfExists(Path path) {
        Objects.requireNonNull(path, "path must not be null");

        if (!Files.exists(path)) {
            LOGGER.fine(() -> "Mapping file does not exist: " + path);
            return false;
        }

        try {
            load(path);
            return true;
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to load mappings from " + path, e);
            return false;
        }
    }

    /**
     * Creates a composite key for the customModelData index.
     *
     * @param baseItem        The base item identifier
     * @param customModelData The CustomModelData value
     * @return The composite key
     */
    private String createModelDataKey(String baseItem, int customModelData) {
        return baseItem + ":" + customModelData;
    }
}
