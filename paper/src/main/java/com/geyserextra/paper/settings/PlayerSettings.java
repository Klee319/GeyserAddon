package com.geyserextra.paper.settings;

import java.util.Objects;

/**
 * Immutable data class representing per-player display settings for Bedrock Edition players.
 *
 * Why: Bedrock players connecting via Geyser/Floodgate need individual display preferences
 * that persist across reconnects and server restarts. Using an immutable design ensures
 * thread safety when settings are read from the main thread while being loaded/saved
 * asynchronously.
 *
 * Why (GSON compatibility): Fields use non-final access for GSON deserialization via
 * the default no-arg constructor, but the class exposes only immutable "with" methods
 * for programmatic updates, preserving the immutable contract at the API level.
 */
public final class PlayerSettings {

    /**
     * Why: Environment-related displays (biome, light level) can be shown in different ways.
     * BOSSBAR uses the boss bar UI element visible at the top of the screen.
     * MENU allows toggling through an interactive form/menu.
     * OFF disables the display entirely, which is the safe default to avoid
     * unexpected visual clutter for new players.
     */
    public enum EnvironmentDisplayMode {
        BOSSBAR,
        MENU,
        OFF
    }

    /**
     * Why: Entity information displays have different rendering strategies.
     * BOSSBAR uses the boss bar UI (limited slots but universally visible).
     * TEXT_DISPLAY uses floating text entities (more flexible positioning but
     * requires entity spawning).
     * OFF disables the display entirely — the safe default.
     */
    public enum EntityDisplayMode {
        BOSSBAR,
        TEXT_DISPLAY,
        OFF
    }

    // Why: Each field defaults to OFF/false so that new players see no extra HUD elements
    // until they explicitly opt in. This prevents confusion and performance impact.
    private EnvironmentDisplayMode biomeDisplay;
    private EnvironmentDisplayMode lightLevelDisplay;
    private boolean chunkBoundaryDisplay;
    private EntityDisplayMode entityDisplay;
    // Why default true: lore tooltip injection (durability + over-enchantment lines)
    // was the original behaviour for every Bedrock player. Switching the default to
    // false would silently hide useful information for existing players who upgrade
    // the plugin. Players who find the extra lines noisy can disable it via the
    // /ga menu without affecting other players.
    private boolean loreTooltipEnabled;

    /**
     * Creates a new PlayerSettings with all displays disabled.
     *
     * Why: GSON requires a no-arg constructor for deserialization. The defaults are
     * intentionally all OFF/false so that a missing or corrupt JSON file results in
     * a safe, non-intrusive player experience. {@code loreTooltipEnabled} is the
     * one exception (default true) to preserve pre-upgrade behaviour for existing
     * Bedrock players.
     */
    public PlayerSettings() {
        this.biomeDisplay = EnvironmentDisplayMode.OFF;
        this.lightLevelDisplay = EnvironmentDisplayMode.OFF;
        this.chunkBoundaryDisplay = false;
        this.entityDisplay = EntityDisplayMode.OFF;
        this.loreTooltipEnabled = true;
    }

    /**
     * Creates a new PlayerSettings with explicit values for the original four fields.
     *
     * <p>Retained for backward compatibility with callers that don't set the
     * lore-tooltip flag. {@code loreTooltipEnabled} defaults to true here too.</p>
     */
    public PlayerSettings(
            EnvironmentDisplayMode biomeDisplay,
            EnvironmentDisplayMode lightLevelDisplay,
            boolean chunkBoundaryDisplay,
            EntityDisplayMode entityDisplay) {
        this(biomeDisplay, lightLevelDisplay, chunkBoundaryDisplay, entityDisplay, true);
    }

    /**
     * Creates a new PlayerSettings with explicit values for all fields.
     */
    public PlayerSettings(
            EnvironmentDisplayMode biomeDisplay,
            EnvironmentDisplayMode lightLevelDisplay,
            boolean chunkBoundaryDisplay,
            EntityDisplayMode entityDisplay,
            boolean loreTooltipEnabled) {

        // Why: Null enum values would cause NullPointerExceptions in downstream switch
        // statements and comparisons. Failing fast here prevents obscure errors later.
        this.biomeDisplay = Objects.requireNonNull(biomeDisplay, "biomeDisplay must not be null");
        this.lightLevelDisplay = Objects.requireNonNull(lightLevelDisplay, "lightLevelDisplay must not be null");
        this.chunkBoundaryDisplay = chunkBoundaryDisplay;
        this.entityDisplay = Objects.requireNonNull(entityDisplay, "entityDisplay must not be null");
        this.loreTooltipEnabled = loreTooltipEnabled;
    }

    // ── Getters ──────────────────────────────────────────────────────────

