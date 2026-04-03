package com.geyserextra.paper.util;

import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * Utility for detecting Bedrock Edition players.
 *
 * Why: Multiple components in GeyserExtraPaper need Bedrock detection.
 * Centralizing this logic avoids duplication and ensures consistent behavior.
 */
public final class BedrockPlayerUtil {

    private BedrockPlayerUtil() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Checks if a player is a Bedrock Edition player.
     * Uses Floodgate API first, then Geyser API, falls back to UUID version check.
     *
     * Why multiple checks: auth-type: online uses Mojang auth for Bedrock players,
     * which gives them a regular UUID (version 4). UUID-based detection fails in this case.
     * Floodgate/Geyser API reliably detects Bedrock players regardless of auth-type.
     *
     * @param player the player to check
     * @return true if the player is a Bedrock player
     */
    public static boolean isBedrockPlayer(Player player) {
        // Try Floodgate API first (works for auth-type: floodgate)
        try {
            if (org.geysermc.floodgate.api.FloodgateApi.getInstance()
                    .isFloodgatePlayer(player.getUniqueId())) {
                return true;
            }
        } catch (NoClassDefFoundError | Exception ignored) {}

        // Try Geyser API (works for auth-type: online)
        try {
            if (org.geysermc.geyser.api.GeyserApi.api().isBedrockPlayer(player.getUniqueId())) {
                return true;
            }
        } catch (NoClassDefFoundError | Exception ignored) {}

        // Fallback to UUID version check
        return isBedrockUuid(player.getUniqueId());
    }

    /**
     * Checks if a UUID has the Bedrock/Floodgate version pattern.
     * Floodgate sets UUID version to 0 for Bedrock players.
     *
     * @param uuid the UUID to check
     * @return true if the UUID matches the Bedrock pattern
     */
    public static boolean isBedrockUuid(UUID uuid) {
        long msb = uuid.getMostSignificantBits();
        int version = (int) ((msb >> 12) & 0xF);
        return version == 0;
    }
}
