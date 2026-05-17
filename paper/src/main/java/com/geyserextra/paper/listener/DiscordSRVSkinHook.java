package com.geyserextra.paper.listener;

import com.geyserextra.paper.util.BedrockPlayerUtil;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import github.scarsz.discordsrv.DiscordSRV;
import github.scarsz.discordsrv.api.ListenerPriority;
import github.scarsz.discordsrv.api.Subscribe;
import github.scarsz.discordsrv.api.events.AchievementMessagePostProcessEvent;
import github.scarsz.discordsrv.api.events.DeathMessagePostProcessEvent;
import github.scarsz.discordsrv.api.events.GameChatMessagePostProcessEvent;
import github.scarsz.discordsrv.dependencies.jda.api.entities.MessageEmbed;
import github.scarsz.discordsrv.dependencies.jda.api.entities.TextChannel;
import github.scarsz.discordsrv.util.WebhookUtil;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Hooks into DiscordSRV to fix avatar URLs for Bedrock players.
 *
 * Why: DiscordSRV uses Mojang UUID-based avatar services (Crafthead/Crafatar) which don't
 * recognize Floodgate's fake UUIDs, resulting in Steve/Alex avatars for Bedrock players.
 * This hook intercepts chat messages and re-sends them via WebhookUtil with the correct
 * skin texture URL extracted from the player's GameProfile.
 *
 * Gracefully no-ops if DiscordSRV is not installed.
 */
public final class DiscordSRVSkinHook implements Listener {

    private static final Logger LOGGER = Logger.getLogger(DiscordSRVSkinHook.class.getName());

    private final Plugin plugin;
    private volatile boolean registered;

    /** Cache of Bedrock player skin URLs, populated on join (main thread safe). */
    private final Map<UUID, String> skinUrlCache = new ConcurrentHashMap<>();

    public DiscordSRVSkinHook(Plugin plugin) {
        this.plugin = plugin;
        this.registered = false;
    }

