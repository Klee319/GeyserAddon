package com.geyserextra.paper.pack;

import com.geyserextra.core.config.GeyserExtraConfig;
import com.geyserextra.core.util.JsonUtil;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Reads an unzipped Java edition resource pack and extracts {@code (baseItem,
 * custom_model_data)} -> texture-file mappings so that the companion
 * {@link AutoBedrockPackBuilder} can mirror the operator's Java textures into
 * the auto-generated Bedrock resource pack.
 *
 * <p><b>Scope:</b> 2D textures only. 3D model geometry, attachables,
 * animations, and block models are out of scope - they remain the job of
 * external tools such as Kas-tle's {@code java2bedrock} converter (see README).
 * Bedrock players see custom textures applied to the vanilla model shape;
 * Java's custom geometry is not transferred.</p>
 *
 * <p><b>Supported formats:</b>
 * <ul>
 *   <li>{@code LEGACY} (1.20.x - 1.21.3): reads
 *       {@code assets/<ns>/models/item/<base>.json} with an {@code overrides[]}
 *       array of {@code {predicate:{custom_model_data:N}, model:"<ref>"}}
 *       entries.</li>
 *   <li>{@code MODERN} (1.21.4+): reads {@code assets/<ns>/items/<name>.json}
 *       containing a {@code model.type == "range_dispatch"} block with
 *       {@code property == "custom_model_data"} and an {@code entries[]}
 *       array.</li>
 *   <li>{@code AUTO} (default): tries both formats and merges results; if both
 *       define the same key, MODERN wins.</li>
 * </ul></p>
 *
 * <p><b>Texture resolution chain:</b>
 * <pre>
 *   base model JSON (overrides) -> custom model JSON (textures.layer0) -> PNG file
 *   modern items JSON (range_dispatch) -> model ref -> textures.layer0 -> PNG file
 * </pre>
 * The resolved {@link Path} is the absolute on-disk PNG file; reading bytes is
 * left to the caller to keep this class side-effect free.</p>
 */
public final class JavaPackReader {

    private static final String DEFAULT_NAMESPACE = "minecraft";

    private final Path packRoot;
    private final String formatHint;
    private final Logger logger;
    private final boolean debug;

    /**
     * @param packRoot   path to the unzipped Java pack root directory (the folder
     *                   containing {@code pack.mcmeta} and {@code assets/}); must
     *                   not be null
     * @param formatHint one of {@link GeyserExtraConfig.CustomItemsConfig#JAVA_PACK_FORMAT_AUTO},
     *                   {@code JAVA_PACK_FORMAT_LEGACY}, {@code JAVA_PACK_FORMAT_MODERN}
     * @param logger     plugin logger for diagnostic output
     * @param debug      whether to emit verbose per-file logs
     */
    public JavaPackReader(Path packRoot, String formatHint, Logger logger, boolean debug) {
        this.packRoot = Objects.requireNonNull(packRoot, "packRoot must not be null");
        this.formatHint = formatHint != null
            ? formatHint
            : GeyserExtraConfig.CustomItemsConfig.JAVA_PACK_FORMAT_AUTO;
        this.logger = Objects.requireNonNull(logger, "logger must not be null");
        this.debug = debug;
    }

    /**
     * Scans the pack and returns every detected {@code (baseItem, CMD)} ->
     * {@link JavaModelDefinition} mapping.
     *
     * <p>Returns an empty map if the pack root is invalid or no overrides are
     * found. Errors on individual JSON files are logged and skipped so a single
     * malformed file cannot abort the entire scan.</p>
     */
    public Map<CmdKey, JavaModelDefinition> scan() {
        if (!Files.isDirectory(packRoot)) {
            logger.warning("[JavaPack] not a directory: " + packRoot);
            return Map.of();
        }
        Path assets = packRoot.resolve("assets");
        if (!Files.isDirectory(assets)) {
            logger.warning("[JavaPack] missing assets/ under " + packRoot);
            return Map.of();
        }

        Map<CmdKey, JavaModelDefinition> result = new HashMap<>();
        // Counters surface per-pass attempt/resolve numbers in the final
        // summary log line so operators can tell at a glance whether the
        // scanner found CMD entries but failed to resolve their textures
        // (X attempted, 0 resolved) vs. didn't find any candidates at all
        // (0 attempted). Reset on each scan() call.
        modernAttempted = 0;
        modernResolved = 0;
        legacyAttempted = 0;
        legacyResolved = 0;
        // Build the basename -> path index up-front. One walk of every
        // assets/<ns>/textures/ subtree amortises every later
        // deep-fallback lookup to O(1). Doing this lazily on first miss
        // would still pay the walk cost but spread it across per-CMD
        // log lines.
        textureBasenameIndex = buildTextureBasenameIndex(assets);

        boolean wantLegacy = !GeyserExtraConfig.CustomItemsConfig.JAVA_PACK_FORMAT_MODERN.equals(formatHint);
        boolean wantModern = !GeyserExtraConfig.CustomItemsConfig.JAVA_PACK_FORMAT_LEGACY.equals(formatHint);

        for (Path namespaceDir : listSubdirectories(assets)) {
            String namespace = namespaceDir.getFileName().toString();

            if (wantLegacy) {
                scanLegacyNamespace(namespace, namespaceDir, result);
            }
            if (wantModern) {
                // MODERN entries overwrite LEGACY ones for the same key under AUTO.
                scanModernNamespace(namespace, namespaceDir, result);
            }
        }

        logger.fine("[JavaPack] scanned " + result.size()
            + " custom_model_data entries from " + packRoot.getFileName()
            + " (modern: " + modernResolved + "/" + modernAttempted + " resolved, "
            + "legacy: " + legacyResolved + "/" + legacyAttempted + " resolved)");
        return result;
    }

    /**
     * Resolves direct 1.21.4+ item-model definitions. The returned key is the
     * value stored in the Java {@code minecraft:item_model} component.
     */
    public Map<String, JavaModelDefinition> scanDirectItemModels() {
        Path assets = packRoot.resolve("assets");
        if (!Files.isDirectory(assets)) {
            return Map.of();
        }
        if (textureBasenameIndex.isEmpty()) {
            textureBasenameIndex = buildTextureBasenameIndex(assets);
        }
        Map<String, JavaModelDefinition> result = new HashMap<>();
        for (Path namespaceDir : listSubdirectories(assets)) {
            String namespace = namespaceDir.getFileName().toString();
            Path itemsDir = namespaceDir.resolve("items");
            if (!Files.isDirectory(itemsDir)) {
                continue;
            }
            for (Path jsonFile : listJsonFilesRecursive(itemsDir)) {
                try {
                    Map<String, Object> root = readJsonObject(jsonFile);
                    Object rawModel = root.get("model");
                    if (!(rawModel instanceof Map<?, ?>)) {
                        continue;
                    }
                    List<String> modelRefs = new ArrayList<>();
                    collectModelRefs(rawModel, jsonFile.toString(),
                        modelRefs, 0);
                    if (modelRefs.isEmpty()) {
                        continue;
                    }
                    String relative = itemsDir.relativize(jsonFile).toString()
                        .replace('\\', '/');
                    relative = stripJsonExtension(relative);
                    String itemModelId = namespace + ":" + relative;
                    JavaModelDefinition resolved = null;
                    for (String modelRef : modelRefs) {
                        resolved = resolveSingleModelRef(
                            itemModelId, 0, modelRef);
                        if (resolved != null) {
                            break;
                        }
                    }
                    if (resolved != null) {
                        result.put(itemModelId, resolved);
                    }
                } catch (Exception ex) {
                    logger.log(Level.WARNING,
                        "[JavaPack] failed to parse direct item model "
                            + jsonFile + ": " + ex.getMessage(), ex);
                }
            }
        }
        return Map.copyOf(result);
    }

