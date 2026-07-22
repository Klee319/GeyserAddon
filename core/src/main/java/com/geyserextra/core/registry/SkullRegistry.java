package com.geyserextra.core.registry;

import com.geyserextra.core.api.SkullData;
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
 * Thread-safe registry for managing skull texture data.
 *
 * This registry provides CRUD operations for SkullData objects
 * with thread-safe access using ConcurrentHashMap and ReadWriteLock
 * for batch operations.
 *
 * Skulls are primarily indexed by their texture hash.
 */
public final class SkullRegistry {

    private static final Logger LOGGER = Logger.getLogger(SkullRegistry.class.getName());
    private static final Type SKULL_LIST_TYPE = new TypeToken<List<SkullData>>() {}.getType();

    /**
     * Primary storage: textureHash -> SkullData
     */
    private final Map<String, SkullData> skullsByHash;

    /**
     * Lock for batch operations that require consistency.
     */
    private final ReadWriteLock batchLock;

    /**
     * Creates a new empty SkullRegistry.
     */
    public SkullRegistry() {
        this.skullsByHash = new ConcurrentHashMap<>();
        this.batchLock = new ReentrantReadWriteLock();
    }

    /**
     * Registers a new skull data entry.
     *
     * If skull data with the same texture hash already exists, it will be replaced.
     *
     * @param skullData The skull data to register
     * @return The previously registered skull data with the same hash, or null if none existed
     * @throws NullPointerException if skullData is null
     */
    public SkullData register(SkullData skullData) {
        Objects.requireNonNull(skullData, "skullData must not be null");

        SkullData previous = skullsByHash.put(skullData.textureHash(), skullData);
        LOGGER.fine(() -> "Registered skull data: " + skullData.textureHash());
        return previous;
    }

    /**
     * Registers multiple skull data entries at once.
     *
     * This is more efficient than calling register() multiple times
     * as it acquires the write lock only once for logging consistency.
     *
     * @param skulls The collection of skull data to register
     * @throws NullPointerException if skulls is null or contains null elements
     */
    public void registerAll(Collection<SkullData> skulls) {
        Objects.requireNonNull(skulls, "skulls must not be null");
        skulls.forEach(s -> Objects.requireNonNull(s, "skull in collection must not be null"));

        batchLock.writeLock().lock();
        try {
            for (SkullData skull : skulls) {
                skullsByHash.put(skull.textureHash(), skull);
            }
            LOGGER.fine(() -> "Registered " + skulls.size() + " skull data entries");
        } finally {
            batchLock.writeLock().unlock();
        }
    }

    /**
     * Unregisters skull data by texture hash.
     *
     * @param textureHash The texture hash of the skull data to unregister
     * @return The unregistered skull data, or null if no skull was found
     * @throws NullPointerException if textureHash is null
     */
    public SkullData unregister(String textureHash) {
        Objects.requireNonNull(textureHash, "textureHash must not be null");

        SkullData removed = skullsByHash.remove(textureHash);
        if (removed != null) {
            LOGGER.fine(() -> "Unregistered skull data: " + textureHash);
        }
        return removed;
    }

    /**
     * Gets all registered skull data.
     *
     * @return An unmodifiable collection of all registered skull data
     */
    public Collection<SkullData> getSkulls() {
        batchLock.readLock().lock();
        try {
            return Collections.unmodifiableCollection(new ArrayList<>(skullsByHash.values()));
        } finally {
            batchLock.readLock().unlock();
        }
    }

    /**
     * Gets skull data by its texture hash.
     *
     * @param textureHash The texture hash of the skull data to retrieve
     * @return An Optional containing the skull data if found, or empty if not found
     * @throws NullPointerException if textureHash is null
     */
    public Optional<SkullData> getByTextureHash(String textureHash) {
        Objects.requireNonNull(textureHash, "textureHash must not be null");
        return Optional.ofNullable(skullsByHash.get(textureHash));
    }

