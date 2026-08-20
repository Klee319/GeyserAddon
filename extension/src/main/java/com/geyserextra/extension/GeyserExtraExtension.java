/*
 * GeyserExtra Extension
 * Enhanced Bedrock experience - auto custom items, skulls, enchantment display
 */
package com.geyserextra.extension;

import com.geyserextra.core.config.GeyserExtraConfig;
import com.geyserextra.extension.bedrock.BedrockRecipeInjector;
import com.geyserextra.extension.handler.CustomItemsHandler;
import com.geyserextra.extension.handler.CustomSkullsHandler;
import org.geysermc.event.subscribe.Subscribe;
import org.geysermc.geyser.api.event.bedrock.SessionLoadResourcePacksEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineCustomItemsEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineCustomSkullsEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineResourcePacksEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserPostInitializeEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserPreInitializeEvent;
import org.geysermc.geyser.api.extension.Extension;
import org.geysermc.geyser.api.pack.PackCodec;
import org.geysermc.geyser.api.pack.ResourcePack;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipFile;

/**
 * Main extension class for GeyserExtra.
 * Provides enhanced Bedrock experience with custom items, skulls, and more.
 *
 * Why: This class serves as the entry point for the Geyser extension, coordinating
 * all handlers that provide enhanced functionality for Bedrock players.
 */
public class GeyserExtraExtension implements Extension {

    private static final String CONFIG_FILE = "config.json";
    /**
     * Header UUIDs used by {@code AutoBedrockPackBuilder} (current + recent).
     * Session join re-reads the ZIP from disk and replaces any stale
     * DefineResourcePacks-time registration that still points at old bytes.
     */
    private static final List<UUID> AUTO_PACK_HEADER_UUIDS = List.of(
        UUID.fromString("9d8c1e76-2a3b-4c5d-9e6f-1a2b3c4d5e6f"),
        UUID.fromString("9d8c1e76-2a3b-4c5d-9e6f-1a2b3c4d5e70"),
        UUID.fromString("9d8c1e76-2a3b-4c5d-9e6f-1a2b3c4d5e71")
    );

    private GeyserExtraConfig config;
    private CustomItemsHandler customItemsHandler;
    private BedrockRecipeInjector bedrockRecipeInjector;
    private CustomSkullsHandler customSkullsHandler;


    /**
     * Handles Geyser pre-initialization event.
     * Use this phase for loading configurations before full API availability.
     *
     * Why: Configuration must be loaded first so that feature-specific settings
     * can be applied to handlers during their initialization.
     *
     * @param event the pre-initialization event
     */
    @Subscribe
    public void onPreInitialize(GeyserPreInitializeEvent event) {
        logger().debug("GeyserExtra pre-initializing...");

        Path dataFolder = dataFolder();
        logger().debug("Extension data folder: " + dataFolder.toAbsolutePath());

        promotePendingAutoPack(
            dataFolder.resolve("packs").resolve("geyserextra_auto.zip"));

        // Load unified config (shared with Paper plugin)
        loadConfiguration(dataFolder);

        applyConfigurationSettings();

        // Initialize handlers with data folder path
        initializeHandlers(dataFolder);

        logger().debug("GeyserExtra handlers initialized.");
    }

    /**
     * Loads unified configuration from data folder.
     * This config is shared with Paper plugin - Paper manages it, Extension reads it.
     *
     * @param dataFolder path to the extension data folder
     */
    private void loadConfiguration(Path dataFolder) {
        Path configPath = dataFolder.resolve(CONFIG_FILE);
        this.config = GeyserExtraConfig.loadOrCreate(configPath);
    }