    // Per-scan counters; reset at the top of scan() and incremented inside
    // each parse method. Not thread-safe — scan() is intended to be invoked
    // from the main thread or with external synchronisation.
    private int modernAttempted;
    private int modernResolved;
    private int legacyAttempted;
    private int legacyResolved;

    // Built once at the top of scan() and consulted as the last resort by
    // resolveSingleModelRef() when both the model-JSON chain and the
    // model-name-as-texture-path fallback fail. Maps a PNG basename (with
    // case lowered and ".png" stripped) to the first {@link Path} found
    // during a recursive walk of every assets/<ns>/textures/ subtree. The
    // "first found" tie-breaker is deterministic per pack content because
    // Files.walk visits the tree in NIO's documented order.
    //
    // Why first-wins: a multi-namespace pack may legitimately ship two
    // PNGs with the same basename (e.g. a minecraft override + a custom
    // namespace icon). Without source-of-truth in the model JSON we can't
    // tell which is "right", so we pick deterministically rather than
    // making the resolution outcome depend on filesystem ordering.
    private Map<String, Path> textureBasenameIndex = Map.of();

    // ========================================================================
    // Legacy: models/item/<base>.json with overrides[]
    // ========================================================================

    private void scanLegacyNamespace(
        String namespace,
        Path namespaceDir,
        Map<CmdKey, JavaModelDefinition> out
    ) {
        Path itemsDir = namespaceDir.resolve("models").resolve("item");
        if (!Files.isDirectory(itemsDir)) {
            return;
        }
        for (Path jsonFile : listJsonFiles(itemsDir)) {
            try {
                parseLegacyItemModel(namespace, jsonFile, out);
            } catch (Exception ex) {
                logger.log(Level.WARNING,
                    "[JavaPack] failed to parse " + jsonFile + ": " + ex.getMessage(), ex);
            }
        }
    }

    private void parseLegacyItemModel(
        String namespace,
        Path jsonFile,
        Map<CmdKey, JavaModelDefinition> out
    ) throws IOException {
        Map<String, Object> root = readJsonObject(jsonFile);
        Object overridesObj = root.get("overrides");
        if (!(overridesObj instanceof List<?> overrides) || overrides.isEmpty()) {
            return;
        }

        String baseFileName = stripJsonExtension(jsonFile.getFileName().toString());
        String baseItem = namespace + ":" + baseFileName;

        for (Object entryObj : overrides) {
            if (!(entryObj instanceof Map<?, ?> entry)) {
                continue;
            }
            Object predicateObj = entry.get("predicate");
            if (!(predicateObj instanceof Map<?, ?> predicate)) {
                continue;
            }
            Object cmdObj = predicate.get("custom_model_data");
            if (!(cmdObj instanceof Number cmdNumber)) {
                continue;
            }
            Object modelObj = entry.get("model");
            if (!(modelObj instanceof String modelRef) || modelRef.isBlank()) {
                continue;
            }

            int cmd = cmdNumber.intValue();
            if (cmd <= 0) {
                // Skip CMD <= 0 entries: a Geyser custom-item registered with
                // predicate-less CMD 0 would override the vanilla base item
                // itself ("Custom item ... overrides the vanilla item model ...
                // without additional predicates"), replacing the base material's
                // texture with our texture for every player. Vanilla packs
                // occasionally ship a CMD 0 entry as the "fallback model", so
                // dropping it here is the correct behaviour for our use case.
                if (debug) {
                    logger.fine("[JavaPack-legacy] skipped CMD<=0 override on "
                        + baseItem + " (would clobber the vanilla item itself)");
                }
                continue;
            }
            legacyAttempted++;
            JavaModelDefinition resolved = resolveSingleModelRef(baseItem, cmd, modelRef);
            if (resolved == null) {
                if (debug) {
                    logger.fine("[JavaPack-legacy] no texture resolved for "
                        + baseItem + " CMD=" + cmd + " -> " + modelRef);
                }
                continue;
            }

            CmdKey key = new CmdKey(baseItem, cmd);
            out.put(key, resolved);
            legacyResolved++;
            if (debug) {
                logger.fine("[JavaPack-legacy] " + key + " -> " + resolved.textureFile());
            }
        }
    }

    // ========================================================================
    // Modern: items/<name>.json with model.type == range_dispatch
    // ========================================================================

    private void scanModernNamespace(
        String namespace,
        Path namespaceDir,
        Map<CmdKey, JavaModelDefinition> out
    ) {
        Path itemsDir = namespaceDir.resolve("items");
        if (!Files.isDirectory(itemsDir)) {
            return;
        }
        for (Path jsonFile : listJsonFiles(itemsDir)) {
            try {
                parseModernItemDefinition(namespace, jsonFile, out);
            } catch (Exception ex) {
                logger.log(Level.WARNING,
                    "[JavaPack] failed to parse " + jsonFile + ": " + ex.getMessage(), ex);
            }
        }
    }

    private void parseModernItemDefinition(
        String namespace,
        Path jsonFile,
        Map<CmdKey, JavaModelDefinition> out
    ) throws IOException {
        Map<String, Object> root = readJsonObject(jsonFile);
        Object modelObj = root.get("model");
        if (modelObj == null) {
            return;  // file just doesn't have a model definition — not actionable
        }
        if (!(modelObj instanceof Map<?, ?> model)) {
            if (debug) {
                logger.fine("[JavaPack-modern] " + jsonFile.getFileName()
                    + ": 'model' is not an object (" + modelObj.getClass().getSimpleName()
                    + ") — skipped");
            }
            return;
        }
        Object typeObj = model.get("type");
        if (!"range_dispatch".equals(typeObj) && !"minecraft:range_dispatch".equals(typeObj)) {
            // Most item files use other types (model, select, etc.) and aren't CMD overrides.
            // Debug-only because logging every non-CMD file would spam warnings.
            return;
        }
        Object propertyObj = model.get("property");
        if (!"custom_model_data".equals(propertyObj)
            && !"minecraft:custom_model_data".equals(propertyObj)) {
            // range_dispatch is present but on a different property (e.g. "damage").
            // Worth warning because this is unusual and the operator may have intended
            // custom_model_data.
            logger.warning("[JavaPack-modern] " + jsonFile.getFileName()
                + ": range_dispatch on property '" + propertyObj
                + "' (expected 'custom_model_data') — skipped");
            return;
        }
        Object entriesObj = model.get("entries");
        if (!(entriesObj instanceof List<?> entries)) {
            logger.warning("[JavaPack-modern] " + jsonFile.getFileName()
                + ": range_dispatch.entries is not an array (got "
                + (entriesObj == null ? "null" : entriesObj.getClass().getSimpleName())
                + ") — skipped");
            return;
        }

        String baseFileName = stripJsonExtension(jsonFile.getFileName().toString());
        String baseItem = namespace + ":" + baseFileName;

        for (Object entryObj : entries) {
            if (!(entryObj instanceof Map<?, ?> entry)) {
                continue;
            }
            Object thresholdObj = entry.get("threshold");
            if (!(thresholdObj instanceof Number thresholdNumber)) {
                continue;
            }
            Object innerModelObj = entry.get("model");
            int cmd = thresholdNumber.intValue();
            if (cmd <= 0) {
                // See parseLegacyItemModel for the rationale: CMD 0 entries
                // would override the base vanilla item itself, breaking
                // every player's view of the underlying material.
                if (debug) {
                    logger.fine("[JavaPack-modern] skipped CMD<=0 entry on "
                        + baseItem + " (would clobber the vanilla item itself)");
                }
                continue;
            }
            String contextLabel = baseItem + "#" + cmd;
            modernAttempted++;
            List<String> modelRefs = new ArrayList<>();
            collectModelRefs(innerModelObj, contextLabel, modelRefs, 0);
            if (modelRefs.isEmpty()) {
                if (debug) {
                    logger.fine("[JavaPack-modern] no model refs collected for "
                        + contextLabel);
                }
                continue;
            }

            // Try each candidate model ref in declaration order and accept
            // the first one whose chain resolves to an actual on-disk PNG.
            // Why iterate rather than take the first: composite / condition /
            // select wrappers expose several refs and only some may resolve
            // (e.g. a condition's on_true branch may reference a vanilla
            // model with no override while on_false has the custom texture
            // we want).
            JavaModelDefinition resolved = null;
            String lastTriedRef = null;
            for (String modelRef : modelRefs) {
                if (modelRef == null || modelRef.isBlank()) continue;
                lastTriedRef = modelRef;
                resolved = resolveSingleModelRef(baseItem, cmd, modelRef);
                if (resolved != null) {
                    break;
                }
            }
            if (resolved == null) {
                if (debug) {
                    logger.fine("[JavaPack-modern] no texture resolved for "
                        + contextLabel + " (" + modelRefs.size()
                        + " model ref(s) tried, last=" + lastTriedRef + ")");
                }
                continue;
            }

            CmdKey key = new CmdKey(baseItem, cmd);
            out.put(key, resolved);
            modernResolved++;
            if (debug) {
                logger.fine("[JavaPack-modern] " + key + " -> " + resolved.textureFile());
            }
        }
    }

