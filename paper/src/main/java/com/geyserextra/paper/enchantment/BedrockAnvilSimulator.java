package com.geyserextra.paper.enchantment;

import com.geyserextra.core.config.GeyserExtraConfig.EnchantmentConfig;
import com.geyserextra.paper.GeyserExtraPaper;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.geysermc.floodgate.api.FloodgateApi;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import org.bukkit.NamespacedKey;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simulates anvil UI as a chest inventory (GENERIC_9x3) for Bedrock players.
 *
 * <p>Bedrock clients enforce their own anvil validation, blocking custom/over-enchantments.
 * This simulator intercepts anvil open events for Bedrock players, cancels them,
 * and opens a chest-based UI instead. All enchantment merging is calculated server-side,
 * bypassing Bedrock's client-side restrictions entirely.</p>
 *
 * <p>This approach uses only Bukkit API -- no ProtocolLib dependency required.</p>
 */
public final class BedrockAnvilSimulator implements Listener {

    // --- Chest layout slot indices ---
    private static final int SLOT_LEFT_INPUT = 10;
    private static final int SLOT_RIGHT_INPUT = 12;
    private static final int SLOT_ARROW = 14;
    private static final int SLOT_RESULT = 15;
    private static final int CHEST_SIZE = 27;

    private static final Component CHEST_TITLE = Component.text("Anvil");

    /** Pre-built filler pane (gray stained glass with blank name). */
    private static final ItemStack FILLER;

    /** Pre-built arrow indicator item. */
    private static final ItemStack ARROW_INDICATOR;

    static {
        FILLER = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta fillerMeta = FILLER.getItemMeta();
        if (fillerMeta != null) {
            fillerMeta.displayName(Component.text(" "));
            FILLER.setItemMeta(fillerMeta);
        }

        ARROW_INDICATOR = new ItemStack(Material.ARROW);
        ItemMeta arrowMeta = ARROW_INDICATOR.getItemMeta();
        if (arrowMeta != null) {
            arrowMeta.displayName(Component.text(" "));
            ARROW_INDICATOR.setItemMeta(arrowMeta);
        }
    }

    private final GeyserExtraPaper plugin;
    private final FloodgateApi floodgateApi;
    private final boolean enabled;
    private final boolean overEnchantLevelUpEnabled;
    private final Map<UUID, AnvilSession> sessions;

    /**
     * Tracks players currently having their vanilla anvil re-opened.
     * Why needed: reopenVanillaAnvil triggers another InventoryOpenEvent,
     * which would cause an infinite loop without this bypass flag.
     */
    private final java.util.Set<UUID> bypassingPlayers = ConcurrentHashMap.newKeySet();

    // ========================================================================
    // Inner class: per-player session state
    // ========================================================================

    /**
     * Tracks the state of a simulated anvil session for one player.
     * Mutable by design -- updated as the player interacts with slots.
     */
    private static final class AnvilSession {
        private final Inventory chestInventory;
        private ItemStack resultItem;
        private int repairCost;

        AnvilSession(Inventory chestInventory) {
            this.chestInventory = Objects.requireNonNull(chestInventory);
            this.resultItem = null;
            this.repairCost = 0;
        }
    }

    // ========================================================================
    // Constructor
    // ========================================================================

    public BedrockAnvilSimulator(GeyserExtraPaper plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin must not be null");
        this.sessions = new ConcurrentHashMap<>();

        FloodgateApi api = null;
        boolean isEnabled = false;
        boolean levelUp = false;

        try {
            api = FloodgateApi.getInstance();
            EnchantmentConfig enchantConfig = plugin.getGeyserExtraConfig().enchantment();
            isEnabled = api != null && enchantConfig.anvilSimulationEnabled();
            levelUp = enchantConfig.overEnchantmentLevelUpEnabled();

            if (isEnabled) {
                plugin.getLogger().info(
                    "BedrockAnvilSimulator: Anvil-to-chest simulation enabled (Bukkit API mode)");
            }
        } catch (NoClassDefFoundError | Exception e) {
            plugin.getLogger().info(
                "BedrockAnvilSimulator: Floodgate not available, disabled");
        }

        this.floodgateApi = api;
        this.enabled = isEnabled;
        this.overEnchantLevelUpEnabled = levelUp;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Cleans up all active sessions, returning items to online players.
     * Called from plugin onDisable.
     */
    public void cleanup() {
        for (Map.Entry<UUID, AnvilSession> entry : sessions.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null && player.isOnline()) {
                returnInputItems(player, entry.getValue());
            }
        }
        sessions.clear();
    }