    /**
     * Attempts to register with DiscordSRV's API with delayed initialization.
     *
     * Why delayed: DiscordSRV may not be fully initialized when GeyserExtra enables.
     * Scheduling registration 2 seconds later ensures DiscordSRV.api is available.
     */
    public void tryRegister() {
        try {
            Class.forName("github.scarsz.discordsrv.DiscordSRV");
            LOGGER.fine("[DiscordSRV] DiscordSRV class found, initializing avatar hook...");
        } catch (ClassNotFoundException e) {
            LOGGER.info("[DiscordSRV] DiscordSRV not installed, skipping avatar hook");
            return;
        }

        // Register Bukkit listener for skin caching (join/quit events)
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        LOGGER.fine("[DiscordSRV] Bukkit listeners registered for skin caching");

        // Delay DiscordSRV API registration to ensure it's fully initialized
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            try {
                DiscordSRV.api.subscribe(this);
                registered = true;
                LOGGER.info("[DiscordSRV] Avatar hook registered for Bedrock players");
            } catch (Exception e) {
                LOGGER.warning("[DiscordSRV] Failed to register avatar hook: " + e.getMessage());
                e.printStackTrace();
            }
        }, 40L); // 2 second delay
    }

    /**
     * Unregisters from DiscordSRV's API.
     */
    public void unregister() {
        skinUrlCache.clear();
        if (!registered) return;
        try {
            DiscordSRV.api.unsubscribe(this);
            registered = false;
        } catch (Exception e) {
            // DiscordSRV may already be unloaded
        }
    }

    // ================== Skin URL Caching (main thread) ==================

    /**
     * Caches the Bedrock player's skin URL on join.
     * Why cache: extractSkinUrl accesses Paper API (getPlayerProfile) which must be
     * called from the main thread, but DiscordSRV events may fire asynchronously.
     */
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!BedrockPlayerUtil.isBedrockPlayer(player)) return;

        // Delay slightly to ensure Geyser has set the GameProfile texture
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;
            String url = extractSkinUrl(player);
            if (url != null) {
                skinUrlCache.put(player.getUniqueId(), url);
                LOGGER.fine("[DiscordSRV] Cached skin URL for " + player.getName());
            }
        }, 60L); // 3 seconds — wait for Geyser to process skin
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        skinUrlCache.remove(event.getPlayer().getUniqueId());
    }

    // ================== DiscordSRV Hook ==================

    /**
     * Intercepts processed chat messages for Bedrock players.
     * Cancels the original message and re-sends it with the correct avatar URL.
     */
    @Subscribe(priority = ListenerPriority.HIGH)
    public void onGameChatPostProcess(GameChatMessagePostProcessEvent event) {
        Player player = event.getPlayer();
        if (player == null) return;

        LOGGER.fine("[DiscordSRV] Chat event from " + player.getName()
            + " | isBedrock=" + BedrockPlayerUtil.isBedrockPlayer(player)
            + " | hasCachedUrl=" + skinUrlCache.containsKey(player.getUniqueId()));

        if (!BedrockPlayerUtil.isBedrockPlayer(player)) {
            return;
        }

        String skinUrl = skinUrlCache.get(player.getUniqueId());
        if (skinUrl == null) {
            LOGGER.fine("[DiscordSRV] No cached skin URL for " + player.getName() + ", using default");
            return;
        }

        LOGGER.fine("[DiscordSRV] Replacing avatar for " + player.getName() + " → " + skinUrl);
        event.setCancelled(true);

        String channelName = event.getChannel();
        TextChannel textChannel = DiscordSRV.getPlugin()
            .getDestinationTextChannelForGameChannelName(channelName);
        if (textChannel == null) {
            return;
        }

        // Why PlainTextComponentSerializer: Paper deprecated Player#getDisplayName()
        // (legacy String) in favour of Player#displayName() returning an Adventure
        // Component. WebhookUtil expects a plain String, so we serialize the
        // Component back to plain text, dropping decorations that webhook chat
        // can't render anyway.
        String displayName = PlainTextComponentSerializer.plainText()
            .serialize(player.displayName());
        String message = event.getProcessedMessage();

        WebhookUtil.deliverMessage(textChannel, displayName, skinUrl, message,
            (MessageEmbed) null);
    }

    /**
     * Intercepts death messages for Bedrock players.
     * Uses setWebhookAvatarUrl() to replace avatar without cancelling the message.
     */
    @Subscribe(priority = ListenerPriority.HIGH)
    public void onDeathMessagePostProcess(DeathMessagePostProcessEvent event) {
        Player player = event.getPlayer();
        if (player == null || !BedrockPlayerUtil.isBedrockPlayer(player)) return;

        String skinUrl = skinUrlCache.get(player.getUniqueId());
        if (skinUrl == null) return;

        event.setWebhookAvatarUrl(skinUrl);
        event.setUsingWebhooks(true);
        LOGGER.fine("[DiscordSRV] Death message avatar replaced for " + player.getName());
    }

    /**
     * Intercepts achievement/advancement messages for Bedrock players.
     * Uses setWebhookAvatarUrl() to replace avatar without cancelling the message.
     */
    @Subscribe(priority = ListenerPriority.HIGH)
    public void onAchievementMessagePostProcess(AchievementMessagePostProcessEvent event) {
        Player player = event.getPlayer();
        if (player == null || !BedrockPlayerUtil.isBedrockPlayer(player)) return;

        String skinUrl = skinUrlCache.get(player.getUniqueId());
        if (skinUrl == null) return;

        event.setWebhookAvatarUrl(skinUrl);
        event.setUsingWebhooks(true);
        LOGGER.fine("[DiscordSRV] Achievement message avatar replaced for " + player.getName());
    }

    // ================== Skin URL Extraction ==================

    /**
     * Extracts the skin head render URL from a player's GameProfile.
     * Must be called from the main thread (accesses Paper API).
     *
     * @return Crafthead head render URL, or null if no texture is available
     */
    private String extractSkinUrl(Player player) {
        try {
            var profile = player.getPlayerProfile();
            var texturesProp = profile.getProperties().stream()
                .filter(p -> "textures".equals(p.getName()))
                .findFirst();

            if (texturesProp.isEmpty()) {
                return null;
            }

            String base64Value = texturesProp.get().getValue();
            String json = new String(Base64.getDecoder().decode(base64Value));

            // Parse with Gson for robustness
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonObject textures = root.getAsJsonObject("textures");
            if (textures == null) return null;

            JsonObject skin = textures.getAsJsonObject("SKIN");
            if (skin == null) return null;

            String url = skin.get("url").getAsString();
            if (url == null || !url.contains("textures.minecraft.net")) return null;

            // Extract texture hash from URL
            String hash = url.substring(url.lastIndexOf('/') + 1);

            // Use Crafthead to render the head as a Discord avatar
            return "https://crafthead.net/helm/" + hash + "/128";

        } catch (Exception e) {
            LOGGER.warning("[DiscordSRV] Failed to extract skin URL for "
                + player.getName() + ": " + e.getMessage());
            return null;
        }
    }
}