    /** Recursion depth cap for {@link #collectModelRefs} to defend against pathological pack structures. */
    private static final int MODEL_REF_RECURSION_LIMIT = 12;

    /**
     * Recursively collects every model reference embedded in a 1.21.4+
     * modern item-definition {@code model} block.
     *
     * <p>Supported wrappers (Mojang model types, namespaced or bare):
     * <ul>
     *   <li>{@code "model"} — leaf, takes the {@code model} string.</li>
     *   <li>{@code "condition"} — visits {@code on_false} <b>only</b>.
     *       The {@code on_true} branch is the transient / in-use state
     *       (drawing a bow, casting a fishing rod, opening an item)
     *       and Bedrock has no equivalent of the predicate that drives
     *       it, so it can only display a single static frame. Walking
     *       on_true and silently substituting its texture when on_false
     *       fails to resolve produces visibly wrong results — e.g.
     *       ValhallaMMO's skill icons (bow#1 = skillicon_archery,
     *       fishing_rod#1 = skillicon_fishing, anvil#1 = skillicon_smithing,
     *       enchanted_book#1 = skillicon_enchanting) whose icon PNGs are
     *       missing from the Java pack would fall through to the
     *       bow_pulling / fishing_rod_cast frame and look "buggy" on
     *       Bedrock. Dropping the entry instead lets the Bedrock client
     *       render the base material's vanilla texture, matching what a
     *       Java player without the pack would see.</li>
     *   <li>{@code "composite"} — visits every entry of {@code models[]};
     *       composite layers stack textures so any layer can carry the
     *       custom artwork we want.</li>
     *   <li>{@code "select"} — visits {@code fallback} first, then every
     *       {@code cases[].model}.</li>
     *   <li>{@code "range_dispatch"} — visits {@code fallback} first, then
     *       every {@code entries[].model}. Nested under the top-level
     *       range_dispatch this becomes a tiered CMD.</li>
     *   <li>Bare string — treated as a direct model reference.</li>
     *   <li>Map with {@code model} field but no explicit {@code type} —
     *       treated as a leaf model reference for backward compatibility.</li>
     * </ul>
     * Unknown types are logged once at debug and skipped — callers fall
     * back through the other collected refs.</p>
     *
     * <p>Refs are appended to {@code out} in declaration order so the
     * caller can iterate "preferred → fallback".</p>
     */
    private void collectModelRefs(Object obj, String contextLabel, List<String> out, int depth) {
        if (obj == null) return;
        if (depth >= MODEL_REF_RECURSION_LIMIT) {
            if (debug) {
                logger.fine("[JavaPack] model-ref recursion depth limit hit for "
                    + contextLabel + " — remaining branches skipped");
            }
            return;
        }
        if (obj instanceof String s) {
            if (!s.isBlank()) {
                out.add(s);
            }
            return;
        }
        if (!(obj instanceof Map<?, ?> map)) {
            return;
        }

        Object typeObj = map.get("type");
        String type = typeObj == null ? null : String.valueOf(typeObj);
        String typeKey = type == null ? null : stripNamespace(type);

        if (typeKey == null || "model".equals(typeKey)) {
            // Either a typeless wrapper (legacy/loose shape) or an explicit
            // {type:"model", model:"<ref>"} leaf.
            Object modelRef = map.get("model");
            if (modelRef instanceof String s && !s.isBlank()) {
                out.add(s);
            } else if (modelRef != null) {
                // Non-string nested object — keep walking in case it's
                // another wrapper rather than the expected leaf.
                collectModelRefs(modelRef, contextLabel, out, depth + 1);
            }
            return;
        }

        switch (typeKey) {
            case "condition" -> {
                // Only on_false: it's the steady-state branch. on_true is
                // transient (using item / drawing bow / casting rod) and
                // Bedrock can't mirror the predicate, so substituting its
                // texture when on_false fails produces wrong icons (e.g.
                // bow_pulling frame instead of the missing skillicon_*).
                // See the javadoc above for the full rationale.
                collectModelRefs(map.get("on_false"), contextLabel, out, depth + 1);
            }
            case "composite" -> {
                Object models = map.get("models");
                if (models instanceof List<?> list) {
                    for (Object sub : list) {
                        collectModelRefs(sub, contextLabel, out, depth + 1);
                    }
                }
            }
            case "select" -> {
                collectModelRefs(map.get("fallback"), contextLabel, out, depth + 1);
                Object cases = map.get("cases");
                if (cases instanceof List<?> caseList) {
                    for (Object c : caseList) {
                        if (c instanceof Map<?, ?> caseMap) {
                            collectModelRefs(caseMap.get("model"), contextLabel, out, depth + 1);
                        }
                    }
                }
            }
            case "range_dispatch" -> {
                collectModelRefs(map.get("fallback"), contextLabel, out, depth + 1);
                Object entries = map.get("entries");
                if (entries instanceof List<?> entryList) {
                    for (Object e : entryList) {
                        if (e instanceof Map<?, ?> entryMap) {
                            collectModelRefs(entryMap.get("model"), contextLabel, out, depth + 1);
                        }
                    }
                }
            }
            default -> {
                // Unknown wrapper type. Drop to debug so a pack with a brand-new
                // model type (added in a future Minecraft release) doesn't spam
                // warnings for every CMD entry that uses it. The caller's "any
                // ref resolves?" loop will simply not find a texture and skip
                // the entry, which is the desired conservative behaviour.
                if (debug) {
                    logger.fine("[JavaPack] unsupported model type '" + type
                        + "' for " + contextLabel + " — branch skipped");
                }
            }
        }
    }

