package com.geyserextra.paper;

import com.geyserextra.core.config.GeyserExtraConfig;
import com.geyserextra.core.registry.ItemMappingRegistry;
import com.geyserextra.core.registry.SkullRegistry;
import com.geyserextra.paper.listener.ChunkLoadListener;
import com.geyserextra.paper.listener.ItemListener;
import com.geyserextra.paper.listener.ElytraFlightListener;
import com.geyserextra.paper.listener.OffhandInteractionListener;
import com.geyserextra.paper.enchantment.BedrockAnvilSimulator;
import com.geyserextra.paper.enchantment.BedrockEnchantmentHandler;
import com.geyserextra.paper.enchantment.BedrockEnchantmentTableGuard;
import com.geyserextra.paper.recipe.CraftingRecipeHandler;
import com.geyserextra.paper.recipe.SmithingRecipeHandler;
import com.geyserextra.paper.pack.AutoBedrockPackBuilder;
import com.geyserextra.paper.scanner.CustomItemScanner;
import com.geyserextra.paper.scanner.RecipeScanner;
import com.geyserextra.paper.scanner.SkullScanner;
import com.geyserextra.paper.scanner.WorldSkullScanner;
import org.bukkit.plugin.java.JavaPlugin;

import com.geyserextra.paper.command.AdvancementCommand;
import com.geyserextra.paper.command.BedrockMenuCommand;
import com.geyserextra.paper.command.GameRulesCommand;
import com.geyserextra.paper.command.OffhandCommand;
import com.geyserextra.paper.command.SettingsCommand;
import com.geyserextra.paper.command.StatisticsCommand;
import com.geyserextra.paper.command.TooltipCommand;
import com.geyserextra.paper.display.DisplayManager;
import com.geyserextra.paper.settings.PlayerSettingsManager;
import com.geyserextra.paper.util.JapaneseTranslationLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.logging.Level;

/**
 * Main plugin class for GeyserExtra Paper plugin.
 *
 * This plugin scans for custom items and skulls on the server,
 * then exports the data to a shared folder that the Geyser extension
 * can read to provide enhanced Bedrock player experience.
 *
 */
public final class GeyserExtraPaper extends JavaPlugin {

    private static final String CUSTOM_ITEMS_FILE = "custom_items.json";
    private static final String SKULLS_FILE = "skulls.json";
    private static final String CONFIG_FILE = "config.json";

    // Cached extension data folder path to avoid repeated detection logging
    private Path cachedExtensionDataFolder;

    private GeyserExtraConfig config;
    private ItemMappingRegistry itemMappingRegistry;
    private SkullRegistry skullRegistry;
    private CustomItemScanner customItemScanner;
    private RecipeScanner recipeScanner;
    private SkullScanner skullScanner;
    private WorldSkullScanner worldSkullScanner;

    // Recipe handlers and enchantment handler for Bedrock compatibility
    private BedrockEnchantmentHandler bedrockEnchantmentHandler;
    private BedrockAnvilSimulator bedrockAnvilSimulator;
    private BedrockEnchantmentTableGuard bedrockEnchantmentTableGuard;
    private SmithingRecipeHandler smithingRecipeHandler;
    private CraftingRecipeHandler craftingRecipeHandler;

    // Elytra flight workaround for Bedrock gliding without elytra
    private ElytraFlightListener elytraFlightListener;

    // DiscordSRV avatar hook for Bedrock players (optional — no-op if DiscordSRV absent)
    private com.geyserextra.paper.listener.DiscordSRVSkinHook discordSRVSkinHook;

    // Per-player display settings persistence and display orchestration
    private PlayerSettingsManager playerSettingsManager;
    private DisplayManager displayManager;