    // ========================================================================
    // Bukkit Event Listeners
    // ========================================================================

    /**
     * Intercepts anvil open events for Bedrock players and redirects to chest UI.
     *
     * Why HIGH priority: we need to cancel before other plugins process the event,
     * but after plugins that might modify the anvil behavior.
     *
     * Why cancel before sneak check: Bedrock clients send sneak state and block
     * interaction as separate packets. The sneak packet may arrive AFTER the
     * interaction event, causing player.isSneaking() to return false when the
     * player is actually sneaking. We cancel unconditionally for Bedrock players,
     * then delay the sneak check by 1 tick to let the sneak state packet arrive.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!enabled) return;
        if (!(event.getPlayer() instanceof Player player)) return;
        if (event.getInventory().getType() != InventoryType.ANVIL) return;
        if (!isBedrockPlayer(player)) return;

        // Why bypass check: reopenVanillaAnvil fires another InventoryOpenEvent.
        // Without this guard, we would enter an infinite cancel-reopen loop.
        if (bypassingPlayers.remove(player.getUniqueId())) return;

        // Capture the anvil block location before cancelling.
        // Why: reopenVanillaAnvil needs the real block location to open a proper
        // server-backed anvil. Bukkit.createInventory(null, ANVIL) creates a fake
        // anvil that can't complete operations (result slot click rolls back).
        org.bukkit.Location anvilLocation = event.getInventory().getLocation();

        // Why cancel before sneak check: the sneak state packet from Bedrock
        // clients may not have arrived yet, so we must cancel now and re-evaluate
        // after a 1-tick delay.
        event.setCancelled(true);

        // Why 1-tick delay: allows the Bedrock sneak state packet to arrive
        // before we check shouldSimulate, preventing the race condition where
        // isSneaking() returns false for a player who is actually sneaking.
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (shouldSimulate(player)) {
                openSimulatedAnvil(player);
            } else {
                // Why re-open vanilla anvil: player is sneaking and the simulation
                // mode dictates they should use the vanilla anvil UI.
                reopenVanillaAnvil(player, anvilLocation);
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!enabled) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;

        AnvilSession session = sessions.get(player.getUniqueId());
        if (session == null) return;
        if (!event.getInventory().equals(session.chestInventory)) return;

        int rawSlot = event.getRawSlot();

        // Clicks in the player's own inventory area are allowed freely
        if (rawSlot >= CHEST_SIZE) {
            return;
        }

        // Only input slots and result slot are interactive
        if (rawSlot == SLOT_LEFT_INPUT || rawSlot == SLOT_RIGHT_INPUT) {
            handleInputSlotClick(event, player, session, rawSlot);
            return;
        }

        if (rawSlot == SLOT_RESULT) {
            handleResultSlotClick(event, player, session);
            return;
        }

        // All other chest slots are filler -- block interaction
        event.setCancelled(true);
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!enabled) return;
        if (!(event.getPlayer() instanceof Player player)) return;

        AnvilSession session = sessions.remove(player.getUniqueId());
        if (session == null) return;

        returnInputItems(player, session);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        bypassingPlayers.remove(event.getPlayer().getUniqueId());
        AnvilSession session = sessions.remove(event.getPlayer().getUniqueId());
        if (session == null) return;

        returnInputItems(event.getPlayer(), session);
    }

    // ========================================================================
    // Simulated Anvil Operations
    // ========================================================================

    /**
     * Re-opens the vanilla anvil UI for a Bedrock player whose interaction
     * event was pre-emptively cancelled.
     *
     * Why needed: because we cancel the InventoryOpenEvent before the sneak
     * state is known, we must manually re-open the vanilla anvil when the
     * delayed check reveals the player should NOT use the simulated UI.
     *
     * <p>{@code @SuppressWarnings("deprecation")} on HumanEntity#openAnvil:
     * Paper marked the {@code openAnvil(Location, boolean)} overload
     * deprecated but has not published a public replacement that opens a
     * vanilla anvil container at a specific block location with the same
     * force-open semantics. Until a typed builder lands, the deprecated call
     * remains the only way to achieve the required behaviour. The method is
     * not marked {@code [removal]}.</p>
     */
    @SuppressWarnings("deprecation")
    private void reopenVanillaAnvil(Player player, org.bukkit.Location anvilLocation) {
        // Why set bypass flag: opening a vanilla anvil triggers onInventoryOpen again.
        // The flag prevents re-cancellation and infinite recursion.
        bypassingPlayers.add(player.getUniqueId());

        if (anvilLocation != null) {
            // Use Paper API to open a real server-backed anvil at the block location.
            // Why: player.openAnvil(location, true) creates a proper anvil container
            // tied to the actual block, allowing full vanilla anvil operations
            // (combining, renaming, result slot extraction).
            player.openAnvil(anvilLocation, true);
        } else {
            // Fallback: if location is unknown (shouldn't happen in normal gameplay),
            // open at the player's location
            player.openAnvil(player.getLocation(), true);
        }
    }

