package com.geyserextra.paper.display;

import com.geyserextra.paper.settings.PlayerSettings;
import com.geyserextra.paper.settings.PlayerSettings.EntityDisplayMode;
import com.geyserextra.paper.settings.PlayerSettings.EnvironmentDisplayMode;
import com.geyserextra.paper.settings.PlayerSettingsManager;
import com.geyserextra.paper.util.BedrockPlayerUtil;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Orchestrates all display features by running a repeating task that updates
 * active displays based on each Bedrock player's current settings.
 *
 * Why centralized orchestration: Individual display components (biome/light,
 * chunk boundary, entity info) each need periodic updates tied to player
 * settings. A single manager with one repeating task avoids multiple independent
 * schedulers competing for tick time and ensures consistent update ordering.
 */
public final class DisplayManager implements Listener {

    /**
     * Why 10 ticks: 0.5-second update interval balances responsiveness with
     * server performance. Faster intervals waste CPU on negligible visual
     * improvement; slower intervals cause noticeable lag in display updates
     * when players move between biomes or chunks.
     */
    private static final long UPDATE_INTERVAL_TICKS = 10L;

    private final Plugin plugin;
    private final PlayerSettingsManager settingsManager;
    private final BiomeLightDisplay biomeLightDisplay;
    private final ChunkBoundaryDisplay chunkBoundaryDisplay;
    private final EntityInfoDisplay entityInfoDisplay;

    /** The repeating task handle, stored for cleanup cancellation. */
    private BukkitTask updateTask;

    /**
     * @param plugin          the owning plugin instance for event registration and scheduling
     * @param settingsManager provides per-player display settings
     */
    public DisplayManager(Plugin plugin, PlayerSettingsManager settingsManager) {
        this.plugin = plugin;
        this.settingsManager = settingsManager;
        this.biomeLightDisplay = new BiomeLightDisplay();
        this.chunkBoundaryDisplay = new ChunkBoundaryDisplay();
        this.entityInfoDisplay = new EntityInfoDisplay();
    }

    /**
     * Registers event listeners and starts the repeating update task.
     *
     * Why separate from constructor: Allows the caller to fully configure
     * the plugin (register commands, load settings) before display updates
     * begin, preventing updates against incomplete state.
     */
    public void start() {
        Bukkit.getPluginManager().registerEvents(this, plugin);

        // Why runTaskTimer: Runs updateAll() every UPDATE_INTERVAL_TICKS on the
        // main server thread, ensuring all Bukkit API calls are thread-safe.
        updateTask = Bukkit.getScheduler().runTaskTimer(
            plugin, this::updateAll, UPDATE_INTERVAL_TICKS, UPDATE_INTERVAL_TICKS);
    }

    /**
     * Cancels the repeating task and releases all display resources.
     *
     * Why explicit cleanup: Bukkit does not automatically cancel tasks or
     * despawn custom entities (e.g., text displays) on plugin disable.
     * Failing to clean up leaves orphaned boss bars and floating text
     * in the world.
     */
    public void cleanup() {
        if (updateTask != null) {
            updateTask.cancel();
            updateTask = null;
        }
        biomeLightDisplay.cleanup();
        chunkBoundaryDisplay.cleanup();
        entityInfoDisplay.cleanup();
    }

    /**
     * Immediately updates displays for a player whose settings have changed.
     *
     * Why immediate removal: When a player turns off a display via the settings
     * form, waiting up to 0.5 seconds for the next tick cycle would feel laggy.
     * Displays that are newly enabled will be picked up on the next scheduled
     * update cycle, which is fast enough to feel responsive.
     *
     * @param player the player whose settings were modified
     */
    public void onSettingsChanged(Player player) {
        PlayerSettings settings = settingsManager.getSettings(player.getUniqueId());

        // Why: Remove displays that were turned off so the player gets immediate
        // visual feedback. Newly enabled displays will appear on the next tick.
        boolean biomeActive = settings.getBiomeDisplay() == EnvironmentDisplayMode.BOSSBAR;
        boolean lightActive = settings.getLightLevelDisplay() == EnvironmentDisplayMode.BOSSBAR;
        if (!biomeActive && !lightActive) {
            biomeLightDisplay.remove(player);
        }

        // Why: ChunkBoundaryDisplay uses transient particles — no removal needed.
        // When disabled, simply stopping updates is sufficient.

        if (settings.getEntityDisplay() == EntityDisplayMode.OFF) {
            entityInfoDisplay.remove(player);
        }
    }