    @Override
    public void onEnable() {
        // Check required dependencies
        if (!checkDependencies()) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Load configuration
        loadConfiguration();

        if (!config.general().enabled()) {
            getLogger().info("GeyserExtra is disabled in configuration.");
            return;
        }

        // Load Japanese translations asynchronously (non-blocking)
        // Why here: Start early so translations are available by the time
        // players use commands. Does not block subsequent initialization.
        JapaneseTranslationLoader.loadAsync(getExtensionDataFolder(), getLogger());

        // Initialize per-player settings (before registries/commands so they can use it)
        // Why: Settings must be available before commands and displays are registered,
        // because BedrockMenuCommand and DisplayManager depend on PlayerSettingsManager.
        initializePlayerSettings();

        // Initialize registries
        initializeRegistries();

        // Initialize scanners
        initializeScanners();

        // Initialize recipe handlers for Bedrock compatibility
        initializeRecipeHandlers();

        // Start display manager (handles biome/light/chunk/entity displays)
        // Why: Must start before registerCommands because SettingsCommand captures
        // a method reference to displayManager::onSettingsChanged. If displayManager
        // is null at that point, the method reference throws NullPointerException.
        startDisplayManager();

        // Register commands
        registerCommands();

        // Register event listeners
        registerListeners();

        // Schedule periodic tasks
        scheduleTasks();

        // Perform initial scan of all online players
        performInitialScan();

        getLogger().info("GeyserExtra Paper plugin enabled successfully.");
    }

    @Override
    public void onDisable() {
        // Cleanup ProtocolLib listeners to prevent handler accumulation on reload
        if (bedrockAnvilSimulator != null) {
            bedrockAnvilSimulator.cleanup();
        }
        if (bedrockEnchantmentHandler != null) {
            bedrockEnchantmentHandler.cleanup();
        }
        // TooltipCommand is now stateless — no cleanup needed

        // Restore all Bedrock players' chestplates before shutdown
        if (elytraFlightListener != null) {
            elytraFlightListener.cleanup();
        }

        // Cleanup display manager (cancel tasks, remove boss bars)
        if (displayManager != null) {
            displayManager.cleanup();
        }

        // Shutdown player settings save executor (allow pending writes to complete)
        if (playerSettingsManager != null) {
            playerSettingsManager.shutdown();
        }

        // Unregister DiscordSRV hook
        if (discordSRVSkinHook != null) {
            discordSRVSkinHook.unregister();
        }

        // Flush any pending auto-named summary so admins see the final count even if
        // the debounced summary task hadn't fired yet, then cancel the scheduled task
        // to prevent it from firing against a disabled plugin.
        if (customItemScanner != null) {
            customItemScanner.cancelPendingAutoNamedSummary();
            customItemScanner.logAutoNamedSummary();
        }

        // Save registries to shared folder for Geyser extension
        saveRegistriesToSharedFolder();

        getLogger().info("GeyserExtra Paper plugin disabled.");
    }

    /**
     * Checks for required plugin dependencies.
     * @return true if all dependencies are present
     */
    private boolean checkDependencies() {
        boolean allPresent = true;

        if (getServer().getPluginManager().getPlugin("ProtocolLib") == null) {
            getLogger().severe("===========================================");
            getLogger().severe("ProtocolLib is required but not installed!");
            getLogger().severe("Download: https://www.spigotmc.org/resources/protocollib.1997/");
            getLogger().severe("===========================================");
            allPresent = false;
        }

        if (getServer().getPluginManager().getPlugin("floodgate") == null) {
            getLogger().severe("===========================================");
            getLogger().severe("Floodgate is required but not installed!");
            getLogger().severe("Download: https://geysermc.org/download#floodgate");
            getLogger().severe("===========================================");
            allPresent = false;
        }

        if (getServer().getPluginManager().getPlugin("Geyser-Spigot") == null) {
            getLogger().warning("Geyser-Spigot not found. Some features may not work.");
        }

        return allPresent;
    }

    /**
     * Loads configuration from Extension folder (unified config location).
     * Both Paper plugin and Geyser Extension share this single config file.
     */
    private void loadConfiguration() {
        Path configPath = getExtensionDataFolder().resolve(CONFIG_FILE);
        config = GeyserExtraConfig.loadOrCreate(configPath);

        getLogger().info("Config loaded from: " + configPath.toAbsolutePath());

        if (config.general().debugMode()) {
            getLogger().info("Debug mode enabled.");
        }
    }

