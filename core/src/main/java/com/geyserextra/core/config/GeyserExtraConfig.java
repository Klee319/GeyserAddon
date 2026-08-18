package com.geyserextra.core.config;

import com.geyserextra.core.util.JsonUtil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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
        LOGGER.fine(() -> "Saved configuration to " + path);
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

        LOGGER.fine(() -> "Loaded configuration from " + path);
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
            } catch (RuntimeException e) {
                // Gson/JsonUtil typically throws RuntimeException (JsonParseException,
                // JsonSyntaxException, or IllegalArgumentException from a value-object
                // canonical constructor) on malformed JSON. Without this catch, a single
                // bad entry in dynamicResourcePackUrls or any other nested record could
                // escape to onEnable and crash the entire plugin. Falling back to
                // defaults keeps the server bootable; the operator sees the warning
                // and can repair the config without losing service.
                LOGGER.log(Level.WARNING, "Configuration at " + path
                    + " is malformed (" + e.getClass().getSimpleName() + "): "
                    + e.getMessage() + " — falling back to defaults", e);
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
        /**
         * Boxed on purpose. This class is deserialized by plain Gson, which
         * bypasses the constructors and leaves a field absent from the JSON at
         * its zero value — {@code false} for a primitive boolean. Every
         * config.json written before this option existed would therefore have
         * switched the skin repair off. A {@code Boolean} comes back
         * {@code null} instead, which {@link #bedrockSkinFixEnabled()} reads as
         * "not configured, use the default".
         */
        private final Boolean bedrockSkinFixEnabled;

        public GeneralConfig() {
            this.enabled = true;
            this.debugMode = false;
            this.workerThreads = 2;
            this.tooltipDefaultEnabled = false;
            this.sneakDropOffhandSwapEnabled = true;
            this.bedrockSkinFixEnabled = true;
        }

        public GeneralConfig(boolean enabled, boolean debugMode,
                             int workerThreads, boolean tooltipDefaultEnabled) {
            this(enabled, debugMode, workerThreads, tooltipDefaultEnabled, true);
        }

        public GeneralConfig(boolean enabled, boolean debugMode,
                             int workerThreads, boolean tooltipDefaultEnabled,
                             boolean sneakDropOffhandSwapEnabled) {
            this(enabled, debugMode, workerThreads, tooltipDefaultEnabled,
                sneakDropOffhandSwapEnabled, true);
        }

        public GeneralConfig(boolean enabled, boolean debugMode,
                             int workerThreads, boolean tooltipDefaultEnabled,
                             boolean sneakDropOffhandSwapEnabled,
                             boolean bedrockSkinFixEnabled) {
            this.enabled = enabled;
            this.debugMode = debugMode;
            this.workerThreads = workerThreads > 0 ? workerThreads : 2;
            this.tooltipDefaultEnabled = tooltipDefaultEnabled;
            this.sneakDropOffhandSwapEnabled = sneakDropOffhandSwapEnabled;
            this.bedrockSkinFixEnabled = bedrockSkinFixEnabled;
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

        /**
         * Whether to repair a Bedrock player's missing skin from the public
         * GeyserMC skin API on join.
         *
         * <p>Default: true, including for a config.json that predates the
         * option. It only ever writes a {@code textures} property that is
         * absent, so on a network where Floodgate delivers skins correctly it
         * costs one profile check per Bedrock join and changes nothing. Set it
         * to false to hand skin handling back to Floodgate unconditionally.</p>
         */
        public boolean bedrockSkinFixEnabled() {
            return bedrockSkinFixEnabled == null || bedrockSkinFixEnabled;
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
        private final List<String> javaResourcePackPaths;
        private final String javaResourcePackFormat;
        private final String javaPackLocale;
        // --- New fields (Phase 0): see plan cmd-plugin-3dmodel-java-robust-meteor.md ---
        // pdcEnabled: emergency rollback flag for PDC-only craft result feature.
        // dynamicResourcePackUrls: optional HTTP(S) URLs of Java resource packs to fetch and merge.
        // attachableGeneration: controls Bedrock attachable/geometry/animation generation.
        // entityTextureOverride: Phase 7b — Java pack entity textures mirrored into the Bedrock pack.
        // armorGeneration: Phase 7a — armor attachable generation for equippable items.
        private final boolean pdcEnabled;
        private final List<DynamicResourcePackEntry> dynamicResourcePackUrls;
        private final AttachableGenerationConfig attachableGeneration;
        private final EntityTextureOverrideConfig entityTextureOverride;
        private final ArmorGenerationConfig armorGeneration;

        public CustomItemsConfig() {
            this.enabled = true;
            this.mappingsFile = "custom_items.json";
            this.autoReload = false;
            this.reloadIntervalSeconds = 60;
            this.bedrockPacksPath = "";
            this.pdcWarning = PDC_WARNING_COMPACT;
            this.javaResourcePackPath = "";
            this.javaResourcePackPaths = Collections.emptyList();
            this.javaResourcePackFormat = JAVA_PACK_FORMAT_AUTO;
            this.javaPackLocale = "en_us";
            this.pdcEnabled = true;
            this.dynamicResourcePackUrls = Collections.emptyList();
            this.attachableGeneration = new AttachableGenerationConfig();
            this.entityTextureOverride = new EntityTextureOverrideConfig();
            this.armorGeneration = new ArmorGenerationConfig();
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
            this(enabled, mappingsFile, autoReload, reloadIntervalSeconds,
                bedrockPacksPath, pdcWarning, javaResourcePackPath,
                javaResourcePackFormat, "en_us");
        }

        public CustomItemsConfig(
                boolean enabled,
                String mappingsFile,
                boolean autoReload,
                int reloadIntervalSeconds,
                String bedrockPacksPath,
                String pdcWarning,
                String javaResourcePackPath,
                String javaResourcePackFormat,
                String javaPackLocale
        ) {
            this(enabled, mappingsFile, autoReload, reloadIntervalSeconds,
                bedrockPacksPath, pdcWarning, javaResourcePackPath,
                Collections.emptyList(), javaResourcePackFormat, javaPackLocale);
        }

        public CustomItemsConfig(
                boolean enabled,
                String mappingsFile,
                boolean autoReload,
                int reloadIntervalSeconds,
                String bedrockPacksPath,
                String pdcWarning,
                String javaResourcePackPath,
                List<String> javaResourcePackPaths,
                String javaResourcePackFormat,
                String javaPackLocale
        ) {
            this(enabled, mappingsFile, autoReload, reloadIntervalSeconds,
                 bedrockPacksPath, pdcWarning, javaResourcePackPath, javaResourcePackPaths,
                 javaResourcePackFormat, javaPackLocale,
                 true, Collections.emptyList(), new AttachableGenerationConfig());
        }

        /**
         * Canonical constructor including Phase 0 additions
         * ({@code pdcEnabled}, {@code dynamicResourcePackUrls}, {@code attachableGeneration}).
         * All shorter constructors ultimately delegate here so a single normalization
         * path applies to every field.
         */
        public CustomItemsConfig(
                boolean enabled,
                String mappingsFile,
                boolean autoReload,
                int reloadIntervalSeconds,
                String bedrockPacksPath,
                String pdcWarning,
                String javaResourcePackPath,
                List<String> javaResourcePackPaths,
                String javaResourcePackFormat,
                String javaPackLocale,
                boolean pdcEnabled,
                List<DynamicResourcePackEntry> dynamicResourcePackUrls,
                AttachableGenerationConfig attachableGeneration
        ) {
            this.enabled = enabled;
            this.mappingsFile = mappingsFile != null ? mappingsFile : "custom_items.json";
            this.autoReload = autoReload;
            this.reloadIntervalSeconds = reloadIntervalSeconds > 0 ? reloadIntervalSeconds : 60;
            this.bedrockPacksPath = bedrockPacksPath != null ? bedrockPacksPath : "";
            this.pdcWarning = normalizePdcWarning(pdcWarning);
            this.javaResourcePackPath = javaResourcePackPath != null ? javaResourcePackPath : "";
            this.javaResourcePackPaths = javaResourcePackPaths != null
                ? List.copyOf(javaResourcePackPaths)
                : Collections.emptyList();
            this.javaResourcePackFormat = normalizeJavaPackFormat(javaResourcePackFormat);
            this.javaPackLocale = (javaPackLocale != null && !javaPackLocale.isBlank())
                ? javaPackLocale.toLowerCase()
                : "en_us";
            this.pdcEnabled = pdcEnabled;
            // Late-filter invalid entries. DynamicResourcePackEntry's compact
            // constructor is intentionally lenient (accepts null/blank url)
            // so a malformed JSON does not throw mid-Gson-deserialisation and
            // crash plugin enable; instead we drop the bad entries here where
            // we can fail closed for that entry alone.
            this.dynamicResourcePackUrls = dynamicResourcePackUrls != null
                ? List.copyOf(dynamicResourcePackUrls.stream()
                    .filter(e -> e != null && e.hasValidUrl())
                    .toList())
                : Collections.emptyList();
            this.attachableGeneration = attachableGeneration != null
                ? attachableGeneration
                : new AttachableGenerationConfig();
            // Phase 7: new nested configs default to enabled instances when
            // the canonical constructor doesn't receive explicit values via
            // legacy shorter-arity calls. Gson deserialisation populates them
            // through reflection on the actual field type when JSON declares
            // the section.
            this.entityTextureOverride = new EntityTextureOverrideConfig();
            this.armorGeneration = new ArmorGenerationConfig();
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
         * Returns additional Java pack paths to merge alongside
         * {@link #javaResourcePackPath()}. Each entry is interpreted exactly
         * like the singleton path: an absolute path is used verbatim, a
         * relative path is resolved against the Paper plugin's data folder,
         * and {@code .zip} archives are extracted into the managed cache
         * before scanning.
         *
         * <p>Use case: a server that runs multiple custom-item plugins
         * (e.g. ValhallaMMO + ItemsAdder + MMOItems) where each plugin
         * generates its own Java resource pack into its own folder. Listing
         * each pack here lets GeyserExtra merge all of them into a single
         * Bedrock auto-pack without the operator having to hand-merge ZIPs
         * before pointing {@code server.properties}'s {@code resource-pack}
         * at the result.</p>
         *
         * <p>Merge order: the singleton {@link #javaResourcePackPath()} is
         * scanned first, then each entry of {@code javaResourcePackPaths}
         * in declaration order. When two packs define the same
         * {@code (baseItem, custom_model_data)} key, the <b>later</b> pack
         * wins — so place higher-priority packs later in the list.</p>
         *
         * <p>Default: empty list (only the singleton path /
         * {@code server.properties} URL is used).</p>
         *
         * @return the configured additional pack paths (never {@code null})
         */
        public List<String> javaResourcePackPaths() {
            return javaResourcePackPaths != null ? javaResourcePackPaths : Collections.emptyList();
        }

        /**
         * Returns the effective list of Java pack paths to scan, combining
         * {@link #javaResourcePackPath()} (when non-blank) and every
         * non-blank entry of {@link #javaResourcePackPaths()} in declaration
         * order.
         *
         * <p>Returned list is unmodifiable; blank entries are dropped so the
         * downstream {@code JavaPackResolver} doesn't waste a no-op resolve
         * attempt on each empty string. When the result is empty the
         * resolver falls back to the {@code server.properties} URL.</p>
         */
        public List<String> effectiveJavaResourcePackPaths() {
            List<String> out = new ArrayList<>();
            String single = javaResourcePackPath();
            if (!single.isBlank()) {
                out.add(single);
            }
            for (String path : javaResourcePackPaths()) {
                if (path != null && !path.isBlank()) {
                    out.add(path);
                }
            }
            return List.copyOf(out);
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

        /**
         * Primary locale (e.g. {@code "ja_jp"}, {@code "en_us"}) used when
         * resolving {@code TranslatableComponent} display names against the
         * Java pack's {@code assets/<ns>/lang/<locale>.json} files. The
         * resolver falls back to {@code en_us} when the primary locale lacks
         * a key, matching Minecraft's client behaviour.
         *
         * <p>Default: {@code "en_us"}. Only meaningful when
         * {@link #javaResourcePackPath()} is set.</p>
         */
        public String javaPackLocale() {
            return javaPackLocale != null && !javaPackLocale.isBlank()
                ? javaPackLocale.toLowerCase()
                : "en_us";
        }

        /**
         * Whether the PDC-only custom item registration path is enabled.
         *
         * <p>When {@code true} (default), items identified by a stable
         * PersistentDataContainer key (Oraxen / ItemsAdder / MMOItems / etc.)
         * without a CustomModelData value are scanned, registered, and made
         * available as Bedrock recipe results via the {@code hasComponent}
         * predicate.</p>
         *
         * <p>Setting this to {@code false} reverts to pre-feature behaviour
         * (PDC-only items are not registered). Provided as an emergency rollback
         * lever; should not be needed under normal operation.</p>
         */
        public boolean pdcEnabled() {
            return pdcEnabled;
        }

        /**
         * Optional list of remote Java resource pack URLs to fetch and merge
         * alongside the locally-resolved packs.
         *
         * <p>Default: empty list (no remote fetching, behaviour identical to
         * pre-feature releases). When non-empty, each entry is downloaded,
         * cached, and extracted by {@code JavaPackResolver} using the same
         * pipeline as {@code server.properties} resource packs.</p>
         */
        public List<DynamicResourcePackEntry> dynamicResourcePackUrls() {
            return dynamicResourcePackUrls != null ? dynamicResourcePackUrls : Collections.emptyList();
        }

        /**
         * Returns the {@link #dynamicResourcePackUrls()} list with null entries
         * and blank URLs filtered out. Returned list is unmodifiable.
         *
         * <p>Use this from the resolver call sites to avoid wasting a no-op
         * resolve attempt on each invalid entry.</p>
         */
        public List<DynamicResourcePackEntry> effectiveDynamicResourcePackUrls() {
            List<DynamicResourcePackEntry> raw = dynamicResourcePackUrls();
            if (raw.isEmpty()) {
                return Collections.emptyList();
            }
            List<DynamicResourcePackEntry> out = new ArrayList<>(raw.size());
            for (DynamicResourcePackEntry entry : raw) {
                if (entry == null || !entry.hasValidUrl()) {
                    continue;
                }
                out.add(entry);
            }
            return List.copyOf(out);
        }

        /**
         * Returns the Bedrock attachable generation configuration.
         * Never {@code null}; missing JSON section yields a default-mode instance.
         */
        public AttachableGenerationConfig attachableGeneration() {
            return attachableGeneration != null ? attachableGeneration : new AttachableGenerationConfig();
        }

        /**
         * Returns the entity texture override configuration (Phase 7b).
         * Never {@code null}; missing JSON section yields a default-enabled instance.
         */
        public EntityTextureOverrideConfig entityTextureOverride() {
            return entityTextureOverride != null ? entityTextureOverride : new EntityTextureOverrideConfig();
        }

        /**
         * Returns the armor attachable generation configuration (Phase 7a).
         * Never {@code null}; missing JSON section yields a default-enabled instance.
         */
        public ArmorGenerationConfig armorGeneration() {
            return armorGeneration != null ? armorGeneration : new ArmorGenerationConfig();
        }
    }

    /**
     * Phase 7b: controls whether the auto-pack mirrors operator-supplied
     * entity textures from the Java pack ({@code assets/<ns>/textures/entity/**.png})
     * into the generated Bedrock pack. When enabled (default), every PNG under
     * the entity texture root is copied verbatim into {@code textures/entity/<rest>}
     * inside the Bedrock pack — Bedrock then renders matching vanilla entity
     * types with the operator's custom artwork.
     *
     * <p>Side-effect contract: when {@code enabled=false} OR no entity textures
     * exist in the Java pack(s), the generated Bedrock zip is byte-identical
     * to the pre-Phase-7b build.</p>
     */
    public static final class EntityTextureOverrideConfig {
        private final boolean enabled;

        public EntityTextureOverrideConfig() {
            this(true);
        }

        public EntityTextureOverrideConfig(boolean enabled) {
            this.enabled = enabled;
        }

        /** Whether entity texture mirroring is active. Default: true. */
        public boolean enabled() {
            return enabled;
        }
    }

    /**
     * Phase 7a: controls whether the auto-pack generates Bedrock armor
     * attachables for Java items declaring the {@code minecraft:equippable}
     * data component (Paper 1.21.4+). When enabled (default), each equippable
     * mapping gets a slot-appropriate attachable referencing the built-in
     * humanoid armor geometry so Bedrock players see the custom armor texture
     * when the item is worn.
     *
     * <p>Side-effect contract: when {@code enabled=false} OR no equippable
     * items are detected, the generated Bedrock zip is byte-identical to
     * the pre-Phase-7a build.</p>
     */
    public static final class ArmorGenerationConfig {
        private final boolean enabled;

        public ArmorGenerationConfig() {
            this(true);
        }

        public ArmorGenerationConfig(boolean enabled) {
            this.enabled = enabled;
        }

        /** Whether armor attachable generation is active. Default: true. */
        public boolean enabled() {
            return enabled;
        }
    }

    /**
     * Controls generation of Bedrock attachable / geometry / animation artifacts
     * derived from Java models. Lives under {@code customItems.attachableGeneration}
     * in the config JSON.
     *
     * <p>Modes:</p>
     * <ul>
     *   <li>{@code off} — write nothing extra. Resulting auto-pack ZIP is
     *       bit-for-bit identical to the pre-feature build. Emergency rollback.</li>
     *   <li>{@code offsets_only} (<b>default</b>) — write attachable + display
     *       transform animations. Models <em>without</em> Java {@code elements}
     *       use a flat-quad icon. Models <em>with</em> {@code elements} (3D
     *       Blockbench weapons, etc.) automatically use full 3D geometry so
     *       large hand offsets are not applied to a 2D quad (which caused
     *       floating / mis-oriented hammers).</li>
     *   <li>{@code full} — always prefer full 3D geometry from Java
     *       {@code elements} when present (same 3D path as the auto-upgrade
     *       above). Falls back to flat-quad when the model has no elements.</li>
     * </ul>
     *
     * <p><b>Default change history:</b> {@code offsets_only} stayed the default
     * for 2D icon stability; the elements auto-upgrade was added after
     * ValhallaMMO warhammers floated off-hand under pure flat-quad + display
     * offsets.</p>
     *
     * <p><b>Removed keys.</b> Five settings existed only to let an in-game
     * comparison pick between two candidate behaviours without a rebuild. Each
     * has since been settled on a real Bedrock client, so the winning value is
     * now compiled in and the key is gone. Leaving them in a
     * {@code config.json} is harmless — Gson ignores unknown keys — but they no
     * longer do anything:</p>
     * <ul>
     *   <li>{@code firstPersonTranslationFrame} → {@code zxy}. The change of
     *       basis is always done properly; java2bedrock's per-axis sign flips
     *       are no longer used for the first-person root. Restoring
     *       {@code j2b} throws the greataxe and dagger out of frame.</li>
     *   <li>{@code faceUvRotation} → {@code true}. Java per-face texture
     *       rotation is always forwarded to Bedrock {@code uv_rotation}, which
     *       pins the pack at {@code min_engine_version 1.21.0}. Bedrock clients
     *       older than 1.21.0 can no longer load the generated pack; there is
     *       no longer a fallback that keeps them in.</li>
     *   <li>{@code rainbowFirstPersonMapping} → {@code false}. The GeyserMC
     *       Rainbow single-bone mapping lost the comparison and its code path
     *       is deleted.</li>
     *   <li>{@code mirrorOffHandTranslation} → {@code true}. Both hands negate
     *       the declared X. The off-hand misplacement this was meant to control
     *       turned out to be a bone-binding bug, not a sign error.</li>
     *   <li>{@code debugDumpArtifacts} → removed outright; nothing ever read
     *       it, so no artifact dump existed to disable.</li>
     * </ul>
     */
    public static final class AttachableGenerationConfig {

        /** Disable attachable generation (zip identical to pre-feature). */
        public static final String MODE_OFF = "off";
        /** Write attachable with flat-quad geometry + display transform animation. Default. */
        public static final String MODE_OFFSETS_ONLY = "offsets_only";
        /**
         * Always prefer full 3D geometry from Java elements when present.
         * Note: {@link #MODE_OFFSETS_ONLY} already auto-upgrades to 3D when
         * elements exist; {@code full} forces that path explicitly.
         */
        public static final String MODE_FULL = "full";

        private final String mode;
        private final boolean forceFirstPersonOnly;
        private final BasePose firstPersonBasePose;
        /**
         * Boxed so an absent key falls back to
         * {@link BasePose#FIRST_PERSON_HEIGHT_TRIM} while an explicit
         * {@code 0} disables the correction.
         */
        private final Float firstPersonHeightOffset;

        public AttachableGenerationConfig() {
            this(MODE_OFFSETS_ONLY, false);
        }

        public AttachableGenerationConfig(String mode,
                                          boolean forceFirstPersonOnly) {
            this(mode, forceFirstPersonOnly, null);
        }

        public AttachableGenerationConfig(String mode,
                                          boolean forceFirstPersonOnly,
                                          BasePose firstPersonBasePose) {
            this(mode, forceFirstPersonOnly, firstPersonBasePose,
                BasePose.FIRST_PERSON_HEIGHT_TRIM);
        }

        public AttachableGenerationConfig(String mode,
                                          boolean forceFirstPersonOnly,
                                          BasePose firstPersonBasePose,
                                          float firstPersonHeightOffset) {
            this.mode = normalizeMode(mode);
            this.forceFirstPersonOnly = forceFirstPersonOnly;
            this.firstPersonBasePose = firstPersonBasePose;
            this.firstPersonHeightOffset = firstPersonHeightOffset;
        }

        /**
         * Height correction added to the Y of the first-person root position,
         * in model units (1 unit = 1 texture pixel), for <em>both</em> the flat
         * and 3D default poses. Positive values move the item toward the hand.
         *
         * <p>The stock java2bedrock and flat reference poses both float the
         * item roughly one item-height above the hand in game. The sign is
         * empirical: the first-person arm frame's Y runs opposite to the
         * third-person and head frames, so subtracting pushes the item further
         * away. See {@link BasePose#FIRST_PERSON_HEIGHT_TRIM}.</p>
         *
         * <p>Tunable via
         * {@code customItems.attachableGeneration.firstPersonHeightOffset} so
         * dialling this in costs a config edit and a restart rather than a
         * rebuild. Ignored for 3D items when the operator supplies an explicit
         * {@link #firstPersonBasePose()}, which is absolute.</p>
         */
        public float firstPersonHeightOffset() {
            return firstPersonHeightOffset != null
                ? firstPersonHeightOffset
                : BasePose.FIRST_PERSON_HEIGHT_TRIM;
        }

        private static String normalizeMode(String raw) {
            if (raw == null || raw.isBlank()) {
                return MODE_OFFSETS_ONLY;
            }
            String lower = raw.toLowerCase(java.util.Locale.ROOT);
            return switch (lower) {
                case MODE_OFF, MODE_OFFSETS_ONLY, MODE_FULL -> lower;
                default -> MODE_OFFSETS_ONLY;
            };
        }

        /**
         * The active mode: {@link #MODE_OFF}, {@link #MODE_OFFSETS_ONLY}, or
         * {@link #MODE_FULL}. Always normalised to one of these values.
         */
        public String mode() {
            return mode != null ? mode : MODE_OFFSETS_ONLY;
        }

        /**
         * If {@code true}, skip writing the {@code hold_third_person} animation
         * channel. Emergency rollback lever for the case where a buggy third-person
         * transform causes visible glitches across many items.
         */
        public boolean forceFirstPersonOnly() {
            return forceFirstPersonOnly;
        }

        /**
         * The fixed first-person base pose applied to the attachable root
         * bone. Bedrock's first-person arm frame differs from Java's
         * camera-space item frame, so this constant maps one onto the other;
         * the default is java2bedrock's empirically-tuned approximation.
         * Operators can fine-tune per server via
         * {@code customItems.attachableGeneration.firstPersonBasePose}.
         * Never {@code null}.
         */
        public BasePose firstPersonBasePose() {
            return firstPersonBasePose != null
                ? firstPersonBasePose
                : BasePose.FIRST_PERSON_DEFAULT;
        }

        /**
         * Whether the operator explicitly configured a first-person base
         * pose. When {@code true}, {@link #firstPersonBasePose()} returns that
         * override instead of {@link BasePose#FIRST_PERSON_DEFAULT}. The
         * writer applies this override only to 3D ({@code elements}) items;
         * texture-only flat items always use {@link BasePose#FIRST_PERSON_DEFAULT}
         * so CMD texture-swap swords keep vanilla hold framing.
         */
        public boolean hasExplicitFirstPersonBasePose() {
            return firstPersonBasePose != null;
        }

        /**
         * A fixed bone pose (rotation degrees, position pixels, uniform scale)
         * for attachable base-pose tuning. Missing JSON fields fall back to
         * the java2bedrock first-person defaults.
         */
        public static final class BasePose {

            /**
             * Default for {@link AttachableGenerationConfig#firstPersonHeightOffset()}:
             * the correction that pulls the first-person item back down toward
             * the hand, in model units (1 unit = 1 texture pixel). <b>Added</b>
             * to the root position's Y.
             *
             * <p>Both java2bedrock's 3D pose and this project's flat pose sat
             * about one item-height above the hand. The value is bisected from
             * in-game reports, not computed: {@code 0} read as one item too
             * high; {@code 16} (one item-height, matching the observed gap),
             * {@code 8} and {@code 4} clearly too low, then {@code 3} slightly
             * low, {@code 2} and {@code 2.5} too high. Settled on {@code 2.7}
             * by the operator's call. The usable range is under one unit wide
             * and the root carries a {@code [90,60,-40]} rotation, so this Y is
             * not screen-vertical -- one screen item-height is far less than 16
             * units of it, which is why estimating the offset from the apparent
             * gap overshoots, and why there is no gap-to-units conversion to
             * derive the value from.</p>
             *
             * <p><b>The sign is empirical, not derived.</b> This constant was
             * first applied as a subtraction on the assumption that +Y is
             * screen-up in the bound {@code rightitem} bone's frame — the way
             * it reads in the third-person ({@code y = 13}) and head
             * ({@code y = 19.9}) poses. In game the item moved <i>further</i>
             * above the hand, so in the first-person arm frame the axis runs
             * the other way and the correction is additive. Anchoring poses off
             * the third-person frame is therefore unsafe; only in-game
             * observation settles first-person.</p>
             *
             * <p>Kept as one named constant so the flat and 3D poses cannot
             * drift apart — they were reported equally high, which is only
             * consistent with a shared cause.</p>
             */
            public static final float FIRST_PERSON_HEIGHT_TRIM = 2.7f;

            private static final float[] DEFAULT_ROTATION = {90f, 60f, -40f};
            /**
             * Kas-tle java2bedrock.sh first-person root position, lowered by
             * {@link #FIRST_PERSON_HEIGHT_TRIM} on Y.
             *
             * <p>Left at the stock reference value. The in-game height
             * correction is applied at build time from
             * {@link AttachableGenerationConfig#firstPersonHeightOffset()} so
             * one operator-tunable number moves the flat and 3D poses
             * together.</p>
             */
            private static final float[] DEFAULT_POSITION = {4f, 10f, 4f};
            /**
             * Kas-tle java2bedrock.sh first-person root scale. Oversized items
             * lerp toward {@code base * 0.75} in {@code BedrockAttachableWriter}
             * (greataxe at display scale 1.7).
             */
            private static final float DEFAULT_SCALE = 1.5f;

            /** java2bedrock's first-person main-hand constants. */
            public static final BasePose FIRST_PERSON_DEFAULT =
                new BasePose(DEFAULT_ROTATION.clone(), DEFAULT_POSITION.clone(), DEFAULT_SCALE);

            private final float[] rotation;
            private final float[] position;
            private final float scale;

            public BasePose(float[] rotation, float[] position, float scale) {
                this.rotation = rotation;
                this.position = position;
                this.scale = scale;
            }

            public float[] rotation() {
                return normalized(rotation, DEFAULT_ROTATION);
            }

            public float[] position() {
                return normalized(position, DEFAULT_POSITION);
            }

            public float scale() {
                return scale > 0f ? scale : DEFAULT_SCALE;
            }

            private static float[] normalized(float[] value, float[] fallback) {
                if (value == null || value.length != 3) {
                    return fallback.clone();
                }
                return value.clone();
            }
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
