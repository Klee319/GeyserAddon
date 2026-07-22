package com.geyserextra.paper.listener;

import com.geyserextra.paper.util.BedrockPlayerUtil;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Workaround for Bedrock players to fly (glide) without elytra.
 *
 * Why: When a server-side plugin sets a player's gliding state,
 * the Java client allows flight regardless of chestplate, but the
 * Bedrock client requires an elytra to be equipped. This listener
 * temporarily swaps the chestplate with a fake elytra and preserves
 * the original armor's defensive stats via transient attribute modifiers
 * and manual enchantment-based damage reduction.
 *
 * Equipment protection: Prevents chest slot modification during flight
 * to avoid losing the original armor. Persists gliding state to disk
 * for crash recovery.
 */
public final class ElytraFlightListener implements Listener {

    /** Transient modifier key for temporary armor value. */
    private static final NamespacedKey TEMP_ARMOR_KEY =
            NamespacedKey.fromString("geyserextra:temp_armor");

    /** Transient modifier key for temporary armor toughness value. */
    private static final NamespacedKey TEMP_TOUGHNESS_KEY =
            NamespacedKey.fromString("geyserextra:temp_toughness");

    /** PersistentDataContainer key to tag fake elytra items for identification. */
    private static final NamespacedKey FAKE_ELYTRA_TAG_KEY =
            NamespacedKey.fromString("geyserextra:fake_elytra");

    /** Maximum EPF cap as defined by Minecraft's protection calculation. */
    private static final int MAX_EPF = 20;

    /** Maximum protection reduction ratio (EPF 20 / 25 = 0.8 = 80%). */
    private static final double MAX_PROTECTION_RATIO = 25.0;

    /** EPF weight for type-specific protection enchantments (Fire, Blast, Projectile). */
    private static final int TYPE_SPECIFIC_EPF_WEIGHT = 2;

    /** EPF weight for Feather Falling enchantment. */
    private static final int FEATHER_FALLING_EPF_WEIGHT = 3;

    /** Chestplate armor slot index in Bukkit inventory. */
    private static final int CHESTPLATE_SLOT = 38;

    /** File name for crash recovery persistence. */
    private static final String PERSISTENCE_FILE = "gliding_players.json";

    /** Monitoring interval in ticks for detecting plugin-initiated gliding. */
    private static final int MONITOR_INTERVAL_TICKS = 5;

    private final Plugin plugin;
    private final Logger logger;
    private final Path persistenceFile;
    private final Gson gson;
    private final Map<UUID, GlidingPlayerData> glidingPlayers = new ConcurrentHashMap<>();
    private BukkitTask monitorTask;

    /**
     * Immutable snapshot of a player's original chestplate data,
     * captured when fake elytra is equipped.
     *
     * @param originalChestplate the chestplate that was replaced (may be null if empty)
     * @param enchantments       protection-related enchantments from the original chestplate
     * @param armorValue         vanilla armor points of the original chestplate
     * @param toughnessValue     vanilla armor toughness of the original chestplate
     */
    private record GlidingPlayerData(
            ItemStack originalChestplate,
            Map<Enchantment, Integer> enchantments,
            double armorValue,
            double toughnessValue
    ) {}

    /**
     * @param plugin the owning plugin instance
     * @throws NullPointerException if plugin is null
     */
    public ElytraFlightListener(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin must not be null");
        this.logger = plugin.getLogger();
        this.persistenceFile = plugin.getDataFolder().toPath().resolve(PERSISTENCE_FILE);
        this.gson = new GsonBuilder().setPrettyPrinting().create();

        // Restore any players who were gliding during a crash
        restoreFromPersistence();
    }

    // ── Event Handlers ──────────────────────────────────────────────

