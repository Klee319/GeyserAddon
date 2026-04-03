package com.geyserextra.paper.scanner;

import com.geyserextra.core.api.SkullData;
import com.geyserextra.core.registry.SkullRegistry;
import com.geyserextra.paper.GeyserExtraPaper;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;

import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Skull;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scanner for detecting and registering custom skull textures.
 *
 * This scanner extracts texture data from player head items and skull blocks
 * to enable Bedrock players to see custom skull textures via Geyser.
 */
public final class SkullScanner {

    /**
     * Set of skull block materials to scan.
     */
    private static final Set<Material> SKULL_MATERIALS = Set.of(
        Material.PLAYER_HEAD,
        Material.PLAYER_WALL_HEAD,
        Material.SKELETON_SKULL,
        Material.SKELETON_WALL_SKULL,
        Material.WITHER_SKELETON_SKULL,
        Material.WITHER_SKELETON_WALL_SKULL,
        Material.ZOMBIE_HEAD,
        Material.ZOMBIE_WALL_HEAD,
        Material.CREEPER_HEAD,
        Material.CREEPER_WALL_HEAD,
        Material.DRAGON_HEAD,
        Material.DRAGON_WALL_HEAD,
        Material.PIGLIN_HEAD,
        Material.PIGLIN_WALL_HEAD
    );

    /**
     * Pattern to extract texture URL from base64 decoded profile data.
     * Matches alphanumeric characters, underscores, and hyphens in the hash.
     */
    private static final Pattern TEXTURE_URL_PATTERN = Pattern.compile(
        "\"url\"\\s*:\\s*\"(https?://textures\\.minecraft\\.net/texture/[a-zA-Z0-9_-]+)\""
    );

    /**
     * The property name for textures in player profiles.
     */
    private static final String TEXTURES_PROPERTY = "textures";

    private final SkullRegistry registry;
    private final GeyserExtraPaper plugin;

