package com.geyserextra.core.config;

import com.geyserextra.core.util.JsonUtil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Main configuration class for GeyserExtra.
 *
 * This class holds all configuration settings organized into
 * feature-specific nested configuration classes.
 * All configuration values are immutable after construction.
 */
public final class GeyserExtraConfig {

    private static final Logger LOGGER = Logger.getLogger(GeyserExtraConfig.class.getName());

    private final GeneralConfig general;
    private final CustomItemsConfig customItems;
    private final SkullConfig skulls;
    private final CacheConfig cache;
    private final LoggingConfig logging;
    private final EnchantmentConfig enchantment;
    private final ResourcePackConfig resourcePacks;

    /**
     * Creates a new configuration with default values.
     */
    public GeyserExtraConfig() {
        this.general = new GeneralConfig();
        this.customItems = new CustomItemsConfig();
        this.skulls = new SkullConfig();
        this.cache = new CacheConfig();
        this.logging = new LoggingConfig();
        this.enchantment = new EnchantmentConfig();
        this.resourcePacks = new ResourcePackConfig();
    }

    /**
     * Creates a new configuration with specified values.
     *
     * @param general       General configuration
     * @param customItems   Custom items configuration
     * @param skulls        Skull configuration
     * @param cache         Cache configuration
     * @param logging       Logging configuration
     * @param enchantment   Enchantment configuration
     * @param resourcePacks Resource pack configuration
     */
    public GeyserExtraConfig(
        GeneralConfig general,
        CustomItemsConfig customItems,
        SkullConfig skulls,
        CacheConfig cache,
        LoggingConfig logging,
        EnchantmentConfig enchantment,
        ResourcePackConfig resourcePacks
    ) {
        this.general = general != null ? general : new GeneralConfig();
        this.customItems = customItems != null ? customItems : new CustomItemsConfig();
        this.skulls = skulls != null ? skulls : new SkullConfig();
        this.cache = cache != null ? cache : new CacheConfig();
        this.logging = logging != null ? logging : new LoggingConfig();
        this.enchantment = enchantment != null ? enchantment : new EnchantmentConfig();
        this.resourcePacks = resourcePacks != null ? resourcePacks : new ResourcePackConfig();
    }

    public GeneralConfig general() {
        return general;
    }

    public CustomItemsConfig customItems() {
        return customItems;
    }

    public SkullConfig skulls() {
        return skulls;
    }

    public CacheConfig cache() {
        return cache;
    }

    public LoggingConfig logging() {
        return logging;
    }

    public EnchantmentConfig enchantment() {
        return enchantment;
    }

    public ResourcePackConfig resourcePacks() {
        return resourcePacks;
    }

    /**
     * Saves this configuration to a JSON file.
     *
     * @param path The path to save the configuration to
     * @throws IOException if an I/O error occurs
     * @throws NullPointerException if path is null
     */
    public void save(Path path) throws IOException {
        Objects.requireNonNull(path, "path must not be null");

        // Ensure parent directories exist
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        String json = JsonUtil.toPrettyJson(this);
        Files.writeString(path, json);
        LOGGER.info(() -> "Saved configuration to " + path);
    }

    /**
     * Loads configuration from a JSON file.
     *
     * @param path The path to load the configuration from
     * @return The loaded configuration
     * @throws IOException if an I/O error occurs or the file does not exist
     * @throws NullPointerException if path is null
     */
    public static GeyserExtraConfig load(Path path) throws IOException {
        Objects.requireNonNull(path, "path must not be null");

        if (!Files.exists(path)) {
            throw new IOException("Configuration file does not exist: " + path);
        }

        String json = Files.readString(path);
        GeyserExtraConfig config = JsonUtil.fromJson(json, GeyserExtraConfig.class);

        if (config == null) {
            LOGGER.warning(() -> "Loaded null configuration from " + path + ", using defaults");
            return new GeyserExtraConfig();
        }

        LOGGER.info(() -> "Loaded configuration from " + path);
        return config;
    }