    /**
     * Applies configuration settings to handlers.
     *
     * Why: This method centralizes the application of configuration values
     * to ensure all handlers receive their settings consistently.
     */
    private void applyConfigurationSettings() {
        if (!config.general().debugMode()) {
            return;
        }

        logger().debug("Applying configuration settings...");
        logger().debug("Feature status:");
        logger().debug("  - Custom Items: " + (config.customItems().enabled() ? "enabled" : "disabled"));
        logger().debug("  - Custom Skulls: " + (config.skulls().enabled() ? "enabled" : "disabled"));
        logger().debug("  - Enchantment Display (Custom): " + (config.enchantment().showCustomEnchantments() ? "enabled" : "disabled"));
        logger().debug("  - Enchantment Display (Over): " + (config.enchantment().showOverEnchantments() ? "enabled" : "disabled"));
        logger().debug("  - Anvil Over-Enchant Protection: " + (config.enchantment().overEnchantmentProtectionEnabled() ? "enabled" : "disabled"));
        logger().debug("  - Anvil Over-Enchant Level-Up: " + (config.enchantment().overEnchantmentLevelUpEnabled() ? "enabled" : "disabled"));
        logger().debug("  - Invisible Glow Frames Pack: " + (config.resourcePacks().invisibleGlowFramesEnabled() ? "enabled" : "disabled"));
        logger().debug("  - Debug Mode: enabled");
    }

    /**
     * Initializes all handlers with their respective paths and configuration.
     *
     * @param dataFolder the path to the extension data folder
     */
    private void initializeHandlers(Path dataFolder) {
        boolean debug = config.general().debugMode();

        if (debug) {
            logger().debug("=== Initializing Handlers ===");
            logger().debug("Config loaded: " + (config != null));
            if (config != null) {
                logger().debug("  customItems.enabled: " + config.customItems().enabled());
                logger().debug("  skulls.enabled: " + config.skulls().enabled());
            }
        }

        // Initialize custom items handler if enabled
        if (config.customItems().enabled()) {
            if (debug) {
                logger().debug("Initializing CustomItemsHandler...");
            }
            this.customItemsHandler = new CustomItemsHandler(this, dataFolder);
            if (debug) {
                logger().debug("CustomItemsHandler initialized: " + (customItemsHandler != null));
            }
        } else {
            logger().warning("Custom items feature is DISABLED in config.");
        }

        // Initialize custom skulls handler if enabled
        if (config.skulls().enabled()) {
            if (debug) {
                logger().debug("Initializing CustomSkullsHandler...");
            }
            this.customSkullsHandler = new CustomSkullsHandler(this, dataFolder);
            if (debug) {
                logger().debug("CustomSkullsHandler initialized: " + (customSkullsHandler != null));
            }
        } else {
            logger().warning("Custom skulls feature is disabled in config.");
        }

        if (debug) {
            logger().debug("=== Handler Initialization Complete ===");
        }
    }

    /**
     * Handles Geyser post-initialization event.
     * The bulk of initialization should occur here as the full Geyser API is available.
     *
     * @param event the post-initialization event
     */
    @Subscribe
    public void onPostInitialize(GeyserPostInitializeEvent event) {
        logger().debug("GeyserExtra fully initialized!");
        logger().debug("Data folder: " + dataFolder().toAbsolutePath());
        startBedrockRecipeInjector();
    }

    /**
     * Starts sending Bedrock clients recipes whose ingredients carry the real custom item.
     *
     * <p>Without this, every recipe with a custom ingredient is unmatched on Bedrock: the client
     * computes crafting results itself and Geyser can only tell it the ingredient's vanilla base,
     * so the player sees no result at all. The corrected tables come from the backend plugins via
     * {@code <dataFolder>/bedrock-recipes/}.
     *
     * <p>Installed here because it needs the custom item registrations, which happen during
     * {@code GeyserDefineCustomItemsEvent}. Any failure only costs Bedrock crafting hints, so it
     * never propagates.
     */
    private void startBedrockRecipeInjector() {
        if (customItemsHandler == null) {
            logger().debug("[bedrock-recipes] custom items are off; nothing to correct");
            return;
        }
        try {
            bedrockRecipeInjector = new BedrockRecipeInjector(
                customItemsHandler::registeredBedrockIdentifiers,
                logger()::info, logger()::warning, logger()::debug);
            bedrockRecipeInjector.reload(dataFolder());
            bedrockRecipeInjector.install();
        } catch (Throwable t) {
            logger().warning("[bedrock-recipes] could not start the recipe injector ("
                + t.getClass().getSimpleName() + ": " + t.getMessage()
                + "); Bedrock crafting with custom ingredients stays broken");
        }
    }

