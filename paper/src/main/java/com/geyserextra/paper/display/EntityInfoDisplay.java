package com.geyserextra.paper.display;

import com.geyserextra.paper.settings.PlayerSettings.EntityDisplayMode;
import com.geyserextra.paper.util.TranslationUtil;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Displays entity name and health information for the entity a player is looking at.
 *
 * Why: Bedrock Edition does not display entity health or detailed mob information the
 * way Java Edition does (e.g., via F3 debug screen or modded HUD overlays). This class
 * provides Bedrock players with real-time entity information using either a BossBar
 * (persistent display at top of screen) or ActionBar (transient display above hotbar),
 * depending on the player's configured EntityDisplayMode.
 *
 * Why raytrace approach: Raytrace from the player's eye position accurately determines
 * which entity the crosshair is pointing at, matching the intuitive "I'm looking at this
 * mob" behavior that Bedrock players expect.
 *
 * Mode differences:
 * - BOSSBAR: Persistent red bar at the top of the screen. Remains visible until the
 *   player looks away. Health bar progress reflects the entity's current/max health ratio.
 * - TEXT_DISPLAY: Sends entity info as an ActionBar message above the hotbar. Fades
 *   naturally when the player looks away. Chosen over actual TextDisplay entities because
 *   per-player TextDisplay visibility requires complex packet manipulation, and ActionBar
 *   works reliably on Bedrock via Geyser.
 */
public final class EntityInfoDisplay {

    /**
     * Why: 20 blocks is a practical maximum engagement distance for identifying mobs.
     * Beyond this distance, entities are too small to meaningfully distinguish, and
     * raytracing further would waste computation for diminishing returns.
     */
    private static final double MAX_RAYTRACE_DISTANCE = 20.0;

    /**
     * Why: Heart symbol provides an intuitive health indicator that transcends language
     * barriers. Bedrock players from any locale immediately recognize it as health.
     */
    private static final String HEART_SYMBOL = "\u2665";

    /**
     * Why: Per-player BossBar tracking for BOSSBAR mode. Uses UUID keys to avoid
     * holding strong references to Player objects, preventing memory leaks after
     * disconnect. Only populated when a player uses BOSSBAR mode and is looking
     * at an entity.
     */
    private final Map<UUID, BossBar> bossBars = new ConcurrentHashMap<>();

    /**
     * Creates a new EntityInfoDisplay instance.
     *
     * Why: No-arg constructor is intentional. BossBars are created lazily when a
     * player first looks at an entity with BOSSBAR mode enabled, avoiding
     * unnecessary allocation for players using TEXT_DISPLAY mode or who have
     * entity display disabled.
     */
    public EntityInfoDisplay() {
        // Why: intentionally empty — BossBars are created on demand in update()
    }

    /**
     * Updates the entity information display for the given player.
     *
     * Why mode parameter: The display mode is passed in rather than read from
     * PlayerSettings directly, keeping this class decoupled from the settings
     * persistence layer. The caller (typically a scheduled task) resolves the
     * mode and passes it in.
     *
     * @param player the Bedrock player to update the display for
     * @param mode   the entity display mode (BOSSBAR or TEXT_DISPLAY)
     */
    public void update(Player player, EntityDisplayMode mode) {
        if (mode == EntityDisplayMode.OFF) {
            remove(player);
            return;
        }

        LivingEntity target = findTargetEntity(player);

        if (target == null) {
            handleNoTarget(player, mode);
            return;
        }

        Component entityInfo = buildEntityInfoText(target);
        double currentHealth = target.getHealth();
        double maxHealth = resolveMaxHealth(target);

        switch (mode) {
            case BOSSBAR -> updateBossBar(player, entityInfo, currentHealth, maxHealth);
            case TEXT_DISPLAY -> sendActionBar(player, entityInfo);
            default -> {
                // Why: Defensive default for future enum values. OFF is already handled
                // above, but this prevents silent failures if new modes are added.
            }
        }
    }

    /**
     * Removes the entity display for the given player and cleans up resources.
     *
     * Why: Must be called on player disconnect or when the player switches to OFF mode,
     * to prevent BossBar leaks and stale displays.
     *
     * @param player the player whose entity display should be removed
     */
    public void remove(Player player) {
        BossBar bossBar = bossBars.remove(player.getUniqueId());
        if (bossBar != null) {
            player.hideBossBar(bossBar);
        }
    }

