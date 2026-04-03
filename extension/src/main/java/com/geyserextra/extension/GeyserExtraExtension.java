/*
 * GeyserExtra Extension
 * Enhanced Bedrock experience - auto custom items, skulls, enchantment display
 */
package com.geyserextra.extension;

import com.geyserextra.core.config.GeyserExtraConfig;
import com.geyserextra.extension.handler.CustomItemsHandler;
import com.geyserextra.extension.handler.CustomSkullsHandler;
import org.geysermc.event.subscribe.Subscribe;
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

/**
 * Main extension class for GeyserExtra.
 * Provides enhanced Bedrock experience with custom items, skulls, and more.
 *
 * Why: This class serves as the entry point for the Geyser extension, coordinating
 * all handlers that provide enhanced functionality for Bedrock players.
 */
public class GeyserExtraExtension implements Extension {

    private static final String CONFIG_FILE = "config.json";

    private GeyserExtraConfig config;
    private CustomItemsHandler customItemsHandler;
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
        logger().info("GeyserExtra pre-initializing...");

        Path dataFolder = dataFolder();
        logger().info("Extension data folder: " + dataFolder.toAbsolutePath());

        // Load unified config (shared with Paper plugin)
        loadConfiguration(dataFolder);

        applyConfigurationSettings();

        // Initialize handlers with data folder path
        initializeHandlers(dataFolder);

        logger().info("GeyserExtra handlers initialized.");
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

        logger().info("Applying configuration settings...");
        logger().info("Feature status:");
        logger().info("  - Custom Items: " + (config.customItems().enabled() ? "enabled" : "disabled"));
        logger().info("  - Custom Skulls: " + (config.skulls().enabled() ? "enabled" : "disabled"));
        logger().info("  - Enchantment Display (Custom): " + (config.enchantment().showCustomEnchantments() ? "enabled" : "disabled"));
        logger().info("  - Enchantment Display (Over): " + (config.enchantment().showOverEnchantments() ? "enabled" : "disabled"));
        logger().info("  - Anvil Over-Enchant Protection: " + (config.enchantment().overEnchantmentProtectionEnabled() ? "enabled" : "disabled"));
        logger().info("  - Anvil Over-Enchant Level-Up: " + (config.enchantment().overEnchantmentLevelUpEnabled() ? "enabled" : "disabled"));
        logger().info("  - Invisible Glow Frames Pack: " + (config.resourcePacks().invisibleGlowFramesEnabled() ? "enabled" : "disabled"));
        logger().info("  - Debug Mode: enabled");
    }

    /**
     * Initializes all handlers with their respective paths and configuration.
     *
     * @param dataFolder the path to the extension data folder
     */
    private void initializeHandlers(Path dataFolder) {
        boolean debug = config.general().debugMode();

        if (debug) {
            logger().info("=== Initializing Handlers ===");
            logger().info("Config loaded: " + (config != null));
            if (config != null) {
                logger().info("  customItems.enabled: " + config.customItems().enabled());
                logger().info("  skulls.enabled: " + config.skulls().enabled());
            }
        }

        // Initialize custom items handler if enabled
        if (config.customItems().enabled()) {
            if (debug) {
                logger().info("Initializing CustomItemsHandler...");
            }
            // Why: Pass BE packs path from config to enable texture filtering.
            // Auto-detect Geyser packs directory if not configured.
            String bedrockPacksPath = config.customItems().bedrockPacksPath();
            if (bedrockPacksPath == null || bedrockPacksPath.isBlank()) {
                // Auto-detect: dataFolder = extensions/geyserextra, parent = extensions, parent.parent = Geyser-Spigot
                java.nio.file.Path geyserPacksDir = dataFolder.getParent().getParent().resolve("packs");
                if (java.nio.file.Files.isDirectory(geyserPacksDir)) {
                    bedrockPacksPath = geyserPacksDir.toString();
                    logger().info("Auto-detected BE packs path: " + bedrockPacksPath);
                }
            }
            this.customItemsHandler = new CustomItemsHandler(
                this, dataFolder, bedrockPacksPath
            );
            if (debug) {
                logger().info("CustomItemsHandler initialized: " + (customItemsHandler != null));
            }
        } else {
            logger().warning("Custom items feature is DISABLED in config.");
        }

        // Initialize custom skulls handler if enabled
        if (config.skulls().enabled()) {
            if (debug) {
                logger().info("Initializing CustomSkullsHandler...");
            }
            this.customSkullsHandler = new CustomSkullsHandler(this, dataFolder);
            if (debug) {
                logger().info("CustomSkullsHandler initialized: " + (customSkullsHandler != null));
            }
        } else {
            logger().warning("Custom skulls feature is disabled in config.");
        }

        if (debug) {
            logger().info("=== Handler Initialization Complete ===");
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
        logger().info("GeyserExtra fully initialized!");
        logger().info("Data folder: " + dataFolder().toAbsolutePath());
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
                logger().info("Registered resource pack: " + packName);
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
            logger().info("=== GeyserDefineCustomItemsEvent triggered ===");
            logger().info("customItemsHandler initialized: " + (customItemsHandler != null));
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