    // ── Event handlers ──────────────────────────────────────────────────

    /**
     * Loads player settings into cache when a Bedrock player joins.
     *
     * Why: Settings must be loaded before the first update tick fires,
     * otherwise the player would see default (all OFF) settings for up to
     * 0.5 seconds. Loading synchronously on join is acceptable because
     * the settings file is tiny (<1KB).
     */
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (BedrockPlayerUtil.isBedrockPlayer(player)) {
            settingsManager.loadPlayer(player.getUniqueId());
        }
    }

    /**
     * Cleans up all displays and unloads settings when a player disconnects.
     *
     * Why: Boss bars persist server-side even after disconnection. Removing them
     * prevents resource leaks. Settings are saved to disk and removed from cache
     * to prevent memory leaks from accumulating entries.
     */
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        biomeLightDisplay.remove(player);
        entityInfoDisplay.remove(player);
        settingsManager.unloadPlayer(player.getUniqueId());
    }

    // ── Private methods ─────────────────────────────────────────────────

    /**
     * Updates all display features for every online Bedrock player.
     *
     * Why skip non-Bedrock players: Display features use Floodgate/Geyser-specific
     * rendering (boss bars tuned for Bedrock UI, text display entities for Bedrock
     * rendering). Java Edition players have native F3 debug access and do not need
     * these overlays.
     */
    private void updateAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!BedrockPlayerUtil.isBedrockPlayer(player)) {
                continue;
            }

            PlayerSettings settings = settingsManager.getSettings(player.getUniqueId());
            updateBiomeLightDisplay(player, settings);
            updateChunkBoundaryDisplay(player, settings);
            updateEntityDisplay(player, settings);
        }
    }

    /**
     * Updates or removes the biome/light boss bar display based on settings.
     *
     * Why combined check: BiomeLightDisplay accepts two booleans because biome
     * and light level share the same boss bar rendering strategy. Both flags
     * must be evaluated together so the display can format a combined message
     * or remove itself when neither is active.
     */
    private void updateBiomeLightDisplay(Player player, PlayerSettings settings) {
        boolean showBiome = settings.getBiomeDisplay() == EnvironmentDisplayMode.BOSSBAR;
        boolean showLight = settings.getLightLevelDisplay() == EnvironmentDisplayMode.BOSSBAR;

        if (showBiome || showLight) {
            biomeLightDisplay.update(player, showBiome, showLight);
        } else {
            biomeLightDisplay.remove(player);
        }
    }

    /**
     * Updates the chunk boundary particle display if enabled.
     *
     * Why no remove call: ChunkBoundaryDisplay uses transient particles that
     * naturally disappear. When disabled, simply stopping updates is sufficient.
     */
    private void updateChunkBoundaryDisplay(Player player, PlayerSettings settings) {
        if (settings.isChunkBoundaryDisplay()) {
            chunkBoundaryDisplay.update(player);
        }
    }

    /**
     * Updates or removes the entity information display based on settings.
     */
    private void updateEntityDisplay(Player player, PlayerSettings settings) {
        EntityDisplayMode entityMode = settings.getEntityDisplay();

        if (entityMode != EntityDisplayMode.OFF) {
            entityInfoDisplay.update(player, entityMode);
        } else {
            entityInfoDisplay.remove(player);
        }
    }
}
