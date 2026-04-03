package com.geyserextra.paper.display;

import com.geyserextra.paper.util.TranslationUtil;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages a BossBar per player that displays biome name and/or light level information.
 *
 * Why: Bedrock Edition players connecting via Geyser cannot see the F3 debug screen
 * that Java Edition players use to check biome and light level. This class provides
 * an equivalent HUD element using the Adventure BossBar API, which Geyser translates
 * into a Bedrock-compatible boss bar display.
 *
 * Why BossBar (not ActionBar): BossBar persists on screen until explicitly removed,
 * whereas ActionBar text fades after a few seconds. For continuously displayed
 * environment information, BossBar provides a more stable reading experience.
 */
public final class BiomeLightDisplay {

    /**
     * Why: Maximum light level in Minecraft is 15. Used to normalize the BossBar
     * progress value into the 0.0-1.0 range, providing a visual indicator of
     * brightness as the bar fill amount.
     */
    private static final float MAX_LIGHT_LEVEL = 15.0f;

    /**
     * Why: Per-player BossBar tracking is necessary because each player may be in
     * a different biome with different light levels. Using UUID keys instead of
     * Player references avoids holding strong references to Player objects that
     * could prevent garbage collection after disconnect.
     */
    private final Map<UUID, BossBar> bossBars = new ConcurrentHashMap<>();

    /**
     * Creates a new BiomeLightDisplay instance.
     *
     * Why: No-arg constructor is intentional. BossBars are created lazily on first
     * update() call per player, avoiding unnecessary object creation for players
     * who have not enabled this display.
     */
    public BiomeLightDisplay() {
        // Why: intentionally empty — BossBars are created on demand in update()
    }

    /**
     * Updates or creates the BossBar for the given player with current biome and/or light info.
     *
     * Why two boolean flags instead of a single enum: A player may want to see biome only,
     * light only, or both simultaneously. Using separate flags allows independent control
     * without a combinatorial explosion of enum values.
     *
     * @param player    the Bedrock player to show the BossBar to
     * @param showBiome whether to include biome name in the display
     * @param showLight whether to include light level in the display
     */
    public void update(Player player, boolean showBiome, boolean showLight) {
        if (!showBiome && !showLight) {
            // Why: If neither display is requested, clean up any existing BossBar
            // to avoid showing a stale bar from a previous setting
            remove(player);
            return;
        }

        Block block = player.getLocation().getBlock();
        Component displayText = buildDisplayText(block, showBiome, showLight);
        float progress = calculateProgress(block, showLight);

        UUID playerId = player.getUniqueId();
        BossBar bossBar = bossBars.get(playerId);

        if (bossBar == null) {
            // Why: Create with GREEN color and PROGRESS overlay to provide a clean,
            // environment-themed visual. PROGRESS overlay shows a smooth bar without
            // segment notches, which better represents the continuous light level value.
            bossBar = BossBar.bossBar(displayText, progress, BossBar.Color.GREEN, BossBar.Overlay.PROGRESS);
            bossBars.put(playerId, bossBar);
            player.showBossBar(bossBar);
        } else {
            // Why: Updating existing BossBar instead of recreating avoids visual flicker
            // on the client. Adventure's BossBar automatically pushes changes to viewers.
            bossBar.name(displayText);
            bossBar.progress(progress);
        }
    }

    /**
     * Removes and hides the BossBar for the given player.
     *
     * Why: Must be called on player disconnect or when the player disables the display,
     * otherwise the BossBar remains visible and the UUID entry leaks memory.
     *
     * @param player the player whose BossBar should be removed
     */
    public void remove(Player player) {
        BossBar bossBar = bossBars.remove(player.getUniqueId());
        if (bossBar != null) {
            player.hideBossBar(bossBar);
        }
    }

    /**
     * Removes all tracked BossBars from all players.
     *
     * Why: Called during plugin disable to ensure no orphaned BossBars persist
     * after a server reload or shutdown. On /reload, players remain connected,
     * so we must explicitly hide BossBars to prevent stale displays.
     */
    public void cleanup() {
        for (var entry : bossBars.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null) {
                player.hideBossBar(entry.getValue());
            }
        }
        bossBars.clear();
    }

    /**
     * Builds the display text Component based on which information is enabled.
     *
     * Why separate method: Keeps the update() method focused on BossBar lifecycle
     * management, while this method handles the text formatting logic.
     *
     * @param block     the block at the player's location (source of biome/light data)
     * @param showBiome whether to include biome name
     * @param showLight whether to include light level
     * @return the formatted Component for the BossBar name
     */
    private Component buildDisplayText(Block block, boolean showBiome, boolean showLight) {
        Component biomeComponent = null;
        Component lightComponent = null;

        if (showBiome) {
            String biomeName = resolveBiomeName(block);
            biomeComponent = Component.text("バイオーム: ", NamedTextColor.WHITE)
                    .append(Component.text(biomeName, NamedTextColor.GREEN));
        }

        if (showLight) {
            int lightLevel = block.getLightLevel();
            lightComponent = Component.text("明るさ: ", NamedTextColor.WHITE)
                    .append(Component.text(lightLevel, NamedTextColor.YELLOW));
        }

        // Why: When both are shown, a separator pipe character improves readability
        // by visually dividing the two distinct pieces of information.
        if (biomeComponent != null && lightComponent != null) {
            return biomeComponent
                    .append(Component.text(" | ", NamedTextColor.GRAY))
                    .append(lightComponent);
        }

        if (biomeComponent != null) {
            return biomeComponent;
        }

        // Why: lightComponent is guaranteed non-null here because we return early
        // if both showBiome and showLight are false at the top of update()
        return lightComponent;
    }

    /**
     * Resolves the biome name to a Japanese-translated plain text string.
     *
     * Why TranslationUtil: Bedrock players expect Japanese text in the UI. Paper's
     * GlobalTranslator has Minecraft's translation bundles registered, so we can
     * resolve "biome.minecraft.plains" to its Japanese equivalent server-side.
     *
     * @param block the block whose biome to resolve
     * @return the translated biome name as plain text
     */
    private String resolveBiomeName(Block block) {
        // Why: Paper 1.21 Biome enum provides getKey() returning a NamespacedKey.
        // The value() portion (e.g., "plains", "dark_forest") maps directly to
        // Minecraft's translation key format: "biome.minecraft.{value}"
        String biomeKeyValue = block.getBiome().getKey().value();
        String translationKey = "biome.minecraft." + biomeKeyValue;
        return TranslationUtil.renderJapanese(Component.translatable(translationKey));
    }

    /**
     * Calculates the BossBar progress value based on light level.
     *
     * Why proportional to light: The BossBar fill provides an intuitive visual cue
     * for brightness. A full bar means maximum light (15), an empty bar means total
     * darkness (0). This is more immediately readable than the numeric value alone.
     *
     * @param block     the block at the player's location
     * @param showLight whether light display is enabled
     * @return progress value between 0.0 and 1.0
     */
    private float calculateProgress(Block block, boolean showLight) {
        if (!showLight) {
            // Why: When only showing biome (no light), use full bar to avoid an
            // empty-looking BossBar that might be confused with a depleted health bar
            return 1.0f;
        }
        return block.getLightLevel() / MAX_LIGHT_LEVEL;
    }
}