    /**
     * Handles resource pack registration event.
     * Registers built-in resource packs (e.g., invisible glow frames) based on config.
     *
     * <p>Why: Bedrock Edition doesn't natively support invisible glow item frames.
     * By distributing a resource pack that replaces glow frame textures with transparent ones,
     * Bedrock players can see through glow item frames just like Java Edition's invisible frames.</p>
     *
     * @param event the resource pack definition event
     */
    @Subscribe
    public void onDefineResourcePacks(GeyserDefineResourcePacksEvent event) {
        if (config.resourcePacks().invisibleGlowFramesEnabled()) {
            registerBuiltInPack(event, "packs/invisible_glow_frames.zip", "invisible_glow_frames");
        }

        // Auto-generated pack from Paper plugin's CustomItem registry. May be absent on
        // first launch (Paper hasn't built it yet) — that is OK; it ships next start.
        // Prefer SessionLoadResourcePacks for the live bytes; this Define-time
        // registration is a fallback for clients that only see the global list.
        if (config.customItems().enabled()) {
            registerAutoCustomItemsPack(event);
        }
    }

    /**
     * Re-binds the auto pack from disk for each Bedrock session. Geyser caches
     * packs registered in {@link GeyserDefineResourcePacksEvent}; Paper may
     * rebuild {@code geyserextra_auto.zip} afterward, so Define-time bytes go
     * stale. Session-time {@link PackCodec#path} + register replaces them.
     *
     * <p>The unregister runs <em>before</em> the file check, and that ordering
     * is the whole point. A pack registered at Define time stays in Geyser's
     * global list for the rest of the proxy's life; Geyser stats the file again
     * for every login, to size the pack info packet. If the ZIP is deleted
     * after Define time — the Paper backend restarting into a rebuild, an
     * operator clearing {@code packs/}, a backup job — that stat throws
     * {@code NoSuchFileException} out of {@code infoPacketEntries} and
     * <b>every Bedrock player fails to join</b>, custom items or not. Returning
     * early on a missing file (the previous order) left the dead registration
     * in place and made that permanent until a proxy restart.</p>
     *
     * <p>Dropping the registration first degrades the same situation to "no
     * custom items this session", and the next session picks the pack back up
     * as soon as Paper regenerates it. The proxy topology makes this worth
     * guarding: the file is written by a different process on a different
     * lifecycle, so the window where it is absent is real rather than
     * theoretical.</p>
     */
    @Subscribe
    public void onSessionLoadResourcePacks(SessionLoadResourcePacksEvent event) {
        if (config == null || !config.customItems().enabled()) {
            return;
        }
        Path packFile = dataFolder().resolve("packs").resolve("geyserextra_auto.zip");
        promotePendingAutoPack(packFile);
        for (UUID uuid : AUTO_PACK_HEADER_UUIDS) {
            try {
                event.unregister(uuid);
            } catch (Exception ignored) {
                // Not present in this session's pack list — fine.
            }
        }
        if (!Files.isRegularFile(packFile)) {
            logger().warning("Auto custom items pack is missing at " + packFile
                + " — dropping its registration for this session so Bedrock"
                + " players can still join. It returns once the Paper backend"
                + " regenerates it.");
            return;
        }
        try {
            ResourcePack pack = ResourcePack.create(PackCodec.path(packFile));
            event.register(pack);
            logger().debug("Session-registered auto custom items pack from disk: " + packFile);
        } catch (Exception e) {
            logger().warning("Failed to session-register auto custom items pack: "
                + e.getMessage());
        }
    }