    /**
     * Handles glide toggle to equip/remove fake elytra for Bedrock players.
     *
     * Why HIGHEST priority: We need to observe the final glide state after
     * other plugins have had a chance to cancel or modify the event.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onToggleGlide(EntityToggleGlideEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        if (!BedrockPlayerUtil.isBedrockPlayer(player)) {
            return;
        }

        if (event.isGliding()) {
            handleGlideStart(player);
        } else {
            handleGlideEnd(player);
        }
    }

    /**
     * Prevents chest slot modification while a player is using fake elytra.
     *
     * Why: If a player changes their chestplate during fake elytra flight,
     * the original armor stored in memory would be lost. Cancelling the
     * event preserves data integrity.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (!glidingPlayers.containsKey(player.getUniqueId())) {
            return;
        }

        // Block direct click on chestplate slot
        if (event.getSlotType() == InventoryType.SlotType.ARMOR
                && event.getSlot() == CHESTPLATE_SLOT) {
            event.setCancelled(true);
            player.sendMessage(
                    Component.text("飛行中は胸防具を変更できません。", NamedTextColor.YELLOW));
            return;
        }

        // Block shift-click of armor items that would go to chest slot
        if (event.isShiftClick() && isChestplateItem(event.getCurrentItem())) {
            event.setCancelled(true);
            player.sendMessage(
                    Component.text("飛行中は胸防具を変更できません。", NamedTextColor.YELLOW));
            return;
        }

        // Block number-key hotbar swap targeting chestplate slot
        if (event.getClick() == org.bukkit.event.inventory.ClickType.NUMBER_KEY
                && event.getSlot() == CHESTPLATE_SLOT) {
            event.setCancelled(true);
            player.sendMessage(
                    Component.text("飛行中は胸防具を変更できません。", NamedTextColor.YELLOW));
        }
    }

    /**
     * Prevents drag events from placing items into the chestplate slot during flight.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryDrag(org.bukkit.event.inventory.InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (!glidingPlayers.containsKey(player.getUniqueId())) {
            return;
        }
        // Raw slot 6 is the chestplate slot in the player's inventory view
        if (event.getRawSlots().contains(6)) {
            event.setCancelled(true);
            player.sendMessage(
                    Component.text("飛行中は胸防具を変更できません。", NamedTextColor.YELLOW));
        }
    }

    /**
     * Restores original chestplate for players who were gliding during
     * a server crash, when they rejoin.
     *
     * Why: Persistence file stores serialized chestplates. On rejoin,
     * we check if the player has a pending restoration and apply it.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        GlidingPlayerData pendingData = glidingPlayers.remove(uuid);
        if (pendingData == null) {
            return;
        }

        // Player was gliding during crash — restore their chestplate
        // Remove fake elytra if still equipped (unlikely after crash, but safe)
        ItemStack currentChest = player.getInventory().getChestplate();
        if (currentChest != null && isGeyserExtraElytra(currentChest)) {
            player.getInventory().setChestplate(pendingData.originalChestplate());
        } else if (pendingData.originalChestplate() != null
                && pendingData.originalChestplate().getType() != Material.AIR) {
            // Chestplate slot may be empty after crash — restore original
            if (currentChest == null || currentChest.getType() == Material.AIR) {
                player.getInventory().setChestplate(pendingData.originalChestplate());
            } else {
                // Slot has a different item; give original back to inventory
                Map<Integer, ItemStack> overflow =
                        player.getInventory().addItem(pendingData.originalChestplate());
                if (!overflow.isEmpty()) {
                    // Inventory full — drop at player's location
                    for (ItemStack item : overflow.values()) {
                        player.getWorld().dropItemNaturally(player.getLocation(), item);
                    }
                }
            }
        }

        removeArmorModifiers(player);
        savePersistence();

        logger.fine("[ElytraFlight] Restored crash-recovery chestplate for: "
                + player.getName());
    }

    /**
     * Applies enchantment-based damage reduction for players wearing fake elytra.
     *
     * Why HIGH priority: We modify damage before most gameplay listeners
     * observe the final damage value, but after LOWEST/LOW cancellation checks.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }

        GlidingPlayerData data = glidingPlayers.get(player.getUniqueId());
        if (data == null || data.enchantments().isEmpty()) {
            return;
        }

        double reduction = calculateProtectionReduction(
                data.enchantments(), event.getCause());
        if (reduction > 0) {
            double newDamage = event.getDamage() * (1.0 - reduction);
            event.setDamage(newDamage);
        }
    }

    /**
     * Restores original chestplate when a tracked player disconnects.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        restoreAndRemove(event.getPlayer());
    }

    /**
     * Restores original chestplate when a tracked player dies.
     *
     * Why: Death resets equipment; we must not leave stale tracking data.
     * The fake elytra is removed from drops and original chestplate is
     * added back so the death drop is correct.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        GlidingPlayerData data = glidingPlayers.remove(player.getUniqueId());
        if (data == null) {
            return;
        }

        removeArmorModifiers(player);
        savePersistence();

        if (event.getKeepInventory()) {
            // keepInventory=true: restore chestplate directly (items not dropped)
            player.getInventory().setChestplate(data.originalChestplate());
        } else {
            // Replace fake elytra in drops with original chestplate
            event.getDrops().removeIf(item ->
                    item != null && item.getType() == Material.ELYTRA && isGeyserExtraElytra(item));

            if (data.originalChestplate() != null
                    && data.originalChestplate().getType() != Material.AIR) {
                event.getDrops().add(data.originalChestplate());
            }
        }
    }

    /**
     * Restores original chestplate when a tracked player changes world.
     *
     * Why: World changes can desync equipment state between server and client.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        restoreAndRemove(event.getPlayer());
    }

    // ── Public API ──────────────────────────────────────────────────

    /**
     * Starts a periodic task that detects plugin-initiated gliding state changes.
     *
     * Why: EntityToggleGlideEvent may NOT fire when a plugin calls
     * player.setGliding(true) directly. This monitor runs every 5 ticks
     * to detect Bedrock players whose gliding state changed without an event,
     * ensuring fake elytra is equipped/removed correctly.
     *
     * @param ownerPlugin the plugin to schedule the task under
     */
    public void startMonitorTask(Plugin ownerPlugin) {
        monitorTask = ownerPlugin.getServer().getScheduler().runTaskTimer(
                ownerPlugin,
                this::checkGlidingStates,
                MONITOR_INTERVAL_TICKS,
                MONITOR_INTERVAL_TICKS
        );
        logger.fine("[ElytraFlight] Gliding monitor task started (interval: "
                + MONITOR_INTERVAL_TICKS + " ticks).");
    }