    /**
     * Creates and opens a chest-based anvil UI for the given player.
     */
    private void openSimulatedAnvil(Player player) {
        Inventory chest = Bukkit.createInventory(null, CHEST_SIZE, CHEST_TITLE);

        // Fill all slots with filler panes
        for (int i = 0; i < CHEST_SIZE; i++) {
            chest.setItem(i, FILLER.clone());
        }

        // Clear interactive slots (input slots start empty)
        chest.setItem(SLOT_LEFT_INPUT, null);
        chest.setItem(SLOT_RIGHT_INPUT, null);
        chest.setItem(SLOT_RESULT, null);
        chest.setItem(SLOT_ARROW, ARROW_INDICATOR.clone());

        AnvilSession session = new AnvilSession(chest);
        sessions.put(player.getUniqueId(), session);

        player.openInventory(chest);
    }

    /**
     * Handles clicks on input slots (left or right).
     * Allows standard item placement/swap behavior, then recalculates result.
     */
    private void handleInputSlotClick(
            InventoryClickEvent event,
            Player player,
            AnvilSession session,
            int slot) {
        // Allow the click to process naturally (item swap/place)
        // Then recalculate result on the next tick after Bukkit finishes processing
        Bukkit.getScheduler().runTask(plugin, () -> recalculateResult(session));
    }

    /**
     * Handles clicks on the result slot.
     * If a valid result exists, gives it to the player and consumes inputs + XP.
     */
    private void handleResultSlotClick(
            InventoryClickEvent event,
            Player player,
            AnvilSession session) {
        event.setCancelled(true);

        if (session.resultItem == null) return;

        int cost = session.repairCost;

        // Check if player has enough XP levels (creative mode bypasses cost)
        if (!player.getGameMode().name().equals("CREATIVE") && player.getLevel() < cost) {
            player.sendActionBar(
                Component.text("Not enough experience levels! (Need " + cost + ")")
                    .color(NamedTextColor.RED));
            return;
        }

        // Give result to cursor
        ItemStack result = session.resultItem.clone();
        player.setItemOnCursor(result);

        // Consume inputs
        session.chestInventory.setItem(SLOT_LEFT_INPUT, null);
        session.chestInventory.setItem(SLOT_RIGHT_INPUT, null);

        // Deduct XP (skip for creative)
        if (!player.getGameMode().name().equals("CREATIVE")) {
            player.setLevel(player.getLevel() - cost);
        }

        // Clear result
        session.resultItem = null;
        session.repairCost = 0;
        session.chestInventory.setItem(SLOT_RESULT, null);
    }