    /**
     * Loads configuration from a JSON file if it exists, otherwise creates default.
     *
     * @param path The path to load the configuration from
     * @return The loaded or default configuration
     * @throws NullPointerException if path is null
     */
    public static GeyserExtraConfig loadOrCreate(Path path) {
        Objects.requireNonNull(path, "path must not be null");

        if (Files.exists(path)) {
            try {
                return load(path);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Failed to load configuration from " + path + ", using defaults", e);
                return new GeyserExtraConfig();
            }
        }

        GeyserExtraConfig defaultConfig = new GeyserExtraConfig();
        try {
            defaultConfig.save(path);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to save default configuration to " + path, e);
        }
        return defaultConfig;
    }

    /**
     * General configuration settings.
     */
    public static final class GeneralConfig {
        private final boolean enabled;
        private final boolean debugMode;
        private final int workerThreads;
        private final boolean tooltipDefaultEnabled;
        private final boolean sneakDropOffhandSwapEnabled;

        public GeneralConfig() {
            this.enabled = true;
            this.debugMode = false;
            this.workerThreads = 2;
            this.tooltipDefaultEnabled = false;
            this.sneakDropOffhandSwapEnabled = true;
        }

        public GeneralConfig(boolean enabled, boolean debugMode,
                             int workerThreads, boolean tooltipDefaultEnabled) {
            this(enabled, debugMode, workerThreads, tooltipDefaultEnabled, true);
        }

        public GeneralConfig(boolean enabled, boolean debugMode,
                             int workerThreads, boolean tooltipDefaultEnabled,
                             boolean sneakDropOffhandSwapEnabled) {
            this.enabled = enabled;
            this.debugMode = debugMode;
            this.workerThreads = workerThreads > 0 ? workerThreads : 2;
            this.tooltipDefaultEnabled = tooltipDefaultEnabled;
            this.sneakDropOffhandSwapEnabled = sneakDropOffhandSwapEnabled;
        }

        public boolean enabled() {
            return enabled;
        }

        public boolean debugMode() {
            return debugMode;
        }

        /**
         * @deprecated The plugin no longer maintains a worker thread pool; all
         *     background work uses Bukkit's scheduler directly. The field is
         *     retained so existing {@code config.json} files do not fail to load.
         *     Scheduled for removal in a future major release.
         */
        @Deprecated
        public int workerThreads() {
            return workerThreads;
        }

        /**
         * Whether tooltip (held item info) display is enabled by default for new Bedrock players.
         * Players can still toggle it with /tooltip.
         * Default: false
         */
        public boolean tooltipDefaultEnabled() {
            return tooltipDefaultEnabled;
        }

        /**
         * Whether to register the sneak + drop key off-hand swap listener for
         * Bedrock players.
         *
         * <p>Default: true. Operators who run anti-cheat plugins that flag the
         * synthetic off-hand interaction event, or want their Bedrock players
         * to be able to drop items while sneaking, can set this to false. The
         * {@code /offhand} command remains available either way.</p>
         */
        public boolean sneakDropOffhandSwapEnabled() {
            return sneakDropOffhandSwapEnabled;
        }
    }

    /**
     * Custom items feature configuration.
     */
    public static final class CustomItemsConfig {

        /** Verbose per-item warning (legacy 14-line block per occurrence). */
        public static final String PDC_WARNING_FULL = "FULL";
        /** Compact mode: full instructions once, then 1-line per unique item, debounced summary. */
        public static final String PDC_WARNING_COMPACT = "COMPACT";
        /** Suppress all PDC-missing warnings (registration is still skipped). */
        public static final String PDC_WARNING_DISABLED = "DISABLED";

        /** Auto-detect Java pack format from directory structure. */
        public static final String JAVA_PACK_FORMAT_AUTO = "AUTO";
        /** Read only legacy {@code models/item/<base>.json} overrides arrays (1.20.x-1.21.3). */
        public static final String JAVA_PACK_FORMAT_LEGACY = "LEGACY";
        /** Read only modern {@code items/<name>.json} range_dispatch entries (1.21.4+). */
        public static final String JAVA_PACK_FORMAT_MODERN = "MODERN";

        private final boolean enabled;
        private final String mappingsFile;
        private final boolean autoReload;
        private final int reloadIntervalSeconds;
        private final String bedrockPacksPath;
        private final String pdcWarning;
        private final String javaResourcePackPath;
        private final String javaResourcePackFormat;