    /**
     * Gets the Extension data folder path where unified config is stored.
     * Automatically detects Geyser folder (Geyser-Spigot, Geyser-Paper, Geyser-Velocity, etc.)
     * Result is cached to avoid repeated detection and logging.
     *
     * @return The path to the Extension data folder
     */
    public Path getExtensionDataFolder() {
        // Return cached path if already detected
        if (cachedExtensionDataFolder != null) {
            return cachedExtensionDataFolder;
        }

        Path pluginsFolder = getServer().getPluginsFolder().toPath();

        // Try different Geyser folder names
        String[] geyserFolderNames = {
            "Geyser-Spigot",
            "Geyser-Paper",
            "Geyser-Bukkit",
            "Geyser"
        };

        for (String folderName : geyserFolderNames) {
            Path geyserFolder = pluginsFolder.resolve(folderName);
            if (Files.exists(geyserFolder)) {
                cachedExtensionDataFolder = geyserFolder.resolve("extensions").resolve("geyserextra");
                getLogger().info("Detected Geyser folder: " + geyserFolder.toAbsolutePath());
                return cachedExtensionDataFolder;
            }
        }

        // Default to Geyser-Spigot if none found
        getLogger().warning("Could not detect Geyser folder, using default: Geyser-Spigot");
        cachedExtensionDataFolder = pluginsFolder
            .resolve("Geyser-Spigot")
            .resolve("extensions")
            .resolve("geyserextra");
        return cachedExtensionDataFolder;
    }

    /**
     * Initializes per-player settings manager.
     *
     * Why separate folder: Player settings are stored in a "playerdata" subfolder
     * within the extension data folder, keeping them organized separately from
     * global config and registry data files.
     */
    private void initializePlayerSettings() {
        Path playerDataFolder = getExtensionDataFolder().resolve("playerdata");
        playerSettingsManager = new PlayerSettingsManager(playerDataFolder, getLogger());
        getLogger().info("Player settings manager initialized.");
    }

    /**
     * Starts the display manager that orchestrates biome/light/chunk/entity displays.
     *
     * Why separate method: The display manager must start after all dependencies
     * (settings manager, event system) are ready.
     */
    private void startDisplayManager() {
        displayManager = new DisplayManager(this, playerSettingsManager);
        displayManager.start();
        getLogger().info("Display manager started.");
    }

    /**
     * Initializes item mapping and skull registries.
     * Attempts to load existing data from shared folder.
     */
    private void initializeRegistries() {
        itemMappingRegistry = new ItemMappingRegistry();
        skullRegistry = new SkullRegistry();

        // Try to load existing data from shared folder
        Path sharedFolder = getSharedFolder();
        itemMappingRegistry.loadIfExists(sharedFolder.resolve(CUSTOM_ITEMS_FILE));
        skullRegistry.loadIfExists(sharedFolder.resolve(SKULLS_FILE));

        getLogger().info(() -> String.format(
            "Loaded %d custom items and %d skulls from shared folder.",
            itemMappingRegistry.size(),
            skullRegistry.size()
        ));
    }

    /**
     * Initializes scanners for custom items and skulls.
     */
    private void initializeScanners() {
        customItemScanner = new CustomItemScanner(itemMappingRegistry, this);
        recipeScanner = new RecipeScanner(customItemScanner, this);
        skullScanner = new SkullScanner(skullRegistry, this);
        worldSkullScanner = new WorldSkullScanner(skullScanner, this);

        // Schedule startup scans AFTER all plugins have loaded
        // This ensures other plugins' recipes are registered before scanning
        scheduleStartupScans();
    }

