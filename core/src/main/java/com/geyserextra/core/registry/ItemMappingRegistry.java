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
     *
     * <p>Only populated when {@code customModelData > 0}. CMD-less PDC-only
     * mappings live exclusively in {@link #mappingsByPdcKey}. This split keeps
     * {@code getByCustomModelData} from accidentally returning a PDC entry
     * when callers pass {@code customModelData = 0} as a sentinel.</p>
     */
    private final Map<String, CustomItemMapping> mappingsByModelData;

    /**
     * Tertiary index: baseItem::pdc::pdcIdentifier -> CustomItemMapping.
     *
     * <p>Mirrors {@link #mappingsByModelData} but keyed on the stable PDC
     * identifier (e.g. {@code "oraxen:fire_sword"}). Only populated when
     * the mapping has a non-blank {@link CustomItemMapping#pdcIdentifier()}.</p>
     */
    private final Map<String, CustomItemMapping> mappingsByPdcKey;
    private final Map<String, CustomItemMapping> mappingsByItemModel;

    /**
     * Lock for batch operations that require consistency across multiple maps.
     */
    private final ReadWriteLock batchLock;

    /**
     * Set when a load found the file present but unparseable.
     *
     * <p>This is the fail-closed latch for the全消失 path. The ledger is a
     * persistent record that cannot be regenerated — PDC-path entries only
     * come back by observing a live {@code ItemStack}, so a startup that
     * silently starts from an empty registry and then saves destroys data no
     * process can restore. While this flag is set, {@link #save(Path)} refuses
     * to overwrite so the damaged file (and its {@code .bak}) survive for
     * manual recovery.</p>
     */
    private volatile boolean loadFailed;

    /**
     * Creates a new empty ItemMappingRegistry.
     */
    public ItemMappingRegistry() {
        this.mappingsByName = new ConcurrentHashMap<>();
        this.mappingsByModelData = new ConcurrentHashMap<>();
        this.mappingsByPdcKey = new ConcurrentHashMap<>();
        this.mappingsByItemModel = new ConcurrentHashMap<>();
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
            // Remove old secondary/tertiary index entries if updating an existing mapping
            CustomItemMapping existing = mappingsByName.get(mapping.name());
            if (existing != null) {
                if (existing.customModelData() > 0) {
                    String oldCmdKey = createModelDataKey(existing.baseItem(), existing.customModelData());
                    mappingsByModelData.remove(oldCmdKey);
                }
                if (existing.hasPdcIdentifier()) {
                    String oldPdcKey = createPdcKey(existing.baseItem(), existing.pdcIdentifier());
                    mappingsByPdcKey.remove(oldPdcKey);
                }
                if (existing.hasItemModelId()) {
                    mappingsByItemModel.remove(createItemModelKey(
                        existing.baseItem(), existing.itemModelId()));
                }
            }

            // Register in primary storage
            CustomItemMapping previous = mappingsByName.put(mapping.name(), mapping);

            // CMD index: skip CMD=0 entries so getByCustomModelData(base, 0) does not
            // accidentally return a PDC-only mapping that happens to share the base.
            if (mapping.customModelData() > 0) {
                String newCmdKey = createModelDataKey(mapping.baseItem(), mapping.customModelData());
                mappingsByModelData.put(newCmdKey, mapping);
            }
            // PDC index: populated alongside the CMD index when present.
            if (mapping.hasPdcIdentifier()) {
                String newPdcKey = createPdcKey(mapping.baseItem(), mapping.pdcIdentifier());
                mappingsByPdcKey.put(newPdcKey, mapping);
            }
            if (mapping.hasItemModelId()) {
                mappingsByItemModel.put(createItemModelKey(
                    mapping.baseItem(), mapping.itemModelId()), mapping);
            }

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
                // Remove old secondary/tertiary index entries if updating an existing mapping
                CustomItemMapping existing = mappingsByName.get(mapping.name());
                if (existing != null) {
                    if (existing.customModelData() > 0) {
                        String oldCmdKey = createModelDataKey(existing.baseItem(), existing.customModelData());
                        mappingsByModelData.remove(oldCmdKey);
                    }
                    if (existing.hasPdcIdentifier()) {
                        String oldPdcKey = createPdcKey(existing.baseItem(), existing.pdcIdentifier());
                        mappingsByPdcKey.remove(oldPdcKey);
                    }
                    if (existing.hasItemModelId()) {
                        mappingsByItemModel.remove(createItemModelKey(
                            existing.baseItem(), existing.itemModelId()));
                    }
                }

                mappingsByName.put(mapping.name(), mapping);

                if (mapping.customModelData() > 0) {
                    String newCmdKey = createModelDataKey(mapping.baseItem(), mapping.customModelData());
                    mappingsByModelData.put(newCmdKey, mapping);
                }
                if (mapping.hasPdcIdentifier()) {
                    String newPdcKey = createPdcKey(mapping.baseItem(), mapping.pdcIdentifier());
                    mappingsByPdcKey.put(newPdcKey, mapping);
                }
                if (mapping.hasItemModelId()) {
                    mappingsByItemModel.put(createItemModelKey(
                        mapping.baseItem(), mapping.itemModelId()), mapping);
                }
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
                if (removed.customModelData() > 0) {
                    String cmdKey = createModelDataKey(removed.baseItem(), removed.customModelData());
                    mappingsByModelData.remove(cmdKey);
                }
                if (removed.hasPdcIdentifier()) {
                    String pdcKey = createPdcKey(removed.baseItem(), removed.pdcIdentifier());
                    mappingsByPdcKey.remove(pdcKey);
                }
                if (removed.hasItemModelId()) {
                    mappingsByItemModel.remove(createItemModelKey(
                        removed.baseItem(), removed.itemModelId()));
                }
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
     * Gets a mapping by base item and stable PersistentDataContainer identifier.
     *
     * @param baseItem      The base item identifier (e.g., {@code "minecraft:stick"})
     * @param pdcIdentifier The stable PDC identifier (e.g., {@code "oraxen:fire_sword"})
     * @return An Optional containing the mapping if found, or empty if not found
     * @throws NullPointerException if either argument is null
     */
    public Optional<CustomItemMapping> getByPdc(String baseItem, String pdcIdentifier) {
        Objects.requireNonNull(baseItem, "baseItem must not be null");
        Objects.requireNonNull(pdcIdentifier, "pdcIdentifier must not be null");
        String key = createPdcKey(baseItem, pdcIdentifier);
        return Optional.ofNullable(mappingsByPdcKey.get(key));
    }

    public Optional<CustomItemMapping> getByItemModel(String baseItem, String itemModelId) {
        Objects.requireNonNull(baseItem, "baseItem must not be null");
        Objects.requireNonNull(itemModelId, "itemModelId must not be null");
        return Optional.ofNullable(mappingsByItemModel.get(
            createItemModelKey(baseItem, itemModelId)));
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
            mappingsByPdcKey.clear();
            mappingsByItemModel.clear();
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
     * <p>Written through a temp file and promoted with {@code ATOMIC_MOVE}, and
     * the previous generation is kept as {@code &lt;name&gt;.bak}. A half-written
     * ledger is unrecoverable: {@link #load(Path)} would find it unparseable and
     * every PDC-path entry would need a live {@code ItemStack} to come back.</p>
     *
     * <p>Refuses to write while {@link #loadFailed} is set — see that field.</p>
     *
     * @param path The path to save the mappings to
     * @throws IOException if an I/O error occurs
     * @throws NullPointerException if path is null
     */
    public void save(Path path) throws IOException {
        Objects.requireNonNull(path, "path must not be null");

        if (loadFailed) {
            LOGGER.severe(() -> "Refusing to overwrite " + path
                + ": the existing file failed to parse at load time, so this registry"
                + " does not hold its contents. Restore it from " + backupPath(path)
                + " (or delete both to start over) and restart.");
            return;
        }

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

            // Identifiers that stand for a category rather than one item, so
            // they can be pushed to the back of their group below.
            java.util.Set<String> genericPdcIds = findGenericPdcIdentifiers();

            // Sort base items alphabetically, then sort items within each group by CMD
            Map<String, List<Map<String, Object>>> sortedItemsByBase = new java.util.LinkedHashMap<>();
            itemsByBase.keySet().stream().sorted().forEach(baseItem -> {
                List<CustomItemMapping> items = itemsByBase.get(baseItem);
                // Sort by customModelData to ensure consistent order, but keep
                // category-wide PDC identifiers behind item-specific ones.
                //
                // Geyser v2 has no predicate that inspects a PDC *value*, so
                // several PDC mappings on one base material all match the same
                // stacks and the first registration wins. Ordering therefore
                // decides which definition an item gets. A leftover entry like
                // "trinityforge:tradeable" covers every tradeable item on that
                // material and would shadow the specific one; putting it last
                // resolves that in favour of the specific entry without
                // deleting anything — deletions here are unrecoverable for
                // PDC-path entries.
                items.sort(java.util.Comparator
                    .comparingInt((CustomItemMapping m) -> specificityRank(m, genericPdcIds))
                    .thenComparingInt(CustomItemMapping::customModelData)
                    .thenComparing(CustomItemMapping::name));
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

            writeAtomicWithBackup(path, json);
            LOGGER.fine(() -> "Saved " + mappingsByName.size() + " item mappings to " + path);
        } finally {
            batchLock.readLock().unlock();
        }
    }

    /**
     * How narrowly a mapping's Bedrock predicate matches, lowest = narrowest.
     *
     * <p>Geyser v2 registers the first definition that claims a base item, and
     * the predicates differ in reach: {@code legacyCustomModelData} matches one
     * CMD value, {@code item_model} matches one model id, while the PDC path
     * can only test that {@code minecraft:custom_data} is <em>present</em> —
     * it matches every custom item on that material. Registering the broad one
     * first therefore swallows the narrow ones, which is why an item with a
     * perfectly good CMD definition could still render as something else.</p>
     */
    private static int specificityRank(
        CustomItemMapping mapping, java.util.Set<String> genericPdcIds
    ) {
        if (mapping.customModelData() > 0) {
            return 0;
        }
        if (mapping.hasItemModelId()) {
            return 1;
        }
        if (mapping.hasPdcIdentifier() && genericPdcIds.contains(mapping.pdcIdentifier())) {
            return 3;
        }
        return 2;
    }

    /**
     * PDC identifiers that appear on several different base items.
     *
     * <p>A real item identifier names one item, so it shows up on one base
     * material. One spread across many materials is a category — the value of
     * some attribute slot that got mistaken for identity — and must not be
     * allowed to shadow the specific mappings it overlaps.</p>
     *
     * <p>The threshold is 3 rather than 2 so a genuine identifier shared by a
     * small set (an armour set written with one id across its pieces) is not
     * demoted.</p>
     */
    private java.util.Set<String> findGenericPdcIdentifiers() {
        Map<String, java.util.Set<String>> basesById = new java.util.HashMap<>();
        for (CustomItemMapping mapping : mappingsByName.values()) {
            if (!mapping.hasPdcIdentifier()) {
                continue;
            }
            basesById.computeIfAbsent(mapping.pdcIdentifier(), k -> new java.util.HashSet<>())
                .add(mapping.baseItem());
        }
        java.util.Set<String> generic = new java.util.HashSet<>();
        for (Map.Entry<String, java.util.Set<String>> entry : basesById.entrySet()) {
            if (entry.getValue().size() >= 3) {
                generic.add(entry.getKey());
            }
        }
        return generic;
    }

    /**
     * Writes {@code json} to {@code path} so that the file on disk is either the
     * old content or the new content, never a truncated mix, and keeps the
     * previous generation as {@code .bak}.
     */
    private static void writeAtomicWithBackup(Path path, String json) throws IOException {
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(tmp, json);
        if (Files.exists(path)) {
            // Copy rather than move: a move would leave no file at `path` for
            // the window between the two operations, and a crash there is the
            // same total loss this method exists to prevent.
            Files.copy(path, backupPath(path), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        try {
            Files.move(tmp, path,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            // Some network/virtual filesystems refuse ATOMIC_MOVE. A plain
            // replace is still strictly better than writing over the original.
            Files.move(tmp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Path backupPath(Path path) {
        return path.resolveSibling(path.getFileName() + ".bak");
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

        // Why: Bedrock clients reject offhand for many weapon types unless Geyser
        // mapping sets allow_offhand; Extension defaults true but explicit write
        // avoids silent client refusal.
        json.put("allow_offhand", true);

        // PDC identifier — only present for CMD-less / PDC-based mappings. The
        // extension reads this to switch the predicate from legacyCustomModelData
        // to hasComponent("minecraft:custom_data") when registering with Geyser.
        if (mapping.hasPdcIdentifier()) {
            json.put("pdc_identifier", mapping.pdcIdentifier());
        }
        if (mapping.hasItemModelId()) {
            json.put("item_model", mapping.itemModelId());
        }

        // Phase 7a: armor metadata (slot + asset_id). Persisted so the
        // auto-pack rebuild after restart can route this mapping through the
        // armor attachable path without re-scanning the item.
        if (mapping.hasArmor()) {
            Map<String, Object> armorJson = new java.util.LinkedHashMap<>();
            armorJson.put("slot", mapping.armor().slot());
            armorJson.put("asset_id", mapping.armor().assetId());
            json.put("armor", armorJson);
        }

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

        List<CustomItemMapping> mappings;
        try {
            mappings = parseJsonToMappings(Files.readString(path));
            loadFailed = false;
        } catch (CorruptLedgerException e) {
            // The file exists but is not readable as a ledger. Before giving up,
            // try the previous generation: this is exactly the case .bak is for.
            Path backup = backupPath(path);
            List<CustomItemMapping> recovered = null;
            if (Files.exists(backup)) {
                try {
                    recovered = parseJsonToMappings(Files.readString(backup));
                } catch (CorruptLedgerException ignored) {
                    recovered = null;
                }
            }
            if (recovered == null || recovered.isEmpty()) {
                // Latch fail-closed. Starting empty and then saving would turn one
                // interrupted write into permanent loss of every PDC-path entry.
                loadFailed = true;
                throw new IOException("Ledger " + path + " is corrupt and no usable "
                    + backup.getFileName() + " is present; refusing to start from an"
                    + " empty registry (saves are suppressed until this is resolved)", e);
            }
            final int recoveredCount = recovered.size();
            LOGGER.warning(() -> "Ledger " + path + " was corrupt; recovered "
                + recoveredCount + " mapping(s) from " + backup.getFileName());
            mappings = recovered;
            loadFailed = false;
        }

        if (mappings.isEmpty()) {
            LOGGER.warning(() -> "Loaded empty mapping list from " + path);
            return;
        }

        batchLock.writeLock().lock();
        try {
            mappingsByName.clear();
            mappingsByModelData.clear();
            mappingsByPdcKey.clear();
            mappingsByItemModel.clear();

            for (CustomItemMapping mapping : mappings) {
                if (mapping != null) {
                    mappingsByName.put(mapping.name(), mapping);
                    if (mapping.customModelData() > 0) {
                        String cmdKey = createModelDataKey(mapping.baseItem(), mapping.customModelData());
                        mappingsByModelData.put(cmdKey, mapping);
                    }
                    if (mapping.hasPdcIdentifier()) {
                        String pdcKey = createPdcKey(mapping.baseItem(), mapping.pdcIdentifier());
                        mappingsByPdcKey.put(pdcKey, mapping);
                    }
                    if (mapping.hasItemModelId()) {
                        mappingsByItemModel.put(createItemModelKey(
                            mapping.baseItem(), mapping.itemModelId()), mapping);
                    }
                }
            }

            LOGGER.fine(() -> "Loaded " + mappingsByName.size() + " item mappings from " + path);
        } finally {
            batchLock.writeLock().unlock();
        }
    }

    /** Raised when a ledger file is present but cannot be read as one. */
    private static final class CorruptLedgerException extends Exception {
        CorruptLedgerException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Parses JSON to list of CustomItemMapping, supporting multiple formats.
     *
     * <p>Throws rather than returning an empty list on malformed input. The two
     * cases must stay distinguishable: an empty ledger is a normal first run,
     * while an unparseable one means the caller must not proceed to save over
     * it.</p>
     *
     * @param json The JSON string to parse
     * @return List of mappings (possibly empty for a legitimately empty ledger)
     * @throws CorruptLedgerException if the text is not readable as a ledger
     */
    private List<CustomItemMapping> parseJsonToMappings(String json)
            throws CorruptLedgerException {
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
            throw new CorruptLedgerException("Failed to parse mappings JSON", e);
        }

        // Parsed cleanly but matched neither known shape (not an object with
        // "items", not an array). Treating that as "empty" would let a file of
        // the wrong kind pass for a fresh ledger.
        throw new CorruptLedgerException(
            "Mappings JSON is neither an {\"items\": ...} object nor a legacy array", null);
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
            // Optional PDC identifier — null for legacy CMD-only JSON files.
            String pdcIdentifier = getStringOrNull(itemDef, "pdc_identifier");
            String itemModelId = getStringOrNull(itemDef, "item_model");

            // Phase 7a: optional armor metadata. Older JSON files lack the
            // "armor" object, in which case the mapping carries no armor data
            // and the auto-pack routes it through the held-item path.
            com.geyserextra.core.api.ArmorData armor = parseArmorData(itemDef);

            // Defensive filter: a mapping with neither a valid CMD nor a PDC
            // identifier has no way for the extension to identify the matching
            // Java item at runtime. Older builds occasionally wrote CMD=0
            // sentinels into custom_items.json; drop those entries here rather
            // than letting them survive into the registry.
            if (customModelData <= 0
                && (pdcIdentifier == null || pdcIdentifier.isBlank())
                && (itemModelId == null || itemModelId.isBlank())) {
                LOGGER.fine(() -> "Skipping mapping '" + name
                    + "' (base=" + baseItem + ") — no CMD and no PDC identifier");
                return null;
            }

            return new CustomItemMapping(
                name,
                baseItem,
                customModelData,
                unbreakable,
                displayName,
                iconPath,
                creativeCategory,
                creativeGroup,
                register,
                pdcIdentifier,
                armor,
                itemModelId
            );
        } catch (Exception e) {
            LOGGER.warning(() -> "Failed to parse item definition: " + e.getMessage());
            return null;
        }
    }

    /**
     * Phase 7a: parses the optional {@code armor} object from a mapping JSON
     * entry. Returns {@code null} when the field is missing, structurally
     * wrong, or carries blank values — in which case the loaded mapping has
     * no armor metadata and is treated as a regular held-item by the
     * auto-pack.
     */
    private com.geyserextra.core.api.ArmorData parseArmorData(com.google.gson.JsonObject itemDef) {
        if (!itemDef.has("armor") || itemDef.get("armor").isJsonNull()) {
            return null;
        }
        if (!itemDef.get("armor").isJsonObject()) {
            return null;
        }
        com.google.gson.JsonObject armorObj = itemDef.getAsJsonObject("armor");
        String slot = getStringOrNull(armorObj, "slot");
        String assetId = getStringOrNull(armorObj, "asset_id");
        if (slot == null || slot.isBlank() || assetId == null || assetId.isBlank()) {
            return null;
        }
        try {
            return new com.geyserextra.core.api.ArmorData(slot, assetId);
        } catch (IllegalArgumentException ex) {
            LOGGER.fine(() -> "Skipping invalid armor block in mapping: " + ex.getMessage());
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
            // SEVERE, not WARNING: with the ledger unreadable every Bedrock
            // player sees vanilla items until it is restored, and the operator
            // has to act. A warning buried in startup noise is how the previous
            // loss went unnoticed until players reported it.
            LOGGER.log(Level.SEVERE, "Failed to load mappings from " + path, e);
            return false;
        }
    }

    /**
     * Whether the last load found the ledger present but unreadable, in which
     * case {@link #save(Path)} is suppressed to protect the file on disk.
     */
    public boolean isLoadFailed() {
        return loadFailed;
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

    /**
     * Creates a composite key for the PDC identifier index.
     *
     * <p>Uses {@code ::pdc::} as the separator to guarantee non-overlap with the
     * {@code baseItem:CMD} keyspace, even if a future plugin coins a PDC
     * identifier that looks like an integer.</p>
     */
    private String createPdcKey(String baseItem, String pdcIdentifier) {
        return baseItem + "::pdc::" + pdcIdentifier;
    }

    private String createItemModelKey(String baseItem, String itemModelId) {
        return baseItem + "::item_model::" + itemModelId;
    }
}