        public CustomItemsConfig() {
            this.enabled = true;
            this.mappingsFile = "custom_items.json";
            this.autoReload = false;
            this.reloadIntervalSeconds = 60;
            this.bedrockPacksPath = "";
            this.pdcWarning = PDC_WARNING_COMPACT;
            this.javaResourcePackPath = "";
            this.javaResourcePackFormat = JAVA_PACK_FORMAT_AUTO;
        }

        public CustomItemsConfig(
                boolean enabled,
                String mappingsFile,
                boolean autoReload,
                int reloadIntervalSeconds,
                String bedrockPacksPath,
                String pdcWarning
        ) {
            this(enabled, mappingsFile, autoReload, reloadIntervalSeconds,
                bedrockPacksPath, pdcWarning, "", JAVA_PACK_FORMAT_AUTO);
        }

        public CustomItemsConfig(
                boolean enabled,
                String mappingsFile,
                boolean autoReload,
                int reloadIntervalSeconds,
                String bedrockPacksPath,
                String pdcWarning,
                String javaResourcePackPath,
                String javaResourcePackFormat
        ) {
            this.enabled = enabled;
            this.mappingsFile = mappingsFile != null ? mappingsFile : "custom_items.json";
            this.autoReload = autoReload;
            this.reloadIntervalSeconds = reloadIntervalSeconds > 0 ? reloadIntervalSeconds : 60;
            this.bedrockPacksPath = bedrockPacksPath != null ? bedrockPacksPath : "";
            this.pdcWarning = normalizePdcWarning(pdcWarning);
            this.javaResourcePackPath = javaResourcePackPath != null ? javaResourcePackPath : "";
            this.javaResourcePackFormat = normalizeJavaPackFormat(javaResourcePackFormat);
        }

        private static String normalizePdcWarning(String raw) {
            if (raw == null || raw.isBlank()) {
                return PDC_WARNING_COMPACT;
            }
            String upper = raw.toUpperCase(java.util.Locale.ROOT);
            return switch (upper) {
                case PDC_WARNING_FULL, PDC_WARNING_COMPACT, PDC_WARNING_DISABLED -> upper;
                default -> PDC_WARNING_COMPACT;
            };
        }

        private static String normalizeJavaPackFormat(String raw) {
            if (raw == null || raw.isBlank()) {
                return JAVA_PACK_FORMAT_AUTO;
            }
            String upper = raw.toUpperCase(java.util.Locale.ROOT);
            return switch (upper) {
                case JAVA_PACK_FORMAT_AUTO, JAVA_PACK_FORMAT_LEGACY, JAVA_PACK_FORMAT_MODERN -> upper;
                default -> JAVA_PACK_FORMAT_AUTO;
            };
        }

        public boolean enabled() {
            return enabled;
        }

        public String mappingsFile() {
            return mappingsFile;
        }

        public boolean autoReload() {
            return autoReload;
        }

        public int reloadIntervalSeconds() {
            return reloadIntervalSeconds;
        }

        /**
         * Returns the path to the directory containing BE resource packs.
         *
         * @deprecated As of the auto-generated pack rework, GeyserExtra no longer
         *     scans an external BE resource pack directory to filter mappings.
         *     The companion {@code AutoBedrockPackBuilder} writes a textureless pack
         *     under {@code <extension>/packs/geyserextra_auto.zip} that points every
         *     mapping at the matching vanilla BE texture, so admins do not author
         *     a pack themselves. This field is kept for backwards-compat with older
         *     {@code config.json} files but is unused.
         * @return the configured value (kept for compatibility), or empty string
         */
        @Deprecated
        public String bedrockPacksPath() {
            return bedrockPacksPath;
        }