    /**
     * Recalculates the anvil result based on current input slot contents.
     * Updates the result slot and repair cost in the session.
     */
    private void recalculateResult(AnvilSession session) {
        ItemStack left = session.chestInventory.getItem(SLOT_LEFT_INPUT);
        ItemStack right = session.chestInventory.getItem(SLOT_RIGHT_INPUT);

        // Clear result if either input is missing
        if (isEmptyItem(left) || isEmptyItem(right)) {
            session.resultItem = null;
            session.repairCost = 0;
            session.chestInventory.setItem(SLOT_RESULT, null);
            return;
        }

        ItemStack result = calculateAnvilResult(left, right);
        if (result == null) {
            session.resultItem = null;
            session.repairCost = 0;
            session.chestInventory.setItem(SLOT_RESULT, null);
            return;
        }

        int cost = calculateRepairCost(left, right, result);
        session.resultItem = result;
        session.repairCost = cost;

        // Add cost lore to the displayed result item
        ItemStack displayResult = result.clone();
        addCostIndicator(displayResult, cost);
        session.chestInventory.setItem(SLOT_RESULT, displayResult);
    }

    // ========================================================================
    // Anvil Result Calculation
    // ========================================================================

    /**
     * Calculates the anvil merge result for two items.
     *
     * <p>Supports:</p>
     * <ul>
     *   <li>Same-type item combining: merges enchantments, highest level wins</li>
     *   <li>Enchanted book application: applies stored enchantments to the target item</li>
     *   <li>No enchantment compatibility restrictions (allows custom/over-enchants)</li>
     * </ul>
     *
     * @param left  The target item (base)
     * @param right The sacrifice item or enchanted book
     * @return The merged result, or null if no valid combination exists
     */
    private ItemStack calculateAnvilResult(ItemStack left, ItemStack right) {
        if (isEmptyItem(left) || isEmptyItem(right)) return null;

        Map<Enchantment, Integer> rightEnchants = extractEnchantments(right);
        boolean isBook = right.getType() == Material.ENCHANTED_BOOK;

        // For non-book items, types must match
        if (!isBook && left.getType() != right.getType()) return null;

        // Books can only be applied to enchantable items or other books
        if (isBook && rightEnchants.isEmpty()) return null;

        Map<Enchantment, Integer> leftEnchants = extractEnchantments(left);

        // If neither side has enchantments, no valid anvil operation
        if (leftEnchants.isEmpty() && rightEnchants.isEmpty()) return null;

        // Why: Prevent enchanted books from being applied to non-enchantable items (e.g. dirt, stone)
        // while preserving compatibility with plugin-added custom enchantments and over-enchantments.
        // Only vanilla enchantments are checked via canEnchantItem() because plugin-added
        // enchantments may not properly implement this method for their intended targets.
        if (isBook && left.getType() != Material.ENCHANTED_BOOK && leftEnchants.isEmpty()) {
            boolean anyCompatible = rightEnchants.keySet().stream()
                .anyMatch(e -> !isVanillaEnchantment(e) || e.canEnchantItem(left));
            if (!anyCompatible) return null;
        }

        // Clone the left item as the base for the result
        ItemStack result = left.clone();
        Map<Enchantment, Integer> mergedEnchants = new HashMap<>(leftEnchants);
        boolean changed = false;

        for (Map.Entry<Enchantment, Integer> entry : rightEnchants.entrySet()) {
            Enchantment enchant = entry.getKey();
            int rightLevel = entry.getValue();
            int currentLevel = mergedEnchants.getOrDefault(enchant, 0);

            // Why: Skip incompatible vanilla enchantments from books when they are new to the item,
            // but allow if already present (plugin-applied), target is another book,
            // or the enchantment is from a plugin (non-vanilla namespace)
            if (isBook && currentLevel == 0
                    && left.getType() != Material.ENCHANTED_BOOK
                    && isVanillaEnchantment(enchant)
                    && !enchant.canEnchantItem(left)) {
                continue;
            }

            if (currentLevel == 0) {
                // New enchantment being added
                mergedEnchants.put(enchant, rightLevel);
                changed = true;
            } else if (currentLevel == rightLevel) {
                // Same-level combining: level up by 1.
                // Why: vanilla allows free level-up up to the enchantment's max level
                // (e.g. Fortune I + Fortune I = Fortune II). The overEnchantLevelUpEnabled
                // flag only governs the over-max case (e.g. Fortune III + III = IV).
                int newLevel = currentLevel + 1;
                int maxLevel = enchant.getMaxLevel();
                boolean wouldExceedMax = maxLevel > 0 && newLevel > maxLevel;
                if ((!wouldExceedMax || overEnchantLevelUpEnabled) && newLevel <= 255) {
                    mergedEnchants.put(enchant, newLevel);
                    changed = true;
                }
                // else: would exceed vanilla max and over-enchant level-up disabled — keep current level
            } else if (rightLevel > currentLevel) {
                // Higher level from sacrifice wins
                mergedEnchants.put(enchant, rightLevel);
                changed = true;
            }
            // If currentLevel > rightLevel, left enchantment stays (no change needed)
        }

        // For same-type combining without enchantment changes, still allow (durability repair)
        if (!changed && !isBook && left.getType() == right.getType()) {
            // Same type items can still be combined for durability even without enchant changes
            if (left.getType().getMaxDurability() > 0) {
                return repairDurability(result, right);
            }
            return null;
        }

        if (!changed) return null;

        // Apply merged enchantments to result
        applyEnchantments(result, mergedEnchants);

        // Handle durability repair for same-type combining
        if (!isBook && left.getType() == right.getType() && left.getType().getMaxDurability() > 0) {
            result = repairDurability(result, right);
        }

        return result;
    }

