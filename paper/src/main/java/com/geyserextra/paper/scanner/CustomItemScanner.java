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
     * Scans an item stack for custom model data (or, when CMD is absent, a
     * stable PersistentDataContainer identifier) and registers it if found.
     *
     * <p>Dispatch order:</p>
     * <ol>
     *   <li>If the item has CMD data, follow the legacy CMD-only path
     *       (no behavioural changes from pre-Phase-1 builds).</li>
     *   <li>If the item has no CMD but {@code customItems.pdcEnabled=true}
     *       (default) and {@link #extractStableIdFromPDC(ItemStack)} produces
     *       a usable identifier, register the item via the PDC path so the
     *       extension can expose it to Bedrock players via the
     *       {@code hasComponent("minecraft:custom_data")} predicate.</li>
     *   <li>Otherwise return empty (non-custom item).</li>
     * </ol>
     */
    public Optional<CustomItemMapping> scanItem(ItemStack itemStack) {
        if (itemStack == null || itemStack.getType() == Material.AIR) {
            return Optional.empty();
        }

        if (itemStack.isDataOverridden(DataComponentTypes.ITEM_MODEL)) {
            net.kyori.adventure.key.Key itemModel =
                itemStack.getData(DataComponentTypes.ITEM_MODEL);
            if (itemModel != null
                && !MINECRAFT_NAMESPACE.substring(0,
                    MINECRAFT_NAMESPACE.length() - 1).equals(itemModel.namespace())) {
                return scanItemModelItem(itemStack, itemModel.asString());
            }
        }

        if (!itemStack.hasData(DataComponentTypes.CUSTOM_MODEL_DATA)) {
            // Phase 1: route CMD-less items through the PDC path when the
            // feature flag is on. When the flag is off, behaviour is identical
            // to the pre-Phase-1 build (return empty, never register).
            if (!isPdcEnabled()) {
                return Optional.empty();
            }
            return scanPdcItem(itemStack);
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
        if (primaryCmdValue <= 0) {
            // Don't register CMD 0 (or negative) items. Geyser registers a
            // CustomItemDefinition with no CMD predicate as a wholesale
            // override of the base vanilla item, which would clobber every
            // player's view of, say, "minecraft:bucket" with our custom
            // texture. Plugins occasionally tag items with CMD=0 as a
            // sentinel without intending vanilla-override semantics; the
            // scanner has no way to tell the difference, so skipping is
            // the only safe default.
            return Optional.empty();
        }
        String baseItem = buildBaseItemIdentifier(itemStack);

        // Check if already registered by CMD
        Optional<CustomItemMapping> existingByCmd = registry.getByCustomModelData(baseItem, primaryCmdValue);
        if (existingByCmd.isPresent()) {
            CustomItemMapping existing = existingByCmd.get();

            // Non-auto-named entries are presumed authoritative (came from PDC,
            // CMD strings, or operator-curated custom_items.json) and are left
            // alone to avoid clobbering richer metadata with whatever the
            // runtime ItemStack happens to expose.
            if (!existing.name().startsWith("custom_")) {
                return Optional.of(existing);
            }

            // Auto-named entries can be upgraded once a richer source surfaces.
            // This handles two cases at once:
            //   (1) Pack-first registered a CMD from the Java pack with null
            //       display name / unbreakable=false / category=ITEMS as
            //       placeholders, and now the scanner sees the actual ItemStack
            //       with operator-set metadata.
            //   (2) The previous "auto-name + no PDC" entry now has a PDC value
            //       available because a plugin filled it in after the fact.
            String pdcId = extractItemIdFromPDC(itemStack);
            String currentDisplayName = extractDisplayName(itemStack);
            boolean currentUnbreakable = isUnbreakable(itemStack);
            int currentCategory = determineCreativeCategory(itemStack.getType());

            boolean wantNameUpgrade = pdcId != null && !pdcId.startsWith("custom_");
            boolean wantDisplayUpgrade = !existing.hasDisplayName()
                && currentDisplayName != null && !currentDisplayName.isBlank();
            boolean wantUnbreakableUpgrade = !existing.unbreakable() && currentUnbreakable;
            boolean wantCategoryUpgrade = currentCategory != CustomItemMapping.CREATIVE_CATEGORY_ITEMS
                && existing.creativeCategory() == CustomItemMapping.CREATIVE_CATEGORY_ITEMS;

            if (!wantNameUpgrade && !wantDisplayUpgrade
                && !wantUnbreakableUpgrade && !wantCategoryUpgrade) {
                return Optional.of(existing);
            }

            registry.unregister(existing.name());
            StringBuilder reasons = new StringBuilder();
            if (wantNameUpgrade) reasons.append("name,");
            if (wantDisplayUpgrade) reasons.append("displayName,");
            if (wantUnbreakableUpgrade) reasons.append("unbreakable,");
            if (wantCategoryUpgrade) reasons.append("category,");
            plugin.getLogger().fine("Upgraded auto-named mapping " + existing.name()
                + " [+" + reasons.substring(0, reasons.length() - 1) + "]"
                + " — continuing to register with richer metadata");
            // Fall through to register a new entry below.
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
        // Phase 7a: armor metadata is extracted from the EQUIPPABLE component
        // when present, so the auto-pack can route this mapping through the
        // armor attachable path instead of the held-item path.
        com.geyserextra.core.api.ArmorData armorData = extractArmorData(itemStack);
        CustomItemMapping mapping = new CustomItemMapping(
            name,
            baseItem,
            primaryCmdValue,
            isUnbreakable(itemStack),
            extractDisplayName(itemStack),
            null,
            determineCreativeCategory(itemStack.getType()),
            null,
            true,
            null,        // pdcIdentifier (CMD path)
            armorData
        );

        registry.register(mapping);

        if (autoNamed) {
            trackAutoNamed(baseItem, primaryCmdValue);
            if (plugin.getGeyserExtraConfig().general().debugMode()) {
                plugin.getLogger().fine("Auto-registered " + name + " (no PDC; CMD=" + primaryCmdValue + ")");
            }
        } else {
            plugin.getLogger().fine("Registered custom item: " + name + " (CMD: " + primaryCmdValue + ")");
        }

        return Optional.of(mapping);
    }

    private Optional<CustomItemMapping> scanItemModelItem(
        ItemStack itemStack,
        String itemModelId
    ) {
        String baseItem = buildBaseItemIdentifier(itemStack);
        Optional<CustomItemMapping> existing =
            registry.getByItemModel(baseItem, itemModelId);
        if (existing.isPresent()) {
            return existing;
        }

        String name = "itemmodel_" + itemModelId.toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9_\\-./]", "_")
            .replace('/', '_')
            .replace('.', '_');
        if (registry.contains(name)) {
            name += "_" + Integer.toHexString(
                (baseItem + "\0" + itemModelId).hashCode() & 0xfffff);
        }

        CustomItemMapping mapping = new CustomItemMapping(
            name,
            baseItem,
            0,
            isUnbreakable(itemStack),
            extractDisplayName(itemStack),
            null,
            determineCreativeCategory(itemStack.getType()),
            null,
            true,
            null,
            extractArmorData(itemStack),
            itemModelId
        );
        registry.register(mapping);
        plugin.getLogger().info("[ItemModel] Discovered " + itemModelId
            + " on " + baseItem + "; Bedrock texture will activate after restart.");
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
            plugin.getLogger().fine(String.format(
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

        plugin.getLogger().fine(String.format(
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
     *
     * <p><b>Must be called on the primary server thread.</b> A previous revision
     * gated each per-player block with {@code isPrimaryThread()}, which silently
     * turned the entire method into a no-op whenever the caller scheduled it
     * asynchronously — and the actual call site (now {@code performInitialScan}
     * in {@code GeyserExtraPaper}) did exactly that, so the advertised
     * "initial scan of all online inventories" never happened. The thread
     * guard is removed; callers must arrange the proper thread themselves
     * (use {@code BukkitScheduler.runTask}, not {@code runTaskAsynchronously}).</p>
     */
    public void scanAllPlayers() {
        if (!plugin.getServer().isPrimaryThread()) {
            plugin.getLogger().warning(
                "scanAllPlayers() called off the primary thread; this is a programmer error. "
                + "Skipping to avoid concurrent inventory access.");
            return;
        }

        int totalDiscovered = 0;
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            try {
                totalDiscovered += scanInventory(player.getInventory());
                totalDiscovered += scanInventory(player.getEnderChest());
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING,
                    "Failed to scan inventory for player: " + player.getName(), e);
            }
        }

        plugin.getLogger().fine("Player scan complete. Found " + totalDiscovered
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

        for (NamespacedKey key : rankKeysByHint(DISPLAY_ID_KEY_HINTS, sortKeys(pdc))) {
            try {
                String value = pdc.get(key, PersistentDataType.STRING);
                if (value != null && !value.isBlank()) {
                    String sanitized = sanitizeItemId(value);
                    if (isValidItemId(sanitized)) {
                        return sanitized;
                    }
                }
            } catch (Exception ignored) {}
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
            net.kyori.adventure.text.Component name = meta.displayName();

            // Resolve TranslatableComponent against the operator's Java pack lang
            // file so the registry stores the human-readable name (e.g. "Fire Sword")
            // rather than the bare translation key. Without this, plugins that set
            // display names via Component.translatable("item.mymod.fire_sword")
            // leave us storing "item.mymod.fire_sword" — which Bedrock has no way
            // to render and which would leak through every fallback chain we have.
            if (name instanceof net.kyori.adventure.text.TranslatableComponent translatable) {
                String resolved = plugin.getJavaPackLangReader().resolve(translatable.key());
                if (resolved != null && !resolved.isBlank()) {
                    return resolved;
                }
                // Lang file doesn't contain the key — fall through to the plain
                // serializer below. The result will be the raw key string, which
                // is still better than nothing for diagnostic purposes and at
                // least lets later upgrade paths recognise the entry.
            }

            String plain = net.kyori.adventure.text.serializer.plain
                .PlainTextComponentSerializer.plainText().serialize(name);
            if (plain != null && !plain.isBlank()) {
                return plain;
            }
        }

        // ItemMeta has no displayName Component. Two more shots before
        // giving up — without them the registry stores null, Geyser sees
        // a null displayName, and the Bedrock client falls back to the
        // raw Geyser identifier (e.g. "gmdl_abc1234") which is what the
        // user reported as "items show their internal ID instead of a
        // readable name".
        //
        // Order matters: PDC first because plugins that store display
        // names in PDC (ItemsAdder, Oraxen, MMOItems variants) carry the
        // intended human-readable string there. Vanilla lang fallback is
        // a last resort that at least localises the base material name.
        String pdcName = extractDisplayNameFromPDC(itemStack.getItemMeta());
        if (pdcName != null && !pdcName.isBlank()) {
            return pdcName;
        }

        return resolveVanillaMaterialName(itemStack.getType());
    }

    /**
     * Common PDC keys plugins use to store a player-facing display name.
     * Plain {@code name} / {@code id} variants are excluded here because
     * they are usually internal identifiers; {@link #extractItemIdFromPDC}
     * already consumes them. Order is preference-first.
     */
    private static final String[] PDC_DISPLAY_NAME_KEYS = {
        "displayname", "display_name", "title", "label"
    };

    /**
     * Probes the item's PersistentDataContainer for a display-name string.
     * Returns {@code null} when no recognised key carries one. The search
     * is intentionally narrow (compared to {@link #extractItemIdFromPDC})
     * so we don't accidentally surface an internal ID as a display name.
     */
    private String extractDisplayNameFromPDC(ItemMeta meta) {
        if (meta == null) {
            return null;
        }
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        if (pdc.isEmpty()) {
            return null;
        }
        for (NamespacedKey key : pdc.getKeys()) {
            String lower = key.getKey().toLowerCase(Locale.ROOT);
            for (String candidate : PDC_DISPLAY_NAME_KEYS) {
                if (!lower.contains(candidate)) continue;
                try {
                    String value = pdc.get(key, PersistentDataType.STRING);
                    if (value != null && !value.isBlank()) {
                        return value;
                    }
                } catch (Exception ignored) {
                    // Non-string PDC value at this key → keep searching.
                }
                break;
            }
        }
        return null;
    }

    /**
     * Resolves the base material's vanilla display name via the operator's
     * Java pack lang file when present (so a Japanese-locale pack returns
     * "ダイヤモンドの剣" for {@code minecraft:diamond_sword}), falling back
     * to a prettified material identifier ("Diamond Sword") when no lang
     * file is configured. Used as the last-ditch display name when the
     * item has neither ItemMeta displayName nor a recognised PDC display
     * key — at least the Bedrock player sees a sensible material name
     * instead of a raw Geyser identifier.
     */
    private String resolveVanillaMaterialName(Material material) {
        return com.geyserextra.paper.pack.VanillaDisplayNames.resolve(
            material, plugin.getJavaPackLangReader());
    }

    public static String prettifyMaterialKey(String raw) {
        return com.geyserextra.paper.pack.VanillaDisplayNames.prettify(raw);
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

    /**
     * Phase 7a: extracts {@link com.geyserextra.core.api.ArmorData} from an
     * item's {@code minecraft:equippable} data component (Paper 1.21.4+).
     * Returns {@code null} when the item is not equippable, the slot is not
     * recognised, or the asset_id is missing — in which case the auto-pack
     * routes the item through the standard held-item attachable path.
     *
     * <p>The reflective-style component access used here means a Paper build
     * that ships without the EQUIPPABLE constant (older 1.21.x) silently
     * falls back to "no armor metadata", keeping the scanner functional on
     * legacy server versions. Modern equipped-armor plugins are 1.21.4+ so
     * this is a strict no-op on the legacy path.</p>
     */
    private com.geyserextra.core.api.ArmorData extractArmorData(ItemStack itemStack) {
        if (itemStack == null) {
            return null;
        }
        try {
            if (!itemStack.hasData(DataComponentTypes.EQUIPPABLE)) {
                return null;
            }
            io.papermc.paper.datacomponent.item.Equippable eq =
                itemStack.getData(DataComponentTypes.EQUIPPABLE);
            if (eq == null) {
                return null;
            }
            org.bukkit.inventory.EquipmentSlot slot = eq.slot();
            net.kyori.adventure.key.Key assetId = eq.assetId();
            if (slot == null || assetId == null) {
                return null;
            }
            String slotName = switch (slot) {
                case HEAD -> com.geyserextra.core.api.ArmorData.SLOT_HEAD;
                case CHEST -> com.geyserextra.core.api.ArmorData.SLOT_CHEST;
                case LEGS -> com.geyserextra.core.api.ArmorData.SLOT_LEGS;
                case FEET -> com.geyserextra.core.api.ArmorData.SLOT_FEET;
                default -> null;
            };
            if (slotName == null) {
                // Body slot (wolves/horses) and hand slots are out of scope
                // for player armor rendering — skip silently rather than
                // emitting an attachable that Bedrock cannot render.
                return null;
            }
            return new com.geyserextra.core.api.ArmorData(slotName, assetId.asString());
        } catch (LinkageError legacyPaper) {
            // Pre-1.21.4 Paper builds lack the EQUIPPABLE accessor. Treat as
            // "no armor metadata" so the scanner stays functional on legacy
            // servers without forcing operators to upgrade Paper.
            // LinkageError covers NoSuchMethodError, NoClassDefFoundError, and
            // related cases — Java's exception hierarchy makes a multi-catch
            // with both impossible (NoSuchMethodError extends LinkageError).
            return null;
        } catch (Throwable unexpected) {
            // Defensive: any other failure (NPE in record accessor, etc.)
            // shouldn't disable item scanning. Log at FINE so debug-mode
            // operators see it but live servers aren't spammed.
            plugin.getLogger().fine("[ArmorScan] equippable extraction failed for "
                + itemStack.getType() + ": " + unexpected.getClass().getSimpleName()
                + " — treating as non-equippable");
            return null;
        }
    }

    // =================================================================
    // Phase 1: PDC-only craft result path
    // =================================================================

    /**
     * Preferred PDC namespaces from known custom-item plugins. Checked in
     * declaration order against each PDC key's namespace. Used by
     * {@link #extractStableIdFromPDC(ItemStack)} so the registry key for a
     * given item is deterministic even if the plugin writes multiple unrelated
     * PDC keys to the same {@code ItemMeta}.
     *
     * <p>{@code "skript"} is intentionally excluded: it is a scripting plugin,
     * not an item-management plugin, and ships items with arbitrary PDC values
     * that are usually script state, not stable item identifiers. Treating any
     * {@code skript:*} key as an item ID would surface those internal values
     * as Bedrock mapping names.</p>
     */
    private static final String[] PREFERRED_PDC_NAMESPACES = {
        "oraxen", "itemsadder", "mythicmobs", "mythiccrucible",
        "mmoitems", "mmocore", "ecoitems", "nexo"
    };

    /**
     * PDC key-name hint patterns, most identity-like first. When no preferred
     * namespace matches, the scanner picks the highest-ranked key whose name
     * contains one of these tokens (see {@link #rankKeysByHint}).
     *
     * <p>Order is load-bearing, not cosmetic: {@code *_type} keys are last
     * because they usually hold a category rather than an identity, and picking
     * one collapses every item sharing that category onto a single mapping.</p>
     */
    static final String[] PDC_ID_KEY_HINTS = {
        "item_id", "itemid", "catalog_id", "custom_id", "identifier", "id",
        "item_type", "type"
    };

    /**
     * Hint patterns for the display-name path ({@link #extractItemIdFromPDC}).
     *
     * <p>Same ranking rule as {@link #PDC_ID_KEY_HINTS} and deliberately kept
     * in step with it — both paths must agree on which PDC slot carries the
     * item's logical identity — but this one also accepts name-ish keys, which
     * are useful for a label and useless as an identifier.</p>
     */
    static final String[] DISPLAY_ID_KEY_HINTS = {
        "item_id", "itemid", "catalog_id", "custom_item", "custom_id",
        "identifier", "id", "item_name", "name", "item_type", "type"
    };

    /**
     * Whether the PDC-only scan path is enabled via configuration.
     *
     * <p>Defensive against partially-initialised plugin state (config might be
     * null during early boot in test harnesses); defaults to {@code true} so a
     * missing config does not silently disable the feature on a real server.</p>
     */
    private boolean isPdcEnabled() {
        GeyserExtraConfig config = plugin.getGeyserExtraConfig();
        if (config == null || config.customItems() == null) {
            return true;
        }
        return config.customItems().pdcEnabled();
    }

    /**
     * Scan path for items that have no CustomModelData but carry a stable
     * PersistentDataContainer identifier.
     *
     * <p>Reuses the existing {@code generateMappingName} / display-name /
     * unbreakable / creative-category helpers so the resulting
     * {@link CustomItemMapping} is structurally identical to a CMD-based
     * mapping — only the predicate side differs at registration time
     * (the extension switches to {@code hasComponent("minecraft:custom_data")}
     * when {@code customModelData == 0 && pdcIdentifier != null}).</p>
     *
     * <p>Returns {@link Optional#empty()} when the item lacks a usable PDC
     * identifier, when the identifier sanitises down to an invalid name, or
     * when an entry for the same {@code (baseItem, pdcIdentifier)} already
     * exists and needs no upgrade.</p>
     */
    private Optional<CustomItemMapping> scanPdcItem(ItemStack itemStack) {
        String pdcId = extractStableIdFromPDC(itemStack);
        if (pdcId == null) {
            return Optional.empty();
        }
        String baseItem = buildBaseItemIdentifier(itemStack);

        // An operator-curated or otherwise pre-existing entry for the same
        // (base, pdcId) is honoured as-is — it may carry an icon or CMD this
        // path could never produce, and clobbering it would lose that art.
        Optional<CustomItemMapping> existing = registry.getByPdc(baseItem, pdcId);
        if (existing.isPresent()) {
            return existing;
        }

        // Derive a Geyser-compatible name. Geyser identifiers only allow
        // [a-z0-9_\-./], so sanitise the colon out of "namespace:value" into
        // "namespace_value". Collisions (e.g. two plugins both producing
        // "fire_sword") are resolved by tagging the base material on.
        String sanitizedName = sanitizeItemId(pdcId);
        if (sanitizedName == null || sanitizedName.isBlank() || !isValidItemId(sanitizedName)) {
            return Optional.empty();
        }
        if (registry.contains(sanitizedName)) {
            String suffix = baseItem.startsWith(MINECRAFT_NAMESPACE)
                ? baseItem.substring(MINECRAFT_NAMESPACE.length())
                : baseItem;
            sanitizedName = sanitizedName + "_" + sanitizeItemId(suffix);
            if (registry.contains(sanitizedName)) {
                // Last resort: short hash of the full (base, pdcId) pair.
                sanitizedName = sanitizedName + "_"
                    + Integer.toHexString((baseItem + "::" + pdcId).hashCode() & 0xfffff);
            }
        }

        // These mappings carry no art (customModelData 0, iconPath null), so
        // it is tempting to skip registering them — that was tried on
        // 2026-07-28 and had to be reverted. Registration is what makes an
        // item placeable in the Bedrock off-hand: Bedrock allows the off-hand
        // slot only for a short vanilla whitelist (shield, totem, map, arrow),
        // and everything else needs allowOffhand on a registered custom item.
        // Dropping these registrations silently took the off-hand away from
        // every TF item that had no model. See [[batch-2026-07-28-9reqs]].
        //
        // The two costs of registering are paid elsewhere instead: the held
        // pose is restored with CustomItemBedrockOptions.displayHandheld in
        // the extension, and the display name is the open one — an item with
        // no name of its own still gets our prettified English guess.
        com.geyserextra.core.api.ArmorData armorDataPdc = extractArmorData(itemStack);
        CustomItemMapping mapping = new CustomItemMapping(
            sanitizedName,
            baseItem,
            0,                                            // customModelData (none — PDC path)
            isUnbreakable(itemStack),
            extractDisplayName(itemStack),
            null,                                         // iconPath — falls back to vanilla texture
            determineCreativeCategory(itemStack.getType()),
            null,                                         // creativeGroup
            true,                                         // register with Geyser
            pdcId,                                        // stable PDC identifier
            armorDataPdc                                  // Phase 7a armor metadata (nullable)
        );
        registry.register(mapping);
        plugin.getLogger().fine("Registered PDC custom item: " + sanitizedName
            + " (pdc=" + pdcId + ", base=" + baseItem + ")");
        return Optional.of(mapping);
    }

    /**
     * Extracts a <b>namespace-qualified</b> PDC identifier suitable as a
     * stable registry key. Returns the same identifier across server restarts
     * because the underlying plugins (Oraxen, ItemsAdder, MMOItems, etc.) keep
     * writing the same {@code NamespacedKey}+value pair to every instance of
     * their items.
     *
     * <p>Selection order, deliberately deterministic:</p>
     * <ol>
     *   <li>Walk preferred plugin namespaces ({@link #PREFERRED_PDC_NAMESPACES})
     *       in order. For each, scan the item's PDC keys (sorted lexicographically)
     *       and return the first {@code namespace:value} pair whose value is a
     *       valid item id string.</li>
     *   <li>If no preferred namespace matches, walk all PDC keys in sorted order
     *       and return the first one whose <i>key name</i> contains a hint token
     *       from {@link #PDC_ID_KEY_HINTS}.</li>
     *   <li>If nothing matches, return {@code null} — the item has PDC data but
     *       no clear identifier slot, so it is not safe to register.</li>
     * </ol>
     *
     * <p>The returned form is always {@code namespace:value} (e.g.
     * {@code "oraxen:fire_sword"}), never the bare value. Two plugins that
     * accidentally coin the same value (one's {@code "fire_sword"} and
     * another's) will therefore produce distinct registry keys.</p>
     *
     * <p>Static + no plugin/config dependencies so {@code RecipeScanner} can
     * call it from {@code createItemKey} without an extra detour through this
     * scanner instance.</p>
     */
    public static String extractStableIdFromPDC(ItemStack itemStack) {
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

        List<NamespacedKey> sortedKeys = sortKeys(pdc);

        // Phase 1: preferred plugin namespaces.
        for (String preferred : PREFERRED_PDC_NAMESPACES) {
            for (NamespacedKey key : sortedKeys) {
                if (!preferred.equalsIgnoreCase(key.getNamespace())) {
                    continue;
                }
                String value = readPdcStringSafe(pdc, key);
                if (value == null) {
                    continue;
                }
                String sanitized = sanitizeForStableId(value);
                if (!isStableIdValueValid(sanitized)) {
                    continue;
                }
                return key.getNamespace() + ":" + sanitized;
            }
        }

        // Phase 2: key-name hints across any namespace, ranked by hint.
        for (NamespacedKey key : rankKeysByHint(PDC_ID_KEY_HINTS, sortedKeys)) {
            String value = readPdcStringSafe(pdc, key);
            if (value == null) {
                continue;
            }
            String sanitized = sanitizeForStableId(value);
            if (!isStableIdValueValid(sanitized)) {
                continue;
            }
            return key.getNamespace() + ":" + sanitized;
        }

        return null;
    }

    /**
     * Snapshot + sort the PDC keys once for deterministic iteration.
     *
     * <p>HashMap-backed PDC implementations don't promise an order, so sorting
     * up-front is what makes "first match wins" stable across restarts.</p>
     */
    private static List<NamespacedKey> sortKeys(PersistentDataContainer pdc) {
        List<NamespacedKey> keys = new java.util.ArrayList<>(pdc.getKeys());
        keys.sort(java.util.Comparator.comparing(NamespacedKey::toString));
        return keys;
    }

    /**
     * Orders {@code keys} by how strongly their name suggests identity, dropping
     * keys that match no hint. Rank is the index of the first matching hint, so
     * earlier entries in {@code hints} win; ties keep the incoming order.
     *
     * <p>This is hint-major on purpose. The previous key-major walk let the
     * winner be whichever <em>matching</em> key sorted earliest, so a plugin
     * writing both an identity and a category was identified by whichever name
     * happened to come first in the alphabet. TrinityForge writes
     * {@code bind_type} and {@code catalog_id}; {@code "b" < "c"}, so all 51 of
     * its ledger entries were identified as {@code trinityforge:tradeable} (or
     * {@code :soulbound}) and every item sharing a base material collapsed onto
     * one mapping. Both PDC extraction paths route through here so they cannot
     * disagree about which slot carries identity.</p>
     */
    static List<NamespacedKey> rankKeysByHint(String[] hints, List<NamespacedKey> keys) {
        List<NamespacedKey> ranked = new java.util.ArrayList<>();
        for (String hint : hints) {
            for (NamespacedKey key : keys) {
                if (!key.getKey().toLowerCase(Locale.ROOT).contains(hint)) {
                    continue;
                }
                if (!ranked.contains(key)) {
                    ranked.add(key);
                }
            }
        }
        return ranked;
    }

    /**
     * Reads a PDC value as String, swallowing only the narrow
     * {@code IllegalArgumentException} that Bukkit throws when the stored
     * value is of a non-string type. Broader exceptions propagate so the
     * scanner does not silently mask programmer errors.
     */
    private static String readPdcStringSafe(PersistentDataContainer pdc, NamespacedKey key) {
        try {
            String value = pdc.get(key, PersistentDataType.STRING);
            return (value != null && !value.isBlank()) ? value : null;
        } catch (IllegalArgumentException nonStringValue) {
            return null;
        }
    }

    /**
     * Sanitises a raw PDC value into the canonical lower-cased, underscore-only
     * form used in the registry key. Drops separators and any chars outside
     * {@code [a-z0-9_]}. Returns {@code null} when the result would be empty.
     */
    private static String sanitizeForStableId(String raw) {
        if (raw == null) return null;
        String lower = raw.toLowerCase(Locale.ROOT)
            .replace(' ', '_')
            .replace('-', '_')
            .replace(':', '_')
            .replaceAll("[^a-z0-9_]", "");
        return lower.isBlank() ? null : lower;
    }

    /**
     * Stricter validity check than the existing {@link #isValidItemId(String)}:
     * stable identifiers must be at least 2 characters, max 64, and contain
     * only {@code [a-z0-9_]} so they survive the colon-to-underscore conversion
     * applied at Geyser registration time.
     */
    private static boolean isStableIdValueValid(String sanitized) {
        return sanitized != null
            && sanitized.length() >= 2
            && sanitized.length() <= 64
            && sanitized.matches("[a-z0-9_]+");
    }
}
