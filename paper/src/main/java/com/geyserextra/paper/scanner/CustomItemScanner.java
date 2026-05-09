package com.geyserextra.paper.scanner;

import com.geyserextra.core.api.CustomItemMapping;
import com.geyserextra.core.config.GeyserExtraConfig;
import com.geyserextra.core.registry.ItemMappingRegistry;
import com.geyserextra.paper.GeyserExtraPaper;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.CustomModelData;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;

/**
 * Scanner for detecting and registering custom items with CustomModelData.
 * Uses Paper's Data Component API (1.21+) to detect items and register them.
 *
 * Registration policy: Every detected CMD item is registered with {@code register=true}
 * regardless of whether a PDC identifier was found. Items lacking a PDC fall back to
 * an auto-generated name {@code custom_<base>_<CMD>}; the companion auto-generated BE
 * pack supplies an {@code item_texture.json} entry that points the auto name at the
 * vanilla base item texture, so BE clients render them as the underlying base item.
 *
 * The previous "skip without PDC + warn loudly" behaviour produced log spam on servers
 * with bulk-registered custom items (ItemsAdder, Oraxen, Skript). The new flow keeps
 * a debounced INFO summary per scan burst instead.
 */
public final class CustomItemScanner {

    private static final String MINECRAFT_NAMESPACE = "minecraft:";
    /** Debounce delay before printing the aggregate auto-generated summary (5s @ 20 tps). */
    private static final long AUTO_NAMED_SUMMARY_DELAY_TICKS = 100L;

    /** Tracks which (baseItem:CMD) pairs we've already counted as auto-named to avoid double counting. */
    private final Set<String> autoNamedSeen = new HashSet<>();

    // Auto-named summary state — guarded by main thread (warnings are emitted from main thread).
    private BukkitTask pendingAutoNamedSummaryTask;
    /** Tracks the autoNamedSeen.size() observed at last summary print so we don't re-emit unchanged counts. */
    private int lastAutoNamedSummaryCount = 0;

    private final ItemMappingRegistry registry;
    private final GeyserExtraPaper plugin;