    /**
     * Extracts enchantments from an item, handling both regular items and enchanted books.
     */
    private Map<Enchantment, Integer> extractEnchantments(ItemStack item) {
        if (isEmptyItem(item)) return Map.of();

        ItemMeta meta = item.getItemMeta();
        if (meta instanceof EnchantmentStorageMeta bookMeta) {
            Map<Enchantment, Integer> stored = bookMeta.getStoredEnchants();
            return stored.isEmpty() ? Map.of() : new HashMap<>(stored);
        }

        Map<Enchantment, Integer> enchants = item.getEnchantments();
        return enchants.isEmpty() ? Map.of() : new HashMap<>(enchants);
    }

    /**
     * Checks whether an enchantment belongs to the vanilla Minecraft namespace.
     *
     * Why: Only vanilla enchantments should be subject to canEnchantItem() checks.
     * Plugin-added custom enchantments may not properly implement canEnchantItem()
     * for their intended target items, so they must be exempt from this validation.
     *
     * @param enchantment the enchantment to check
     * @return true if the enchantment is from the vanilla minecraft namespace
     */
    private boolean isVanillaEnchantment(Enchantment enchantment) {
        NamespacedKey key = enchantment.getKey();
        return NamespacedKey.MINECRAFT.equals(key.getNamespace());
    }

    /**
     * Applies enchantments to an item, clearing existing enchantments first.
     * Uses unsafe enchantment application to allow over-enchant levels.
     */
    private void applyEnchantments(ItemStack item, Map<Enchantment, Integer> enchantments) {
        // Why: Enchanted books store enchantments in EnchantmentStorageMeta (storedEnchants),
        // not in the ItemStack's enchantment map. Using addUnsafeEnchantment on a book
        // adds to BOTH locations, causing double Lore display on Bedrock clients.
        if (item.getItemMeta() instanceof org.bukkit.inventory.meta.EnchantmentStorageMeta storageMeta) {
            // Why copy keySet: getStoredEnchants() returns a live view backed by the
            // meta's internal map, so iterating it while calling removeStoredEnchant
            // can throw ConcurrentModificationException. Mirrors the safe pattern in
            // BedrockEnchantmentHandler.applyEnchantmentsToMeta.
            for (Enchantment existing : new java.util.ArrayList<>(storageMeta.getStoredEnchants().keySet())) {
                storageMeta.removeStoredEnchant(existing);
            }
            // Apply new enchantments to stored enchants (allows over-enchant levels)
            for (Map.Entry<Enchantment, Integer> entry : enchantments.entrySet()) {
                storageMeta.addStoredEnchant(entry.getKey(), entry.getValue(), true);
            }
            item.setItemMeta(storageMeta);
        } else {
            // Non-book items: use standard enchantment map. Copy the keySet for the
            // same reason as the book branch above.
            for (Enchantment existing : new java.util.ArrayList<>(item.getEnchantments().keySet())) {
                item.removeEnchantment(existing);
            }
            for (Map.Entry<Enchantment, Integer> entry : enchantments.entrySet()) {
                item.addUnsafeEnchantment(entry.getKey(), entry.getValue());
            }
        }
    }