    /**
     * Creates a new SkullScanner.
     *
     * @param registry The skull registry to store discovered skulls
     * @param plugin   The plugin instance for logging and configuration
     * @throws NullPointerException if registry or plugin is null
     */
    public SkullScanner(SkullRegistry registry, GeyserExtraPaper plugin) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.plugin = Objects.requireNonNull(plugin, "plugin must not be null");
    }

    /**
     * Scans a skull item stack for texture data.
     *
     * @param itemStack The item stack to scan (can be null)
     * @return Optional containing the discovered skull data, or empty if not a skull
     */
    public Optional<SkullData> scanSkull(ItemStack itemStack) {
        if (itemStack == null || itemStack.getType() != Material.PLAYER_HEAD) {
            return Optional.empty();
        }

        if (!(itemStack.getItemMeta() instanceof SkullMeta skullMeta)) {
            return Optional.empty();
        }

        return extractTextureFromMeta(skullMeta);
    }

    /**
     * Scans a block for skull texture data.
     *
     * @param block The block to scan (can be null)
     * @return Optional containing the discovered skull data, or empty if not a skull
     */
    public Optional<SkullData> scanBlock(Block block) {
        if (block == null || !SKULL_MATERIALS.contains(block.getType())) {
            return Optional.empty();
        }

        // Only player heads have custom textures
        if (block.getType() != Material.PLAYER_HEAD
            && block.getType() != Material.PLAYER_WALL_HEAD) {
            return Optional.empty();
        }

        BlockState state = block.getState();
        if (!(state instanceof Skull skull)) {
            return Optional.empty();
        }

        PlayerProfile profile = skull.getPlayerProfile();
        if (profile == null) {
            return Optional.empty();
        }

        return extractTextureFromProfile(profile);
    }

    /**
     * Scans all skull blocks in a chunk.
     *
     * @param chunk The chunk to scan (can be null)
     * @return The number of new skulls discovered
     */
    public int scanChunk(Chunk chunk) {
        if (chunk == null) {
            return 0;
        }

        int discovered = 0;

        // Get all tile entities in the chunk
        for (BlockState state : chunk.getTileEntities()) {
            if (!(state instanceof Skull skull)) {
                continue;
            }

            // Only process player heads
            Material type = state.getType();
            if (type != Material.PLAYER_HEAD && type != Material.PLAYER_WALL_HEAD) {
                continue;
            }

            PlayerProfile profile = skull.getPlayerProfile();
            if (profile == null) {
                continue;
            }

            Optional<SkullData> skullData = extractTextureFromProfile(profile);
            if (skullData.isPresent()) {
                discovered++;
            }
        }

        if (discovered > 0 && plugin.getGeyserExtraConfig().general().debugMode()) {
            final int discoveredCount = discovered;
            plugin.getLogger().info(() -> String.format(
                "Scanned chunk [%d, %d], discovered %d skulls",
                chunk.getX(),
                chunk.getZ(),
                discoveredCount
            ));
        }

        return discovered;
    }

    /**
     * Extracts texture data from SkullMeta.
     *
     * @param skullMeta The skull meta to extract from
     * @return Optional containing the skull data if texture found
     */
    private Optional<SkullData> extractTextureFromMeta(SkullMeta skullMeta) {
        PlayerProfile profile = skullMeta.getPlayerProfile();
        if (profile == null) {
            if (plugin.getGeyserExtraConfig().general().debugMode()) {
                plugin.getLogger().info("[SkullDebug] PlayerProfile is null");
            }
            return Optional.empty();
        }

        if (plugin.getGeyserExtraConfig().general().debugMode()) {
            plugin.getLogger().info("[SkullDebug] Found profile: " + profile.getName()
                + ", properties: " + profile.getProperties().size());
        }

        return extractTextureFromProfile(profile);
    }

    /**
     * Extracts texture data from a PlayerProfile.
     *
     * @param profile The player profile to extract from
     * @return Optional containing the skull data if texture found
     */
    private Optional<SkullData> extractTextureFromProfile(PlayerProfile profile) {
        // Find the textures property
        Optional<ProfileProperty> texturesProperty = profile.getProperties().stream()
            .filter(prop -> TEXTURES_PROPERTY.equals(prop.getName()))
            .findFirst();

        if (texturesProperty.isEmpty()) {
            if (plugin.getGeyserExtraConfig().general().debugMode()) {
                plugin.getLogger().info("[SkullDebug] No 'textures' property found. Available properties: "
                    + profile.getProperties().stream()
                        .map(ProfileProperty::getName)
                        .toList());
            }
            // Try to register by username if profile name exists
            return registerByUsername(profile);
        }

        String base64Value = texturesProperty.get().getValue();
        if (base64Value == null || base64Value.isBlank()) {
            if (plugin.getGeyserExtraConfig().general().debugMode()) {
                plugin.getLogger().info("[SkullDebug] Base64 value is null or blank");
            }
            // Try to register by username if profile name exists
            return registerByUsername(profile);
        }

        if (plugin.getGeyserExtraConfig().general().debugMode()) {
            plugin.getLogger().info("[SkullDebug] Base64 value length: " + base64Value.length());
        }

        // Decode base64 to extract texture URL
        return extractTextureUrl(base64Value)
            .flatMap(this::createAndRegisterSkullData);
    }

    /**
     * Registers a skull by username when texture data is not available.
     *
     * Why: Player heads created via /give command with just a profile name
     * don't have texture data immediately. Geyser can resolve textures
     * by username at runtime.
     *
     * @param profile The player profile with a name but no textures
     * @return Optional containing the skull data if registered
     */
    private Optional<SkullData> registerByUsername(PlayerProfile profile) {
        String username = profile.getName();
        if (username == null || username.isBlank()) {
            if (plugin.getGeyserExtraConfig().general().debugMode()) {
                plugin.getLogger().info("[SkullDebug] Cannot register by username - name is null or blank");
            }
            return Optional.empty();
        }

        // Check if already registered
        if (registry.contains(username)) {
            if (plugin.getGeyserExtraConfig().general().debugMode()) {
                plugin.getLogger().info("[SkullDebug] Username already registered: " + username);
            }
            return registry.getByTextureHash(username);
        }

        // Create skull data with USERNAME type
        // Using username as both the "hash" (identifier) and for Geyser registration
        SkullData skullData = new SkullData(
            username,
            null,  // No URL for username-based skulls
            SkullData.SkullTextureType.USERNAME
        );
        registry.register(skullData);

        if (plugin.getGeyserExtraConfig().general().debugMode()) {
            plugin.getLogger().info("[SkullDebug] Registered skull by username: " + username);
        }

        return Optional.of(skullData);
    }

    /**
     * Decodes base64 profile data and extracts texture URL.
     *
     * @param base64Value The base64 encoded profile data
     * @return Optional containing the texture URL if found
     */
    private Optional<String> extractTextureUrl(String base64Value) {
        try {
            byte[] decodedBytes = Base64.getDecoder().decode(base64Value);
            String decodedJson = new String(decodedBytes, StandardCharsets.UTF_8);

            if (plugin.getGeyserExtraConfig().general().debugMode()) {
                plugin.getLogger().info("[SkullDebug] Decoded JSON: " + decodedJson);
            }

            Matcher matcher = TEXTURE_URL_PATTERN.matcher(decodedJson);
            if (matcher.find()) {
                String url = matcher.group(1);
                if (plugin.getGeyserExtraConfig().general().debugMode()) {
                    plugin.getLogger().info("[SkullDebug] Extracted URL: " + url);
                }
                return Optional.of(url);
            }

            if (plugin.getGeyserExtraConfig().general().debugMode()) {
                plugin.getLogger().info("[SkullDebug] URL pattern did not match");
            }
            return Optional.empty();
        } catch (IllegalArgumentException e) {
            plugin.getLogger().log(
                Level.WARNING,
                "[SkullDebug] Failed to decode base64 texture data: " + e.getMessage(),
                e
            );
            return Optional.empty();
        }
    }

    /**
     * Creates SkullData from texture URL and registers it.
     *
     * Why: We use SKIN_HASH type because we're extracting the texture hash
     * from the URL, which is what Geyser expects for SKIN_HASH type registration.
     *
     * @param textureUrl The texture URL
     * @return Optional containing the skull data
     */
    private Optional<SkullData> createAndRegisterSkullData(String textureUrl) {
        // Extract texture hash from URL
        String textureHash = extractTextureHash(textureUrl);

        // Check if already registered
        if (registry.contains(textureHash)) {
            return registry.getByTextureHash(textureHash);
        }

        // Create and register skull data with SKIN_HASH type
        // Since we extracted the hash from the texture URL, we use SKIN_HASH
        // not PROFILE (which expects Base64 encoded profile JSON)
        SkullData skullData = SkullData.fromSkinHash(textureHash);
        registry.register(skullData);

        if (plugin.getGeyserExtraConfig().general().debugMode()) {
            plugin.getLogger().info(() -> String.format(
                "Registered skull texture: %s",
                textureHash.substring(0, Math.min(16, textureHash.length())) + "..."
            ));
        }

        return Optional.of(skullData);
    }

    /**
     * Extracts the texture hash from a Minecraft texture URL.
     *
     * @param textureUrl The full texture URL
     * @return The texture hash portion of the URL
     */
    private String extractTextureHash(String textureUrl) {
        int lastSlash = textureUrl.lastIndexOf('/');
        if (lastSlash >= 0 && lastSlash < textureUrl.length() - 1) {
            return textureUrl.substring(lastSlash + 1);
        }
        return textureUrl;
    }

    /**
     * Gets the skull registry.
     *
     * @return The registry
     */
    public SkullRegistry getRegistry() {
        return registry;
    }
}