    public CustomItemScanner(ItemMappingRegistry registry, GeyserExtraPaper plugin) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.plugin = Objects.requireNonNull(plugin, "plugin must not be null");
    }

    /**
     * Scans an item stack for custom model data and registers it if found.
     */
    public Optional<CustomItemMapping> scanItem(ItemStack itemStack) {
        if (itemStack == null || itemStack.getType() == Material.AIR) {
            return Optional.empty();
        }

        if (!itemStack.hasData(DataComponentTypes.CUSTOM_MODEL_DATA)) {
            return Optional.empty();
        }

        CustomModelData customModelData = itemStack.getData(DataComponentTypes.CUSTOM_MODEL_DATA);
        if (customModelData == null) {
            return Optional.empty();
        }

        List<Float> floats = customModelData.floats();
        if (floats.isEmpty() && customModelData.strings().isEmpty()
            && customModelData.flags().isEmpty() && customModelData.colors().isEmpty()) {
            return Optional.empty();
        }

        int primaryCmdValue = floats.isEmpty() ? 0 : floats.getFirst().intValue();
        String baseItem = buildBaseItemIdentifier(itemStack);

        // Check if already registered by CMD
        Optional<CustomItemMapping> existingByCmd = registry.getByCustomModelData(baseItem, primaryCmdValue);
        if (existingByCmd.isPresent()) {
            CustomItemMapping existing = existingByCmd.get();

            // Try to upgrade auto-generated name to PDC name
            if (existing.name().startsWith("custom_")) {
                String pdcId = extractItemIdFromPDC(itemStack);
                if (pdcId != null && !pdcId.startsWith("custom_")) {
                    registry.unregister(existing.name());
                    plugin.getLogger().info("Upgraded item mapping: " + existing.name() + " -> " + pdcId);
                    // Continue to register with new name
                } else {
                    return Optional.of(existing);
                }
            } else {
                return Optional.of(existing);
            }
        }

        // Generate name (prefers PDC, falls back to auto-generated form)
        String name = generateMappingName(itemStack, baseItem, primaryCmdValue, customModelData);
        boolean autoNamed = name.startsWith("custom_");

        // Check by name
        if (registry.contains(name)) {
            return registry.getByName(name);
        }

        // Create and register. register=true so the BE Geyser side picks the item up; the
        // auto-generated BE pack supplies the matching item_texture.json entry pointing at
        // the vanilla base texture, which is what makes this safe without an authored pack.
        CustomItemMapping mapping = new CustomItemMapping(
            name,
            baseItem,
            primaryCmdValue,
            isUnbreakable(itemStack),
            extractDisplayName(itemStack),
            null,
            determineCreativeCategory(itemStack.getType()),
            null,
            true
        );

        registry.register(mapping);

        if (autoNamed) {
            trackAutoNamed(baseItem, primaryCmdValue);
            if (plugin.getGeyserExtraConfig().general().debugMode()) {
                plugin.getLogger().fine("Auto-registered " + name + " (no PDC; CMD=" + primaryCmdValue + ")");
            }
        } else {
            plugin.getLogger().info("Registered custom item: " + name + " (CMD: " + primaryCmdValue + ")");
        }

        return Optional.of(mapping);
    }

    /**
     * Records that an auto-named item was seen and schedules a debounced INFO summary.
     *
     * Verbosity is governed by {@code customItems.pdcWarning} configuration:
     * <ul>
     *   <li>{@code FULL}: per-item INFO line plus the debounced summary.</li>
     *   <li>{@code COMPACT} (default): summary only.</li>
     *   <li>{@code DISABLED}: no log output (registration still happens).</li>
     * </ul>
     */
    private void trackAutoNamed(String baseItem, int cmdValue) {
        String key = baseItem + ":" + cmdValue;
        if (!autoNamedSeen.add(key)) {
            return;
        }

        String mode = resolvePdcWarningMode();

        if (GeyserExtraConfig.CustomItemsConfig.PDC_WARNING_DISABLED.equals(mode)) {
            return;
        }

        if (GeyserExtraConfig.CustomItemsConfig.PDC_WARNING_FULL.equals(mode)) {
            plugin.getLogger().info(String.format(
                "[Auto] Registered without PDC: %s CMD=%d (BE will use base texture)",
                baseItem, cmdValue));
        }

        scheduleAutoNamedSummary();
    }

    /**
     * Resolves the configured verbosity mode, normalizing case and falling back to COMPACT
     * when the config (or the field within it) is null. Defensive against partially-loaded
     * plugins (tests, reload paths).
     */
    private String resolvePdcWarningMode() {
        GeyserExtraConfig config = plugin.getGeyserExtraConfig();
        if (config == null || config.customItems() == null) {
            return GeyserExtraConfig.CustomItemsConfig.PDC_WARNING_COMPACT;
        }
        String raw = config.customItems().pdcWarning();
        if (raw == null || raw.isBlank()) {
            return GeyserExtraConfig.CustomItemsConfig.PDC_WARNING_COMPACT;
        }
        return raw.toUpperCase(Locale.ROOT);
    }

    /**
     * (Re)schedules a debounced summary task that logs the total auto-named count once a
     * scan burst settles (5s of quiet). Each new event during the burst cancels and re-arms
     * so the user sees one summary line per burst with the final count.
     */
    private void scheduleAutoNamedSummary() {
        try {
            if (pendingAutoNamedSummaryTask != null) {
                pendingAutoNamedSummaryTask.cancel();
                pendingAutoNamedSummaryTask = null;
            }
            pendingAutoNamedSummaryTask = plugin.getServer().getScheduler().runTaskLater(
                plugin,
                () -> {
                    pendingAutoNamedSummaryTask = null;
                    logAutoNamedSummary();
                },
                AUTO_NAMED_SUMMARY_DELAY_TICKS
            );
        } catch (IllegalStateException | IllegalArgumentException ignored) {
            // Plugin disabled mid-burst — onDisable will flush the summary instead.
        }
    }

    /**
     * Logs the aggregate "N items auto-registered without PDC" summary if new
     * auto-named items have been observed since the last summary. Idempotent.
     *
     * Called by:
     * <ul>
     *   <li>The debounced scheduled task at the end of a scan burst.</li>
     *   <li>{@code GeyserExtraPaper.onDisable} as a safety net.</li>
     * </ul>
     */
    public void logAutoNamedSummary() {
        int count = autoNamedSeen.size();
        if (count == 0 || count == lastAutoNamedSummaryCount) {
            return;
        }
        lastAutoNamedSummaryCount = count;

        String mode = resolvePdcWarningMode();
        if (GeyserExtraConfig.CustomItemsConfig.PDC_WARNING_DISABLED.equals(mode)) {
            return;
        }

        plugin.getLogger().info(String.format(
            "[CustomItems] Auto-registered %d item(s) without PDC identifier "
            + "(BE clients render them as the base item via the auto-generated pack).",
            count));
    }

    /**
     * Cancels any pending debounced summary task. Intended for plugin shutdown so
     * the scheduler doesn't try to fire a task against a disabled plugin instance.
     */
    public void cancelPendingAutoNamedSummary() {
        try {
            if (pendingAutoNamedSummaryTask != null) {
                pendingAutoNamedSummaryTask.cancel();
                pendingAutoNamedSummaryTask = null;
            }
        } catch (Exception ignored) {
            // Best-effort cleanup during shutdown.
        }
    }

    /**
     * Scans all items in an inventory for custom model data.
     */
    public int scanInventory(Inventory inventory) {
        if (inventory == null) {
            return 0;
        }

        int discovered = 0;
        for (ItemStack item : inventory.getContents()) {
            if (scanItem(item).isPresent()) {
                discovered++;
            }
        }
        return discovered;
    }

    /**
     * Scans all online player inventories for custom items.
     */
    public void scanAllPlayers() {
        int totalDiscovered = 0;

        for (Player player : plugin.getServer().getOnlinePlayers()) {
            try {
                if (plugin.getServer().isPrimaryThread()) {
                    totalDiscovered += scanInventory(player.getInventory());
                    totalDiscovered += scanInventory(player.getEnderChest());
                }
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING,
                    "Failed to scan inventory for player: " + player.getName(), e);
            }
        }

        plugin.getLogger().info("Player scan complete. Found " + totalDiscovered
            + " items. Registry size: " + registry.size());
    }

    private String buildBaseItemIdentifier(ItemStack itemStack) {
        return itemStack.getType().getKey().toString();
    }

    /**
     * Generates mapping name. Priority: PDC > CMD strings > auto-generated
     */
    private String generateMappingName(ItemStack itemStack, String baseItem, int cmdValue, CustomModelData customModelData) {
        // Priority 1: PersistentDataContainer
        String pdcId = extractItemIdFromPDC(itemStack);
        if (pdcId != null) {
            return pdcId;
        }

        // Priority 2: CustomModelData strings
        List<String> strings = customModelData.strings();
        if (!strings.isEmpty()) {
            String firstString = strings.getFirst();
            if (firstString != null && !firstString.isBlank() && firstString.matches("[a-z0-9_:]+")) {
                return firstString;
            }
        }

        // Priority 3: Auto-generate
        String baseName = baseItem.replace(MINECRAFT_NAMESPACE, "");
        return "custom_" + baseName + "_" + cmdValue;
    }

    /**
     * Extracts item identifier from PersistentDataContainer.
     */
    private String extractItemIdFromPDC(ItemStack itemStack) {
        if (itemStack == null) {
            return null;
        }

        ItemMeta meta = itemStack.getItemMeta();
        if (meta == null) {
            return null;
        }

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        if (pdc.isEmpty()) {
            return null;
        }

        String[] commonKeyNames = {
            "item_id", "itemid", "id", "identifier", "name", "item_name",
            "custom_item", "custom_id", "type", "item_type"
        };

        // Scan for common key patterns
        for (NamespacedKey key : pdc.getKeys()) {
            String keyName = key.getKey().toLowerCase();

            for (String pattern : commonKeyNames) {
                if (keyName.contains(pattern)) {
                    try {
                        String value = pdc.get(key, PersistentDataType.STRING);
                        if (value != null && !value.isBlank()) {
                            String sanitized = sanitizeItemId(value);
                            if (isValidItemId(sanitized)) {
                                return sanitized;
                            }
                        }
                    } catch (Exception ignored) {}
                    break;
                }
            }
        }

        // Fallback: find any valid-looking string
        for (NamespacedKey key : pdc.getKeys()) {
            try {
                String value = pdc.get(key, PersistentDataType.STRING);
                if (value != null && !value.isBlank()
                    && !value.contains(" ") && !value.contains("-")
                    && value.length() < 64 && isValidItemId(value)) {
                    return sanitizeItemId(value);
                }
            } catch (Exception ignored) {}
        }

        return null;
    }

    private String sanitizeItemId(String input) {
        return input.toLowerCase()
            .replace(" ", "_")
            .replace("-", "_")
            .replaceAll("[^a-z0-9_:]", "");
    }

    private boolean isValidItemId(String id) {
        return id != null && !id.isBlank() && id.length() >= 2 && id.matches("[a-z0-9_:]+");
    }

    private String extractDisplayName(ItemStack itemStack) {
        ItemMeta meta = itemStack.getItemMeta();
        if (meta != null && meta.hasDisplayName() && meta.displayName() != null) {
            return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(meta.displayName());
        }
        return null;
    }

    private boolean isUnbreakable(ItemStack itemStack) {
        ItemMeta meta = itemStack.getItemMeta();
        return meta != null && meta.isUnbreakable();
    }

    private int determineCreativeCategory(Material material) {
        if (material == null) {
            return CustomItemMapping.CREATIVE_CATEGORY_ITEMS;
        }

        String name = material.name();

        // Equipment (3)
        if (name.endsWith("_SWORD") || name.endsWith("_AXE") || name.endsWith("_PICKAXE")
            || name.endsWith("_SHOVEL") || name.endsWith("_HOE")
            || name.endsWith("_HELMET") || name.endsWith("_CHESTPLATE")
            || name.endsWith("_LEGGINGS") || name.endsWith("_BOOTS")
            || name.equals("BOW") || name.equals("CROSSBOW") || name.equals("TRIDENT")
            || name.equals("SHIELD") || name.equals("ELYTRA") || name.equals("FISHING_ROD")
            || name.equals("FLINT_AND_STEEL") || name.equals("SHEARS")
            || name.equals("BRUSH") || name.equals("SPYGLASS") || name.equals("MACE")) {
            return CustomItemMapping.CREATIVE_CATEGORY_EQUIPMENT;
        }

        // Construction (1)
        if (material.isBlock()) {
            return CustomItemMapping.CREATIVE_CATEGORY_CONSTRUCTION;
        }

        // Nature (2)
        if (name.contains("SEED") || name.contains("SAPLING") || name.contains("FLOWER")
            || name.contains("CORAL") || name.contains("MUSHROOM") || name.contains("EGG")) {
            return CustomItemMapping.CREATIVE_CATEGORY_NATURE;
        }

        // Commands (5)
        if (name.contains("COMMAND_BLOCK") || name.equals("STRUCTURE_BLOCK")
            || name.equals("JIGSAW") || name.equals("BARRIER") || name.equals("LIGHT")) {
            return CustomItemMapping.CREATIVE_CATEGORY_COMMANDS;
        }

        return CustomItemMapping.CREATIVE_CATEGORY_ITEMS;
    }

    public ItemMappingRegistry getRegistry() {
        return registry;
    }
}