        /**
         * Returns the verbosity mode for PDC-missing warnings.
         *
         * Why: Servers using plugins that bulk-register CustomModelData items without PDC
         * (ItemsAdder, Oraxen, Skript scripts, raw /give component data) hit dozens of
         * unique items at once, and the legacy 14-line block per item produced log spam
         * that drowned out other diagnostics. COMPACT keeps the educational guidance
         * (printed once) while listing subsequent items as a single line each plus
         * a debounced aggregate summary.
         *
         * Values: {@link #PDC_WARNING_FULL}, {@link #PDC_WARNING_COMPACT},
         * {@link #PDC_WARNING_DISABLED}. Default: COMPACT.
         *
         * @return the configured warning mode (always normalized to one of the constants)
         */
        public String pdcWarning() {
            return pdcWarning != null ? pdcWarning : PDC_WARNING_COMPACT;
        }

        /**
         * Returns the operator-provided Java edition resource pack path used as a
         * source for custom item textures when generating the Bedrock auto-pack.
         *
         * <p>Empty string disables the feature; the auto-pack falls back to vanilla
         * textures for every custom item (matching pre-feature behaviour). When
         * non-empty, the value is interpreted relative to the Paper plugin's data
         * folder unless it is an absolute path.</p>
         *
         * <p>The path must point at an <b>unzipped</b> Java pack directory (the one
         * containing {@code pack.mcmeta} and {@code assets/}). ZIP archives are not
         * scanned in this release.</p>
         *
         * @return the configured path, or empty string when disabled
         */
        public String javaResourcePackPath() {
            return javaResourcePackPath != null ? javaResourcePackPath : "";
        }

        /**
         * Returns the Java pack scan format hint.
         *
         * <p>Values: {@link #JAVA_PACK_FORMAT_AUTO} (detect based on directory
         * contents), {@link #JAVA_PACK_FORMAT_LEGACY} (force 1.20.x-1.21.3
         * {@code models/item/<base>.json overrides[]} parsing), and
         * {@link #JAVA_PACK_FORMAT_MODERN} (force 1.21.4+
         * {@code items/<name>.json range_dispatch} parsing). Default: AUTO.</p>
         */
        public String javaResourcePackFormat() {
            return javaResourcePackFormat != null ? javaResourcePackFormat : JAVA_PACK_FORMAT_AUTO;
        }
    }

    /**
     * Skull texture feature configuration.
     */
    public static final class SkullConfig {
        private final boolean enabled;
        private final String dataFile;
        private final boolean cacheTextures;
        private final int maxCachedTextures;
        private final int textureResolution;

        public SkullConfig() {
            this.enabled = true;
            this.dataFile = "skulls.json";
            this.cacheTextures = true;
            this.maxCachedTextures = 1000;
            this.textureResolution = 64;
        }

        public SkullConfig(boolean enabled, String dataFile, boolean cacheTextures, int maxCachedTextures, int textureResolution) {
            this.enabled = enabled;
            this.dataFile = dataFile != null ? dataFile : "skulls.json";
            this.cacheTextures = cacheTextures;
            this.maxCachedTextures = maxCachedTextures > 0 ? maxCachedTextures : 1000;
            this.textureResolution = textureResolution > 0 ? textureResolution : 64;
        }

        public boolean enabled() {
            return enabled;
        }

        public String dataFile() {
            return dataFile;
        }

        public boolean cacheTextures() {
            return cacheTextures;
        }

        public int maxCachedTextures() {
            return maxCachedTextures;
        }

        public int textureResolution() {
            return textureResolution;
        }
    }

    /**
     * Cache configuration settings.
     *
     * @deprecated The plugin no longer maintains a separate texture cache. Skull
     *     textures are cached in-memory by {@link SkullConfig#cacheTextures()}
     *     and {@link SkullConfig#maxCachedTextures()}; all other on-disk cache
     *     plans were never implemented. Fields are retained so existing
     *     {@code config.json} files do not fail to load; the section is
     *     scheduled for removal in a future major release.
     */
    @Deprecated
    public static final class CacheConfig {
        private final boolean enabled;
        private final String cacheDirectory;
        private final long maxCacheSizeMb;
        private final int cleanupIntervalMinutes;
        private final boolean persistCache;

        public CacheConfig() {
            this.enabled = true;
            this.cacheDirectory = "cache";
            this.maxCacheSizeMb = 100;
            this.cleanupIntervalMinutes = 60;
            this.persistCache = true;
        }

