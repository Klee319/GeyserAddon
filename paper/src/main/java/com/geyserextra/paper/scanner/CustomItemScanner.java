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
 */
public final class CustomItemScanner {

    private static final String MINECRAFT_NAMESPACE = "minecraft:";
    /** Debounce delay before printing the aggregate PDC-missing summary (5s @ 20 tps). */
    private static final long PDC_SUMMARY_DELAY_TICKS = 100L;

    // Track items that have been warned about missing PDC
    private final Set<String> warnedItems = new HashSet<>();

    // PDC compact-mode state — guarded by main thread (warnings are emitted from main thread).
    private boolean pdcInstructionShown = false;
    // Debounced summary task. Mutated only on main thread; reads on main thread.
    private BukkitTask pendingPdcSummaryTask;
    // Tracks the warnedItems.size() observed at last summary print so we don't re-emit unchanged counts.
    private int lastPdcSummaryCount = 0;

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

        // Generate name (requires PDC or CMD strings)
        String name = generateMappingName(itemStack, baseItem, primaryCmdValue, customModelData);

        // If auto-generated name (no PDC found), warn and skip registration
        if (name.startsWith("custom_")) {
            warnMissingPDC(baseItem, primaryCmdValue, name);
            return Optional.empty();  // Do not register without proper identifier
        }

        // Check by name
        if (registry.contains(name)) {
            return registry.getByName(name);
        }

        // Create and register
        // Why register=false by default: auto-detected items may not have BE textures.
        // Users must set register=true in custom_items.json after preparing the texture.
        CustomItemMapping mapping = new CustomItemMapping(
            name,
            baseItem,
            primaryCmdValue,
            isUnbreakable(itemStack),
            extractDisplayName(itemStack),
            null,
            determineCreativeCategory(itemStack.getType()),
            null,
            false
        );

        registry.register(mapping);
        plugin.getLogger().info("Registered custom item: " + name + " (CMD: " + primaryCmdValue + ")");