    /**
     * Gets all skull data of a specific type.
     *
     * @param type The skull texture type to filter by
     * @return A list of all skull data with the specified type
     * @throws NullPointerException if type is null
     */
    public List<SkullData> getByType(SkullData.SkullTextureType type) {
        Objects.requireNonNull(type, "type must not be null");

        batchLock.readLock().lock();
        try {
            return skullsByHash.values().stream()
                .filter(s -> s.type() == type)
                .collect(Collectors.toUnmodifiableList());
        } finally {
            batchLock.readLock().unlock();
        }
    }

    /**
     * Checks if skull data with the given texture hash exists.
     *
     * @param textureHash The texture hash to check
     * @return true if skull data with the given hash exists
     * @throws NullPointerException if textureHash is null
     */
    public boolean contains(String textureHash) {
        Objects.requireNonNull(textureHash, "textureHash must not be null");
        return skullsByHash.containsKey(textureHash);
    }

    /**
     * Gets the number of registered skull data entries.
     *
     * @return The number of registered skull data entries
     */
    public int size() {
        return skullsByHash.size();
    }

    /**
     * Checks if the registry is empty.
     *
     * @return true if no skull data is registered
     */
    public boolean isEmpty() {
        return skullsByHash.isEmpty();
    }

    /**
     * Clears all registered skull data.
     */
    public void clear() {
        batchLock.writeLock().lock();
        try {
            skullsByHash.clear();
            LOGGER.fine("Cleared all skull data");
        } finally {
            batchLock.writeLock().unlock();
        }
    }

    /**
     * Saves all skull data to a JSON file in the format expected by Geyser Extension.
     *
     * Format:
     * {
     *   "skulls": [
     *     { "texture": "hash_or_profile", "type": "SKIN_HASH|PROFILE|USERNAME|UUID" }
     *   ]
     * }
     *
     * @param path The path to save the skull data to
     * @throws IOException if an I/O error occurs
     * @throws NullPointerException if path is null
     */
    public void save(Path path) throws IOException {
        Objects.requireNonNull(path, "path must not be null");

        batchLock.readLock().lock();
        try {
            // Build the expected format: { "skulls": [ { "texture": "...", "type": "..." } ] }
            List<Map<String, String>> skullsList = new ArrayList<>();
            for (SkullData skull : skullsByHash.values()) {
                Map<String, String> skullEntry = new java.util.LinkedHashMap<>();
                // Use texture hash as the texture value
                skullEntry.put("texture", skull.textureHash());
                // Preserve the original type from the skull data
                skullEntry.put("type", skull.type().name());
                skullsList.add(skullEntry);
            }

            Map<String, Object> root = new java.util.LinkedHashMap<>();
            root.put("skulls", skullsList);

            String json = JsonUtil.toPrettyJson(root);

            // Ensure parent directories exist
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            Files.writeString(path, json);
            LOGGER.fine(() -> "Saved " + skullsList.size() + " skull data entries to " + path);
        } finally {
            batchLock.readLock().unlock();
        }
    }

    /**
     * Loads skull data from a JSON file.
     *
     * Supports both formats:
     * - New format: { "skulls": [ { "texture": "...", "type": "..." } ] }
     * - Legacy format: [ { "textureHash": "...", ... } ]
     *
     * Existing skull data is cleared before loading.
     *
     * @param path The path to load the skull data from
     * @throws IOException if an I/O error occurs or the file does not exist
     * @throws NullPointerException if path is null
     */
    public void load(Path path) throws IOException {
        Objects.requireNonNull(path, "path must not be null");

        if (!Files.exists(path)) {
            throw new IOException("File does not exist: " + path);
        }

        String json = Files.readString(path);
        List<SkullData> skulls = parseSkullsJson(json);

        if (skulls == null || skulls.isEmpty()) {
            LOGGER.warning(() -> "Loaded empty or null skull list from " + path);
            return;
        }

        batchLock.writeLock().lock();
        try {
            skullsByHash.clear();

            for (SkullData skull : skulls) {
                if (skull != null) {
                    skullsByHash.put(skull.textureHash(), skull);
                }
            }

            LOGGER.fine(() -> "Loaded " + skullsByHash.size() + " skull data entries from " + path);
        } finally {
            batchLock.writeLock().unlock();
        }
    }

