package com.geyserextra.paper.listener;

import com.geyserextra.paper.skin.BedrockSkinService;
import com.geyserextra.paper.util.BedrockPlayerUtil;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

import java.util.Collection;
import java.util.Objects;

/**
 * Puts the missing {@code textures} property back on a Bedrock player's profile.
 *
 * <p><b>The failure this repairs.</b> Floodgate is responsible for grafting the
 * Mojang-signed skin Geyser uploaded onto the player's game profile. On this
 * network the property never reaches the Paper backend — every Bedrock player's
 * profile arrives with no {@code textures} at all. One missing property explains
 * every symptom at once: Java clients render Steve, the tab list shows a
 * fallback head, DiscordSRV's avatar hook finds nothing to extract, and a player
 * head taken from that profile is textureless. The upload half is healthy; the
 * signed skin is sitting on the GeyserMC API the whole time.</p>
 *
 * <p><b>Why applying it here is a fix and not a patch.</b> The defect is inside
 * Floodgate's Velocity applier, which this project cannot change. What it can do
 * is read the same authoritative source Floodgate reads and write the same
 * property Floodgate would have written. Nothing is faked or approximated: the
 * value and signature are Mojang's own, byte for byte.</p>
 *
 * <p><b>Never fights Floodgate.</b> The property is only written when the
 * profile genuinely lacks one, re-checked on the main thread immediately before
 * the write. If Floodgate starts working again — a fixed build, a Velocity
 * downgrade — this listener silently stops doing anything, and the log line it
 * emits per repair is the signal that it is still needed.</p>
 */
public final class BedrockSkinApplier implements Listener {

    private static final String TEXTURES = "textures";

    private final Plugin plugin;
    private final BedrockSkinService skins;

    public BedrockSkinApplier(Plugin plugin, BedrockSkinService skins) {
        this.plugin = Objects.requireNonNull(plugin, "plugin must not be null");
        this.skins = Objects.requireNonNull(skins, "skins must not be null");
    }

    /**
     * MONITOR so the check runs after any other plugin that legitimately sets a
     * skin on join (cosmetics, nick plugins). Those write a real texture
     * property, {@link #hasTextures} sees it, and this listener stands down.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!BedrockPlayerUtil.isBedrockPlayer(player)) {
            return;
        }

        if (hasTextures(player.getPlayerProfile())) {
            plugin.getLogger().fine(() -> "[BedrockSkin] " + player.getName()
                + " already carries a textures property — nothing to repair.");
            // Still cache it: player heads of this player need the hash too.
            skins.warm(player.getUniqueId());
            return;
        }

        // No delay before the fetch. Waiting on the off-chance Floodgate lands
        // it late would only leave the player as Steve for that whole window;
        // the re-check below covers the race for free, because the fetch itself
        // takes long enough for a late delivery to have happened.
        skins.resolve(player.getUniqueId(), skin -> applyIfStillMissing(player, skin));
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        skins.forget(event.getPlayer().getUniqueId());
    }

    /**
     * Repairs everyone already online.
     *
     * <p>A plugin reload enables this listener with players already connected,
     * and those players will never fire another {@link PlayerJoinEvent}. Without
     * this they would stay skinless until they reconnect — which is exactly the
     * kind of "please relog for me" request this whole fix exists to avoid.</p>
     *
     * @return how many Bedrock players were found to be missing a skin
     */
    public int repairOnlinePlayers() {
        int missing = 0;
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (!BedrockPlayerUtil.isBedrockPlayer(player)) {
                continue;
            }
            if (hasTextures(player.getPlayerProfile())) {
                skins.warm(player.getUniqueId());
                continue;
            }
            missing++;
            skins.resolve(player.getUniqueId(), skin -> applyIfStillMissing(player, skin));
        }
        return missing;
    }

    /**
     * Writes the property, on the main thread, unless something supplied one
     * while the lookup was in flight.
     */
    private void applyIfStillMissing(Player player, BedrockSkinService.BedrockSkin skin) {
        if (!player.isOnline()) {
            return;
        }

        PlayerProfile profile = player.getPlayerProfile();
        if (hasTextures(profile)) {
            plugin.getLogger().fine(() -> "[BedrockSkin] " + player.getName()
                + " gained a textures property while the lookup ran — leaving it alone.");
            return;
        }

        try {
            profile.setProperty(new ProfileProperty(TEXTURES, skin.value(), skin.signature()));
            // setPlayerProfile is what makes the change visible: Paper re-sends
            // the player-info and respawn packets so every other client redraws
            // the skin, rather than only changing it for future viewers.
            player.setPlayerProfile(profile);
            plugin.getLogger().info("[BedrockSkin] Applied the missing skin for "
                + player.getName() + " (texture " + shortHash(skin.textureId())
                + "). Floodgate did not deliver it.");
        } catch (RuntimeException e) {
            plugin.getLogger().warning("[BedrockSkin] Could not apply the skin for "
                + player.getName() + " (" + e.getClass().getSimpleName()
                + ": " + e.getMessage() + ")");
        }
    }

    private static boolean hasTextures(PlayerProfile profile) {
        return profile != null && hasTextures(profile.getProperties());
    }

    /**
     * Whether a set of profile properties already carries a usable skin.
     *
     * <p>An empty value counts as absent: Floodgate's own applier treats a
     * blank {@code textures} value as "no skin", and so must this, or a
     * placeholder property would permanently suppress the repair.</p>
     *
     * <p>Takes the property set rather than the profile so it can be tested
     * without a live server — {@code PlayerProfile} is an interface that only
     * CraftBukkit implements.</p>
     */
    static boolean hasTextures(Collection<ProfileProperty> properties) {
        if (properties == null) {
            return false;
        }
        for (ProfileProperty property : properties) {
            if (property != null
                && TEXTURES.equals(property.getName())
                && property.getValue() != null
                && !property.getValue().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static String shortHash(String hash) {
        return hash.length() <= 16 ? hash : hash.substring(0, 16) + "…";
    }
}