    /**
     * Registers the textureless auto-generated pack produced by the Paper plugin's
     * {@code AutoBedrockPackBuilder}. This pack alone makes detected custom items
     * render with their vanilla BE base textures on Bedrock clients, allowing
     * Geyser's custom item registration to succeed without the operator authoring
     * any BE resource pack.
     */
    private void registerAutoCustomItemsPack(GeyserDefineResourcePacksEvent event) {
        Path packFile = dataFolder().resolve("packs").resolve("geyserextra_auto.zip");
        promotePendingAutoPack(packFile);
        if (!Files.exists(packFile)) {
            logger().debug("Auto custom items pack not present yet: " + packFile);
            return;
        }
        try {
            PackCodec codec = PackCodec.path(packFile);
            ResourcePack pack = ResourcePack.create(codec);
            event.register(pack);
            logger().debug("Registered auto custom items pack: " + packFile);
        } catch (IllegalArgumentException e) {
            logger().debug("Auto custom items pack already registered: " + packFile);
        } catch (Exception e) {
            logger().warning("Failed to register auto custom items pack: " + e.getMessage());
        }
    }

    /**
     * Reads the {@code texture_data} keys out of a pack's
     * {@code textures/item_texture.json}. An unreadable or absent entry yields
     * an empty set, which the caller treats as "satisfies nothing" — the
     * conservative direction, since the alternative is promoting a pack whose
     * contents could not be checked.
     */
    private java.util.Set<String> readPackIconKeys(ZipFile zip) {
        java.util.Set<String> keys = new java.util.HashSet<>();
        var entry = zip.getEntry("textures/item_texture.json");
        if (entry == null) {
            return keys;
        }
        try (var reader = new java.io.InputStreamReader(
                zip.getInputStream(entry), java.nio.charset.StandardCharsets.UTF_8)) {
            var root = com.google.gson.JsonParser.parseReader(reader).getAsJsonObject();
            var textureData = root.getAsJsonObject("texture_data");
            if (textureData != null) {
                for (String key : textureData.keySet()) {
                    keys.add(key.toLowerCase(java.util.Locale.ROOT));
                }
            }
        } catch (Exception e) {
            logger().warning("Could not read icon keys from pending pack: " + e.getMessage());
        }
        return keys;
    }

