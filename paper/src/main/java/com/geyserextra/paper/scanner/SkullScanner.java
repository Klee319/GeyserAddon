package com.geyserextra.paper.scanner;

import com.geyserextra.core.api.SkullData;
import com.geyserextra.core.registry.SkullRegistry;
import com.geyserextra.paper.GeyserExtraPaper;

import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Skull;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.profile.PlayerTextures;

import java.net.URL;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Scanner for detecting and registering custom skull textures.
 *
 * This scanner extracts texture data from player head items and skull blocks
 * to enable Bedrock players to see custom skull textures via Geyser.
 *
 * <p><b>Why the Bukkit profile API and not Paper's:</b> this scanner used to
 * cast the returned {@code org.bukkit.profile.PlayerProfile} to Paper's
 * {@code com.destroystokyo.paper.profile.PlayerProfile} to reach
 * {@code getProperties()}. On Paper 1.21.11 that cast still succeeds — Craft's
 * profile implements both interfaces — but {@code getProperties()} now throws
 * {@code UnsupportedOperationException("Do not cast to
 * com.destroystokyo.paper.profile.PlayerProfile")}. The old code predicted this
 * would show up as a {@code null} from the {@code instanceof} check; it did
 * not, so every inventory open threw instead.</p>
 *
 * <p>{@link PlayerTextures#getSkin()} hands back the skin URL directly, which
 * is what this class wanted from the properties blob in the first place. It
 * removes the base64 decode and the regex over the decoded JSON along with the
 * Paper-specific cast.</p>
 *
 * <p><b>{@code @SuppressWarnings("deprecation")}</b>: {@code getOwnerProfile()}
 * and {@code org.bukkit.profile.PlayerProfile} both carry deprecation tags even
 * though they are the documented replacements for the accessors they replaced.
 * Neither is {@code [removal]}-tagged.</p>
 */
@SuppressWarnings("deprecation")
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

        return extractTextureFromProfile(skull.getOwnerProfile());
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

            Optional<SkullData> skullData = extractTextureFromProfile(skull.getOwnerProfile());
            if (skullData.isPresent()) {
                discovered++;
            }
        }

        if (discovered > 0 && plugin.getGeyserExtraConfig().general().debugMode()) {
            final int discoveredCount = discovered;
            plugin.getLogger().fine(() -> String.format(
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
        return extractTextureFromProfile(skullMeta.getOwnerProfile());
    }

    /**
     * Extracts texture data from a profile.
     *
     * @param profile The player profile to extract from (can be null)
     * @return Optional containing the skull data if texture found
     */
    private Optional<SkullData> extractTextureFromProfile(PlayerProfile profile) {
        if (profile == null) {
            return Optional.empty();
        }

        URL skin = skinUrlOf(profile);
        if (skin != null) {
            return createAndRegisterSkullData(skin.toString());
        }
        // A head placed by name alone carries no texture yet; Geyser can
        // resolve those itself at runtime from the username.
        return registerByUsername(profile.getName());
    }

    /**
     * The skin URL a profile carries, or null when it carries none.
     *
     * <p>Defensive because the textures view is populated from whatever the
     * head's NBT happened to hold: a malformed or partially-resolved profile
     * throws out of {@code getTextures()} rather than returning empty, and one
     * bad head in a chest must not abort the scan of the rest.</p>
     */
    private URL skinUrlOf(PlayerProfile profile) {
        try {
            PlayerTextures textures = profile.getTextures();
            if (textures == null || textures.isEmpty()) {
                return null;
            }
            return textures.getSkin();
        } catch (RuntimeException e) {
            if (plugin.getGeyserExtraConfig().general().debugMode()) {
                plugin.getLogger().warning("[SkullDebug] Could not read textures for profile "
                    + profile.getName() + ": " + e.getClass().getSimpleName()
                    + ": " + e.getMessage());
            }
            return null;
        }
    }

    /**
     * Registers a skull by username when texture data is not available.
     *
     * Why: Player heads created via /give command with just a profile name
     * don't have texture data immediately. Geyser can resolve textures
     * by username at runtime.
     *
     * @param username The profile name, or null when the profile has none
     * @return Optional containing the skull data if registered
     */
    private Optional<SkullData> registerByUsername(String username) {
        if (username == null || username.isBlank()) {
            if (plugin.getGeyserExtraConfig().general().debugMode()) {
                plugin.getLogger().fine("[SkullDebug] Cannot register by username - name is null or blank");
            }
            return Optional.empty();
        }

        // Check if already registered
        if (registry.contains(username)) {
            if (plugin.getGeyserExtraConfig().general().debugMode()) {
                plugin.getLogger().fine("[SkullDebug] Username already registered: " + username);
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
            plugin.getLogger().fine("[SkullDebug] Registered skull by username: " + username);
        }

        return Optional.of(skullData);
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
            plugin.getLogger().fine(() -> String.format(
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
