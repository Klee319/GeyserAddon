package com.geyserextra.paper.enchantment;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.events.PacketListener;
import com.geyserextra.core.config.GeyserExtraConfig.EnchantmentConfig;
import com.geyserextra.paper.GeyserExtraPaper;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.geysermc.floodgate.api.FloodgateApi;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

/**
 * Handles enchantment lore injection via ProtocolLib packet interception
 * and anvil over-enchantment protection for Bedrock players.
 *
 * <p>This handler intercepts SET_SLOT and WINDOW_ITEMS packets to inject
 * custom/over-enchantment information as lore text visible only to Bedrock players.
 * It also integrates anvil protection logic formerly in AnvilRecipeHandler,
 * preventing Bedrock clients from downgrading over-enchantments during anvil operations.</p>
 *
 * <p>Requires ProtocolLib, Floodgate, and Paper API as compileOnly dependencies.</p>
 */
public final class BedrockEnchantmentHandler implements Listener {

    private final GeyserExtraPaper plugin;
    private final FloodgateApi floodgateApi;
    private final Map<String, AnvilRecipe> customRecipes;
    private final Map<UUID, CachedAnvilResult> bedrockAnvilCache;
    private final List<PacketListener> registeredListeners = new ArrayList<>();
    private final boolean enabled;

    /**
     * Creates a new BedrockEnchantmentHandler.
     *
     * <p>Attempts to obtain FloodgateApi and ProtocolManager. If either is unavailable,
     * the handler is disabled gracefully without crashing.</p>
     *
     * @param plugin the main plugin instance, must not be null
     */
    public BedrockEnchantmentHandler(GeyserExtraPaper plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin must not be null");
        this.customRecipes = new ConcurrentHashMap<>();
        this.bedrockAnvilCache = new ConcurrentHashMap<>();

        FloodgateApi api = null;
        boolean isEnabled = false;
        try {
            api = FloodgateApi.getInstance();
            EnchantmentConfig enchantConfig = plugin.getGeyserExtraConfig().enchantment();
            isEnabled = api != null && enchantConfig.enabled();
            if (isEnabled) {
                registerPacketListeners();
                plugin.getLogger().info(
                    "BedrockEnchantmentHandler: Enchantment lore injection and anvil protection enabled"
                );
            }
        } catch (NoClassDefFoundError | Exception e) {
            plugin.getLogger().info(
                "BedrockEnchantmentHandler: Floodgate or ProtocolLib not available, disabled"
            );
        }

        this.floodgateApi = api;
        this.enabled = isEnabled;
    }

    /**
     * Whether this handler is enabled and actively processing packets/events.
     *
     * @return true if enabled
     */
    public boolean isEnabled() {
        return enabled;
    }

    // ========================================================================
    // Packet Listener Registration (ProtocolLib)
    // ========================================================================

    /**
     * Registers ProtocolLib packet listeners for SET_SLOT and WINDOW_ITEMS.
     *
     * <p>Why: Bedrock players cannot see custom or over-enchantments natively.
     * By intercepting outbound item packets, we inject enchantment info as lore
     * so Bedrock players can see what enchantments are on their items.</p>
     */
    /**
     * Removes all registered ProtocolLib listeners. Call from plugin onDisable.
     */
    public void cleanup() {
        if (!registeredListeners.isEmpty()) {
            ProtocolManager pm = ProtocolLibrary.getProtocolManager();
            for (PacketListener listener : registeredListeners) {
                pm.removePacketListener(listener);
            }
            registeredListeners.clear();
        }
        bedrockAnvilCache.clear();
    }

    private void registerPacketListeners() {
        ProtocolManager protocolManager = ProtocolLibrary.getProtocolManager();

        PacketListener listener = new PacketAdapter(
            plugin,
            ListenerPriority.NORMAL,
            PacketType.Play.Server.SET_SLOT,
            PacketType.Play.Server.WINDOW_ITEMS
        ) {
            @Override
            public void onPacketSending(PacketEvent event) {
                try {
                    if (event.getPacketType() == PacketType.Play.Server.SET_SLOT) {
                        handleSetSlotPacket(event);
                    } else {
                        handleWindowItemsPacket(event);
                    }
                } catch (Exception e) {
                    BedrockEnchantmentHandler.this.plugin.getLogger().warning(
                        "BedrockEnchantmentHandler: Error processing packet: " + e.getMessage()
                    );
                }
            }
        };
        protocolManager.addPacketListener(listener);
        registeredListeners.add(listener);
    }