    /**
     * Schedules startup scans to run after all plugins have loaded.
     *
     * Why: Other plugins register their recipes in onEnable(), but plugin load
     * order is not guaranteed. By delaying the scan, we ensure all recipes
     * from all plugins are available for scanning.
     *
     * Strategy:
     * 1. Immediate scan: Runs synchronously to capture recipes from plugins loaded before us
     *    This ensures Geyser Extension can read the data if it loads after us
     * 2. Delayed scan (1 tick): After all onEnable() calls complete
     * 3. Late scan (5 seconds): For plugins that register recipes asynchronously
     */
    private void scheduleStartupScans() {
        // Immediate scan: Run synchronously to capture recipes already registered
        // This is critical for Geyser Extension which may load right after us
        if (config.general().debugMode()) {
            getLogger().info("Running immediate startup scan (synchronous)...");
        }
        runStartupScans();

        // Second scan: 1 tick later (after all onEnable() calls complete)
        getServer().getScheduler().runTaskLater(this, () -> {
            if (config.general().debugMode()) {
                getLogger().info("Running deferred startup scans (1 tick delay)...");
            }
            int prevItems = itemMappingRegistry.size();
            runStartupScans();
            int newItems = itemMappingRegistry.size() - prevItems;
            if (newItems > 0) {
                getLogger().info("Deferred scan found " + newItems + " new items.");
            }
        }, 1L);

        // Third scan: 100 ticks (5 seconds) later to catch late-registering plugins
        getServer().getScheduler().runTaskLater(this, () -> {
            if (config.general().debugMode()) {
                getLogger().info("Running late startup scans (5 second delay)...");
            }
            int prevItems = itemMappingRegistry.size();
            int prevSkulls = skullRegistry.size();

            runStartupScans();

            int newItems = itemMappingRegistry.size() - prevItems;
            int newSkulls = skullRegistry.size() - prevSkulls;

            if (newItems > 0 || newSkulls > 0) {
                getLogger().info("Late scan found " + newItems + " new items and " + newSkulls + " new skulls.");
                getLogger().info("NOTE: Server restart required for Geyser Extension to load new items.");
            }
        }, 100L);
    }

    /**
     * Runs startup scans for custom items and skulls.
     * Scans all server recipes and worlds for custom items and skulls.
     */
    private void runStartupScans() {
        boolean debug = config.general().debugMode();

        if (config.customItems().enabled()) {
            int items = recipeScanner.scanAllRecipes();
            if (debug) {
                getLogger().info("Recipe scan complete: " + items + " custom items found (total: " + itemMappingRegistry.size() + ")");
            }
        }

        if (config.skulls().enabled()) {
            int skulls = worldSkullScanner.scanAllWorlds();
            if (debug) {
                getLogger().info("World skull scan complete: " + skulls + " unique skulls found (total: " + skullRegistry.size() + ")");
            }
        }

        // Save to shared folder
        saveRegistriesToSharedFolder();
        if (debug) {
            getLogger().info("Startup scans complete. Data saved to shared folder.");
        }
    }

    /**
     * Initializes recipe handlers and enchantment handler for Bedrock compatibility.
     *
     * Why: Bedrock Edition has different anvil and smithing table mechanics,
     * and cannot display custom/over-enchantments natively.
     * These handlers ensure custom recipes and enchantment display work correctly.
     */
    private void initializeRecipeHandlers() {
        bedrockEnchantmentHandler = new BedrockEnchantmentHandler(this);
        bedrockAnvilSimulator = new BedrockAnvilSimulator(this);
        bedrockEnchantmentTableGuard = new BedrockEnchantmentTableGuard(this);
        smithingRecipeHandler = new SmithingRecipeHandler(this);
        craftingRecipeHandler = new CraftingRecipeHandler(this);

        if (bedrockEnchantmentHandler.isEnabled()) {
            getLogger().info("Bedrock enchantment handler enabled (lore injection + anvil protection).");
        }
        if (bedrockAnvilSimulator.isEnabled()) {
            getLogger().info("Bedrock anvil simulator enabled (chest UI spoofing, mode: "
                + config.enchantment().anvilSimulationMode() + ").");
        }
        if (smithingRecipeHandler.isEnabled()) {
            getLogger().info("Smithing recipe handler enabled for Bedrock players.");
        }
        if (craftingRecipeHandler.isEnabled()) {
            getLogger().info("Crafting recipe handler enabled for Bedrock players.");
        }

        if (config.general().debugMode()) {
            getLogger().info("Recipe handlers initialized.");
        }
    }