        return Optional.of(mapping);
    }

    /**
     * Warns about missing PDC identifier and prompts manual mapping.
     *
     * Verbosity is governed by {@code customItems.pdcWarning} configuration:
     * <ul>
     *   <li>{@code FULL}: full 14-line block per unique item (legacy behavior).</li>
     *   <li>{@code COMPACT} (default): full block emitted once with a transition notice,
     *       then a single {@code [PDC missing] base CMD=N -> auto_name} line per
     *       subsequent unique item plus a debounced aggregate summary 5 seconds after
     *       the last new item (so log readers see one count per scan burst, not 50).</li>
     *   <li>{@code DISABLED}: no log output (registration is still skipped).</li>
     * </ul>
     */
    private void warnMissingPDC(String baseItem, int cmdValue, String autoName) {
        String key = baseItem + ":" + cmdValue;
        if (warnedItems.contains(key)) {
            return;
        }
        warnedItems.add(key);

        String mode = resolvePdcWarningMode();

        if (GeyserExtraConfig.CustomItemsConfig.PDC_WARNING_DISABLED.equals(mode)) {
            return;
        }

        if (GeyserExtraConfig.CustomItemsConfig.PDC_WARNING_FULL.equals(mode)) {
            printFullPdcWarning(baseItem, cmdValue, autoName);
            return;
        }

        // COMPACT mode — show educational block once, then 1-line per unique item.
        if (!pdcInstructionShown) {
            pdcInstructionShown = true;
            printFullPdcWarning(baseItem, cmdValue, autoName);
            plugin.getLogger().warning(
                "=== Further unmapped items will be listed compactly. "
                + "Set customItems.pdcWarning=\"FULL\" for the full block per item, "
                + "or \"DISABLED\" to silence. ===");
        } else {
            plugin.getLogger().warning(String.format(
                "[PDC missing] %s CMD=%d -> %s",
                baseItem, cmdValue, autoName));
        }

        scheduleCompactSummary();
    }

    /**
     * Emits the verbose 14-line guidance block. Used by FULL mode and the first
     * occurrence in COMPACT mode.
     */
    private void printFullPdcWarning(String baseItem, int cmdValue, String autoName) {
        plugin.getLogger().warning("=== Custom Item Mapping Warning ===");
        plugin.getLogger().warning("Item detected without PersistentDataContainer identifier:");
        plugin.getLogger().warning("  Base Item: " + baseItem);
        plugin.getLogger().warning("  CustomModelData: " + cmdValue);
        plugin.getLogger().warning("  Auto-generated name: " + autoName);
        plugin.getLogger().warning("");
        plugin.getLogger().warning("To fix: Add item ID to PersistentDataContainer in your plugin:");
        plugin.getLogger().warning("  meta.getPersistentDataContainer().set(");
        plugin.getLogger().warning("      new NamespacedKey(plugin, \"item_id\"),");
        plugin.getLogger().warning("      PersistentDataType.STRING,");
        plugin.getLogger().warning("      \"your_item_name\"");
        plugin.getLogger().warning("  );");
        plugin.getLogger().warning("");
        plugin.getLogger().warning("Or manually edit custom_items.json to set the correct name.");
        plugin.getLogger().warning("===================================");
    }

    /**
     * Resolves the configured PDC warning mode, normalizing case and falling back to
     * COMPACT when the config (or the field within it) is null.
     *
     * Why defensive null-checks: this method runs from listener callbacks before/after
     * onEnable boundaries (e.g., tests, reload paths), and a partially-loaded plugin
     * shouldn't crash the scanner.
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
     * (Re)schedules a debounced summary task that logs the total unique unmapped item
     * count once a scan burst settles (5s of quiet). Each new warning during the burst
     * cancels the previously scheduled task and re-arms it, so the user sees the
     * summary exactly once per burst with the final count.
     *
     * Safe to call from any thread: BukkitScheduler.runTaskLater dispatches to the
     * primary thread, where mutation of pendingPdcSummaryTask is also performed.
     */
    private void scheduleCompactSummary() {
        try {
            if (pendingPdcSummaryTask != null) {
                pendingPdcSummaryTask.cancel();
                pendingPdcSummaryTask = null;
            }
            pendingPdcSummaryTask = plugin.getServer().getScheduler().runTaskLater(
                plugin,
                () -> {
                    pendingPdcSummaryTask = null;
                    logPdcMissingSummary();
                },
                PDC_SUMMARY_DELAY_TICKS
            );
        } catch (IllegalStateException | IllegalArgumentException ignored) {
            // Plugin disabled mid-burst — onDisable will flush the summary instead.
        }
    }

    /**
     * Logs the aggregate "N unique items lacked PDC identifier" summary if new
     * unmapped items have been observed since the last summary. Idempotent: calling
     * this repeatedly without new warnings is a no-op.
     *
     * Called by:
     * <ul>
     *   <li>The debounced scheduled task at the end of a warning burst.</li>
     *   <li>{@code GeyserExtraPaper.onDisable} as a safety net to flush any pending
     *       summary that the scheduled task didn't get to fire.</li>
     * </ul>
     */
    public void logPdcMissingSummary() {
        int count = warnedItems.size();
        if (count == 0 || count == lastPdcSummaryCount) {
            return;
        }
        lastPdcSummaryCount = count;

        String mode = resolvePdcWarningMode();
        if (GeyserExtraConfig.CustomItemsConfig.PDC_WARNING_DISABLED.equals(mode)) {
            return;
        }

        if (GeyserExtraConfig.CustomItemsConfig.PDC_WARNING_COMPACT.equals(mode)) {
            plugin.getLogger().info(String.format(
                "[PDC] %d unique item(s) lacked PDC identifier (registration skipped). "
                + "Set customItems.pdcWarning=\"FULL\" for per-item details "
                + "or \"DISABLED\" to silence.",
                count));
        } else {
            plugin.getLogger().info(String.format(
                "[PDC] %d unique item(s) lacked PDC identifier (registration skipped).",
                count));
        }
    }

    /**
     * Cancels any pending debounced summary task. Intended for plugin shutdown so
     * the scheduler doesn't try to fire a task against a disabled plugin instance.
     */
    public void cancelPendingPdcSummary() {
        try {
            if (pendingPdcSummaryTask != null) {
                pendingPdcSummaryTask.cancel();
                pendingPdcSummaryTask = null;
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