    /**
     * Strips the {@code minecraft:} (or other) namespace prefix from a model
     * type string so the switch above can match on the bare local name. A
     * {@code null} input returns {@code null}.
     */
    private static String stripNamespace(String type) {
        int colon = type.indexOf(':');
        return colon >= 0 ? type.substring(colon + 1) : type;
    }

    // ========================================================================
    // Texture reference resolution
    // ========================================================================

    /**
     * Resolves a single {@code modelRef} (e.g. {@code "myns:item/sword"}) to a
     * usable {@link JavaModelDefinition} by trying, in order:
     * <ol>
     *   <li><b>Model JSON chain</b>: read the model file, walk the
     *       {@code parent} chain, pick a {@code textures.layerN} (or any
     *       non-{@code #} entry) and verify the referenced PNG exists.</li>
     *   <li><b>Model-name-as-texture-path fallback</b>: when the model JSON
     *       is missing entirely, doesn't declare a {@code textures} map, or
     *       declares only template (#) placeholders, try the model ref's
     *       path verbatim as a texture path (swap {@code models/} for
     *       {@code textures/} and {@code .json} for {@code .png}).
     *       Handles the common 2D-icon convention where pack authors ship
     *       just a PNG under {@code model_name == texture_name}.</li>
     *   <li><b>Basename index lookup</b> (last resort): consult the
     *       pre-built {@link #textureBasenameIndex} for any PNG with the
     *       same final filename as {@code modelRef}, regardless of
     *       directory. Catches packs that ship the texture under a
     *       different {@code textures/...} subdirectory than the
     *       {@code models/...} layout suggests, or under a different
     *       namespace than the modelRef implies.</li>
     * </ol>
     * Returns {@code null} when none of the three paths produces an
     * on-disk PNG. When {@code debug} is on and all three paths fail,
     * the method logs the exact filesystem paths it tried so an operator
     * can diff against their actual pack layout.
     */
    private JavaModelDefinition resolveSingleModelRef(String baseItem, int cmd, String modelRef) {
        // Phase 3/4: resolve display + elements once up-front. They are
        // independent of texture resolution and benign on failure, so do
        // them before any of the texture-resolution branches and reuse the
        // result regardless of which texture path wins.
        JavaModelDisplay display = resolveDisplayFromModelSafe(modelRef);
        JavaModelGeometry geometry = resolveElementsFromModelSafe(modelRef);
        // Only 3D models can carry per-face texture references, so skip the
        // extra parent-chain walk for the far more common flat items.
        Map<String, Path> textureFiles =
            (geometry != null && geometry.hasElements())
                ? resolveTextureMapSafe(modelRef)
                : Map.of();

        // Step 1: model JSON chain -> textures.layerN -> PNG file
        String textureRef = resolveTextureRefFromModel(modelRef);
        if (textureRef != null) {
            Path texturePath = resolveTextureFile(textureRef);
            if (texturePath != null) {
                return new JavaModelDefinition(baseItem, cmd, modelRef, textureRef, texturePath, display, geometry, textureFiles);
            }
        }

        // Step 2: model-name-as-texture-path
        Path direct = resolveTextureFile(modelRef);
        if (direct != null) {
            if (debug) {
                logger.fine("[JavaPack] " + baseItem + "#" + cmd
                    + ": resolved via model-name-as-texture-path fallback for " + modelRef);
            }
            return new JavaModelDefinition(baseItem, cmd, modelRef, modelRef, direct, display, geometry, textureFiles);
        }

        // Step 3: basename index lookup
        String basename = extractBasename(modelRef);
        Path indexed = basename != null ? textureBasenameIndex.get(basename) : null;
        if (indexed != null) {
            if (debug) {
                logger.fine("[JavaPack] " + baseItem + "#" + cmd
                    + ": resolved via basename-index fallback (modelRef=" + modelRef
                    + " -> " + indexed + ")");
            }
            return new JavaModelDefinition(baseItem, cmd, modelRef,
                "basename:" + basename, indexed, display, geometry, textureFiles);
        }

        // All three paths failed: emit a single diagnostic line listing
        // every filesystem path we tried so an operator can see at a
        // glance what was missing. Only at debug=true because otherwise
        // a pack with hundreds of missing entries would spam the log.
        if (debug) {
            String[] parts = splitNamespacedKey(modelRef);
            Path expectedModel = packRoot.resolve("assets").resolve(parts[0])
                .resolve("models").resolve(parts[1] + ".json");
            Path expectedTexture = packRoot.resolve("assets").resolve(parts[0])
                .resolve("textures").resolve(parts[1] + ".png");
            logger.fine("[JavaPack] " + baseItem + "#" + cmd
                + ": all resolution paths failed for modelRef=" + modelRef
                + " — tried model JSON at " + expectedModel
                + " | direct texture at " + expectedTexture
                + " | basename '" + basename + "' (no index hit)");
        }
        return null;
    }