    /**
     * Handles SET_SLOT packet by injecting enchantment lore for Bedrock players.
     *
     * @param event the packet event
     */
    private void handleSetSlotPacket(PacketEvent event) {
        if (!isBedrockPlayer(event.getPlayer())) {
            return;
        }

        ItemStack item = event.getPacket().getItemModifier().read(0);
        ItemStack modified = injectEnchantmentLore(item);
        if (modified != null) {
            event.getPacket().getItemModifier().write(0, modified);
        }
    }

    /**
     * Handles WINDOW_ITEMS packet by injecting enchantment lore for Bedrock players.
     *
     * <p>Uses lazy allocation: only creates a new list when at least one item is modified,
     * avoiding unnecessary ArrayList allocation for packets with no enchanted items.</p>
     *
     * @param event the packet event
     */
    private void handleWindowItemsPacket(PacketEvent event) {
        if (!isBedrockPlayer(event.getPlayer())) {
            return;
        }

        List<ItemStack> items = event.getPacket().getItemListModifier().read(0);
        if (items == null || items.isEmpty()) {
            return;
        }

        // Lazy allocation: only create modifiedList when first modification is found
        List<ItemStack> modifiedList = null;

        for (int i = 0; i < items.size(); i++) {
            ItemStack item = items.get(i);
            ItemStack modified = injectEnchantmentLore(item);

            if (modified != null) {
                if (modifiedList == null) {
                    // First modification found: copy all items up to this point
                    modifiedList = new ArrayList<>(items.size());
                    for (int j = 0; j < i; j++) {
                        modifiedList.add(items.get(j));
                    }
                }
                modifiedList.add(modified);
            } else if (modifiedList != null) {
                modifiedList.add(item);
            }
        }

        if (modifiedList != null) {
            event.getPacket().getItemListModifier().write(0, modifiedList);
        }
    }

    // ========================================================================
    // Lore Injection Logic
    // ========================================================================

    /**
     * Injects enchantment information as lore into the item for Bedrock display.
     *
     * <p>Clones the item before modification to avoid mutating the original.
     * Only adds lore lines for enchantments that match the configured criteria:
     * custom enchantments (non-minecraft namespace) and/or over-enchantments
     * (level exceeding vanilla max).</p>
     *
     * @param item the original item
     * @return a cloned item with lore injected, or null if no lore was added
     */
    private ItemStack injectEnchantmentLore(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) {
            return null;
        }

        List<Component> tooltipLines = new ArrayList<>();

        // Enchantment lore (existing feature)
        Map<Enchantment, Integer> enchantments = getEnchantments(item);
        if (!enchantments.isEmpty()) {
            EnchantmentConfig config = plugin.getGeyserExtraConfig().enchantment();
            tooltipLines.addAll(buildEnchantmentLoreLines(enchantments, config));
        }