    /**
     * Removes all tracked BossBars for all players.
     *
     * Why: Called during plugin disable to prevent orphaned BossBars from persisting
     * after server reload. On /reload, players remain connected, so we must
     * explicitly hide BossBars to prevent stale displays.
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
     * Performs a raytrace from the player's eye to find the targeted LivingEntity.
     *
     * Why rayTraceEntities over getTargetEntity: rayTraceEntities returns a RayTraceResult
     * with precise hit information and allows filtering by entity type via a predicate.
     * getTargetEntity does not support type filtering and may return non-living entities
     * like item frames or armor stands that have no meaningful health to display.
     *
     * Why filter for LivingEntity: Only LivingEntity instances have health values.
     * Displaying info for paintings, item frames, or other non-living entities would
     * show meaningless data and confuse players.
     *
     * Why exclude Player: Displaying another player's health via BossBar could be
     * considered unfair in PvP scenarios. Entity info is intended for mob identification.
     *
     * Why exclude ArmorStand: ArmorStands are LivingEntity but are technical/decorative
     * entities with fixed health (20/20). Worlds with many decorative armor stands would
     * cause frequent unwanted health displays.
     *
     * @param player the player performing the raytrace
     * @return the targeted LivingEntity, or null if none found within range
     */
    private LivingEntity findTargetEntity(Player player) {
        Location eyeLocation = player.getEyeLocation();

        // Why: NEVER for fluid collision — we want to see through water/lava to the
        // entity behind them, matching the visual crosshair behavior
        RayTraceResult result = player.getWorld().rayTraceEntities(
                eyeLocation,
                eyeLocation.getDirection(),
                MAX_RAYTRACE_DISTANCE,
                entity -> entity instanceof LivingEntity
                        && !(entity instanceof Player)
                        && !(entity instanceof ArmorStand)
        );

        if (result == null || result.getHitEntity() == null) {
            return null;
        }

        Entity hitEntity = result.getHitEntity();
        if (hitEntity instanceof LivingEntity livingEntity) {
            return livingEntity;
        }

        return null;
    }

    /**
     * Builds the display text Component for an entity's name and health.
     *
     * Why custom name priority: If an entity has a custom name (e.g., named via name tag),
     * that name is more meaningful to the player than the generic mob type. Players name
     * entities specifically to identify them, so the custom name takes precedence.
     *
     * @param entity the entity to build display text for
     * @return the formatted Component with entity name and health
     */
    private Component buildEntityInfoText(LivingEntity entity) {
        Component entityName = resolveEntityName(entity);

        // Why: Format health as "current / max" with one decimal place for precision
        // without excessive digits. Minecraft health is stored as doubles but displayed
        // values rarely need more than one decimal.
        double maxHealth = resolveMaxHealth(entity);
        String healthText = String.format(" %s %.1f / %.1f",
                HEART_SYMBOL,
                entity.getHealth(),
                maxHealth);

        return entityName
                .append(Component.text(healthText, NamedTextColor.RED));
    }

    /**
     * Resolves the display name for an entity, preferring custom names over type names.
     *
     * Why TranslationUtil for type name: The entity's translatable name (e.g.,
     * "entity.minecraft.zombie") needs to be resolved to Japanese on the server side
     * because Geyser/Bedrock may not apply server-side translation keys correctly
     * in all UI contexts (BossBar, ActionBar).
     *
     * @param entity the entity whose name to resolve
     * @return the resolved name as a Component
     */
    private Component resolveEntityName(LivingEntity entity) {
        // Why: customName() returns null if no custom name is set, not an empty Component.
        // A non-null customName means the entity was explicitly named by a player.
        Component customName = entity.customName();
        if (customName != null) {
            return customName;
        }

        // Why: entity.name() returns a translatable Component (e.g., TranslatableComponent
        // with key "entity.minecraft.zombie"). We render it to Japanese plain text and
        // wrap it back in a text Component for consistent formatting.
        String translatedName = TranslationUtil.renderJapanese(entity.name());
        return Component.text(translatedName, NamedTextColor.WHITE);
    }