    private void promotePendingAutoPack(Path activePack) {
        Path pendingPack = activePack.resolveSibling("geyserextra_auto.pending.zip");
        if (!Files.isRegularFile(pendingPack)) {
            return;
        }
        try (ZipFile zip = new ZipFile(pendingPack.toFile())) {
            if (zip.getEntry("manifest.json") == null
                || zip.getEntry("textures/item_texture.json") == null) {
                logger().warning("Pending auto custom items pack is incomplete; "
                    + "keeping the current active pack.");
                return;
            }
            // The item definitions were registered when Geyser started and
            // cannot be re-registered; only the pack is swappable. Promoting a
            // pack that dropped an icon a definition points at leaves that item
            // broken until the proxy restarts — worse than staying one
            // generation behind, which merely delays new items.
            java.util.List<String> missing =
                customItemsHandler == null
                    ? java.util.List.of()
                    : customItemsHandler.missingRegisteredIcons(readPackIconKeys(zip));
            if (!missing.isEmpty()) {
                logger().severe("Pending auto custom items pack is missing "
                    + missing.size() + " icon(s) that are already registered with Geyser"
                    + " (e.g. " + String.join(", ", missing.subList(0, Math.min(5, missing.size())))
                    + "). Keeping the current active pack. Restart the proxy after the"
                    + " Paper backend has rebuilt the pack to pick up the new definitions.");
                return;
            }
        } catch (IOException invalid) {
            logger().warning("Pending auto custom items pack is invalid; "
                + "keeping the current active pack: " + invalid.getMessage());
            return;
        }

        try {
            Files.createDirectories(activePack.getParent());
            try {
                Files.move(pendingPack, activePack,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException noAtomicMove) {
                Files.move(pendingPack, activePack,
                    StandardCopyOption.REPLACE_EXISTING);
            }

            Path pendingSidecar = dataFolder().resolve("block_icon_bases.pending.json");
            Path activeSidecar = dataFolder().resolve("block_icon_bases.json");
            if (Files.isRegularFile(pendingSidecar)) {
                Files.move(pendingSidecar, activeSidecar,
                    StandardCopyOption.REPLACE_EXISTING);
            }
            // Monotonic manifest patch sidecar lives next to the ZIP. The
            // pending ZIP was moved above; the sidecar still uses the pending
            // file name until renamed to match the active ZIP.
            Path pendingPackVersion = activePack.resolveSibling(
                "geyserextra_auto.pending.zip.pack_version");
            Path activePackVersion = activePack.resolveSibling(
                activePack.getFileName() + ".pack_version");
            if (Files.isRegularFile(pendingPackVersion)) {
                Files.move(pendingPackVersion, activePackVersion,
                    StandardCopyOption.REPLACE_EXISTING);
            }
            logger().debug("Promoted pending auto custom items pack.");
        } catch (IOException promotionFailure) {
            logger().warning("Failed to promote pending auto custom items pack: "
                + promotionFailure.getMessage());
        }
    }

    /**
     * Extracts a built-in resource pack from the JAR and registers it with Geyser.
     *
     * <p>The pack is extracted to the data folder so PackCodec.path() can read it.
     * Existing files are overwritten to ensure the latest version is used.</p>
     *
     * @param event        the resource pack event
     * @param resourcePath the classpath resource path (e.g., "packs/my_pack.zip")
     * @param packName     the display name for logging
     */
    private void registerBuiltInPack(
        GeyserDefineResourcePacksEvent event,
        String resourcePath,
        String packName
    ) {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                logger().warning("Built-in resource pack not found in JAR: " + resourcePath);
                return;
            }

            Path packsDir = dataFolder().resolve("packs");
            Files.createDirectories(packsDir);

            Path packFile = packsDir.resolve(packName + ".zip");
            Files.copy(is, packFile, StandardCopyOption.REPLACE_EXISTING);

            PackCodec codec = PackCodec.path(packFile);
            ResourcePack pack = ResourcePack.create(codec);
            try {
                event.register(pack);
                logger().debug("Registered resource pack: " + packName);
            } catch (IllegalArgumentException e) {
                // Pack already registered (e.g., after Geyser reload) — safe to ignore
                logger().debug("Resource pack already registered: " + packName);
            } catch (Exception e) {
                logger().warning("Failed to register resource pack: " + packName
                    + " - " + e.getMessage());
            }
        } catch (IOException e) {
            logger().error("Failed to extract resource pack: " + packName + " - " + e.getMessage());
        }
    }

    /**
     * Handles custom item registration event.
     * Delegates to CustomItemsHandler to register all custom items from configuration.
     *
     * @param event the custom items definition event
     */
    @Subscribe
    public void onDefineCustomItems(GeyserDefineCustomItemsEvent event) {
        if (config.general().debugMode()) {
            logger().debug("=== GeyserDefineCustomItemsEvent triggered ===");
            logger().debug("customItemsHandler initialized: " + (customItemsHandler != null));
        }
        if (customItemsHandler != null) {
            customItemsHandler.registerItems(event);
        } else {
            logger().warning("CustomItemsHandler not initialized, skipping custom items registration.");
            logger().warning("Check if customItems.enabled is true in config.json");
        }
    }

    /**
     * Handles custom skull registration event.
     * Delegates to CustomSkullsHandler to register all custom skulls from configuration.
     *
     * @param event the custom skulls definition event
     */
    @Subscribe
    public void onDefineCustomSkulls(GeyserDefineCustomSkullsEvent event) {
        if (customSkullsHandler != null) {
            customSkullsHandler.registerSkulls(event);
        } else {
            logger().warning("CustomSkullsHandler not initialized, skipping custom skulls registration.");
        }
    }

    /**
     * Gets the custom items handler.
     *
     * @return the custom items handler instance
     */
    public CustomItemsHandler getCustomItemsHandler() {
        return customItemsHandler;
    }

    /**
     * Gets the custom skulls handler.
     *
     * @return the custom skulls handler instance
     */
    public CustomSkullsHandler getCustomSkullsHandler() {
        return customSkullsHandler;
    }

    /**
     * Gets the unified configuration (shared with Paper plugin).
     *
     * @return the configuration instance
     */
    public GeyserExtraConfig getConfig() {
        return config;
    }
}