    /**
     * Repairs durability when combining two same-type items.
     * Adds 12% max durability bonus on top of combining remaining durability.
     *
     * <p>Why {@code Damageable#getDamage}/{@code setDamage} rather than the
     * legacy {@code ItemStack#getDurability}/{@code setDurability}: Paper
     * marks the {@code ItemStack} methods as deprecated since they conflate
     * "damage" (what is stored) with "durability" (what is remaining). The
     * {@link org.bukkit.inventory.meta.Damageable} accessors operate on the
     * same underlying data with modern, non-ambiguous semantics, and are what
     * Paper recommends for code that wants to keep compiling cleanly.</p>
     */
    private ItemStack repairDurability(ItemStack result, ItemStack sacrifice) {
        int maxDurability = result.getType().getMaxDurability();
        if (maxDurability <= 0) return result;

        int leftDamage = damageOf(result);
        int rightDamage = damageOf(sacrifice);
        int leftRemaining = maxDurability - leftDamage;
        int rightRemaining = maxDurability - rightDamage;
        int bonus = (int) (maxDurability * 0.12);
        int totalRemaining = Math.min(maxDurability, leftRemaining + rightRemaining + bonus);

        setDamage(result, maxDurability - totalRemaining);
        return result;
    }

    /**
     * Reads the damage value via the modern Damageable accessor when the
     * meta supports it, falling back to 0 (full durability) when not.
     */
    private static int damageOf(ItemStack stack) {
        if (stack == null) return 0;
        ItemMeta meta = stack.getItemMeta();
        if (meta instanceof org.bukkit.inventory.meta.Damageable damageable) {
            return damageable.getDamage();
        }
        return 0;
    }

    /**
     * Writes the damage value via the modern Damageable accessor. No-op when
     * the item has no meta or the meta is not damageable.
     */
    private static void setDamage(ItemStack stack, int damage) {
        if (stack == null) return;
        ItemMeta meta = stack.getItemMeta();
        if (meta instanceof org.bukkit.inventory.meta.Damageable damageable) {
            damageable.setDamage(Math.max(0, damage));
            stack.setItemMeta(meta);
        }
    }

    /**
     * Calculates the XP level cost for an anvil operation.
     * Uses a simplified formula based on the total enchantment levels added.
     *
     * @param left   The original left input
     * @param right  The sacrifice input
     * @param result The calculated result
     * @return The XP level cost (minimum 1)
     */
    private int calculateRepairCost(ItemStack left, ItemStack right, ItemStack result) {
        Map<Enchantment, Integer> leftEnchants = extractEnchantments(left);
        Map<Enchantment, Integer> resultEnchants = extractEnchantments(result);

        int cost = 0;

        for (Map.Entry<Enchantment, Integer> entry : resultEnchants.entrySet()) {
            Enchantment enchant = entry.getKey();
            int resultLevel = entry.getValue();
            int originalLevel = leftEnchants.getOrDefault(enchant, 0);

            if (resultLevel > originalLevel) {
                // Cost scales with the level difference and the rarity multiplier
                int diff = resultLevel - originalLevel;
                int rarityMultiplier = getEnchantmentRarityMultiplier(enchant);
                cost += diff * rarityMultiplier;
            } else if (originalLevel == 0 && resultLevel > 0) {
                // Brand new enchantment
                cost += resultLevel * getEnchantmentRarityMultiplier(enchant);
            }
        }

        // Durability repair adds a flat cost
        if (left.getType() == right.getType() && left.getType().getMaxDurability() > 0) {
            cost += 2;
        }

        // Minimum cost of 1 level
        return Math.max(1, cost);
    }