        public CacheConfig(boolean enabled, String cacheDirectory, long maxCacheSizeMb, int cleanupIntervalMinutes, boolean persistCache) {
            this.enabled = enabled;
            this.cacheDirectory = cacheDirectory != null ? cacheDirectory : "cache";
            this.maxCacheSizeMb = maxCacheSizeMb > 0 ? maxCacheSizeMb : 100;
            this.cleanupIntervalMinutes = cleanupIntervalMinutes > 0 ? cleanupIntervalMinutes : 60;
            this.persistCache = persistCache;
        }

        public boolean enabled() {
            return enabled;
        }

        public String cacheDirectory() {
            return cacheDirectory;
        }

        public long maxCacheSizeMb() {
            return maxCacheSizeMb;
        }

        public int cleanupIntervalMinutes() {
            return cleanupIntervalMinutes;
        }

        public boolean persistCache() {
            return persistCache;
        }
    }

    /**
     * Logging configuration settings.
     *
     * @deprecated The plugin uses Bukkit's built-in plugin logger (level set
     *     by the server, no file-rotation logic in this codebase). These fields
     *     were placeholders that never wired up to a logger configuration.
     *     Retained so existing {@code config.json} files do not fail to load;
     *     scheduled for removal in a future major release.
     */
    @Deprecated
    public static final class LoggingConfig {
        private final String level;
        private final boolean logToFile;
        private final String logFile;
        private final boolean includeTimestamp;
        private final int maxLogFileSizeMb;
        private final int maxLogFileCount;

        public LoggingConfig() {
            this.level = "INFO";
            this.logToFile = false;
            this.logFile = "geyserextra.log";
            this.includeTimestamp = true;
            this.maxLogFileSizeMb = 10;
            this.maxLogFileCount = 5;
        }

        public LoggingConfig(String level, boolean logToFile, String logFile, boolean includeTimestamp, int maxLogFileSizeMb, int maxLogFileCount) {
            this.level = level != null ? level : "INFO";
            this.logToFile = logToFile;
            this.logFile = logFile != null ? logFile : "geyserextra.log";
            this.includeTimestamp = includeTimestamp;
            this.maxLogFileSizeMb = maxLogFileSizeMb > 0 ? maxLogFileSizeMb : 10;
            this.maxLogFileCount = maxLogFileCount > 0 ? maxLogFileCount : 5;
        }

        public String level() {
            return level;
        }

        public boolean logToFile() {
            return logToFile;
        }

        public String logFile() {
            return logFile;
        }

        public boolean includeTimestamp() {
            return includeTimestamp;
        }

        public int maxLogFileSizeMb() {
            return maxLogFileSizeMb;
        }

        public int maxLogFileCount() {
            return maxLogFileCount;
        }
    }

    /**
     * Enchantment feature configuration.
     *
     * Controls enchantment display and Bedrock-specific anvil behavior
     * including custom enchantment lore display, over-enchantment handling,
     * and anvil simulation via chest UI spoofing for Bedrock players.
     */
    public static final class EnchantmentConfig {
        private final boolean enabled;
        private final boolean showCustomEnchantments;
        private final boolean showOverEnchantments;
        private final boolean overEnchantmentProtectionEnabled;
        private final boolean overEnchantmentLevelUpEnabled;
        private final boolean anvilSimulationEnabled;
        private final String anvilSimulationMode;

        /**
         * Creates a new enchantment configuration with default values.
         */
        public EnchantmentConfig() {
            this.enabled = true;
            this.showCustomEnchantments = true;
            this.showOverEnchantments = true;
            this.overEnchantmentProtectionEnabled = true;
            this.overEnchantmentLevelUpEnabled = false;
            this.anvilSimulationEnabled = true;
            this.anvilSimulationMode = "NOT_SNEAKING";
        }