    /**
     * Registers plugin commands.
     *
     * Why: Bedrock players lack native equivalents for many Java Edition features,
     * so we provide command-based and form-based alternatives.
     */
    private void registerCommands() {
        Objects.requireNonNull(getCommand("offhand")).setExecutor(new OffhandCommand());

        // Tooltip command — one-shot SimpleForm item detail display
        Objects.requireNonNull(getCommand("tooltip")).setExecutor(new TooltipCommand());

        // Menu command — central Floodgate form menu for all Bedrock commands.
        // /geyserextra (alias /ga) shares the same executor so admins and Bedrock
        // players have a memorable master entry point without re-implementing the form.
        BedrockMenuCommand menuExecutor = new BedrockMenuCommand(this, playerSettingsManager);
        Objects.requireNonNull(getCommand("menu")).setExecutor(menuExecutor);
        Objects.requireNonNull(getCommand("geyserextra")).setExecutor(menuExecutor);

        // Settings command — per-player display settings via Floodgate CustomForm
        // Why: The callback wires SettingsCommand to DisplayManager so that display
        // changes (e.g., turning off BossBar) take effect immediately, not after 0.5s.
        Objects.requireNonNull(getCommand("settings"))
            .setExecutor(new SettingsCommand(this, playerSettingsManager,
                displayManager::onSettingsChanged));

        // Advancement command — browse advancements via forms
        Objects.requireNonNull(getCommand("advancements"))
            .setExecutor(new AdvancementCommand(this));

        // Statistics command — view player stats via forms
        Objects.requireNonNull(getCommand("stats"))
            .setExecutor(new StatisticsCommand(this));

        // Game rules command — view world game rules via forms
        Objects.requireNonNull(getCommand("gamerules"))
            .setExecutor(new GameRulesCommand(this));
    }

    /**
     * Registers event listeners for item and chunk events.
     */
    private void registerListeners() {
        getServer().getPluginManager().registerEvents(
            new ItemListener(customItemScanner, skullScanner, this),
            this
        );

        if (config.skulls().enabled()) {
            getServer().getPluginManager().registerEvents(
                new ChunkLoadListener(skullScanner, this),
                this
            );
        }

        // Register recipe handlers for Bedrock compatibility
        if (bedrockEnchantmentHandler != null && bedrockEnchantmentHandler.isEnabled()) {
            getServer().getPluginManager().registerEvents(bedrockEnchantmentHandler, this);
        }
        if (bedrockAnvilSimulator != null && bedrockAnvilSimulator.isEnabled()) {
            getServer().getPluginManager().registerEvents(bedrockAnvilSimulator, this);
        }
        if (bedrockEnchantmentTableGuard != null && bedrockEnchantmentTableGuard.isEnabled()) {
            getServer().getPluginManager().registerEvents(bedrockEnchantmentTableGuard, this);
        }
        if (smithingRecipeHandler != null && smithingRecipeHandler.isEnabled()) {
            getServer().getPluginManager().registerEvents(smithingRecipeHandler, this);
        }
        if (craftingRecipeHandler != null && craftingRecipeHandler.isEnabled()) {
            getServer().getPluginManager().registerEvents(craftingRecipeHandler, this);
        }

        // Register offhand interaction listener for Bedrock right-click support
        getServer().getPluginManager().registerEvents(
            new OffhandInteractionListener(this),
            this
        );

        // Register elytra flight workaround for Bedrock gliding without elytra
        elytraFlightListener = new ElytraFlightListener(this);
        getServer().getPluginManager().registerEvents(elytraFlightListener, this);
        // Why: EntityToggleGlideEvent may not fire when plugins call setGliding() directly
        elytraFlightListener.startMonitorTask(this);

        // DiscordSRV integration — fix Bedrock player avatars in Discord messages
        // Gracefully no-ops if DiscordSRV is not installed
        discordSRVSkinHook = new com.geyserextra.paper.listener.DiscordSRVSkinHook(this);
        discordSRVSkinHook.tryRegister();

    }

    /**
     * Schedules periodic tasks for auto-reload and registry saves.
     */
    private void scheduleTasks() {
        // Schedule periodic registry save if auto-reload is enabled
        if (config.customItems().autoReload()) {
            long intervalTicks = config.customItems().reloadIntervalSeconds() * 20L;
            getServer().getScheduler().runTaskTimerAsynchronously(
                this,
                this::saveRegistriesToSharedFolder,
                intervalTicks,
                intervalTicks
            );
        }

    }