    /**
     * Checks all online Bedrock players for gliding state mismatches.
     *
     * Why: Covers two cases that EntityToggleGlideEvent may miss:
     * 1. Player is gliding but not tracked and chestplate is not elytra
     *    -> another plugin set gliding; trigger handleGlideStart
     * 2. Player is not gliding but still tracked
     *    -> gliding ended without event; trigger handleGlideEnd
     */
    private void checkGlidingStates() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (!BedrockPlayerUtil.isBedrockPlayer(player)) {
                continue;
            }

            UUID uuid = player.getUniqueId();
            boolean isGliding = player.isGliding();
            boolean isTracked = glidingPlayers.containsKey(uuid);

            if (isGliding && !isTracked) {
                // Player started gliding without triggering the event
                ItemStack currentChest = player.getInventory().getChestplate();
                boolean hasRealElytra = currentChest != null
                        && currentChest.getType() == Material.ELYTRA
                        && !isGeyserExtraElytra(currentChest);
                if (!hasRealElytra) {
                    handleGlideStart(player);
                }
            } else if (!isGliding && isTracked) {
                // Player stopped gliding without triggering the event
                handleGlideEnd(player);
            }
        }
    }

    /**
     * Restores all tracked players' chestplates and clears tracking state.
     * Must be called from {@code onDisable()} to prevent data loss on reload.
     *
     * Saves persistence before clearing so crash recovery is possible
     * if the shutdown is not clean.
     */
    public void cleanup() {
        // Cancel the monitor task to prevent NPE during shutdown
        if (monitorTask != null) {
            monitorTask.cancel();
            monitorTask = null;
        }

        // Save before restoring — if server crashes during cleanup,
        // persistence file ensures recovery on next startup
        savePersistence();

        for (Map.Entry<UUID, GlidingPlayerData> entry : glidingPlayers.entrySet()) {
            Player player = plugin.getServer().getPlayer(entry.getKey());
            if (player != null && player.isOnline()) {
                restoreChestplate(player, entry.getValue());
                removeArmorModifiers(player);
            }
        }
        glidingPlayers.clear();

        // Delete persistence file after successful cleanup
        deletePersistenceFile();

        logger.fine("[ElytraFlight] Cleanup complete — all gliding states restored.");
    }

    // ── Core Logic ──────────────────────────────────────────────────

    /**
     * Initiates fake elytra swap when a Bedrock player starts gliding
     * without elytra equipped.
     *
     * Why: Bedrock client refuses to enter glide state unless an elytra
     * is in the chest slot. We temporarily equip one so the client
     * renders the glide animation and physics.
     */
    private void handleGlideStart(Player player) {
        ItemStack currentChestplate = player.getInventory().getChestplate();

        // If already wearing elytra, no intervention needed
        if (currentChestplate != null && currentChestplate.getType() == Material.ELYTRA) {
            return;
        }

        // If already tracked (e.g., rapid re-toggle), skip
        if (glidingPlayers.containsKey(player.getUniqueId())) {
            return;
        }

        // Capture original armor data before replacement
        ItemStack savedChestplate = (currentChestplate != null)
                ? currentChestplate.clone()
                : null;

        Map<Enchantment, Integer> enchantments = extractProtectionEnchantments(currentChestplate);
        double armorValue = getArmorValue(currentChestplate);
        double toughnessValue = getToughnessValue(currentChestplate);

        GlidingPlayerData data = new GlidingPlayerData(
                savedChestplate, enchantments, armorValue, toughnessValue);

        glidingPlayers.put(player.getUniqueId(), data);

        // Equip fake elytra and apply compensating modifiers
        player.getInventory().setChestplate(createFakeElytra());
        applyArmorModifiers(player, data);

        // Persist state for crash recovery
        savePersistence();

        logger.fine("[ElytraFlight] Equipped fake elytra for Bedrock player: "
                + player.getName());
    }

    /**
     * Restores original chestplate when a tracked player stops gliding.
     */
    private void handleGlideEnd(Player player) {
        restoreAndRemove(player);
    }

    /**
     * Restores chestplate and removes tracking for the given player if tracked.
     */
    private void restoreAndRemove(Player player) {
        GlidingPlayerData data = glidingPlayers.remove(player.getUniqueId());
        if (data == null) {
            return;
        }

        restoreChestplate(player, data);
        removeArmorModifiers(player);

        // Update persistence after state change
        savePersistence();

        logger.fine("[ElytraFlight] Restored chestplate for Bedrock player: "
                + player.getName());
    }

    /**
     * Sets the player's chestplate back to the saved original.
     */
    private void restoreChestplate(Player player, GlidingPlayerData data) {
        player.getInventory().setChestplate(data.originalChestplate());
    }

    // ── Inventory Helpers ───────────────────────────────────────────

    /**
     * Checks whether an item is a chestplate-type armor piece.
     *
     * Why: Shift-clicking a chestplate item would auto-equip it to the
     * chest slot, bypassing normal slot click detection.
     *
     * @param item the item to check (may be null)
     * @return true if the item is a chestplate or elytra
     */
    private static boolean isChestplateItem(ItemStack item) {
        if (item == null) {
            return false;
        }
        return switch (item.getType()) {
            case LEATHER_CHESTPLATE,
                 CHAINMAIL_CHESTPLATE,
                 IRON_CHESTPLATE,
                 GOLDEN_CHESTPLATE,
                 DIAMOND_CHESTPLATE,
                 NETHERITE_CHESTPLATE,
                 ELYTRA -> true;
            default -> false;
        };
    }

    // ── Fake Elytra ─────────────────────────────────────────────────

    /**
     * Creates an unbreakable elytra item with no enchantments,
     * tagged for identification during death-drop cleanup.
     *
     * Why unbreakable: The fake elytra should never lose durability
     * since it is a temporary workaround item.
     */
    private ItemStack createFakeElytra() {
        ItemStack elytra = new ItemStack(Material.ELYTRA);
        ItemMeta meta = elytra.getItemMeta();
        if (meta != null) {
            meta.setUnbreakable(true);
            meta.getPersistentDataContainer().set(
                    FAKE_ELYTRA_TAG_KEY, PersistentDataType.BYTE, (byte) 1);
            elytra.setItemMeta(meta);
        }
        return elytra;
    }

    /**
     * Checks whether an item is a GeyserExtra fake elytra by inspecting
     * the PersistentDataContainer tag.
     *
     * Why PDC: Using a dedicated tag is more reliable than heuristics
     * like "unbreakable + no enchants", which could match player items.
     */
    private boolean isGeyserExtraElytra(ItemStack item) {
        if (item == null || item.getType() != Material.ELYTRA) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        return meta != null
                && meta.getPersistentDataContainer().has(
                        FAKE_ELYTRA_TAG_KEY, PersistentDataType.BYTE);
    }

    // ── Attribute Modifiers ─────────────────────────────────────────

    /**
     * Adds transient armor and toughness modifiers to compensate for the
     * removed chestplate.
     *
     * Why transient: Transient modifiers are not persisted and will be
     * lost on server restart, which is the desired behavior for temporary
     * workaround modifiers.
     */
    private void applyArmorModifiers(Player player, GlidingPlayerData data) {
        if (data.armorValue() > 0) {
            AttributeInstance armor = player.getAttribute(Attribute.ARMOR);
            if (armor != null) {
                armor.addTransientModifier(new AttributeModifier(
                        TEMP_ARMOR_KEY,
                        data.armorValue(),
                        AttributeModifier.Operation.ADD_NUMBER
                ));
            }
        }
        if (data.toughnessValue() > 0) {
            AttributeInstance toughness = player.getAttribute(Attribute.ARMOR_TOUGHNESS);
            if (toughness != null) {
                toughness.addTransientModifier(new AttributeModifier(
                        TEMP_TOUGHNESS_KEY,
                        data.toughnessValue(),
                        AttributeModifier.Operation.ADD_NUMBER
                ));
            }
        }
    }

    /**
     * Removes transient armor and toughness modifiers previously applied.
     */
    private void removeArmorModifiers(Player player) {
        AttributeInstance armor = player.getAttribute(Attribute.ARMOR);
        if (armor != null) {
            armor.removeModifier(TEMP_ARMOR_KEY);
        }
        AttributeInstance toughness = player.getAttribute(Attribute.ARMOR_TOUGHNESS);
        if (toughness != null) {
            toughness.removeModifier(TEMP_TOUGHNESS_KEY);
        }
    }

    // ── Enchantment Extraction ──────────────────────────────────────

    /**
     * Extracts protection-related enchantments from a chestplate item.
     *
     * Why only protection enchantments: Other enchantments (e.g., Thorns,
     * Mending) either do not affect incoming damage calculation or have
     * side effects that are too complex to simulate accurately.
     *
     * @param item the chestplate to inspect (may be null)
     * @return an unmodifiable map of protection enchantments and their levels
     */
    private Map<Enchantment, Integer> extractProtectionEnchantments(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) {
            return Collections.emptyMap();
        }

        Map<Enchantment, Integer> result = new HashMap<>();

        addIfPresent(result, item, Enchantment.PROTECTION);
        addIfPresent(result, item, Enchantment.FIRE_PROTECTION);
        addIfPresent(result, item, Enchantment.BLAST_PROTECTION);
        addIfPresent(result, item, Enchantment.PROJECTILE_PROTECTION);
        addIfPresent(result, item, Enchantment.FEATHER_FALLING);

        return Collections.unmodifiableMap(result);
    }

    /**
     * Adds the enchantment to the result map if present on the item.
     */
    private void addIfPresent(
            Map<Enchantment, Integer> result, ItemStack item, Enchantment enchantment) {
        int level = item.getEnchantmentLevel(enchantment);
        if (level > 0) {
            result.put(enchantment, level);
        }
    }

    // ── Protection EPF Calculation ──────────────────────────────────

    /**
     * Calculates the damage reduction ratio from protection enchantments
     * using the vanilla EPF (Enchantment Protection Factor) system.
     *
     * Why manual calculation: The fake elytra has no enchantments, so the
     * server's built-in protection calculation ignores the original armor's
     * enchantments. We must apply the reduction ourselves.
     *
     * @param enchants the protection enchantments from the original chestplate
     * @param cause    the damage cause to evaluate type-specific protections
     * @return the damage reduction ratio (0.0 to 0.8)
     */
    private double calculateProtectionReduction(
            Map<Enchantment, Integer> enchants, DamageCause cause) {
        int epf = 0;

        // Generic Protection applies to all damage types
        Integer protLevel = enchants.get(Enchantment.PROTECTION);
        if (protLevel != null) {
            epf += protLevel;
        }

        // Type-specific protection bonuses
        epf += getTypeSpecificEpf(enchants, cause);

        epf = Math.min(epf, MAX_EPF);
        return epf / MAX_PROTECTION_RATIO;
    }

    /**
     * Returns the type-specific EPF contribution based on damage cause.
     */
    private int getTypeSpecificEpf(Map<Enchantment, Integer> enchants, DamageCause cause) {
        return switch (cause) {
            case FIRE, FIRE_TICK, LAVA, HOT_FLOOR -> {
                Integer level = enchants.get(Enchantment.FIRE_PROTECTION);
                yield (level != null) ? level * TYPE_SPECIFIC_EPF_WEIGHT : 0;
            }
            case BLOCK_EXPLOSION, ENTITY_EXPLOSION -> {
                Integer level = enchants.get(Enchantment.BLAST_PROTECTION);
                yield (level != null) ? level * TYPE_SPECIFIC_EPF_WEIGHT : 0;
            }
            case PROJECTILE -> {
                Integer level = enchants.get(Enchantment.PROJECTILE_PROTECTION);
                yield (level != null) ? level * TYPE_SPECIFIC_EPF_WEIGHT : 0;
            }
            case FALL -> {
                Integer level = enchants.get(Enchantment.FEATHER_FALLING);
                yield (level != null) ? level * FEATHER_FALLING_EPF_WEIGHT : 0;
            }
            default -> 0;
        };
    }

    // ── Vanilla Armor Value Lookup ──────────────────────────────────

    /**
     * Returns the vanilla armor value for a chestplate material.
     *
     * Why hardcoded: The Bukkit API does not expose default armor values
     * for materials. These values match vanilla Minecraft 1.20+ constants.
     *
     * @param item the chestplate item (may be null)
     * @return the armor value, or 0 if not a recognized chestplate
     */
    private static double getArmorValue(ItemStack item) {
        if (item == null) {
            return 0;
        }
        return switch (item.getType()) {
            case LEATHER_CHESTPLATE -> 3;
            case CHAINMAIL_CHESTPLATE -> 5;
            case IRON_CHESTPLATE -> 6;
            case GOLDEN_CHESTPLATE -> 5;
            case DIAMOND_CHESTPLATE -> 8;
            case NETHERITE_CHESTPLATE -> 8;
            default -> 0;
        };
    }

    /**
     * Returns the vanilla armor toughness value for a chestplate material.
     *
     * @param item the chestplate item (may be null)
     * @return the toughness value, or 0 if not a recognized chestplate
     */
    private static double getToughnessValue(ItemStack item) {
        if (item == null) {
            return 0;
        }
        return switch (item.getType()) {
            case DIAMOND_CHESTPLATE -> 2;
            case NETHERITE_CHESTPLATE -> 3;
            default -> 0;
        };
    }

    // ── Persistence for Crash Recovery ──────────────────────────────

    /**
     * Saves current gliding player data to a JSON file for crash recovery.
     *
     * Why: If the server crashes while players are using fake elytra,
     * their original chestplates would be lost without persistence.
     * The file stores UUID-to-Base64(ItemStack) mappings.
     */
    private void savePersistence() {
        try {
            if (glidingPlayers.isEmpty()) {
                deletePersistenceFile();
                return;
            }

            // Ensure parent directory exists
            Files.createDirectories(persistenceFile.getParent());

            Map<String, String> serialized = new HashMap<>();
            for (Map.Entry<UUID, GlidingPlayerData> entry : glidingPlayers.entrySet()) {
                ItemStack chestplate = entry.getValue().originalChestplate();
                if (chestplate != null && chestplate.getType() != Material.AIR) {
                    byte[] bytes = chestplate.serializeAsBytes();
                    String base64 = Base64.getEncoder().encodeToString(bytes);
                    serialized.put(entry.getKey().toString(), base64);
                } else {
                    // Store empty string to indicate no chestplate was worn
                    serialized.put(entry.getKey().toString(), "");
                }
            }

            try (Writer writer = Files.newBufferedWriter(persistenceFile, StandardCharsets.UTF_8)) {
                gson.toJson(serialized, writer);
            }
        } catch (IOException e) {
            logger.log(Level.WARNING,
                    "[ElytraFlight] Failed to save persistence file", e);
        }
    }

    /**
     * Restores gliding player data from the persistence file on startup.
     *
     * Why: After a crash, players who were gliding will have lost their
     * original chestplates. This method loads saved data so that when
     * those players rejoin, their chestplates can be returned.
     */
    private void restoreFromPersistence() {
        if (!Files.exists(persistenceFile)) {
            return;
        }

        try (Reader reader = Files.newBufferedReader(persistenceFile, StandardCharsets.UTF_8)) {
            Type mapType = new TypeToken<Map<String, String>>() {}.getType();
            Map<String, String> serialized = gson.fromJson(reader, mapType);

            if (serialized == null || serialized.isEmpty()) {
                deletePersistenceFile();
                return;
            }

            int restoredCount = 0;
            for (Map.Entry<String, String> entry : serialized.entrySet()) {
                try {
                    UUID uuid = UUID.fromString(entry.getKey());
                    String base64 = entry.getValue();

                    ItemStack chestplate = null;
                    if (base64 != null && !base64.isEmpty()) {
                        byte[] bytes = Base64.getDecoder().decode(base64);
                        chestplate = ItemStack.deserializeBytes(bytes);
                    }

                    // Store with empty enchantments/armor values — these are only
                    // needed for active damage reduction during flight, not recovery
                    GlidingPlayerData data = new GlidingPlayerData(
                            chestplate, Collections.emptyMap(), 0, 0);
                    glidingPlayers.put(uuid, data);
                    restoredCount++;
                } catch (IllegalArgumentException e) {
                    logger.warning("[ElytraFlight] Invalid UUID in persistence file: "
                            + entry.getKey());
                }
            }

            if (restoredCount > 0) {
                logger.fine("[ElytraFlight] Loaded " + restoredCount
                        + " pending chestplate restoration(s) from crash recovery.");
            }
        } catch (IOException e) {
            logger.log(Level.WARNING,
                    "[ElytraFlight] Failed to read persistence file", e);
        }
    }

    /**
     * Deletes the persistence file if it exists.
     */
    private void deletePersistenceFile() {
        try {
            Files.deleteIfExists(persistenceFile);
        } catch (IOException e) {
            logger.log(Level.WARNING,
                    "[ElytraFlight] Failed to delete persistence file", e);
        }
    }
}
