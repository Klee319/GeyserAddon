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
 * <p><b>Presence is not correctness.</b> An earlier version stood down the
 * moment the profile carried any {@code textures} property at all. On this
 * network that made it a no-op: {@code floodgate-spigot} is installed on the
 * backend as well and does put <em>a</em> property there, so the repair never
 * ran while every Bedrock player still rendered as Steve — and because the
 * stand-down was logged at FINE, it left no trace either. The check is now
 * against the authoritative skin rather than against mere presence: the
 * property is replaced when it names a different texture, or when it carries no
 * signature. An identical, signed property is left exactly as it is, so a
 * working Floodgate still makes this listener do nothing.</p>
 *
 * <p><b>What it will not do.</b> The comparison target is the player's own
 * Bedrock skin from the GeyserMC API, so "different" means "not what this player
 * actually looks like". A cosmetics or nick plugin that deliberately reskins a
 * Bedrock player would be overridden — there is no such plugin on this network,
 * and the alternative is the silent no-op this replaced.</p>
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

        // Always fetch, even when a property is already there: only the
        // authoritative skin can say whether the one on the profile is the right
        // one. The lookup is cached per player and costs one request per join at
        // worst. The decision itself happens on the main thread in reconcile().
        skins.resolve(player.getUniqueId(), skin -> reconcile(player, skin));
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
        int bedrock = 0;
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (!BedrockPlayerUtil.isBedrockPlayer(player)) {
                continue;
            }
            bedrock++;
            skins.resolve(player.getUniqueId(), skin -> reconcile(player, skin));
        }
        return bedrock;
    }

    /**
     * Compares what the profile carries against the authoritative skin and
     * writes only when they disagree. Runs on the main thread.
     */
    private void reconcile(Player player, BedrockSkinService.BedrockSkin skin) {
        if (!player.isOnline()) {
            return;
        }

        PlayerProfile profile = player.getPlayerProfile();
        ProfileProperty current = texturesOf(profile.getProperties());

        if (current == null) {
            write(player, profile, skin, "Floodgate delivered no textures property");
            return;
        }

        String currentId = BedrockSkinService.textureIdFromValue(current.getValue());
        boolean signed = current.getSignature() != null && !current.getSignature().isEmpty();
        if (skin.textureId().equals(currentId) && signed) {
            plugin.getLogger().fine(() -> "[BedrockSkin] " + player.getName()
                + " already carries the correct signed skin — nothing to repair.");
            return;
        }

        // Two distinct failures, one repair. A different texture means the
        // delivered skin is not this player's; an unsigned one means the client
        // has no way to trust it. Both are worth replacing with Mojang's own
        // signed copy, and naming which it was is what makes the log usable.
        write(player, profile, skin, skin.textureId().equals(currentId)
            ? "the delivered property carried no signature"
            : "the delivered property named a different texture ("
                + shortHash(currentId == null ? "(undecodable)" : currentId) + ")");
    }

    private void write(Player player, PlayerProfile profile,
                       BedrockSkinService.BedrockSkin skin, String reason) {
        try {
            profile.setProperty(new ProfileProperty(TEXTURES, skin.value(), skin.signature()));
            // setPlayerProfile is what makes the change visible: Paper re-sends
            // the player-info and respawn packets so every other client redraws
            // the skin, rather than only changing it for future viewers.
            player.setPlayerProfile(profile);
            plugin.getLogger().info("[BedrockSkin] Applied the skin for "
                + player.getName() + " (texture " + shortHash(skin.textureId())
                + ") — " + reason + ".");
        } catch (RuntimeException e) {
            plugin.getLogger().warning("[BedrockSkin] Could not apply the skin for "
                + player.getName() + " (" + e.getClass().getSimpleName()
                + ": " + e.getMessage() + ")");
        }
    }

    /**
     * The {@code textures} property in a profile, or null when there is none
     * that could carry a skin.
     *
     * <p>An empty value counts as absent: Floodgate's own applier treats a
     * blank {@code textures} value as "no skin", and so must this, or a
     * placeholder property would be compared against instead of replaced.</p>
     *
     * <p>Takes the property set rather than the profile so it can be tested
     * without a live server — {@code PlayerProfile} is an interface that only
     * CraftBukkit implements.</p>
     */
    static ProfileProperty texturesOf(Collection<ProfileProperty> properties) {
        if (properties == null) {
            return null;
        }
        for (ProfileProperty property : properties) {
            if (property != null
                && TEXTURES.equals(property.getName())
                && property.getValue() != null
                && !property.getValue().isEmpty()) {
                return property;
            }
        }
        return null;
    }

    private static String shortHash(String hash) {
        return hash.length() <= 16 ? hash : hash.substring(0, 16) + "…";
    }
}