        // Bedrock tooltip: durability display
        // Why: Bedrock Edition doesn't show numeric durability values natively.
        // Displaying remaining/max durability helps Bedrock players manage their tools.
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof org.bukkit.inventory.meta.Damageable damageable) {
            // Why: Use Damageable.getMaxDamage() first (respects custom max_damage component),
            // then fall back to Material.getMaxDurability() for vanilla items.
            // Custom items (plugins, data packs) can set max_damage higher than vanilla.
            int maxDurability = damageable.hasMaxDamage()
                ? damageable.getMaxDamage()
                : item.getType().getMaxDurability();
            if (maxDurability > 0) {
                int remaining = maxDurability - damageable.getDamage();
                // Color based on remaining percentage
                float ratio = (float) remaining / maxDurability;
                net.kyori.adventure.text.format.TextColor durColor;
                if (ratio > 0.5f) {
                    durColor = net.kyori.adventure.text.format.NamedTextColor.GREEN;
                } else if (ratio > 0.2f) {
                    durColor = net.kyori.adventure.text.format.NamedTextColor.YELLOW;
                } else {
                    durColor = net.kyori.adventure.text.format.NamedTextColor.RED;
                }
                tooltipLines.add(Component.text("耐久値: " + remaining + "/" + maxDurability)
                    .color(durColor));
            }
        }

        if (tooltipLines.isEmpty()) {
            return null;
        }

        // Clone to avoid mutating the original server-side item
        ItemStack cloned = item.clone();
        ItemMeta clonedMeta = cloned.getItemMeta();
        if (clonedMeta == null) {
            return null;
        }

        List<Component> existingLore = clonedMeta.lore();
        List<Component> newLore = existingLore != null
            ? new ArrayList<>(existingLore)
            : new ArrayList<>();
        newLore.addAll(tooltipLines);

        clonedMeta.lore(newLore);
        cloned.setItemMeta(clonedMeta);
        return cloned;
    }

    /**
     * Builds lore lines for enchantments that should be displayed to Bedrock players.
     *
     * @param enchantments the enchantments on the item
     * @param config       the enchantment configuration
     * @return list of Component lines to append to lore (may be empty)
     */
    private List<Component> buildEnchantmentLoreLines(
        Map<Enchantment, Integer> enchantments,
        EnchantmentConfig config
    ) {
        List<Component> lines = new ArrayList<>();

        for (Map.Entry<Enchantment, Integer> entry : enchantments.entrySet()) {
            Enchantment enchantment = entry.getKey();
            int level = entry.getValue();

            if (level <= 0) {
                continue;
            }

            if (shouldDisplayEnchantment(enchantment, level, config)) {
                Component line = formatEnchantmentLine(enchantment, level);
                lines.add(line);
            }
        }

        return List.copyOf(lines);
    }

    /**
     * Determines whether an enchantment should be displayed in lore.
     *
     * @param enchantment the enchantment
     * @param level       the enchantment level
     * @param config      the enchantment configuration
     * @return true if this enchantment should appear in lore
     */
    private boolean shouldDisplayEnchantment(
        Enchantment enchantment,
        int level,
        EnchantmentConfig config
    ) {
        NamespacedKey key = enchantment.getKey();

        // Custom enchantments: Geyser already displays custom enchantment names
        // for Bedrock players, so lore injection would cause duplicate display.
        // Only inject lore for over-enchantments (vanilla enchants above max level).

        // Over-enchantments: level exceeds vanilla max (guard against maxLevel <= 0)
        int maxLevel = enchantment.getMaxLevel();
        if (config.showOverEnchantments() && maxLevel > 0 && level > maxLevel) {
            return true;
        }

        return false;
    }

    /**
     * Formats a single enchantment line as an Adventure Component.
     *
     * <p>Curses are displayed in red; normal enchantments in gray.
     * Italic decoration is explicitly disabled to match vanilla lore style.</p>
     *
     * @param enchantment the enchantment
     * @param level       the enchantment level
     * @return the formatted component
     */
    private Component formatEnchantmentLine(Enchantment enchantment, int level) {
        String displayText = EnchantmentNameMapper.formatEnchantment(enchantment, level);
        boolean isCurse = EnchantmentNameMapper.isCurse(enchantment);

        NamedTextColor color = isCurse ? NamedTextColor.RED : NamedTextColor.GRAY;

        return Component.text(displayText)
            .color(color)
            .decoration(TextDecoration.ITALIC, false);
    }

    // ========================================================================
    // Enchantment Extraction Helper
    // ========================================================================

    /**
     * Extracts enchantments from an item, handling both regular items
     * and enchanted books (EnchantmentStorageMeta).
     *
     * @param item the item to extract enchantments from
     * @return a mutable map of enchantments (empty map if item is null or has none)
     */
    private Map<Enchantment, Integer> getEnchantments(ItemStack item) {
        if (item == null) {
            return Map.of();
        }

        if (item.getType() == Material.ENCHANTED_BOOK
            && item.getItemMeta() instanceof EnchantmentStorageMeta bookMeta) {
            return new HashMap<>(bookMeta.getStoredEnchants());
        }

        return new HashMap<>(item.getEnchantments());
    }

    // ========================================================================
    // Anvil Protection (migrated from AnvilRecipeHandler)
    // ========================================================================

    /**
     * Registers a custom anvil recipe.
     *
     * <p>Thread-safe: customRecipes uses ConcurrentHashMap.</p>
     *
     * @param id     the unique recipe identifier
     * @param recipe the recipe implementation
     */
    public void registerRecipe(String id, AnvilRecipe recipe) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(recipe, "recipe must not be null");
        customRecipes.put(id, recipe);
    }

    /**
     * Unregisters a custom anvil recipe by its identifier.
     *
     * @param id the recipe identifier to remove
     */
    public void unregisterRecipe(String id) {
        Objects.requireNonNull(id, "id must not be null");
        customRecipes.remove(id);
    }

    /**
     * Handles custom anvil recipe matching.
     *
     * <p>Priority HIGH: runs before the HIGHEST-priority cache handler so that
     * custom recipes are applied first, and the cache handler sees the final result.</p>
     *
     * @param event the prepare anvil event
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPrepareAnvilCustomRecipe(PrepareAnvilEvent event) {
        if (!enabled) {
            return;
        }

        AnvilInventory inventory = event.getInventory();
        ItemStack firstItem = inventory.getFirstItem();
        ItemStack secondItem = inventory.getSecondItem();

        if (firstItem == null) {
            return;
        }

        for (AnvilRecipe recipe : customRecipes.values()) {
            if (recipe.matches(firstItem, secondItem)) {
                ItemStack result = recipe.getResult(firstItem, secondItem);
                if (result != null) {
                    event.setResult(result);
                    int cost = recipe.getCost(firstItem, secondItem);
                    if (cost > 0) {
                        inventory.setRepairCost(cost);
                    }
                    break;
                }
            }
        }
    }

    /**
     * Caches the correct anvil result for Bedrock players when over-enchantments are involved.
     *
     * <p>Priority HIGHEST: runs after most other handlers have set the result,
     * so we can capture the final state and correct it for over-enchantment preservation.
     * Uses HIGHEST instead of MONITOR to comply with Bukkit event contract
     * (MONITOR should not modify event state).</p>
     *
     * @param event the prepare anvil event
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepareAnvilCacheResult(PrepareAnvilEvent event) {
        if (!enabled) {
            return;
        }

        EnchantmentConfig config = plugin.getGeyserExtraConfig().enchantment();
        if (!config.overEnchantmentProtectionEnabled()) {
            return;
        }

        AnvilInventory inventory = event.getInventory();
        ItemStack firstItem = inventory.getFirstItem();
        ItemStack secondItem = inventory.getSecondItem();
        ItemStack result = event.getResult();

        if (firstItem == null) {
            return;
        }

        for (var viewer : event.getViewers()) {
            if (!(viewer instanceof Player player) || !isBedrockPlayer(player)) {
                continue;
            }

            boolean hasOverEnchant = hasOverEnchantments(firstItem)
                || hasOverEnchantments(secondItem);
            if (!hasOverEnchant || result == null) {
                continue;
            }

            ItemStack correctResult = calculateCorrectResult(firstItem, secondItem, result);
            if (correctResult == null) {
                continue;
            }

            int cost = inventory.getRepairCost();
            bedrockAnvilCache.put(player.getUniqueId(), new CachedAnvilResult(
                firstItem.clone(),
                secondItem != null ? secondItem.clone() : null,
                correctResult,
                cost
            ));

            // Update preview display on next tick to avoid modifying during event
            final ItemStack finalResult = correctResult;
            final ItemStack cachedFirst = firstItem.clone();
            final ItemStack cachedSecond = secondItem != null ? secondItem.clone() : null;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!player.isOnline()) {
                    return;
                }
                if (!(player.getOpenInventory().getTopInventory() instanceof AnvilInventory anvilInv)) {
                    return;
                }
                // Re-validate that anvil contents haven't changed during the tick
                if (!itemsMatch(cachedFirst, anvilInv.getFirstItem())
                    || !itemsMatch(cachedSecond, anvilInv.getSecondItem())) {
                    return;
                }
                anvilInv.setResult(finalResult);
                player.updateInventory();
            }, 1L);
        }
    }

    /**
     * Intercepts anvil result slot clicks for Bedrock players to apply cached correct results.
     *
     * <p>Priority HIGH: intercepts before other handlers to ensure the correct
     * over-enchantment result is applied instead of the Bedrock-downgraded version.</p>
     *
     * @param event the inventory click event
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!enabled || event.getInventory().getType() != InventoryType.ANVIL) {
            return;
        }

        if (!(event.getWhoClicked() instanceof Player player) || !isBedrockPlayer(player)) {
            return;
        }

        // Only handle result slot (slot 2)
        if (event.getRawSlot() != 2) {
            return;
        }

        // Guard: skip if result slot is empty (prevents item creation from nothing)
        ItemStack currentResult = event.getCurrentItem();
        if (currentResult == null || currentResult.getType() == Material.AIR) {
            return;
        }

        CachedAnvilResult cached = bedrockAnvilCache.remove(player.getUniqueId());
        if (cached == null) {
            // No over-enchantment cache — let vanilla anvil handle normally.
            // Do NOT call updateInventory() here as it races with vanilla processing
            // and reverts the result (book combining would appear to fail).
            return;
        }

        AnvilInventory anvil = (AnvilInventory) event.getInventory();
        if (!itemsMatch(cached.firstItem(), anvil.getFirstItem())
            || !itemsMatch(cached.secondItem(), anvil.getSecondItem())) {
            return;
        }

        if (player.getLevel() < cached.cost() && player.getGameMode() != GameMode.CREATIVE) {
            return;
        }

        event.setCancelled(true);
        applyAnvilResult(player, anvil, cached);
    }

    /**
     * Cleans up anvil cache when a player closes an inventory.
     *
     * <p>Why: Without cleanup, cache entries for players who open an anvil but
     * never click the result slot would accumulate indefinitely (memory leak).</p>
     *
     * @param event the inventory close event
     */
    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!enabled) {
            return;
        }
        if (event.getInventory().getType() == InventoryType.ANVIL
            && event.getPlayer() instanceof Player player) {
            bedrockAnvilCache.remove(player.getUniqueId());
        }
    }

    /**
     * Cleans up anvil cache when a player disconnects.
     *
     * <p>Why: Failsafe cleanup in case InventoryCloseEvent was not fired
     * (e.g., network disconnect without proper close sequence).</p>
     *
     * @param event the player quit event
     */
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (!enabled) {
            return;
        }
        bedrockAnvilCache.remove(event.getPlayer().getUniqueId());
    }

    // ========================================================================
    // Anvil Calculation Helpers
    // ========================================================================

    /**
     * Checks whether an item has any enchantments exceeding vanilla max levels.
     *
     * @param item the item to check
     * @return true if any enchantment level exceeds its vanilla maximum
     */
    private boolean hasOverEnchantments(ItemStack item) {
        if (item == null) {
            return false;
        }

        for (var entry : getEnchantments(item).entrySet()) {
            int maxLevel = entry.getKey().getMaxLevel();
            if (maxLevel > 0 && entry.getValue() > maxLevel) {
                return true;
            }
        }
        return false;
    }

    /**
     * Calculates the correct anvil result preserving over-enchantment levels.
     *
     * <p>Why: The vanilla anvil result may have levels capped at vanilla maximums
     * by the Bedrock client. This method recalculates the result using server-side
     * enchantment data to ensure over-enchantments are preserved correctly.</p>
     *
     * @param firstItem     the first anvil input
     * @param secondItem    the second anvil input
     * @param vanillaResult the vanilla-computed result
     * @return the corrected result, or null if combination should be blocked
     */
    private ItemStack calculateCorrectResult(
        ItemStack firstItem,
        ItemStack secondItem,
        ItemStack vanillaResult
    ) {
        if (vanillaResult == null) {
            return null;
        }

        ItemStack result = vanillaResult.clone();
        ItemMeta resultMeta = result.getItemMeta();
        if (resultMeta == null) {
            return vanillaResult;
        }

        Map<Enchantment, Integer> firstEnchants = getEnchantments(firstItem);
        Map<Enchantment, Integer> secondEnchants = getEnchantments(secondItem);
        Map<Enchantment, Integer> vanillaEnchants = getEnchantments(vanillaResult);
        Map<Enchantment, Integer> combinedEnchants = new HashMap<>(firstEnchants);

        boolean levelUpEnabled = plugin.getGeyserExtraConfig()
            .enchantment()
            .overEnchantmentLevelUpEnabled();

        // Merge second item's enchantments with conflict checking
        for (var entry : secondEnchants.entrySet()) {
            Enchantment enchant = entry.getKey();
            int secondLevel = entry.getValue();
            int currentLevel = combinedEnchants.getOrDefault(enchant, 0);

            // Check conflicts in both directions for custom enchantment compatibility
            boolean hasConflict = combinedEnchants.keySet().stream()
                .anyMatch(e -> !e.equals(enchant)
                    && (enchant.conflictsWith(e) || e.conflictsWith(enchant)));

            if (!hasConflict) {
                if (secondLevel > currentLevel) {
                    combinedEnchants.put(enchant, secondLevel);
                } else if (secondLevel == currentLevel && currentLevel > 0) {
                    int maxLevel = enchant.getMaxLevel();
                    boolean isOverEnchant = maxLevel > 0 && currentLevel >= maxLevel;
                    if (isOverEnchant && !levelUpEnabled) {
                        // Block combining when over-enchant level-up is disabled
                        return null;
                    } else if (currentLevel < 255) {
                        combinedEnchants.put(enchant, currentLevel + 1);
                    }
                }
            }
        }

        // Preserve any vanilla-result enchantments not already in combined set
        for (var entry : vanillaEnchants.entrySet()) {
            combinedEnchants.putIfAbsent(entry.getKey(), entry.getValue());
        }

        // Apply combined enchantments to result
        applyEnchantmentsToMeta(result, resultMeta, combinedEnchants);
        result.setItemMeta(resultMeta);
        return result;
    }

    /**
     * Applies enchantments to item meta, handling both regular items and enchanted books.
     *
     * <p>Copies the keySet before iteration to avoid ConcurrentModificationException,
     * since removeStoredEnchant/removeEnchant may modify the underlying map.</p>
     *
     * @param item              the item (used to check type)
     * @param meta              the item meta to modify
     * @param enchantments      the enchantments to apply
     */
    private void applyEnchantmentsToMeta(
        ItemStack item,
        ItemMeta meta,
        Map<Enchantment, Integer> enchantments
    ) {
        boolean isBook = item.getType() == Material.ENCHANTED_BOOK;

        if (isBook && meta instanceof EnchantmentStorageMeta bookMeta) {
            // Copy keySet to avoid ConcurrentModificationException
            new ArrayList<>(bookMeta.getStoredEnchants().keySet())
                .forEach(bookMeta::removeStoredEnchant);
            enchantments.forEach((e, l) -> bookMeta.addStoredEnchant(e, l, true));
        } else {
            // Copy keySet to avoid ConcurrentModificationException
            new ArrayList<>(meta.getEnchants().keySet())
                .forEach(meta::removeEnchant);
            enchantments.forEach((e, l) -> meta.addEnchant(e, l, true));
        }
    }

    /**
     * Applies the cached anvil result: clears anvil slots, deducts XP, and gives item.
     *
     * <p>XP deduction is guarded with Math.max(0, ...) to prevent negative levels
     * in case of TOCTOU race between level check and deduction.</p>
     *
     * <p>Any inventory overflow is dropped naturally at the player's location.</p>
     *
     * @param player the player
     * @param anvil  the anvil inventory
     * @param cached the cached result to apply
     */
    private void applyAnvilResult(Player player, AnvilInventory anvil, CachedAnvilResult cached) {
        anvil.setFirstItem(null);

        // Consume only 1 from secondItem (e.g., repair material may be stacked)
        // Clone to avoid mutating the server's internal ItemStack reference
        ItemStack secondItem = anvil.getSecondItem();
        if (secondItem != null && secondItem.getAmount() > 1) {
            ItemStack remaining = secondItem.clone();
            remaining.setAmount(remaining.getAmount() - 1);
            anvil.setSecondItem(remaining);
        } else {
            anvil.setSecondItem(null);
        }

        anvil.setResult(null);

        if (player.getGameMode() != GameMode.CREATIVE) {
            player.setLevel(Math.max(0, player.getLevel() - cached.cost()));
        }

        HashMap<Integer, ItemStack> overflow = player.getInventory()
            .addItem(cached.result().clone());
        overflow.values().forEach(
            overflowItem -> player.getWorld().dropItemNaturally(player.getLocation(), overflowItem)
        );

        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                player.closeInventory();
                player.updateInventory();
            }
        });
    }

    /**
     * Checks whether two items match for anvil cache validation.
     *
     * <p>Compares type, amount, display name, and enchantments to prevent
     * cache misuse when items are swapped between cache creation and click.</p>
     *
     * @param cached  the cached item
     * @param current the current item in the anvil
     * @return true if the items are considered matching
     */
    private boolean itemsMatch(ItemStack cached, ItemStack current) {
        if (cached == null && current == null) {
            return true;
        }
        if (cached == null || current == null) {
            return false;
        }
        if (cached.getType() != current.getType()) {
            return false;
        }
        if (cached.getAmount() != current.getAmount()) {
            return false;
        }

        // Compare enchantments (covers both regular items and enchanted books)
        if (!getEnchantments(cached).equals(getEnchantments(current))) {
            return false;
        }

        ItemMeta cachedMeta = cached.getItemMeta();
        ItemMeta currentMeta = current.getItemMeta();
        if (cachedMeta != null && currentMeta != null) {
            if (cachedMeta.hasDisplayName() != currentMeta.hasDisplayName()) {
                return false;
            }
            if (cachedMeta.hasDisplayName()
                && !Objects.equals(cachedMeta.displayName(), currentMeta.displayName())) {
                return false;
            }
        }
        return true;
    }

    // ========================================================================
    // Utility
    // ========================================================================

    /**
     * Safely retrieves the player's UUID, returning null for temporary players.
     *
     * <p>During the login process, Paper creates temporary player objects that
     * throw UnsupportedOperationException when getUniqueId() is called.
     * ProtocolLib packet listeners can fire during this phase, so we must
     * handle this gracefully.</p>
     *
     * @param player the player to get UUID from
     * @return the player's UUID, or null if unavailable (temporary player)
     */
    private UUID getPlayerUuidSafely(Player player) {
        try {
            return player.getUniqueId();
        } catch (UnsupportedOperationException e) {
            return null;
        }
    }

    /**
     * Checks whether a player is a Bedrock player via FloodgateApi.
     *
     * <p>Returns false for temporary players whose UUID cannot be retrieved.</p>
     *
     * @param player the player to check
     * @return true if the player is connected via Bedrock Edition
     */
    private boolean isBedrockPlayer(Player player) {
        UUID uuid = getPlayerUuidSafely(player);
        return uuid != null && floodgateApi != null && floodgateApi.isFloodgatePlayer(uuid);
    }

    // ========================================================================
    // Inner Types
    // ========================================================================

    /**
     * Represents a custom anvil recipe that can be registered with this handler.
     */
    public interface AnvilRecipe {
        boolean matches(ItemStack firstItem, ItemStack secondItem);
        ItemStack getResult(ItemStack firstItem, ItemStack secondItem);
        default int getCost(ItemStack firstItem, ItemStack secondItem) { return 0; }
    }

    /**
     * A simple anvil recipe implementation that matches by material type.
     */
    public static class SimpleAnvilRecipe implements AnvilRecipe {

        private final Material firstMaterial;
        private final Material secondMaterial;
        private final BiFunction<ItemStack, ItemStack, ItemStack> resultFunction;
        private final int cost;

        public SimpleAnvilRecipe(
            Material first,
            Material second,
            BiFunction<ItemStack, ItemStack, ItemStack> resultFn,
            int cost
        ) {
            this.firstMaterial = Objects.requireNonNull(first, "first material must not be null");
            this.secondMaterial = second;
            this.resultFunction = Objects.requireNonNull(resultFn, "resultFn must not be null");
            this.cost = cost;
        }

        @Override
        public boolean matches(ItemStack first, ItemStack second) {
            if (first == null || first.getType() != firstMaterial) {
                return false;
            }
            if (secondMaterial == null) {
                return second == null || second.getType() == Material.AIR;
            }
            return second != null && second.getType() == secondMaterial;
        }

        @Override
        public ItemStack getResult(ItemStack first, ItemStack second) {
            return resultFunction.apply(first, second);
        }

        @Override
        public int getCost(ItemStack first, ItemStack second) {
            return cost;
        }
    }

    /**
     * Cached anvil result for a Bedrock player, used to preserve over-enchantments.
     */
    private record CachedAnvilResult(
        ItemStack firstItem,
        ItemStack secondItem,
        ItemStack result,
        int cost
    ) {}
}