    public EnvironmentDisplayMode getBiomeDisplay() {
        return biomeDisplay;
    }

    public EnvironmentDisplayMode getLightLevelDisplay() {
        return lightLevelDisplay;
    }

    public boolean isChunkBoundaryDisplay() {
        return chunkBoundaryDisplay;
    }

    public EntityDisplayMode getEntityDisplay() {
        return entityDisplay;
    }

    /**
     * Whether the BedrockEnchantmentHandler should inject the durability /
     * over-enchantment tooltip lines into items the player sees. Toggleable
     * via the /ga menu so a player who finds the extra lines noisy can hide
     * them without affecting anyone else.
     */
    public boolean isLoreTooltipEnabled() {
        return loreTooltipEnabled;
    }

    // ── Immutable "with" methods ─────────────────────────────────────────
    // Why: "with" methods return a new instance instead of mutating the current one.
    // This ensures that any code holding a reference to the old settings is unaffected,
    // which is critical when the main server thread reads settings while the async
    // save thread processes the previous state.

    /**
     * Returns a new PlayerSettings with the biome display mode changed.
     *
     * @param biomeDisplay the new biome display mode
     * @return a new PlayerSettings instance with the updated value
     */
    public PlayerSettings withBiomeDisplay(EnvironmentDisplayMode biomeDisplay) {
        return new PlayerSettings(
                Objects.requireNonNull(biomeDisplay, "biomeDisplay must not be null"),
                this.lightLevelDisplay,
                this.chunkBoundaryDisplay,
                this.entityDisplay,
                this.loreTooltipEnabled
        );
    }

    /**
     * Returns a new PlayerSettings with the light level display mode changed.
     *
     * @param lightLevelDisplay the new light level display mode
     * @return a new PlayerSettings instance with the updated value
     */
    public PlayerSettings withLightLevelDisplay(EnvironmentDisplayMode lightLevelDisplay) {
        return new PlayerSettings(
                this.biomeDisplay,
                Objects.requireNonNull(lightLevelDisplay, "lightLevelDisplay must not be null"),
                this.chunkBoundaryDisplay,
                this.entityDisplay,
                this.loreTooltipEnabled
        );
    }

    /**
     * Returns a new PlayerSettings with chunk boundary display toggled.
     *
     * @param chunkBoundaryDisplay whether to display chunk boundaries
     * @return a new PlayerSettings instance with the updated value
     */
    public PlayerSettings withChunkBoundaryDisplay(boolean chunkBoundaryDisplay) {
        return new PlayerSettings(
                this.biomeDisplay,
                this.lightLevelDisplay,
                chunkBoundaryDisplay,
                this.entityDisplay,
                this.loreTooltipEnabled
        );
    }

    /**
     * Returns a new PlayerSettings with the entity display mode changed.
     *
     * @param entityDisplay the new entity display mode
     * @return a new PlayerSettings instance with the updated value
     */
    public PlayerSettings withEntityDisplay(EntityDisplayMode entityDisplay) {
        return new PlayerSettings(
                this.biomeDisplay,
                this.lightLevelDisplay,
                this.chunkBoundaryDisplay,
                Objects.requireNonNull(entityDisplay, "entityDisplay must not be null"),
                this.loreTooltipEnabled
        );
    }

    /**
     * Returns a new PlayerSettings with the lore-tooltip toggle flipped.
     */
    public PlayerSettings withLoreTooltipEnabled(boolean loreTooltipEnabled) {
        return new PlayerSettings(
                this.biomeDisplay,
                this.lightLevelDisplay,
                this.chunkBoundaryDisplay,
                this.entityDisplay,
                loreTooltipEnabled
        );
    }

    // ── Object overrides ─────────────────────────────────────────────────

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof PlayerSettings other)) {
            return false;
        }
        return this.biomeDisplay == other.biomeDisplay
                && this.lightLevelDisplay == other.lightLevelDisplay
                && this.chunkBoundaryDisplay == other.chunkBoundaryDisplay
                && this.entityDisplay == other.entityDisplay
                && this.loreTooltipEnabled == other.loreTooltipEnabled;
    }

    @Override
    public int hashCode() {
        return Objects.hash(biomeDisplay, lightLevelDisplay, chunkBoundaryDisplay,
            entityDisplay, loreTooltipEnabled);
    }

    @Override
    public String toString() {
        return "PlayerSettings{"
                + "biomeDisplay=" + biomeDisplay
                + ", lightLevelDisplay=" + lightLevelDisplay
                + ", chunkBoundaryDisplay=" + chunkBoundaryDisplay
                + ", entityDisplay=" + entityDisplay
                + ", loreTooltipEnabled=" + loreTooltipEnabled
                + '}';
    }
}
