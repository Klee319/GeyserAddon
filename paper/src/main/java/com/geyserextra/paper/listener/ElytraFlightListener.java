package com.geyserextra.paper.listener;

import com.geyserextra.paper.util.BedrockPlayerUtil;
import com.geyserextra.paper.util.DebugLog;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import org.bukkit.Bukkit;
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

    /**
     * Monitoring interval in ticks for detecting plugin-initiated gliding.
     *
     * <p>Sampling at 5 was wrong because the thing being sampled is not a
     * plateau. A plugin that grants elytra-less flight has to re-assert the
     * glide flag <em>every tick</em>, since vanilla
     * {@code LivingEntity#updateFallFlying} clears it every tick for a player
     * with no working elytra. The observable state is a flag that flickers off
     * and back on once per tick until a real elytra exists, so a 5-tick sample
     * both missed take-offs and mistook a single cleared tick for a landing —
     * pulling the stand-in back off mid-flight. (The consequence of missing a
     * take-off is total: the Bedrock client refuses to glide without an elytra,
     * falls, lands, and the grantor's own {@code isOnGround()} guard then tears
     * the state down before the stand-in was ever equipped.)</p>
     *
     * <p><b>Not yet established:</b> whether 1 is sufficient, as opposed to
     * merely necessary. Both this task and the grantor's run in the same
     * scheduler heartbeat, so whether this task can observe the flag at all
     * depends on which of the two the scheduler runs first — an ordering
     * neither plugin controls. If the traces in {@link #onToggleGlide} and
     * {@link #traceFlightToggle} show the grant arriving while this task still
     * never sees {@code isGliding()}, polling is the wrong mechanism entirely
     * and the stand-in has to be driven from the glide event instead.</p>
     */
    private static final int MONITOR_INTERVAL_TICKS = 1;

    /**
     * Consecutive non-gliding samples required before the fake elytra is taken
     * back off. One sample is not evidence: see {@link #MONITOR_INTERVAL_TICKS}
     * for why the flag legitimately reads false for single ticks while the
     * player is still flying. A genuine landing keeps reading false, so a small
     * streak separates the two without adding perceptible lag.
     */
    private static final int GLIDE_END_CONFIRM_SAMPLES = 4;

    /** How often an unchanged run of glide toggles reprints, in ticks. */
    private static final int TOGGLE_SUMMARY_TICKS = 100;

    /** How often a player who did not look like a Bedrock player is re-probed. */
    private static final int BEDROCK_REPROBE_TICKS = 40;

    private final Plugin plugin;
    private final Logger logger;
    private final Path persistenceFile;
    private final Gson gson;
    private final Map<UUID, GlidingPlayerData> glidingPlayers = new ConcurrentHashMap<>();

    /**
     * Consecutive ticks each tracked player has read as "not gliding".
     * Reset the moment they read as gliding again.
     */
    private final Map<UUID, Integer> glideEndStreak = new ConcurrentHashMap<>();

    /**
     * Cached Bedrock-ness per player, because the monitor now runs every tick.
     * {@link BedrockPlayerUtil#isBedrockPlayer} probes the Floodgate and Geyser
     * APIs and swallows the failure, so on a proxy setup — where Geyser lives
     * on the proxy and its API class is absent from this server — every call
     * constructs and discards a {@code NoClassDefFoundError}. Paying that once
     * per player per tick is exactly the kind of cost a 1-tick task must not
     * carry. A player's edition never changes within a session, so caching is
     * safe; entries are dropped on quit.
     */
    private final Map<UUID, Boolean> bedrockCache = new ConcurrentHashMap<>();

    /** Last observed {@code isFlying()} per player, for the edge-triggered toggle trace. */
    private final Map<UUID, Boolean> lastFlyingState = new ConcurrentHashMap<>();

    /** Last glide value traced per player, so repeats can be collapsed. */
    private final Map<UUID, Boolean> lastToggleGlide = new ConcurrentHashMap<>();

    /** Consecutive identical glide values seen since the last trace line. */
    private final Map<UUID, Integer> toggleGlideRepeats = new ConcurrentHashMap<>();

    /** Server tick the glide trace last printed for each player. */
    private final Map<UUID, Integer> lastToggleSummaryTick = new ConcurrentHashMap<>();

    /** Server tick each not-yet-known-Bedrock player was last probed on. */
    private final Map<UUID, Integer> bedrockProbeTick = new ConcurrentHashMap<>();

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
        if (!isBedrock(player)) {
            return;
        }

        traceToggleGlide(player, event.isGliding());

        if (event.isGliding()) {
            handleGlideStart(player);
        } else {
            handleGlideEnd(player);
        }
    }

    /**
     * Traces the glide toggle — the signal that distinguishes the remaining
     * failure modes: whether this event fires at all for a Bedrock player who
     * is trying to glide without an elytra, and with which value.
     *
     * <p>Vanilla clears the flag once per tick for such a player, so a repeated
     * {@code gliding=false} here means the grant <em>is</em> reaching the server
     * and only the stand-in is missing, while silence means the glide is never
     * being asserted at all. Both readings need the repeats to be visible and
     * neither needs twenty identical lines a second, so repeats are collapsed
     * into a periodic count: transitions print immediately, and a run of the
     * same value prints once per {@link #TOGGLE_SUMMARY_TICKS} with its tally.</p>
     */
    private void traceToggleGlide(Player player, boolean gliding) {
        if (!DebugLog.isEnabled()) {
            return;
        }
        UUID uuid = player.getUniqueId();
        int tick = Bukkit.getCurrentTick();
        Boolean previous = lastToggleGlide.put(uuid, gliding);
        boolean changed = previous == null || previous != gliding;
        int repeats = changed ? 0 : toggleGlideRepeats.merge(uuid, 1, Integer::sum);

        if (changed) {
            toggleGlideRepeats.remove(uuid);
        } else {
            Integer lastSummary = lastToggleSummaryTick.get(uuid);
            if (lastSummary != null && tick - lastSummary < TOGGLE_SUMMARY_TICKS) {
                return;
            }
        }
        lastToggleSummaryTick.put(uuid, tick);

        final int repeated = repeats;
        DebugLog.log(logger, () -> "[ElytraFlight] toggle-glide for " + player.getName()
                + ": gliding=" + gliding
                + (repeated > 0 ? " (x" + (repeated + 1) + " unchanged)" : "")
                + " tracked=" + glidingPlayers.containsKey(uuid)
                + " chest=" + describeChestplate(player));
    }

    /** Chest-slot summary for the glide traces. */
    private String describeChestplate(Player player) {
        ItemStack chest = player.getInventory().getChestplate();
        if (chest == null || chest.getType() == Material.AIR) {
            return "empty";
        }
        return chest.getType().name() + (isGeyserExtraElytra(chest) ? "(stand-in)" : "");
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
        glideEndStreak.remove(uuid);

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

        DebugLog.log(logger, () -> "[ElytraFlight] Restored crash-recovery chestplate for: "
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
        UUID uuid = event.getPlayer().getUniqueId();
        glideEndStreak.remove(uuid);
        bedrockCache.remove(uuid);
        lastFlyingState.remove(uuid);
        bedrockProbeTick.remove(uuid);
        lastToggleGlide.remove(uuid);
        toggleGlideRepeats.remove(uuid);
        lastToggleSummaryTick.remove(uuid);
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
        glideEndStreak.remove(player.getUniqueId());
        GlidingPlayerData data = glidingPlayers.remove(player.getUniqueId());
        if (data == null) {
            return;
        }

        removeArmorModifiers(player);
        savePersistence();

        if (event.getKeepInventory()) {
            // keepInventory=true: restore chestplate directly (items not dropped)
            restoreChestplate(player, data);
        } else {
            // Swap the stand-in out of the drops for the original — but only if
            // the stand-in is actually there. If something replaced the chest
            // slot before the player died, their drops already contain that
            // replacement, and adding the original on top mints a second
            // chestplate out of nothing.
            boolean removedStandIn = event.getDrops().removeIf(item ->
                    item != null && item.getType() == Material.ELYTRA && isGeyserExtraElytra(item));

            if (removedStandIn
                    && data.originalChestplate() != null
                    && data.originalChestplate().getType() != Material.AIR) {
                event.getDrops().add(data.originalChestplate());
            } else if (!removedStandIn) {
                DebugLog.log(logger, () -> "[ElytraFlight] " + player.getName()
                        + " died without the stand-in in their drops — leaving the drops"
                        + " untouched rather than adding a second chestplate.");
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
        DebugLog.log(logger, () -> "[ElytraFlight] Gliding monitor task started (interval: "
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
    @SuppressWarnings("deprecation") // Player#isOnGround is diagnostics-only here —
    // it is deliberately the *client-reported* value, because that is the value the
    // flight-granting plugin gates its own glide start on. Reading anything else
    // would trace a different number from the one that decides the outcome.
    private void checkGlidingStates() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            UUID uuid = player.getUniqueId();
            if (!isBedrock(player)) {
                continue;
            }

            boolean isGliding = player.isGliding();
            boolean isTracked = glidingPlayers.containsKey(uuid);

            traceFlightToggle(player, uuid, isGliding);

            if (isGliding && !isTracked) {
                // Player started gliding without triggering the event
                ItemStack currentChest = player.getInventory().getChestplate();
                boolean hasRealElytra = currentChest != null
                        && currentChest.getType() == Material.ELYTRA
                        && !isGeyserExtraElytra(currentChest);
                if (!hasRealElytra) {
                    DebugLog.log(logger, () -> "[ElytraFlight] " + player.getName()
                            + " started gliding without an elytra"
                            + " (onGround=" + player.isOnGround()
                            + ", allowFlight=" + player.getAllowFlight()
                            + ", flying=" + player.isFlying()
                            + ") — equipping the stand-in.");
                    handleGlideStart(player);
                }
                glideEndStreak.remove(uuid);
            } else if (isGliding) {
                glideEndStreak.remove(uuid);
            } else if (isTracked) {
                // Not gliding this tick. Only act once it has held for several
                // ticks — a single false reading is the vanilla per-tick clear,
                // not a landing.
                int streak = glideEndStreak.merge(uuid, 1, Integer::sum);
                if (streak >= GLIDE_END_CONFIRM_SAMPLES) {
                    DebugLog.log(logger, () -> "[ElytraFlight] " + player.getName()
                            + " has read as not gliding for " + GLIDE_END_CONFIRM_SAMPLES
                            + " ticks (onGround=" + player.isOnGround()
                            + ") — removing the stand-in.");
                    glideEndStreak.remove(uuid);
                    handleGlideEnd(player);
                }
            }
        }
    }

    /**
     * Traces the Bedrock flight toggle, which is the input the whole workaround
     * depends on and the one failure this class could not previously see.
     *
     * <p>Elytra-less flight is granted by a plugin that watches for the player
     * entering flight mode and converts it into gliding. If the toggle never
     * reaches the server from a Bedrock client, that conversion never runs, the
     * glide flag never turns on, and every other trace in this class stays
     * silent — a dead ability that looks identical to a disabled listener. This
     * logs the {@code isFlying()} edge so the two are distinguishable at a
     * glance, and reports whether gliding followed.</p>
     *
     * <p>Edge-triggered on purpose: the monitor runs every tick, and a level
     * trace here would be twenty lines a second per flying player.</p>
     */
    @SuppressWarnings("deprecation") // Player#isOnGround — see checkGlidingStates.
    private void traceFlightToggle(Player player, UUID uuid, boolean isGliding) {
        if (!DebugLog.isEnabled()) {
            return;
        }
        boolean flying = player.isFlying();
        Boolean previous = lastFlyingState.put(uuid, flying);
        if (previous != null && previous == flying) {
            return;
        }
        DebugLog.log(logger, () -> "[ElytraFlight] " + player.getName()
                + " flight toggle: flying=" + flying
                + " allowFlight=" + player.getAllowFlight()
                + " gliding=" + isGliding
                + " onGround=" + player.isOnGround());
    }

    /**
     * Bedrock check for the per-tick monitor, memoised per player.
     *
     * @see #bedrockCache
     */
    private boolean isBedrock(Player player) {
        UUID uuid = player.getUniqueId();
        if (Boolean.TRUE.equals(bedrockCache.get(uuid))) {
            return true;
        }
        // Only a positive answer is cached for good. A negative one can be a
        // lie told early in a session — on auth-type: online the UUID
        // fallback cannot tell the editions apart, so the answer depends on the
        // Geyser session already being registered. Freezing that "no" would
        // disable the workaround for the rest of the session, so it is retried,
        // but only occasionally: the probe throws and swallows a
        // NoClassDefFoundError on setups where Geyser lives on the proxy, and
        // this runs every tick.
        int tick = Bukkit.getCurrentTick();
        Integer lastProbe = bedrockProbeTick.get(uuid);
        if (lastProbe != null && tick - lastProbe < BEDROCK_REPROBE_TICKS) {
            return false;
        }
        bedrockProbeTick.put(uuid, tick);
        boolean bedrock = BedrockPlayerUtil.isBedrockPlayer(player);
        if (bedrock) {
            bedrockCache.put(uuid, Boolean.TRUE);
        }
        return bedrock;
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

        // Restore everyone reachable, and keep only those who are not.
        glidingPlayers.entrySet().removeIf(entry -> {
            Player player = plugin.getServer().getPlayer(entry.getKey());
            if (player == null || !player.isOnline()) {
                // Offline: their chestplate is still owed to them and only the
                // persistence file remembers it.
                return false;
            }
            restoreChestplate(player, entry.getValue());
            removeArmorModifiers(player);
            return true;
        });

        // Re-save so the file lists exactly the chestplates still owed, then
        // discard it only when nothing is owed. Deleting it while an offline
        // player is still listed — the state a crash-then-restart leaves
        // behind — loses their armour permanently, because their own .dat only
        // has the stand-in. Re-saving matters just as much: leaving the
        // pre-restore snapshot on disk would hand the players we just restored
        // a second chestplate on the next boot.
        int stillOwed = glidingPlayers.size();
        savePersistence();
        if (stillOwed == 0) {
            deletePersistenceFile();
        } else {
            logger.warning("[ElytraFlight] Keeping " + PERSISTENCE_FILE + ": "
                    + stillOwed + " chestplate(s) are still owed to players who are not"
                    + " online. They will be restored when those players next join.");
        }

        glidingPlayers.clear();
        glideEndStreak.clear();
        bedrockCache.clear();
        lastFlyingState.clear();
        bedrockProbeTick.clear();
        lastToggleGlide.clear();
        toggleGlideRepeats.clear();
        lastToggleSummaryTick.clear();

        DebugLog.log(logger, () -> "[ElytraFlight] Cleanup complete — all gliding states restored.");
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

        DebugLog.log(logger, () -> "[ElytraFlight] Equipped fake elytra for Bedrock player: "
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
        // Always clear the streak, even when nothing was tracked. It is keyed
        // on "consecutive ticks while tracked", so a leftover count from an
        // earlier flight would fire the moment the next one starts and rip the
        // stand-in back off a tick after take-off.
        glideEndStreak.remove(player.getUniqueId());

        GlidingPlayerData data = glidingPlayers.remove(player.getUniqueId());
        if (data == null) {
            return;
        }

        restoreChestplate(player, data);
        removeArmorModifiers(player);

        // Update persistence after state change
        savePersistence();

        DebugLog.log(logger, () -> "[ElytraFlight] Restored chestplate for Bedrock player: "
                + player.getName());
    }

    /**
     * Puts the saved chestplate back, without overwriting whatever is in the
     * chest slot now unless it is this plugin's own stand-in.
     *
     * <p>The click and drag guards only stop the <em>player</em> from changing
     * the chest slot. A command, a kit, or a syncing plugin can still replace
     * it, and an unconditional {@code setChestplate} would then destroy that
     * armour — the same reasoning {@link #onPlayerJoin} already applies to the
     * crash-recovery path.</p>
     */
    private void restoreChestplate(Player player, GlidingPlayerData data) {
        ItemStack current = player.getInventory().getChestplate();
        ItemStack original = data.originalChestplate();

        if (current == null || current.getType() == Material.AIR
                || isGeyserExtraElytra(current)) {
            player.getInventory().setChestplate(original);
            return;
        }

        // Something else owns the chest slot now. Leave it alone and hand the
        // original back through the inventory instead of overwriting.
        if (original != null && original.getType() != Material.AIR) {
            DebugLog.log(logger, () -> "[ElytraFlight] Chest slot of " + player.getName()
                    + " holds " + current.getType()
                    + " rather than the stand-in — returning the original to the inventory.");
            for (ItemStack leftover : player.getInventory().addItem(original).values()) {
                if (leftover != null && leftover.getType() != Material.AIR) {
                    player.getWorld().dropItemNaturally(player.getLocation(), leftover);
                }
            }
        }
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
                final int loaded = restoredCount;
                DebugLog.log(logger, () -> "[ElytraFlight] Loaded " + loaded
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