    /**
     * Walks every {@code assets/<ns>/textures/} subtree once and builds a
     * {@code basename -> first-found PNG Path} map. Empty when the walk
     * fails (logged at WARNING) so deep-fallback lookups become no-ops
     * rather than NPEs.
     *
     * <p>Why basename (not full relative path): the deep fallback runs
     * after the relative-path attempts already missed, so by definition
     * we do not know the right subdirectory. Basename is the only stable
     * key left.</p>
     */
    private Map<String, Path> buildTextureBasenameIndex(Path assets) {
        Map<String, Path> out = new HashMap<>();
        try (Stream<Path> walk = Files.walk(assets)) {
            walk.filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString()
                    .toLowerCase(Locale.ROOT).endsWith(".png"))
                .forEach(p -> {
                    String name = p.getFileName().toString();
                    // Case-insensitive on Windows; pack refs on disk are
                    // canonically lowercase per Mojang convention so this
                    // lower also normalises any oddball capitalisation.
                    String basename = name.substring(0, name.length() - ".png".length())
                        .toLowerCase(Locale.ROOT);
                    out.putIfAbsent(basename, p);
                });
        } catch (IOException ex) {
            logger.log(Level.WARNING,
                "[JavaPack] basename-index walk failed under " + assets
                    + " — deep fallback disabled this scan", ex);
        }
        if (debug) {
            logger.fine("[JavaPack] basename index built: " + out.size()
                + " unique PNG basenames under " + assets);
        }
        return Map.copyOf(out);
    }

    /**
     * Returns the last path segment of {@code ref} (everything after the
     * final {@code /}), lowercased so it matches keys in
     * {@link #textureBasenameIndex}. Returns {@code null} when the input
     * is null or blank.
     */
    private static String extractBasename(String ref) {
        if (ref == null || ref.isBlank()) return null;
        int slash = ref.lastIndexOf('/');
        String last = slash >= 0 ? ref.substring(slash + 1) : ref;
        return last.toLowerCase(Locale.ROOT);
    }

    /**
     * Reads the referenced model JSON, walks the {@code parent} chain to find
     * a {@code textures.layer0} (or {@code layerN}) entry, and returns the raw
     * texture reference (e.g. {@code "mymod:items/fire_sword"}).
     *
     * <p>Returns {@code null} when no layered texture key is present anywhere
     * in the chain.</p>
     *
     * <p>Why only {@code layerN}: Java item model JSONs reserve {@code layer0}
     * (and optionally {@code layer1..layer9}) for the rendered icon texture.
     * Other keys ({@code particle}, custom {@code #var} placeholders) are
     * never the icon, and falling back to "any non-{@code #} string" — which
     * earlier revisions did — caused the reader to mis-pick a particle
     * texture for items whose model JSON happened to list one before any
     * layer slot.</p>
     */
    /**
     * Walks the {@code parent} chain rooted at {@code modelRef} and accumulates
     * any {@code display} slots declared by Mojang's per-slot merge rules: a
     * child's slot overrides the parent's, but slots the child does not
     * declare keep the parent's value. Returns a {@link JavaModelDisplay} when
     * at least one slot resolved, otherwise {@code null}.
     *
     * <p>Vanilla {@code item/handheld} / {@code item/generated} defaults live
     * inside the Mojang client jar and are not present in operator packs.
     * When the parent walk hits an unresolved ref of those names,
     * {@link VanillaBuiltinDisplays} supplies the official slot values so
     * CMD items that only declare {@code "parent": "item/handheld"} still
     * produce a correct held-item attachable (Geyser custom IDs cannot fall
     * back to Bedrock's vanilla sword pose).</p>
     *
     * <p>Made public so {@code AutoBedrockPackBuilder} can call it directly if
     * it wants per-item display extraction without going through the full
     * scan pipeline.</p>
     */
    /**
     * Internal wrapper around {@link #resolveDisplayFromModel(String)} that
     * swallows any exception thrown during resolution. Used at the resolver
     * call site so a single malformed model JSON cannot abort the whole scan
     * — failures are logged at FINE and the affected entry simply lands in
     * the registry with {@code display == null}.
     */
    private JavaModelDisplay resolveDisplayFromModelSafe(String modelRef) {
        try {
            return resolveDisplayFromModel(modelRef);
        } catch (RuntimeException ex) {
            if (debug) {
                logger.log(Level.FINE,
                    "[JavaPack] display extraction threw for " + modelRef, ex);
            }
            return null;
        }
    }

    /**
     * Internal wrapper around {@link #resolveElementsFromModel(String)} with
     * the same defensive semantics as {@link #resolveDisplayFromModelSafe}:
     * extraction failures fall back to {@code null} rather than crashing the
     * scan.
     */
    private JavaModelGeometry resolveElementsFromModelSafe(String modelRef) {
        try {
            return resolveElementsFromModel(modelRef);
        } catch (RuntimeException ex) {
            if (debug) {
                logger.log(Level.FINE,
                    "[JavaPack] elements extraction threw for " + modelRef, ex);
            }
            return null;
        }
    }

    /**
     * Resolves the model's <b>whole</b> {@code textures} map to on-disk PNGs,
     * keyed by the variable name without its {@code #} (so {@code "#1"} in a
     * face looks up {@code "1"}).
     *
     * <p>The single {@code textureFile} the rest of the pipeline uses is the
     * model's icon layer; a model built from {@code elements} may additionally
     * reference {@code #1} / {@code #2} on individual faces, and rendering
     * those with the icon layer paints the wrong artwork. Unlike
     * {@code elements}, Mojang <i>does</i> merge {@code textures} down the
     * parent chain, with the child winning, so the walk keeps going after a
     * hit and only fills in keys it has not already seen.</p>
     *
     * <p>Values may themselves be variable references ({@code "#layer0"}),
     * so each is followed until it reaches a real path. Unresolvable entries
     * are simply absent from the result — callers fall back to the primary
     * texture. Never throws.</p>
     */
    private Map<String, Path> resolveTextureMapSafe(String modelRef) {
        try {
            Map<String, String> refs = new LinkedHashMap<>();
            String current = modelRef;
            int hops = 0;
            while (current != null && hops < 8) {
                Path modelFile = resolveModelFile(current);
                if (modelFile == null) {
                    break;
                }
                Map<String, Object> modelJson;
                try {
                    modelJson = readJsonObject(modelFile);
                } catch (IOException ex) {
                    break;
                }
                if (modelJson.get("textures") instanceof Map<?, ?> textures) {
                    for (Map.Entry<?, ?> e : textures.entrySet()) {
                        if (e.getKey() instanceof String k && e.getValue() instanceof String v) {
                            refs.putIfAbsent(k, v);
                        }
                    }
                }
                if (!(modelJson.get("parent") instanceof String parentRef)
                    || parentRef.isBlank()) {
                    break;
                }
                current = parentRef;
                hops++;
            }

            Map<String, Path> out = new LinkedHashMap<>();
            for (Map.Entry<String, String> e : refs.entrySet()) {
                String value = e.getValue();
                // Follow "#other" indirection; the bound of 8 mirrors the
                // parent-chain limit and stops a self-referential pack looping.
                int chase = 0;
                while (value != null && value.startsWith("#") && chase < 8) {
                    value = refs.get(value.substring(1));
                    chase++;
                }
                if (value == null || value.isBlank() || value.startsWith("#")) {
                    continue;
                }
                Path resolved = resolveTextureFile(value);
                if (resolved != null) {
                    out.put(e.getKey(), resolved);
                }
            }
            return out;
        } catch (RuntimeException ex) {
            if (debug) {
                logger.log(Level.FINE,
                    "[JavaPack] texture map extraction threw for " + modelRef, ex);
            }
            return Map.of();
        }
    }

    /**
     * Walks the {@code parent} chain rooted at {@code modelRef} and returns
     * the first non-empty {@code elements} array encountered. Unlike
     * {@code display}, Mojang does NOT merge elements across parents — once
     * the chain finds an elements declaration it stops walking, so child
     * overrides completely replace the parent's geometry.
     *
     * <p>Returns {@code null} when the chain produces no elements (typical
     * for 2D-icon items inheriting from {@code item/generated}).</p>
     */
    public JavaModelGeometry resolveElementsFromModel(String modelRef) {
        if (modelRef == null || modelRef.isBlank()) {
            return null;
        }
        String current = modelRef;
        int hops = 0;
        while (current != null && hops < 8) {
            Path modelFile = resolveModelFile(current);
            if (modelFile == null) {
                break;
            }
            Map<String, Object> modelJson;
            try {
                modelJson = readJsonObject(modelFile);
            } catch (IOException ex) {
                logger.warning("[JavaPack] failed to read model JSON " + modelFile
                    + " for elements extraction: " + ex.getMessage());
                break;
            }
            Object elementsObj = modelJson.get("elements");
            if (elementsObj instanceof List<?> rawList) {
                // S4: an explicit empty array (elements: []) is Mojang's way
                // of saying "I override the parent's geometry with nothing".
                // We honour that by stopping the parent walk here and returning
                // null so callers fall back to the 2D path rather than picking
                // up an inherited 3D shape the operator explicitly removed.
                if (rawList.isEmpty()) {
                    return null;
                }
                List<JavaModelGeometry.Element> parsed = new ArrayList<>(rawList.size());
                for (Object item : rawList) {
                    if (!(item instanceof Map<?, ?> elementMap)) continue;
                    JavaModelGeometry.Element element = parseElement(elementMap);
                    if (element != null) {
                        parsed.add(element);
                    }
                }
                if (!parsed.isEmpty()) {
                    return new JavaModelGeometry(parsed);
                }
                // List was non-empty but every entry failed to parse — treat
                // as malformed and stop walking rather than silently inheriting
                // the parent's elements (which would not match what the
                // operator wrote).
                return null;
            }
            Object parent = modelJson.get("parent");
            if (!(parent instanceof String parentRef) || parentRef.isBlank()) {
                break;
            }
            current = parentRef;
            hops++;
        }
        return null;
    }

    /**
     * Parses one entry of a Java {@code elements} array. Returns {@code null}
     * when the entry is structurally wrong (missing from/to, wrong array
     * length); the caller skips nulls so a single broken element does not
     * disqualify the entire model.
     */
    private static JavaModelGeometry.Element parseElement(Map<?, ?> map) {
        float[] from = parseFloat3(map.get("from"), 0f);
        float[] to = parseFloat3(map.get("to"), 0f);
        JavaModelGeometry.ElementRotation rotation = parseElementRotation(map.get("rotation"));
        Map<String, JavaModelGeometry.Face> faces = parseFaces(map.get("faces"));
        try {
            return new JavaModelGeometry.Element(from, to, rotation, faces);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static float numberOrZero(Object raw) {
        return raw instanceof Number n ? n.floatValue() : 0f;
    }

    private static JavaModelGeometry.ElementRotation parseElementRotation(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        float[] origin = parseFloat3(map.get("origin"), 8f);
        Object axisObj = map.get("axis");
        String axis = (axisObj instanceof String s) ? s : "y";
        Object angleObj = map.get("angle");
        float angle = (angleObj instanceof Number n) ? n.floatValue() : 0f;
        Object rescaleObj = map.get("rescale");
        boolean rescale = rescaleObj instanceof Boolean b && b;
        try {
            // Blockbench's free-rotation form: {"x":..,"y":..,"z":..} instead
            // of vanilla's single axis + angle. Detected by the absence of
            // "axis", so a vanilla object that happens to carry stray x/y/z
            // keys still takes the single-axis path.
            if (axisObj == null) {
                float ex = numberOrZero(map.get("x"));
                float ey = numberOrZero(map.get("y"));
                float ez = numberOrZero(map.get("z"));
                if (ex != 0f || ey != 0f || ez != 0f) {
                    return JavaModelGeometry.ElementRotation.ofEuler(
                        origin, new float[]{ex, ey, ez}, rescale);
                }
            }
            return new JavaModelGeometry.ElementRotation(origin, axis, angle, rescale);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    /**
     * Standard Java face names. Restricting to this whitelist keeps weird
     * keys (typos in operator-authored JSON, future face slots) from
     * leaking into the Bedrock geometry output as unrecognised faces.
     */
    private static final String[] JAVA_FACE_NAMES =
        {"north", "south", "east", "west", "up", "down"};

    /**
     * Parses the {@code faces} object of a Java element into a face-name →
     * {@link JavaModelGeometry.Face} map. Empty map when the input is not a
     * JSON object (matches Java's "no faces declared" semantics, which makes
     * the cube invisible — operators rarely intend that, but if they do,
     * we honour it by emitting a cube with no per-face UV).
     *
     * <p>Face entries that fail to parse individually are skipped, leaving
     * the rest of the cube's faces intact. This is the "graceful degrade"
     * principle: a single malformed face cannot disqualify the entire cube.</p>
     */
    private static Map<String, JavaModelGeometry.Face> parseFaces(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, JavaModelGeometry.Face> result = new java.util.LinkedHashMap<>();
        for (String name : JAVA_FACE_NAMES) {
            Object faceRaw = map.get(name);
            if (!(faceRaw instanceof Map<?, ?> faceMap)) {
                continue;
            }
            JavaModelGeometry.Face face = parseFace(faceMap);
            if (face != null) {
                result.put(name, face);
            }
        }
        return result;
    }

    /**
     * Parses a single face entry. Returns {@code null} on structural failure
     * so the caller can skip it; this keeps a single broken face from
     * blocking the rest of the cube.
     */
    private static JavaModelGeometry.Face parseFace(Map<?, ?> map) {
        float[] uv = null;
        Object uvObj = map.get("uv");
        if (uvObj instanceof List<?> uvList && uvList.size() >= 4) {
            uv = new float[4];
            for (int i = 0; i < 4; i++) {
                Object v = uvList.get(i);
                if (v instanceof Number n) {
                    uv[i] = n.floatValue();
                }
            }
        }

        String texture = null;
        Object texObj = map.get("texture");
        if (texObj instanceof String s) {
            texture = s;
        }

        // Java face rotation is a multiple of 90 degrees; treat anything
        // outside the valid set as 0 (no rotation) rather than rejecting
        // the whole face — defensive degrade.
        int rotation = 0;
        Object rotObj = map.get("rotation");
        if (rotObj instanceof Number n) {
            int r = n.intValue();
            if (r == 0 || r == 90 || r == 180 || r == 270) {
                rotation = r;
            }
        }

        try {
            return new JavaModelGeometry.Face(uv, texture, rotation);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    public JavaModelDisplay resolveDisplayFromModel(String modelRef) {
        if (modelRef == null || modelRef.isBlank()) {
            return null;
        }
        JavaModelDisplay.Transform firstHand = null;
        JavaModelDisplay.Transform thirdHand = null;
        JavaModelDisplay.Transform gui = null;
        JavaModelDisplay.Transform ground = null;
        JavaModelDisplay.Transform head = null;
        JavaModelDisplay.Transform firstHandLeft = null;
        JavaModelDisplay.Transform thirdHandLeft = null;

        String current = modelRef;
        int hops = 0;
        while (current != null && hops < 8) {
            Path modelFile = resolveModelFile(current);
            if (modelFile == null) {
                // Pack has no file for this parent — inject Mojang defaults
                // for item/handheld and item/generated, then continue the
                // chain (handheld → generated) so ground/head still fill in.
                JavaModelDisplay builtin =
                    VanillaBuiltinDisplays.forUnresolvedParent(current);
                if (builtin == null) {
                    break;
                }
                if (firstHand == null) firstHand = builtin.firstpersonRighthand();
                if (thirdHand == null) thirdHand = builtin.thirdpersonRighthand();
                if (gui == null) gui = builtin.gui();
                if (ground == null) ground = builtin.ground();
                if (head == null) head = builtin.head();
                // The left-hand slots must come across too. A missing
                // *_lefthand means "mirror the right hand", so dropping
                // handheld's declared entries here does not fall back to
                // nothing — it actively flips every inherited tool 180 degrees
                // about Y in the off hand.
                if (firstHandLeft == null) firstHandLeft = builtin.firstpersonLefthand();
                if (thirdHandLeft == null) thirdHandLeft = builtin.thirdpersonLefthand();
                current = VanillaBuiltinDisplays.nextBuiltinParent(current);
                hops++;
                continue;
            }
            Map<String, Object> modelJson;
            try {
                modelJson = readJsonObject(modelFile);
            } catch (IOException ex) {
                logger.warning("[JavaPack] failed to read model JSON " + modelFile
                    + " for display extraction: " + ex.getMessage());
                break;
            }
            Object displayObj = modelJson.get("display");
            if (displayObj instanceof Map<?, ?> displayMap) {
                if (firstHand == null) firstHand = parseTransform(displayMap.get("firstperson_righthand"));
                if (thirdHand == null) thirdHand = parseTransform(displayMap.get("thirdperson_righthand"));
                if (gui == null) gui = parseTransform(displayMap.get("gui"));
                if (ground == null) ground = parseTransform(displayMap.get("ground"));
                if (head == null) head = parseTransform(displayMap.get("head"));
                if (firstHandLeft == null) firstHandLeft = parseTransform(displayMap.get("firstperson_lefthand"));
                if (thirdHandLeft == null) thirdHandLeft = parseTransform(displayMap.get("thirdperson_lefthand"));
            }
            Object parent = modelJson.get("parent");
            if (!(parent instanceof String parentRef) || parentRef.isBlank()) {
                break;
            }
            current = parentRef;
            hops++;
        }

        if (firstHand == null && thirdHand == null && gui == null
            && ground == null && head == null) {
            return null;
        }
        return new JavaModelDisplay(firstHand, thirdHand, gui, ground, head,
            firstHandLeft, thirdHandLeft);
    }

    /**
     * Parses one slot of a Java {@code display} map (e.g.
     * {@code {"rotation":[0,-90,25],"translation":[1.13,3.2,1.13],"scale":[0.68,0.68,0.68]}})
     * into a {@link JavaModelDisplay.Transform}. Missing arrays default to the
     * identity values (zero rotation/translation, unit scale) per Mojang's
     * client behaviour. Returns {@code null} when the input is not a JSON
     * object — caller treats that as "slot not declared here".
     */
    private static JavaModelDisplay.Transform parseTransform(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        float[] rotation = parseFloat3(map.get("rotation"), 0f);
        float[] translation = parseFloat3(map.get("translation"), 0f);
        float[] scale = parseFloat3(map.get("scale"), 1f);
        return new JavaModelDisplay.Transform(rotation, translation, scale);
    }

    /**
     * Parses an "expected 3-number array" JSON value into a {@code float[3]}.
     * Pads short arrays and ignores extra entries; non-numeric items become
     * {@code defaultValue}. Always returns a fresh non-null array.
     */
    private static float[] parseFloat3(Object raw, float defaultValue) {
        float[] out = new float[]{defaultValue, defaultValue, defaultValue};
        if (raw instanceof List<?> list) {
            int len = Math.min(3, list.size());
            for (int i = 0; i < len; i++) {
                Object element = list.get(i);
                if (element instanceof Number n) {
                    out[i] = n.floatValue();
                }
            }
        }
        return out;
    }

    private String resolveTextureRefFromModel(String modelRef) {
        String current = modelRef;
        int hops = 0;
        while (current != null && hops < 8) {
            Path modelFile = resolveModelFile(current);
            if (modelFile == null) {
                if (debug) {
                    logger.fine("[JavaPack] parent model unresolved (vanilla file"
                        + " or missing dependency): " + current
                        + " at hop " + hops + " from " + modelRef);
                }
                return null;
            }
            Map<String, Object> modelJson;
            try {
                modelJson = readJsonObject(modelFile);
            } catch (IOException ex) {
                logger.warning("[JavaPack] failed to read model JSON " + modelFile
                    + ": " + ex.getMessage());
                return null;
            }
            Object texturesObj = modelJson.get("textures");
            if (texturesObj instanceof Map<?, ?> textures) {
                String layered = pickLayeredTexture(textures);
                if (layered != null) {
                    return layered;
                }
            }
            Object parent = modelJson.get("parent");
            if (!(parent instanceof String parentRef) || parentRef.isBlank()) {
                return null;
            }
            current = parentRef;
            hops++;
        }
        if (debug) {
            logger.fine("[JavaPack] parent chain depth exceeded for " + modelRef);
        }
        return null;
    }

    /**
     * Returns the best-effort icon texture reference from a model's
     * {@code textures} map, or {@code null} when none is suitable.
     *
     * <p>Resolution order:
     * <ol>
     *   <li><b>{@code layerN}</b> (N from 0 to 9) — the canonical 2D-icon
     *       slot in Mojang item models. Picked first whenever present.</li>
     *   <li><b>First non-{@code particle} non-{@code #}-reference string</b>
     *       value in declaration order — handles 3D models (Blockbench
     *       output) where the textures map uses numeric keys like
     *       {@code "0"}, {@code "1"}, {@code "2"}, … to address each face
     *       of the geometry. The 2D icon Bedrock renders won't perfectly
     *       reproduce a multi-face 3D mesh, but it gives Bedrock players a
     *       sensible single-image representation that matches what they'd
     *       see on the main face of the model. Previously these models
     *       resolved to {@code null}, producing 0 custom textures in the
     *       auto-pack despite the Java pack being correctly extracted.</li>
     * </ol>
     * Internal {@code #var} placeholders reference template variables, not
     * real texture files, so they're skipped. The {@code particle} key is
     * also skipped because it's the block-break / use particle effect, not
     * the item icon.</p>
     */
    private String pickLayeredTexture(Map<?, ?> textures) {
        for (int n = 0; n < 10; n++) {
            Object val = textures.get("layer" + n);
            if (val instanceof String s && !s.isBlank() && !s.startsWith("#")) {
                return s;
            }
        }
        for (Map.Entry<?, ?> entry : textures.entrySet()) {
            Object keyObj = entry.getKey();
            Object valObj = entry.getValue();
            if (!(keyObj instanceof String key) || !(valObj instanceof String val)) {
                continue;
            }
            if (val.isBlank() || val.startsWith("#")) {
                continue;
            }
            if ("particle".equals(key)) {
                continue;
            }
            return val;
        }
        return null;
    }

    /**
     * Phase 7a: resolves the texture file for a Java equipment asset.
     *
     * <p>Java equipment JSON lives at {@code assets/<ns>/equipment/<name>.json}
     * (1.21.4+ format) and declares one or more texture layers per humanoid
     * armor slot. For a given asset id (e.g. {@code "myns:my_helmet"}) and
     * layer key (one of {@code "humanoid"} / {@code "humanoid_leggings"}),
     * this method returns the absolute path to the referenced PNG file, or
     * {@code null} when the asset JSON is missing, the layer is absent, or
     * the referenced texture file cannot be located on disk.</p>
     *
     * <p>Texture reference resolution mirrors {@link #resolveTextureFile}:
     * {@code "myns:my_helmet"} resolves to
     * {@code assets/myns/textures/entity/equipment/<layer>/my_helmet.png}.
     * The {@code <layer>} subdirectory mirrors Mojang's convention (Java
     * 1.21.4+ ships humanoid armor textures under
     * {@code textures/entity/equipment/humanoid/}).</p>
     */
    public Path resolveEquipmentTexture(String assetId, String layerKey) {
        if (assetId == null || assetId.isBlank() || layerKey == null) {
            return null;
        }
        String[] assetParts = splitNamespacedKey(assetId);
        String namespace = assetParts[0];
        String assetName = assetParts[1];
        Path equipmentJson = packRoot.resolve("assets").resolve(namespace)
            .resolve("equipment").resolve(assetName + ".json");
        if (!Files.isRegularFile(equipmentJson)) {
            return null;
        }
        Map<String, Object> json;
        try {
            json = readJsonObject(equipmentJson);
        } catch (IOException ex) {
            logger.warning("[Equipment] failed to read " + equipmentJson + ": " + ex.getMessage());
            return null;
        }
        Object layersObj = json.get("layers");
        if (!(layersObj instanceof Map<?, ?> layers)) {
            return null;
        }
        Object layerListObj = layers.get(layerKey);
        if (!(layerListObj instanceof List<?> layerList) || layerList.isEmpty()) {
            return null;
        }
        // First entry is the primary texture; Mojang allows multiple stacked
        // layers but Bedrock's single-texture attachable can only sample one.
        Object firstLayer = layerList.get(0);
        if (!(firstLayer instanceof Map<?, ?> layerMap)) {
            return null;
        }
        Object textureRef = layerMap.get("texture");
        if (!(textureRef instanceof String textureRefStr) || textureRefStr.isBlank()) {
            return null;
        }
        // Equipment texture references resolve to
        // assets/<ns>/textures/entity/equipment/<layer>/<name>.png
        String[] texParts = splitNamespacedKey(textureRefStr);
        Path texturePath = packRoot.resolve("assets").resolve(texParts[0])
            .resolve("textures").resolve("entity").resolve("equipment")
            .resolve(layerKey).resolve(texParts[1] + ".png");
        return Files.isRegularFile(texturePath) ? texturePath : null;
    }

    /** Resolves a model reference (e.g. {@code "myns:item/fire_sword"}) to a file. */
    private Path resolveModelFile(String modelRef) {
        String[] parts = splitNamespacedKey(modelRef);
        String namespace = parts[0];
        String path = parts[1];
        Path absolute = packRoot.resolve("assets").resolve(namespace)
            .resolve("models").resolve(path + ".json");
        return Files.isRegularFile(absolute) ? absolute : null;
    }

    /**
     * Resolves a texture reference (e.g. {@code "myns:items/fire_sword"} or
     * {@code "item/weapons/copper_dagger"}) to a PNG file path inside the
     * pack. Returns {@code null} when no PNG exists at the resolved path.
     *
     * <p>Resolution is "namespace + path → file on disk", with no
     * namespace-based filtering: a pack that overrides
     * {@code assets/minecraft/textures/item/weapons/copper_dagger.png}
     * (the typical ValhallaMMO / large-modpack pattern of stamping custom
     * textures on top of vanilla paths) is honoured, because the PNG
     * physically exists in the pack and is therefore a real custom texture.
     * A reference whose PNG does not exist returns {@code null} and the
     * caller falls back to vanilla rendering for that entry.</p>
     *
     * <p><b>Previously</b> this method returned {@code null} for any
     * {@code minecraft:} namespace reference under the assumption that
     * "minecraft path = vanilla texture, no custom artwork", but that
     * broke every pack that uses the minecraft-override pattern — they
     * were silently treated as having no custom textures, leading to
     * {@code 0 custom resolved} even on packs that clearly do override
     * vanilla items. The file-existence check above gives the same outcome
     * for genuinely vanilla references (the file isn't there in the
     * operator's pack so it returns {@code null}) without false negatives
     * on minecraft-override packs.</p>
     */
    private Path resolveTextureFile(String textureRef) {
        String[] parts = splitNamespacedKey(textureRef);
        String namespace = parts[0];
        String path = parts[1];
        Path absolute = packRoot.resolve("assets").resolve(namespace)
            .resolve("textures").resolve(path + ".png");
        if (Files.isRegularFile(absolute)) {
            return absolute;
        }
        if (debug) {
            logger.fine("[JavaPack] texture file missing: " + absolute);
        }
        return null;
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private List<Path> listSubdirectories(Path parent) {
        List<Path> out = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(parent, Files::isDirectory)) {
            for (Path p : stream) {
                out.add(p);
            }
        } catch (IOException ex) {
            logger.log(Level.WARNING, "[JavaPack] failed to list " + parent, ex);
        }
        return out;
    }

    private List<Path> listJsonFiles(Path dir) {
        List<Path> out = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.json")) {
            for (Path p : stream) {
                if (Files.isRegularFile(p)) {
                    out.add(p);
                }
            }
        } catch (IOException ex) {
            logger.log(Level.WARNING, "[JavaPack] failed to list " + dir, ex);
        }
        return out;
    }

    private List<Path> listJsonFilesRecursive(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().endsWith(".json"))
                .sorted()
                .toList();
        } catch (IOException ex) {
            logger.log(Level.WARNING, "[JavaPack] failed to walk " + dir, ex);
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readJsonObject(Path file) throws IOException {
        String content = Files.readString(file);
        Object parsed = JsonUtil.fromJson(content, Object.class);
        if (parsed instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        throw new IOException("not a JSON object: " + file);
    }

    private static String stripJsonExtension(String fileName) {
        return fileName.endsWith(".json")
            ? fileName.substring(0, fileName.length() - ".json".length())
            : fileName;
    }

    /**
     * Splits a namespaced key ({@code "ns:path"}) into [namespace, path].
     * If no colon is present, namespace defaults to {@code minecraft}.
     */
    private static String[] splitNamespacedKey(String key) {
        int colon = key.indexOf(':');
        if (colon < 0) {
            return new String[]{DEFAULT_NAMESPACE, key};
        }
        return new String[]{key.substring(0, colon), key.substring(colon + 1)};
    }

    // ========================================================================
    // Value types
    // ========================================================================

    /** Composite key of (base item identifier, custom_model_data integer). */
    public record CmdKey(String baseItem, int cmd) {
        public CmdKey {
            Objects.requireNonNull(baseItem, "baseItem must not be null");
        }

        @Override
        public String toString() {
            return baseItem + "#" + cmd;
        }
    }

    /**
     * One resolved {@code custom_model_data} override.
     *
     * @param baseItem        the Java base item identifier (e.g. {@code "minecraft:diamond_sword"})
     * @param customModelData the override's CMD integer
     * @param modelRef        the raw model reference from the pack
     *                        (e.g. {@code "mymod:item/fire_sword"}); diagnostic only
     * @param textureRef      the raw texture reference resolved from the model
     *                        (e.g. {@code "mymod:items/fire_sword"}); diagnostic only
     * @param textureFile     absolute path to the PNG file on disk
     * @param display         optional {@code display} block harvested from the
     *                        model JSON (Phase 3). {@code null} when the model
     *                        declares no display overrides or could not be
     *                        re-read after texture resolution.
     * @param geometry        optional {@code elements} block harvested from the
     *                        model JSON (Phase 4). {@code null} when the model
     *                        is a flat 2D icon or the elements parse failed.
     */
    public record JavaModelDefinition(
        String baseItem,
        int customModelData,
        String modelRef,
        String textureRef,
        Path textureFile,
        JavaModelDisplay display,
        JavaModelGeometry geometry,
        /**
         * Every entry of the model's {@code textures} map resolved to a PNG,
         * keyed without the leading {@code #}. Faces that reference something
         * other than the icon layer ({@code #1}, {@code #2}) need this to be
         * painted with the right artwork. Never null; empty when the model
         * declares no resolvable textures.
         */
        Map<String, Path> textureFiles
    ) {
        public JavaModelDefinition {
            textureFiles = textureFiles != null ? Map.copyOf(textureFiles) : Map.of();
        }

        /** Back-compatible 7-arg form; carries no per-face texture map. */
        public JavaModelDefinition(String baseItem, int customModelData,
                                   String modelRef, String textureRef, Path textureFile,
                                   JavaModelDisplay display, JavaModelGeometry geometry) {
            this(baseItem, customModelData, modelRef, textureRef, textureFile,
                display, geometry, Map.of());
        }

        /**
         * Backward-compatible 5-arg constructor used by call sites that
         * pre-date the Phase 3 display field. Delegates with
         * {@code display=null, geometry=null}.
         */
        public JavaModelDefinition(String baseItem, int customModelData,
                                   String modelRef, String textureRef, Path textureFile) {
            this(baseItem, customModelData, modelRef, textureRef, textureFile, null, null);
        }

        /**
         * Backward-compatible 6-arg constructor used by Phase 3 call sites
         * that carried a {@link JavaModelDisplay} but not yet a
         * {@link JavaModelGeometry}. Delegates with {@code geometry=null}.
         */
        public JavaModelDefinition(String baseItem, int customModelData,
                                   String modelRef, String textureRef, Path textureFile,
                                   JavaModelDisplay display) {
            this(baseItem, customModelData, modelRef, textureRef, textureFile, display, null);
        }
    }
}