    /**
     * Updates or creates the BossBar for BOSSBAR mode entity display.
     *
     * Why RED color: Red is universally associated with health/HP in games. Using red
     * for the entity health bar distinguishes it from the GREEN biome/light BossBar,
     * preventing visual confusion when both displays are active simultaneously.
     *
     * @param player        the target player
     * @param entityInfo    the formatted entity info Component
     * @param currentHealth the entity's current health
     * @param maxHealth     the entity's maximum health
     */
    private void updateBossBar(Player player, Component entityInfo, double currentHealth, double maxHealth) {
        float progress = calculateHealthProgress(currentHealth, maxHealth);
        UUID playerId = player.getUniqueId();
        BossBar bossBar = bossBars.get(playerId);

        if (bossBar == null) {
            bossBar = BossBar.bossBar(entityInfo, progress, BossBar.Color.RED, BossBar.Overlay.PROGRESS);
            bossBars.put(playerId, bossBar);
            player.showBossBar(bossBar);
        } else {
            bossBar.name(entityInfo);
            bossBar.progress(progress);
        }
    }

    /**
     * Sends entity info as an ActionBar message for TEXT_DISPLAY mode.
     *
     * Why ActionBar over actual TextDisplay entities: TextDisplay entities with per-player
     * visibility require either ProtocolLib packet manipulation or complex entity tracking
     * with show/hide per viewer. ActionBar is natively supported by Geyser and appears
     * above the hotbar, providing a clean, non-intrusive display that fades naturally
     * when the player looks away from the entity.
     *
     * @param player     the target player
     * @param entityInfo the formatted entity info Component
     */
    private void sendActionBar(Player player, Component entityInfo) {
        player.sendActionBar(entityInfo);
    }

    /**
     * Handles the case when no entity is targeted by the player's crosshair.
     *
     * Why mode-specific handling: In BOSSBAR mode, the bar must be explicitly hidden
     * to prevent showing stale entity info. In TEXT_DISPLAY (ActionBar) mode, the
     * message fades naturally after ~2 seconds, so no action is needed.
     *
     * @param player the player with no target entity
     * @param mode   the current display mode
     */
    private void handleNoTarget(Player player, EntityDisplayMode mode) {
        if (mode == EntityDisplayMode.BOSSBAR) {
            // Why: Hide BossBar immediately when not looking at any entity to prevent
            // displaying outdated information about an entity the player can no longer see
            BossBar bossBar = bossBars.remove(player.getUniqueId());
            if (bossBar != null) {
                player.hideBossBar(bossBar);
            }
        }
        // Why: TEXT_DISPLAY mode uses ActionBar which fades on its own — no cleanup needed
    }

    /**
     * Resolves the maximum health of a LivingEntity using the Attribute API.
     *
     * Why Attribute API over getMaxHealth(): LivingEntity.getMaxHealth() is deprecated
     * in modern Paper/Bukkit. The Attribute-based approach is the canonical way to read
     * max health, and correctly accounts for health modifiers from effects, equipment,
     * and custom attributes.
     *
     * @param entity the entity whose max health to resolve
     * @return the entity's maximum health, or 20.0 as a fallback
     */
    private double resolveMaxHealth(LivingEntity entity) {
        AttributeInstance attribute = entity.getAttribute(Attribute.MAX_HEALTH);
        if (attribute != null) {
            return attribute.getValue();
        }
        // Why: 20.0 (10 hearts) is the default max health for most mobs and players.
        // This fallback is extremely unlikely to trigger since all LivingEntities have
        // the MAX_HEALTH attribute, but prevents NPE in theoretical edge cases.
        return 20.0;
    }

    /**
     * Calculates BossBar progress from entity health values.
     *
     * Why clamping: Entity health can theoretically exceed maxHealth through effects
     * like Absorption, or be negative in edge cases during death. Clamping to [0, 1]
     * prevents Adventure API from throwing IllegalArgumentException on invalid progress.
     *
     * @param currentHealth the entity's current health
     * @param maxHealth     the entity's maximum health
     * @return progress value clamped between 0.0 and 1.0
     */
    private float calculateHealthProgress(double currentHealth, double maxHealth) {
        if (maxHealth <= 0) {
            // Why: Prevent division by zero for entities with 0 max health (edge case
            // with certain custom entities or plugins)
            return 0.0f;
        }
        float progress = (float) (currentHealth / maxHealth);
        return Math.max(0.0f, Math.min(1.0f, progress));
    }
}
