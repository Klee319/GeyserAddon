package com.geyserextra.paper;

import com.geyserextra.core.config.GeyserExtraConfig;
import com.geyserextra.core.registry.ItemMappingRegistry;
import com.geyserextra.core.registry.SkullRegistry;
import com.geyserextra.paper.listener.ChunkLoadListener;
import com.geyserextra.paper.listener.ItemListener;
import com.geyserextra.paper.listener.ElytraFlightListener;
import com.geyserextra.paper.listener.OffhandInteractionListener;
import com.geyserextra.paper.listener.OffhandSwapListener;
import com.geyserextra.paper.enchantment.BedrockAnvilSimulator;
import com.geyserextra.paper.enchantment.BedrockEnchantmentHandler;
import com.geyserextra.paper.enchantment.BedrockEnchantmentTablePacketStripper;
import com.geyserextra.paper.recipe.CraftingRecipeHandler;
import com.geyserextra.paper.recipe.SmithingRecipeHandler;
import com.geyserextra.core.api.CustomItemMapping;
import com.geyserextra.paper.pack.AutoBedrockPackBuilder;
import com.geyserextra.paper.pack.JavaPackReader;
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
import java.util.Collections;
import java.util.Map;
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
    private BedrockEnchantmentTablePacketStripper bedrockEnchantmentTableStripper;
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
        // Why stripper first: BedrockEnchantmentHandler depends on the stripper
        // for thread-safe inventory state queries from packet listeners.
        bedrockEnchantmentTableStripper = new BedrockEnchantmentTablePacketStripper(this);
        bedrockEnchantmentHandler = new BedrockEnchantmentHandler(this, bedrockEnchantmentTableStripper);
        bedrockAnvilSimulator = new BedrockAnvilSimulator(this);
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

        // Register the stripper FIRST so its inventory state is populated
        // by InventoryOpenEvent before any handler queries it from packet threads.
        if (bedrockEnchantmentTableStripper != null) {
            getServer().getPluginManager().registerEvents(bedrockEnchantmentTableStripper, this);
        }

        // Register recipe handlers for Bedrock compatibility
        if (bedrockEnchantmentHandler != null && bedrockEnchantmentHandler.isEnabled()) {
            getServer().getPluginManager().registerEvents(bedrockEnchantmentHandler, this);
        }
        if (bedrockAnvilSimulator != null && bedrockAnvilSimulator.isEnabled()) {
            getServer().getPluginManager().registerEvents(bedrockAnvilSimulator, this);
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

        // Register sneak+drop offhand-swap listener so Bedrock players can swap
        // hands without the /offhand command (mirrors Java F-key behaviour).
        // Gated on config because some anti-cheat plugins flag the synthetic
        // off-hand event, and operators who rely on "drop while sneaking" can
        // opt out without losing the /offhand command. See
        // GeneralConfig.sneakDropOffhandSwapEnabled.
        if (config.general().sneakDropOffhandSwapEnabled()) {
            getServer().getPluginManager().registerEvents(
                new OffhandSwapListener(this),
                this
            );
        } else if (config.general().debugMode()) {
            getLogger().info("OffhandSwapListener disabled by config (general.sneakDropOffhandSwapEnabled=false)");
        }

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
     *
     * <p>Why {@code runTask} not {@code runTaskAsynchronously}: Bukkit's inventory
     * API requires the primary server thread, so the scanner has to run there.
     * A previous revision used {@code runTaskAsynchronously} together with an
     * {@code isPrimaryThread()} short-circuit inside {@code scanAllPlayers()},
     * which silently turned the entire startup scan into a no-op. With the
     * scanner's thread guard now refusing to run off-thread, scheduling has to
     * be synchronous from the start.</p>
     */
    private void performInitialScan() {
        getServer().getScheduler().runTask(this, () -> {
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
                // entry. Generate a pack that points every mapping at the matching vanilla
                // BE texture so admins do not have to author a BE resource pack. When a Java
                // pack is configured, custom textures from that pack are mirrored as well so
                // Bedrock players see the operator-supplied 2D textures (3D models remain
                // out of scope — see the README's converter notes).
                Path autoPackPath = extensionFolder.resolve("packs").resolve("geyserextra_auto.zip");
                Path javaPackRoot = resolveJavaPackRoot();
                String javaPackFormat = config.customItems().javaResourcePackFormat();

                // Scan the Java pack once and reuse the result for both pre-registration
                // and texture copying. Pre-registration ("pack-first") guarantees every
                // (baseItem, CMD) override in the operator's pack ends up in the registry
                // — without it, only items the scanner has observed during play would get
                // a Bedrock texture, breaking the "Java pack is always reflected on Bedrock"
                // guarantee for items no one has picked up yet.
                Map<JavaPackReader.CmdKey, JavaPackReader.JavaModelDefinition> javaPackEntries =
                    Collections.emptyMap();
                if (javaPackRoot != null) {
                    try {
                        javaPackEntries = new JavaPackReader(
                            javaPackRoot, javaPackFormat, getLogger(), debug).scan();
                    } catch (Exception ex) {
                        getLogger().warning("[JavaPack] scan failed: "
                            + ex.getClass().getSimpleName() + ": " + ex.getMessage());
                    }
                }
                if (!javaPackEntries.isEmpty()) {
                    prepopulateRegistryFromJavaPack(javaPackEntries);
                }

                try {
                    AutoBedrockPackBuilder.build(
                        itemMappingRegistry,
                        autoPackPath,
                        javaPackEntries,
                        getLogger(),
                        debug
                    );
                    if (debug) {
                        getLogger().info("Built auto BE pack: " + autoPackPath
                            + " (" + itemMappingRegistry.size() + " mappings"
                            + (javaPackRoot != null ? ", Java pack: " + javaPackRoot : "")
                            + ")");
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
     * Pre-populates {@link #itemMappingRegistry} with every {@code (baseItem,
     * custom_model_data)} pair found in the operator's Java edition resource
     * pack that the runtime scanner has not yet observed.
     *
     * <p>Why pack-first: the runtime scanner adds entries lazily as players
     * touch items (inventory join, GUI open, held-item swap, craft result).
     * Until that happens for a given CMD value, the registry has no entry,
     * the auto-pack builder has nothing to iterate, and the matching Java
     * texture never reaches Bedrock — even though the Java pack already
     * defines it. Pre-populating from the pack itself closes that gap so
     * "Java pack defines a texture for X" always implies "Bedrock sees the
     * texture for X" without depending on player interaction order.</p>
     *
     * <p>Existing entries (whether from runtime scanning, custom_items.json
     * load, or a previous pre-population pass) are left untouched: the first
     * registration wins, so any operator-curated metadata (display name,
     * creative category) is preserved.</p>
     *
     * @param packEntries scan result from {@link JavaPackReader#scan()}
     */
    private void prepopulateRegistryFromJavaPack(
        Map<JavaPackReader.CmdKey, JavaPackReader.JavaModelDefinition> packEntries
    ) {
        int added = 0;
        int skipped = 0;
        for (JavaPackReader.CmdKey key : packEntries.keySet()) {
            if (itemMappingRegistry.getByCustomModelData(key.baseItem(), key.cmd()).isPresent()) {
                skipped++;
                continue;
            }
            String autoName = generateAutoMappingName(key.baseItem(), key.cmd());
            if (itemMappingRegistry.contains(autoName)) {
                skipped++;
                continue;
            }
            CustomItemMapping mapping = new CustomItemMapping(
                autoName,
                key.baseItem(),
                key.cmd(),
                false,   // unbreakable unknown from pack alone
                deriveFallbackDisplayName(key.baseItem(), key.cmd()),
                null,    // icon falls back to name via item_texture.json
                CustomItemMapping.CREATIVE_CATEGORY_ITEMS,
                null,    // creative group unset
                true     // register with Geyser
            );
            itemMappingRegistry.register(mapping);
            added++;
        }
        if (added > 0 || skipped > 0) {
            getLogger().info("[JavaPack] pre-registration: " + added
                + " items added from pack, " + skipped + " already in registry");
        }
    }

    /**
     * Builds a human-readable display name fallback for a pack-first registration.
     *
     * <p>Why: when {@code displayName} is null, Geyser falls back to the Bedrock
     * identifier ({@code geyserextra:custom_diamond_sword_100}) which the
     * Bedrock client renders as raw-id-looking text. The runtime scanner
     * replaces this with the player's actual display name once they touch the
     * item, but until then we want a more presentable placeholder. The output
     * is derived from the base material so it carries no information the pack
     * didn't already provide; the CMD suffix keeps variants distinguishable
     * in inventory and creative menu.</p>
     */
    private static String deriveFallbackDisplayName(String baseItem, int cmd) {
        String trimmed = baseItem.startsWith("minecraft:")
            ? baseItem.substring("minecraft:".length())
            : baseItem;
        StringBuilder pretty = new StringBuilder(trimmed.length() + 8);
        boolean upcaseNext = true;
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c == '_' || c == ':' || c == '/') {
                pretty.append(' ');
                upcaseNext = true;
            } else if (upcaseNext) {
                pretty.append(Character.toUpperCase(c));
                upcaseNext = false;
            } else {
                pretty.append(c);
            }
        }
        return pretty + " #" + cmd;
    }

    /**
     * Mirrors {@code CustomItemScanner.generateMappingName}'s auto-generated
     * fallback so a pack-first registration uses the same naming convention
     * the scanner would have produced once a player interacted with the item.
     */
    private static String generateAutoMappingName(String baseItem, int cmd) {
        String trimmed = baseItem.startsWith("minecraft:")
            ? baseItem.substring("minecraft:".length())
            : baseItem;
        return "custom_" + trimmed + "_" + cmd;
    }

    /**
     * Resolves the configured Java edition resource pack root path.
     *
     * <p>Returns {@code null} when the path is unset, blank, missing on disk,
     * not a directory, or escapes the plugin data folder via {@code ..}
     * traversal — in those cases the auto-pack builder skips the Java pack
     * scan and falls back to vanilla textures for every item.</p>
     *
     * <p>Relative paths are interpreted against this plugin's data folder
     * (e.g. {@code plugins/GeyserExtra/}) and must resolve to a location
     * within that folder after normalization. Absolute paths are accepted
     * verbatim — operators choosing an absolute path are trusted to know
     * where they pointed.</p>
     */
    private Path resolveJavaPackRoot() {
        String configured = config.customItems().javaResourcePackPath();
        if (configured == null || configured.isBlank()) {
            return null;
        }
        Path rawCandidate = Path.of(configured);
        boolean wasRelative = !rawCandidate.isAbsolute();
        Path candidate = wasRelative
            ? getDataFolder().toPath().resolve(rawCandidate)
            : rawCandidate;
        Path normalized = candidate.toAbsolutePath().normalize();

        // Why: a relative path like "../../etc" would silently escape the
        // plugin data folder once resolved + normalized. Reject any relative
        // configuration that escapes, while still letting absolute paths
        // through unchanged (an operator setting an absolute path is making
        // an explicit choice).
        if (wasRelative) {
            Path pluginRoot = getDataFolder().toPath().toAbsolutePath().normalize();
            if (!normalized.startsWith(pluginRoot)) {
                getLogger().warning(
                    "[JavaPack] relative javaResourcePackPath escapes plugin data folder: "
                        + configured + " (resolved to " + normalized + ") — ignoring.");
                return null;
            }
        }

        if (!Files.isDirectory(normalized)) {
            getLogger().warning("[JavaPack] configured java pack path is not a directory: "
                + normalized + " — falling back to vanilla textures.");
            return null;
        }
        return normalized;
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
     * Returns the per-player settings manager so other components (e.g. the
     * Bedrock enchantment lore injector) can honour individual player
     * preferences such as the lore-tooltip toggle.
     *
     * @return the manager, or {@code null} if the plugin failed to initialise it
     */
    public PlayerSettingsManager getPlayerSettingsManager() {
        return playerSettingsManager;
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
