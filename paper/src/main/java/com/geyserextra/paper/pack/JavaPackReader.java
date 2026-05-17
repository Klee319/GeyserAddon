package com.geyserextra.paper.pack;

import com.geyserextra.core.config.GeyserExtraConfig;
import com.geyserextra.core.util.JsonUtil;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

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

        logger.info("[JavaPack] scanned " + result.size()
            + " custom_model_data entries from " + packRoot.getFileName()
            + " (modern: " + modernResolved + "/" + modernAttempted + " resolved, "
            + "legacy: " + legacyResolved + "/" + legacyAttempted + " resolved)");
        return result;
    }

    // Per-scan counters; reset at the top of scan() and incremented inside
    // each parse method. Not thread-safe — scan() is intended to be invoked
    // from the main thread or with external synchronisation.
    private int modernAttempted;
    private int modernResolved;
    private int legacyAttempted;
    private int legacyResolved;

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
                    logger.info("[JavaPack-legacy] skipped CMD<=0 override on "
                        + baseItem + " (would clobber the vanilla item itself)");
                }
                continue;
            }
            legacyAttempted++;
            String textureRef = resolveTextureRefFromModel(modelRef);
            if (textureRef == null) {
                if (debug) {
                    logger.info("[JavaPack-legacy] no texture resolved for "
                        + baseItem + " CMD=" + cmd + " -> " + modelRef);
                }
                continue;
            }

            Path texturePath = resolveTextureFile(textureRef);
            if (texturePath == null) {
                if (debug) {
                    logger.info("[JavaPack-legacy] texture file missing for "
                        + baseItem + " CMD=" + cmd + " -> " + textureRef);
                }
                continue;
            }

            CmdKey key = new CmdKey(baseItem, cmd);
            out.put(key, new JavaModelDefinition(baseItem, cmd, modelRef, textureRef, texturePath));
            legacyResolved++;
            if (debug) {
                logger.info("[JavaPack-legacy] " + key + " -> " + texturePath);
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
                    logger.info("[JavaPack-modern] no model refs collected for "
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
                String textureRef = resolveTextureRefFromModel(modelRef);
                if (textureRef == null) continue;
                Path texturePath = resolveTextureFile(textureRef);
                if (texturePath == null) continue;
                resolved = new JavaModelDefinition(baseItem, cmd, modelRef, textureRef, texturePath);
                break;
            }
            if (resolved == null) {
                if (debug) {
                    logger.info("[JavaPack-modern] no texture resolved for "
                        + contextLabel + " (" + modelRefs.size()
                        + " model ref(s) tried, last=" + lastTriedRef + ")");
                }
                continue;
            }

            CmdKey key = new CmdKey(baseItem, cmd);
            out.put(key, resolved);
            modernResolved++;
            if (debug) {
                logger.info("[JavaPack-modern] " + key + " -> " + resolved.textureFile());
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
     *   <li>{@code "condition"} — visits {@code on_false} first (the
     *       default branch for most boolean conditions) then {@code on_true};
     *       lets us pick up whichever branch ships the custom texture.</li>
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
                collectModelRefs(map.get("on_false"), contextLabel, out, depth + 1);
                collectModelRefs(map.get("on_true"), contextLabel, out, depth + 1);
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
     * Resolves a texture reference (e.g. {@code "myns:items/fire_sword"}) to a PNG file.
     *
     * <p>Returns {@code null} for references in the {@code minecraft:} namespace.
     * Why: a CMD override that points at a vanilla texture path is reusing an
     * unmodified Mojang texture — registering it on Bedrock would add a custom
     * item identifier with no visual distinction from its base material, just
     * bloating the registry and the BE resource pack. The user-stated goal is
     * "only register items whose Java pack has a custom texture added"; vanilla
     * references fail that test by definition.</p>
     *
     * <p>If an operator genuinely wants Bedrock-side identity for a vanilla-
     * textured CMD variant (e.g. for inventory distinction without visual
     * changes), the runtime scanner path will still detect and register it as
     * usual when a player interacts with the item — this filter only affects
     * the pack-first pre-registration step.</p>
     */
    private Path resolveTextureFile(String textureRef) {
        String[] parts = splitNamespacedKey(textureRef);
        String namespace = parts[0];
        String path = parts[1];
        if ("minecraft".equals(namespace)) {
            if (debug) {
                logger.fine("[JavaPack] skipping vanilla texture reference (no custom texture added): "
                    + textureRef);
            }
            return null;
        }
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
     */
    public record JavaModelDefinition(
        String baseItem,
        int customModelData,
        String modelRef,
        String textureRef,
        Path textureFile
    ) {}
}