    /**
     * Performs initial scan of all online player inventories.
     */
    private void performInitialScan() {
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            customItemScanner.scanAllPlayers();

            if (config.general().debugMode()) {
                getLogger().info(() -> String.format(
                    "Initial scan complete. Found %d custom items.",
                    itemMappingRegistry.size()
                ));
            }
        });
    }

    /**
     * Saves registries to Extension data folder for Geyser extension access.
     * Config is not saved here as it's managed separately via loadConfiguration().
     */
    private void saveRegistriesToSharedFolder() {
        Path extensionFolder = getExtensionDataFolder();
        boolean debug = config.general().debugMode();

        if (debug) {
            getLogger().info("Saving registries to: " + extensionFolder.toAbsolutePath());
        }

        try {
            Files.createDirectories(extensionFolder);

            if (config.customItems().enabled()) {
                Path itemsPath = extensionFolder.resolve(CUSTOM_ITEMS_FILE);
                itemMappingRegistry.save(itemsPath);
                if (debug) {
                    getLogger().info("Saved " + itemMappingRegistry.size() + " items.");
                }

                // Why: Geyser custom items render as missing texture without an item_texture.json
                // entry. Generate a textureless pack that points every mapping at the matching
                // vanilla BE texture so admins do not have to author a BE resource pack.
                Path autoPackPath = extensionFolder.resolve("packs").resolve("geyserextra_auto.zip");
                try {
                    AutoBedrockPackBuilder.build(itemMappingRegistry, autoPackPath);
                    if (debug) {
                        getLogger().info("Built auto BE pack: " + autoPackPath
                            + " (" + itemMappingRegistry.size() + " mappings)");
                    }
                } catch (IOException e) {
                    getLogger().log(Level.WARNING, "Failed to build auto BE pack at " + autoPackPath, e);
                }
            }

            if (config.skulls().enabled()) {
                Path skullsPath = extensionFolder.resolve(SKULLS_FILE);
                skullRegistry.save(skullsPath);
                if (debug) {
                    getLogger().info("Saved " + skullRegistry.size() + " skulls.");
                }
            }
        } catch (IOException e) {
            getLogger().log(Level.WARNING, "Failed to save registries to extension folder.", e);
        }
    }

    /**
     * Gets the shared folder path where data is exchanged with Geyser extension.
     * This is now the same as the Extension data folder for unified storage.
     *
     * Path: plugins/Geyser-Spigot/extensions/geyserextra/
     *
     * @return The path to the shared folder (Extension data folder)
     */
    public Path getSharedFolder() {
        return getExtensionDataFolder();
    }

    /**
     * Gets the plugin configuration.
     *
     * @return The configuration instance
     */
    public GeyserExtraConfig getGeyserExtraConfig() {
        return config;
    }

    /**
     * Gets the item mapping registry.
     *
     * @return The item mapping registry
     */
    public ItemMappingRegistry getItemMappingRegistry() {
        return itemMappingRegistry;
    }

    /**
     * Gets the skull registry.
     *
     * @return The skull registry
     */
    public SkullRegistry getSkullRegistry() {
        return skullRegistry;
    }

    /**
     * Gets the custom item scanner.
     *
     * @return The custom item scanner
     */
    public CustomItemScanner getCustomItemScanner() {
        return customItemScanner;
    }

    /**
     * Gets the recipe scanner.
     *
     * @return The recipe scanner
     */
    public RecipeScanner getRecipeScanner() {
        return recipeScanner;
    }

    /**
     * Gets the skull scanner.
     *
     * @return The skull scanner
     */
    public SkullScanner getSkullScanner() {
        return skullScanner;
    }

    /**
     * Gets the Bedrock enchantment handler (lore injection + anvil protection).
     *
     * @return The Bedrock enchantment handler
     */
    public BedrockEnchantmentHandler getBedrockEnchantmentHandler() {
        return bedrockEnchantmentHandler;
    }

    /**
     * Gets the smithing recipe handler.
     *
     * @return The smithing recipe handler
     */
    public SmithingRecipeHandler getSmithingRecipeHandler() {
        return smithingRecipeHandler;
    }
}