        /**
         * Creates a new enchantment configuration with specified values.
         *
         * @param enabled                          Whether enchantment handling is enabled
         * @param showCustomEnchantments           Whether to show custom enchantments in lore
         * @param showOverEnchantments             Whether to show over-enchantments in lore
         * @param overEnchantmentProtectionEnabled  Whether to protect over-enchantments from Bedrock downgrade
         * @param overEnchantmentLevelUpEnabled     Whether to allow over-enchantment level up on same-level combine
         * @param anvilSimulationEnabled           Whether anvil simulation via chest UI is enabled
         * @param anvilSimulationMode              The anvil simulation mode (ALWAYS, NOT_SNEAKING, SNEAKING, DISABLED)
         */
        public EnchantmentConfig(
            boolean enabled,
            boolean showCustomEnchantments,
            boolean showOverEnchantments,
            boolean overEnchantmentProtectionEnabled,
            boolean overEnchantmentLevelUpEnabled,
            boolean anvilSimulationEnabled,
            String anvilSimulationMode
        ) {
            this.enabled = enabled;
            this.showCustomEnchantments = showCustomEnchantments;
            this.showOverEnchantments = showOverEnchantments;
            this.overEnchantmentProtectionEnabled = overEnchantmentProtectionEnabled;
            this.overEnchantmentLevelUpEnabled = overEnchantmentLevelUpEnabled;
            this.anvilSimulationEnabled = anvilSimulationEnabled;
            this.anvilSimulationMode = anvilSimulationMode != null ? anvilSimulationMode : "NOT_SNEAKING";
        }

        /**
         * Whether enchantment handling is enabled for Bedrock players.
         */
        public boolean enabled() {
            return enabled;
        }

        /**
         * Whether to display custom enchantments in item lore for Bedrock players.
         * Default: true
         */
        public boolean showCustomEnchantments() {
            return showCustomEnchantments;
        }

        /**
         * Whether to display over-enchantments (levels exceeding vanilla max) in item lore.
         * Default: true
         */
        public boolean showOverEnchantments() {
            return showOverEnchantments;
        }

        /**
         * Whether to protect over-enchantments from being downgraded by Bedrock client.
         * When enabled, the server caches the correct result and applies it directly.
         * Default: true
         */
        public boolean overEnchantmentProtectionEnabled() {
            return overEnchantmentProtectionEnabled;
        }

        /**
         * Whether to allow over-enchantments to level up when combining same levels.
         * For example: Efficiency V + Efficiency V = Efficiency VI
         * Default: false (same levels stay at current level)
         */
        public boolean overEnchantmentLevelUpEnabled() {
            return overEnchantmentLevelUpEnabled;
        }

        /**
         * Whether anvil simulation via chest UI spoofing is enabled for Bedrock players.
         * When enabled, anvil interactions are presented through a chest UI to allow
         * proper handling of custom and over-enchantments.
         * Default: true
         */
        public boolean anvilSimulationEnabled() {
            return anvilSimulationEnabled;
        }

        /**
         * The anvil simulation mode that controls when chest UI spoofing is used.
         *
         * <p>Valid values:</p>
         * <ul>
         *   <li>{@code "ALWAYS"} - Always show anvil as chest UI for Bedrock players</li>
         *   <li>{@code "NOT_SNEAKING"} - Use chest UI normally; vanilla anvil when sneaking (for renaming)</li>
         *   <li>{@code "SNEAKING"} - Use chest UI only when sneaking</li>
         *   <li>{@code "DISABLED"} - Disable chest UI spoofing entirely</li>
         * </ul>
         *
         * Default: "NOT_SNEAKING"
         */
        public String anvilSimulationMode() {
            return anvilSimulationMode;
        }
    }

    /**
     * Resource pack configuration.
     *
     * Controls which built-in resource packs are distributed to Bedrock players.
     */
    public static final class ResourcePackConfig {
        private final boolean invisibleGlowFramesEnabled;

        public ResourcePackConfig() {
            this.invisibleGlowFramesEnabled = true;
        }

        public ResourcePackConfig(boolean invisibleGlowFramesEnabled) {
            this.invisibleGlowFramesEnabled = invisibleGlowFramesEnabled;
        }

        /**
         * Whether to distribute the invisible glow item frames resource pack.
         * When enabled, glow item frames will appear transparent for Bedrock players.
         * Default: true
         */
        public boolean invisibleGlowFramesEnabled() {
            return invisibleGlowFramesEnabled;
        }
    }
}