    /**
     * Parses skull data from JSON, supporting multiple formats.
     *
     * @param json The JSON string to parse
     * @return List of SkullData, or empty list if parsing fails
     */
    private List<SkullData> parseSkullsJson(String json) {
        List<SkullData> result = new ArrayList<>();

        try {
            com.google.gson.JsonElement element = com.google.gson.JsonParser.parseString(json);

            // New format: { "skulls": [...] }
            if (element.isJsonObject()) {
                com.google.gson.JsonObject root = element.getAsJsonObject();

                if (root.has("skulls")) {
                    com.google.gson.JsonArray skullsArray = root.getAsJsonArray("skulls");

                    for (com.google.gson.JsonElement skullEl : skullsArray) {
                        com.google.gson.JsonObject skullObj = skullEl.getAsJsonObject();
                        SkullData skull = parseSkullFromJson(skullObj);
                        if (skull != null) {
                            result.add(skull);
                        }
                    }
                    return result;
                }
            }

            // Legacy format: direct array
            if (element.isJsonArray()) {
                return JsonUtil.fromJson(json, SKULL_LIST_TYPE);
            }

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to parse skulls JSON", e);
        }

        return result;
    }

    /**
     * Parses a single skull entry from JSON.
     *
     * @param skullObj The JSON object containing skull data
     * @return SkullData or null if parsing fails
     */
    private SkullData parseSkullFromJson(com.google.gson.JsonObject skullObj) {
        try {
            // New format uses "texture" and "type"
            String texture = null;
            if (skullObj.has("texture") && !skullObj.get("texture").isJsonNull()) {
                texture = skullObj.get("texture").getAsString();
            }
            // Legacy format uses "textureHash"
            if (texture == null && skullObj.has("textureHash") && !skullObj.get("textureHash").isJsonNull()) {
                texture = skullObj.get("textureHash").getAsString();
            }

            if (texture == null || texture.isBlank()) {
                return null;
            }

            // Get texture URL if present
            String textureUrl = null;
            if (skullObj.has("textureUrl") && !skullObj.get("textureUrl").isJsonNull()) {
                textureUrl = skullObj.get("textureUrl").getAsString();
            }

            // Get type if present
            SkullData.SkullTextureType type = SkullData.SkullTextureType.SKIN_HASH;
            if (skullObj.has("type") && !skullObj.get("type").isJsonNull()) {
                String typeStr = skullObj.get("type").getAsString();
                try {
                    type = SkullData.SkullTextureType.valueOf(typeStr);
                } catch (IllegalArgumentException e) {
                    // Use default
                }
            }

            return new SkullData(texture, textureUrl, type);
        } catch (Exception e) {
            LOGGER.warning(() -> "Failed to parse skull entry: " + e.getMessage());
            return null;
        }
    }

    /**
     * Loads skull data from a JSON file if it exists.
     *
     * If the file does not exist, this method does nothing and returns false.
     *
     * @param path The path to load the skull data from
     * @return true if skull data was loaded, false if the file does not exist
     * @throws NullPointerException if path is null
     */
    public boolean loadIfExists(Path path) {
        Objects.requireNonNull(path, "path must not be null");

        if (!Files.exists(path)) {
            LOGGER.fine(() -> "Skull data file does not exist: " + path);
            return false;
        }

        try {
            load(path);
            return true;
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to load skull data from " + path, e);
            return false;
        }
    }

    /**
     * Gets all unique texture URLs from registered skull data.
     *
     * @return A list of unique texture URLs
     */
    public List<String> getAllTextureUrls() {
        batchLock.readLock().lock();
        try {
            return skullsByHash.values().stream()
                .map(SkullData::textureUrl)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toUnmodifiableList());
        } finally {
            batchLock.readLock().unlock();
        }
    }
}