    /**
     * Returns a rarity-based cost multiplier for an enchantment.
     * Treasure and curse enchantments cost more.
     *
     * <p>{@code @SuppressWarnings("deprecation")} on Enchantment#isTreasure
     * and #isCursed: Paper deprecated these in favour of a Tag-based check
     * ({@code EnchantmentTags.TREASURE.isTagged(...)}, etc.), but the tag
     * keys are not yet exposed in the public API surface for non-NMS code.
     * Keep the deprecated accessor calls until the Tag-based path is
     * documented; the methods are not {@code [removal]}-tagged.</p>
     */
    @SuppressWarnings("deprecation")
    private int getEnchantmentRarityMultiplier(Enchantment enchantment) {
        if (enchantment.isTreasure()) return 4;
        if (enchantment.isCursed()) return 8;
        return 2;
    }

    /**
     * Adds a cost indicator line to the item's lore for display in the result slot.
     */
    private void addCostIndicator(ItemStack item, int cost) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;

        java.util.List<Component> lore = meta.lore();
        java.util.List<Component> newLore = new java.util.ArrayList<>();
        if (lore != null) {
            newLore.addAll(lore);
        }
        newLore.add(Component.empty());
        newLore.add(
            Component.text("Cost: " + cost + " levels")
                .color(NamedTextColor.GREEN)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(newLore);
        item.setItemMeta(meta);
    }

    // ========================================================================
    // Session Cleanup Helpers
    // ========================================================================

    /**
     * Returns items from the input slots back to the player.
     * Handles full inventory by dropping items on the ground.
     */
    private void returnInputItems(Player player, AnvilSession session) {
        returnItemToPlayer(player, session.chestInventory.getItem(SLOT_LEFT_INPUT));
        returnItemToPlayer(player, session.chestInventory.getItem(SLOT_RIGHT_INPUT));
    }

    /**
     * Returns a single item to a player's inventory, dropping it if inventory is full.
     */
    private void returnItemToPlayer(Player player, ItemStack item) {
        if (isEmptyItem(item)) return;

        HashMap<Integer, ItemStack> overflow = player.getInventory().addItem(item.clone());
        if (!overflow.isEmpty()) {
            // Drop items at player's feet if inventory is full
            for (ItemStack dropped : overflow.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), dropped);
            }
        }
    }

    // ========================================================================
    // Simulation Mode & Player Detection
    // ========================================================================

    /**
     * Determines whether the anvil should be simulated for this player,
     * based on the configured simulation mode and the player's sneaking state.
     */
    private boolean shouldSimulate(Player player) {
        String mode = plugin.getGeyserExtraConfig().enchantment().anvilSimulationMode();
        return switch (mode.toUpperCase()) {
            case "ALWAYS" -> true;
            case "NOT_SNEAKING" -> !player.isSneaking();
            case "SNEAKING" -> player.isSneaking();
            case "DISABLED" -> false;
            default -> !player.isSneaking();
        };
    }

    /**
     * Checks whether a player is a Bedrock (Floodgate) player.
     */
    private boolean isBedrockPlayer(Player player) {
        try {
            UUID uuid = player.getUniqueId();
            return floodgateApi != null && floodgateApi.isFloodgatePlayer(uuid);
        } catch (UnsupportedOperationException e) {
            return false;
        }
    }

    // ========================================================================
    // Utility
    // ========================================================================

    /**
     * Checks whether an item is effectively empty (null or air).
     */
    private boolean isEmptyItem(ItemStack item) {
        return item == null || item.getType() == Material.AIR || item.getAmount() <= 0;
    }
}
