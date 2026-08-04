package com.geyserextra.paper.pack;

import com.geyserextra.core.config.GeyserExtraConfig.AttachableGenerationConfig;
import com.geyserextra.core.util.JsonUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Generates the three JSON artifacts ({@code attachables/*.json},
 * {@code models/entity/*.geo.json}, {@code animations/*.animation.json})
 * that Bedrock needs in order to render a held-item appearance matching
 * the Java {@code display} transform.
 *
 * <p>Output identifiers all share the form {@code geyserextra:<iconKey>},
 * mirroring the {@code bedrock_identifier} the Geyser extension registers
 * for the same item. Bedrock auto-binds an attachable to a custom item when
 * the identifiers match exactly, so this is the only "wiring" needed —
 * Geyser itself has no setter for attachables in its v2 API.</p>
 *
 * <p><b>Rendering scheme (same rule for every custom item):</b> both flat
 * items and 3D models with Java {@code elements} use the field-proven
 * <a href="https://github.com/Kas-tle/java2bedrock.sh">java2bedrock</a>
 * bone chain {@code root → x → y → z} (+ {@code geo} leaf for cubes). The
 * root binds to the player skeleton and receives a fixed per-slot base pose;
 * Java {@code display} rotation / translation / scale are decomposed onto
 * {@code x}/{@code y}/{@code z}. Flat items place a {@code texture_meshes}
 * entry on {@code z}; 3D items place cubes on {@code geyserextra_geo} at
 * Java model-space centre pivot {@code [0, 8, 0]}.</p>
 *
 * <p>The GeyserMC Rainbow single-bone AnimationMapper was carried for a while
 * as an alternative first-person mapping behind a config switch, because the
 * two references disagree about that frame and only an in-game comparison
 * settles it. java2bedrock won; the Rainbow path and its switch are gone.
 * Operators may still override the <em>global</em> first-person base constants
 * via {@code customItems.attachableGeneration.firstPersonBasePose} (applies to
 * every item the same way — never per-item).</p>
 *
 * <p><b>Geometry:</b> models with Java {@code elements} emit 3D cubes under
 * both {@code offsets_only} and {@code full} (auto-upgrade). Models without
 * {@code elements} use a Bedrock {@code texture_meshes} entry which extrudes
 * the PNG exactly like Java's {@code item/generated} renderer.</p>
 */
public final class BedrockAttachableWriter {

    /** Bedrock pack-internal namespace for our generated artifacts. */
    private static final String NAMESPACE = "geyserextra";

    /** Sub-folder under each artifact category — keeps generated files out of operator-authored content. */
    private static final String FOLDER = "geyserextra_auto";

    /** Root bone: binds to the player skeleton and carries the per-slot base pose. */
    private static final String BONE_ROOT = "geyserextra";
    /** Carries the X component of the Java display rotation plus the translation. */
    private static final String BONE_X = "geyserextra_x";
    /** Carries the Y component of the Java display rotation. */
    private static final String BONE_Y = "geyserextra_y";
    /** Carries the Z component of the Java display rotation. */
    private static final String BONE_Z = "geyserextra_z";
    /**
     * Cube leaf for 3D geometry: child of {@code z}, pivot at Java model-space
     * centre {@code [0, 8, 0]}. Hold animations drive {@code x}/{@code y}/{@code z};
     * this bone only carries cubes.
     */
    private static final String BONE_GEO = "geyserextra_geo";

    /**
     * Molang binding that attaches the root bone to whichever player bone
     * currently holds the item (main hand, off hand, or head).
     *
     * <p>java2bedrock's query, with the head slot special-cased because the
     * query does not cover it.</p>
     *
     * <p>Naming the off-hand bone explicitly — {@code c.item_slot == 'off_hand'
     * ? 'leftitem' : 'rightitem'} — was tried and <b>reverted</b>. The theory
     * was that the query failed to yield {@code leftitem}, because off-hand
     * items rendered displaced toward the main hand by their declared X offset
     * (greataxe 15.5 a body-width out, rapier 7.5 beside the hand, mace 0
     * directly on it). In game the explicit binding made things <em>worse</em>:
     * third-person off hand stopped rendering where it had, which is what a
     * binding to a bone the player skeleton does not expose looks like. The
     * query is correct and stays.</p>
     *
     * <p>The displacement was not a binding fault at all: the off hand was
     * emitting the wrong sign on translation X, which displaces an item in
     * proportion to its declared offset and leaves a zero-offset item exactly
     * where it belongs — the mace. See {@link #MIRROR_X}.</p>
     */
    private static final String ROOT_BONE_BINDING =
        "c.item_slot == 'head' ? 'head' : q.item_slot_to_bone_name(c.item_slot)";

    /**
     * Euler order Bedrock composes the root bone's {@code [rx, ry, rz]} in,
     * used to invert that rotation when mapping a Java display translation into
     * the root's local frame.
     *
     * <p>The order is not derivable from this pack — the third-person root
     * {@code [90, 0, 0]} has two zero angles, so every order gives the same
     * matrix there, and by that same fact this constant can only ever affect
     * first person ({@code BedrockGeometryConverterTest} asserts the
     * invariant). It was settled on a real Bedrock client: {@code zxy} put the
     * greataxe and dagger in frame where java2bedrock's per-axis sign flips
     * ({@link BedrockGeometryConverter.TranslationFrame#J2B}) threw them out of
     * it, in proportion to their declared translation. Reverting to {@code J2B}
     * reintroduces that.</p>
     */
    private static final BedrockGeometryConverter.TranslationFrame TRANSLATION_FRAME =
        BedrockGeometryConverter.TranslationFrame.ZXY;

    /**
     * The Java&rarr;Bedrock frame mirror: negate translation X. One rule, both
     * hands, applied to what Java <em>renders</em>.
     *
     * <p>Let {@code d} be the declared translation and {@code J} what Java
     * actually renders, after {@code ItemTransform#apply} negates X for the
     * left hand:</p>
     * <pre>
     *   main hand, d = righthand:  J = d
     *   off  hand, d = lefthand:   J = (-d.x, d.y, d.z)
     * </pre>
     * <p>A model that omits {@code *_lefthand} gets the righthand transform
     * substituted and still runs that negation, so the source slot tells you
     * nothing — {@code J} is what matters and it is always reachable. The
     * main-hand mapping {@code J -> emitted} is confirmed correct in game and
     * it negates X, so {@code emitted.x = -J.x} in both hands.
     * {@link BedrockGeometryConverter#applyJavaLeftHandTranslation} produces
     * {@code J} for the off hand; this flag is then the same mirror the main
     * hand gets.</p>
     *
     * <p>This flag used to be applied to {@code d} directly, which for the off
     * hand emits {@code -d.x = +J.x} — the right magnitude on the wrong side,
     * {@code 2*d.x} out toward the main hand. The greataxe
     * ({@code thirdperson_lefthand.translation.x = 15.5}) landed 31 units off;
     * a mace declaring {@code 0} landed correctly. That an item with no
     * declared X offset was unaffected was read at the time as ruling a sign
     * error <em>out</em> (see {@link #ROOT_BONE_BINDING}) — it is in fact the
     * clearest evidence for one, since a sign error is exactly the fault whose
     * magnitude is proportional to the value it flips.</p>
     */
    private static final boolean MIRROR_X = true;

    /**
     * Fixed base poses that map Bedrock's item-slot bone frame onto Java's
     * display frame. Without these, Java display values are applied in the
     * wrong reference frame and the model floats away from the hand.
     * Values are java2bedrock's empirically-tuned constants. The first-person
     * pose is operator-tunable via
     * {@code customItems.attachableGeneration.firstPersonBasePose}.
     */
    private static final List<Float> THIRD_PERSON_BASE_ROTATION = List.of(90f, 0f, 0f);
    private static final List<Float> THIRD_PERSON_BASE_POSITION = List.of(0f, 13f, -3f);
    private static final List<Float> HEAD_BASE_POSITION = List.of(0f, 19.9f, 0f);
    /** Java scales head-slot rendering to 62.5% of the model's declared size. */
    private static final float HEAD_SCALE = 0.625f;

    /** java2bedrock's fixed geometry bounds; only ever grown, never shrunk. */
    private static final float DEFAULT_VISIBLE_BOUNDS_WIDTH = 4f;
    private static final float DEFAULT_VISIBLE_BOUNDS_HEIGHT = 4.5f;
    private static final float DEFAULT_VISIBLE_BOUNDS_OFFSET_Y = 0.75f;
    /** Slack on the computed box so swing animations cannot clip its edge. */
    private static final float VISIBLE_BOUNDS_MARGIN = 1.5f;

    private BedrockAttachableWriter() {}

    /**
     * Builds the three JSON artifacts for a single mapping. The keys of the
     * returned map are absolute zip-entry paths (suitable for direct use with
     * {@link java.util.zip.ZipOutputStream#putNextEntry}); the values are
     * UTF-8-ready strings.
     *
     * <p>Returns an empty map when {@code config.mode == off} or the display
     * block is unusable for held-item rendering. Callers should treat an
     * empty result as "no artifacts to write" and continue without logging
     * an error.</p>
     *
     * @param iconKey   sanitised Bedrock icon key (also used as the
     *                  {@code bedrock_identifier} suffix)
     * @param display   the model's display transform; may be {@code null}
     * @param textureRelativePath the existing {@code textures/items/<iconKey>}
     *                            path written by {@code AutoBedrockPackBuilder}.
     *                            Used as the attachable's default texture so
     *                            held-item rendering uses the same artwork as
     *                            the inventory icon.
     * @param config    the {@code customItems.attachableGeneration} settings
     * @return path -> JSON content map (empty when nothing should be written)
     */
    /**
     * Backward-compatible 5-arg overload (pre-Phase-6). Uses 16x16 as the
     * texture dimensions for UV scaling, which is correct for the default
     * vanilla item texture size but understates UV coordinates on
     * higher-resolution operator-supplied PNGs.
     */
    public static Map<String, String> buildArtifacts(
        String iconKey,
        JavaModelDisplay display,
        JavaModelGeometry geometry,
        String textureRelativePath,
        AttachableGenerationConfig config
    ) {
        return buildArtifacts(iconKey, iconKey, display, geometry, textureRelativePath,
            16, 16, config, null);
    }

    /**
     * Backward-compatible 8-arg overload: uses {@code iconKey} itself as the
     * zip-path file base (correct whenever the key is short enough to stay
     * under Geyser's 80-char entry-path warning threshold).
     */
    public static Map<String, String> buildArtifacts(
        String iconKey,
        JavaModelDisplay display,
        JavaModelGeometry geometry,
        String textureRelativePath,
        int textureWidth,
        int textureHeight,
        AttachableGenerationConfig config,
        Logger logger
    ) {
        return buildArtifacts(iconKey, iconKey, display, geometry, textureRelativePath,
            textureWidth, textureHeight, config, logger);
    }

    /**
     * Phase 6 entry point. Takes the actual PNG dimensions of the icon
     * texture so the geometry descriptor declares matching {@code texture_width}
     * and {@code texture_height}, and so per-face UVs scale correctly for
     * non-16x16 textures (the high-res operator-pack case).
     *
     * @param iconKey               sanitised Bedrock icon key (also the
     *                               {@code bedrock_identifier} suffix)
     * @param fileBase              file base name embedded in the returned
     *                               zip entry paths. Usually equals
     *                               {@code iconKey}; differs when
     *                               {@code AutoBedrockPackBuilder.zipSafeFileBase}
     *                               shortened it to keep entry paths under
     *                               Geyser's 80-char warning threshold.
     *                               Identifiers inside the JSON always keep
     *                               {@code iconKey} (they must match the
     *                               extension-side registration exactly).
     * @param display                Java {@code display} block; may be {@code null}
     * @param geometry               Java {@code elements} block; may be {@code null}
     * @param textureRelativePath    existing {@code textures/items/<fileBase>} path
     *                               written by {@code AutoBedrockPackBuilder}
     * @param textureWidth           actual PNG width in pixels (>0); used as
     *                               the geometry descriptor's {@code texture_width}
     *                               and as the X scale factor for per-face UV
     * @param textureHeight          actual PNG height in pixels (>0)
     * @param config                 attachable generation policy
     * @param logger                 optional logger for FINE-level diagnostics
     * @return path → JSON content map (empty when nothing should be written)
     */
    public static Map<String, String> buildArtifacts(
        String iconKey,
        String fileBase,
        JavaModelDisplay display,
        JavaModelGeometry geometry,
        String textureRelativePath,
        int textureWidth,
        int textureHeight,
        AttachableGenerationConfig config,
        Logger logger
    ) {
        if (iconKey == null || iconKey.isBlank()) {
            return Map.of();
        }
        if (fileBase == null || fileBase.isBlank()) {
            fileBase = iconKey;
        }
        if (config == null) {
            return Map.of();
        }
        String mode = config.mode();
        if (AttachableGenerationConfig.MODE_OFF.equals(mode)) {
            return Map.of();
        }
        // Mode is offsets_only or full. Both still require at least one
        // held-item transform (including VanillaBuiltinDisplays for
        // item/handheld). Geyser custom IDs cannot fall back to Bedrock's
        // vanilla sword pose when we skip here.
        if (display == null || !display.hasAnyHandTransform()) {
            return Map.of();
        }

        // Defensive clamp: a corrupt or unreadable PNG should not produce
        // a zero-sized texture coordinate space which would make every face
        // sample uv (0,0) only. Fall back to vanilla 16x16.
        int safeTw = textureWidth > 0 ? textureWidth : 16;
        int safeTh = textureHeight > 0 ? textureHeight : 16;

        // full mode + elements → 3D cubes with per-face UV.
        // offsets_only + elements → also use 3D cubes: Valhalla-style weapons
        // ship large display translations meant for the voxel mesh; applying
        // those offsets to a flat 16x16 quad makes the item float / face the
        // wrong way in-hand. Auto-upgrade only when elements exist so plain
        // 2D icon overrides keep the lightweight flat-quad path.
        // offsets_only / full with empty elements → flat-quad geometry.
        boolean useFullGeometry = geometry != null
            && geometry.hasElements()
            && (AttachableGenerationConfig.MODE_FULL.equals(mode)
                || AttachableGenerationConfig.MODE_OFFSETS_ONLY.equals(mode));
        // Zip entry paths use the (possibly shortened) file base; the JSON
        // bodies keep the full icon key for identifiers so they still match
        // the geyserextra:<iconKey> registration on the extension side.
        Map<String, String> out = new LinkedHashMap<>();
        out.put(attachableEntryPath(fileBase),
                JsonUtil.toPrettyJson(buildAttachableJson(iconKey, textureRelativePath, display, config)));
        out.put(geometryEntryPath(fileBase),
                JsonUtil.toPrettyJson(buildGeometryJson(
                    iconKey, useFullGeometry ? geometry : null, safeTw, safeTh,
                    display, rootScaleForBounds(config, useFullGeometry), logger)));
        // Texture-only CMD items (no Java elements: item/handheld + custom
        // layer0) must keep vanilla sword hold framing. Operator
        // firstPersonBasePose is tuned for Valhalla 3D meshes and must not
        // apply to the flat texture_meshes path.
        out.put(animationEntryPath(fileBase),
                JsonUtil.toPrettyJson(buildAnimationJson(
                    iconKey, display, config, !useFullGeometry)));
        return out;
    }

    // ---------------------------------------------------------------------
    // Path helpers
    // ---------------------------------------------------------------------

    public static String attachableEntryPath(String iconKey) {
        return "attachables/" + FOLDER + "/" + iconKey + ".json";
    }

    /**
     * Phase 7a: ZIP entry path for the armor texture an equippable item ships.
     * The Bedrock attachable's {@code textures.default} references this path
     * (extension-less, Bedrock appends {@code .png} automatically).
     */
    public static String armorTextureEntryPath(String iconKey) {
        return "textures/entity/equipment/" + iconKey + ".png";
    }

    /**
     * Phase 7a: builds the Bedrock attachable JSON for an armor item. Unlike
     * the held-item path, this references Bedrock's built-in humanoid armor
     * geometry ({@code geometry.humanoid.armor.<slot>}) so the player model
     * wears the operator's custom texture on the correct slot.
     *
     * <p>Returns an empty map when armor generation is disabled or no usable
     * armor data is provided. The texture path written to the attachable
     * matches {@link #armorTextureEntryPath(String)} so the auto-pack can
     * copy the texture file alongside the JSON.</p>
     *
     * @param iconKey  sanitised Bedrock icon key (also the
     *                 {@code bedrock_identifier} suffix)
     * @param armor    {@link com.geyserextra.core.api.ArmorData} carrying
     *                 slot + asset id
     * @param config   armor generation config (gates whether anything is emitted)
     * @return path → JSON content map; one entry when generation succeeds,
     *         empty when armor is null or disabled
     */
    public static Map<String, String> buildArmorArtifacts(
        String iconKey,
        com.geyserextra.core.api.ArmorData armor,
        com.geyserextra.core.config.GeyserExtraConfig.ArmorGenerationConfig config
    ) {
        return buildArmorArtifacts(iconKey, iconKey, armor, config);
    }

    /**
     * Variant taking a distinct zip-path file base (see
     * {@code AutoBedrockPackBuilder.zipSafeFileBase}): entry path and the
     * {@code textures.default} reference use {@code fileBase}, while the
     * attachable {@code identifier} keeps the full {@code iconKey}.
     */
    public static Map<String, String> buildArmorArtifacts(
        String iconKey,
        String fileBase,
        com.geyserextra.core.api.ArmorData armor,
        com.geyserextra.core.config.GeyserExtraConfig.ArmorGenerationConfig config
    ) {
        if (iconKey == null || iconKey.isBlank()) {
            return Map.of();
        }
        if (fileBase == null || fileBase.isBlank()) {
            fileBase = iconKey;
        }
        if (armor == null) {
            return Map.of();
        }
        if (config != null && !config.enabled()) {
            return Map.of();
        }

        Map<String, Object> description = new LinkedHashMap<>();
        description.put("identifier", NAMESPACE + ":" + iconKey);
        description.put("materials", linkedMap(
            "default", "armor",
            "enchanted", "armor_enchanted"));
        description.put("textures", linkedMap(
            "default", "textures/entity/equipment/" + fileBase,
            "enchanted", "textures/misc/enchanted_item_glint"));
        description.put("geometry", Map.of("default", armor.bedrockGeometry()));
        // Suppress the vanilla armor layer Bedrock renders by default —
        // without this, the operator's custom texture appears stacked on top
        // of the vanilla iron/diamond/etc. layer for the base material.
        //
        // Per-slot suppression (Codex round-1 fix): only the variable for this
        // attachable's slot is set, so a custom helmet does not accidentally
        // hide an unrelated vanilla chest/legs/boots layer the player is
        // wearing in another slot. The molang statement comes from
        // ArmorData.slotVisibilitySuppression() so the slot ↔ variable
        // mapping lives in one place.
        description.put("scripts", Map.of(
            "parent_setup", armor.slotVisibilitySuppression()));
        description.put("render_controllers", List.of("controller.render.armor"));

        Map<String, Object> attachable = linkedMap(
            "format_version", "1.10.0",
            "minecraft:attachable", Map.of("description", description));

        return Map.of(
            attachableEntryPath(fileBase),
            JsonUtil.toPrettyJson(attachable));
    }

    public static String geometryEntryPath(String iconKey) {
        return "models/entity/" + FOLDER + "/" + iconKey + ".geo.json";
    }

    public static String animationEntryPath(String iconKey) {
        return "animations/" + FOLDER + "/" + iconKey + ".animation.json";
    }

    // ---------------------------------------------------------------------
    // JSON builders
    // ---------------------------------------------------------------------

    private static Map<String, Object> buildAttachableJson(
        String iconKey,
        String textureRelativePath,
        JavaModelDisplay display,
        AttachableGenerationConfig config
    ) {
        // Path written to attachable.textures must omit the trailing extension
        // (Bedrock appends .png automatically). The auto-pack writes the PNG
        // as "textures/items/<iconKey>.png" so the extension-less form is the
        // textureRelativePath value AutoBedrockPackBuilder already tracks.
        String tex = (textureRelativePath != null && !textureRelativePath.isBlank())
            ? textureRelativePath
            : "textures/items/" + iconKey;

        Map<String, Object> description = new LinkedHashMap<>();
        description.put("identifier", NAMESPACE + ":" + iconKey);
        description.put("materials", linkedMap(
            "default", "entity_alphatest",
            "enchanted", "entity_alphatest_glint"));
        description.put("textures", linkedMap(
            "default", tex,
            "enchanted", "textures/misc/enchanted_item_glint"));
        description.put("geometry", Map.of("default", "geometry." + NAMESPACE + "." + iconKey));

        boolean withThirdPerson = !config.forceFirstPersonOnly();
        boolean withHead = display.head() != null;
        String animPrefix = "animation." + NAMESPACE + "." + iconKey + ".";

        Map<String, String> animations = new LinkedHashMap<>();
        List<Map<String, String>> animate = new ArrayList<>();
        if (withThirdPerson) {
            animations.put("thirdperson_main_hand", animPrefix + "thirdperson_main_hand");
            animations.put("thirdperson_off_hand", animPrefix + "thirdperson_off_hand");
            animate.add(Map.of("thirdperson_main_hand", "v.main_hand && !c.is_first_person"));
            animate.add(Map.of("thirdperson_off_hand", "v.off_hand && !c.is_first_person"));
        }
        animations.put("firstperson_main_hand", animPrefix + "firstperson_main_hand");
        animations.put("firstperson_off_hand", animPrefix + "firstperson_off_hand");
        animate.add(Map.of("firstperson_main_hand", "v.main_hand && c.is_first_person"));
        animate.add(Map.of("firstperson_off_hand", "v.off_hand && c.is_first_person"));
        if (withHead) {
            animations.put("thirdperson_head", animPrefix + "head");
            animations.put("firstperson_head", animPrefix + "disable");
            animate.add(Map.of("thirdperson_head", "v.head && !c.is_first_person"));
            // The vanilla first-person camera never shows the player's own
            // head slot; without the disable animation the mesh floats in view.
            animate.add(Map.of("firstperson_head", "c.is_first_person && v.head"));
        }
        description.put("animations", animations);

        Map<String, Object> scripts = new LinkedHashMap<>();
        scripts.put("pre_animation", List.of(
            "v.main_hand = c.item_slot == 'main_hand';",
            "v.off_hand = c.item_slot == 'off_hand';",
            "v.head = c.item_slot == 'head';"));
        scripts.put("animate", animate);
        description.put("scripts", scripts);

        description.put("render_controllers", List.of("controller.render.item_default"));

        return linkedMap(
            "format_version", "1.10.0",
            "minecraft:attachable", Map.of("description", description));
    }

    private static Map<String, Object> buildGeometryJson(
        String iconKey,
        JavaModelGeometry fullGeometry,
        int textureWidth,
        int textureHeight,
        JavaModelDisplay display,
        float rootScaleForBounds,
        Logger logger
    ) {
        // Cubes are converted up-front so the descriptor can size its
        // visible bounds from the real mesh and pick a format_version that
        // matches the features the cubes actually use.
        //
        // Per-face uv_rotation is always forwarded: it is the only way to get
        // Blockbench side faces oriented as Java draws them, and the legacy
        // fallback (mirror 180, drop 90/270) was only ever there so operators
        // could keep pre-1.21.0 Bedrock clients. That trade is settled — the
        // pack pins min_engine_version 1.21.0.
        List<Map<String, Object>> cubes = (fullGeometry != null && fullGeometry.hasElements())
            ? BedrockGeometryConverter.convertElementsToCubes(
                fullGeometry, textureWidth, textureHeight, logger, true)
            : null;

        Map<String, Object> descriptor = new LinkedHashMap<>();
        descriptor.put("identifier", "geometry." + NAMESPACE + "." + iconKey);
        descriptor.put("texture_width", textureWidth);
        descriptor.put("texture_height", textureHeight);
        // Bounds start at the java2bedrock defaults and are only ever GROWN to
        // enclose the real mesh at its largest rendered scale. Bedrock culls an
        // attachable whose visible bounds leave the frustum, and the fixed
        // 4 x 4.5 box is a Blockbench default that upstream Rainbow also flags
        // as wrong (GeometryMapper carries a "TODO that's wrong" on the same
        // constants). Every model shipped in the TrinityForge pack does fit the
        // default box, so this is hardening for larger meshes rather than a fix
        // for a currently-observed symptom — growing only means no model can
        // render worse than it did before.
        applyVisibleBounds(descriptor, cubes, display, rootScaleForBounds);

        // Bone chain root → x → y → z (all pivot [0,8,0]): the root binds to
        // the player skeleton, the x/y/z bones each carry one axis of the
        // Java display rotation so the three rotations compose in Java's
        // application order — a single Euler triple on one bone cannot
        // reproduce that order in Bedrock's rotation convention.
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("name", BONE_ROOT);
        root.put("binding", ROOT_BONE_BINDING);
        root.put("pivot", List.of(0, 8, 0));

        Map<String, Object> boneX = new LinkedHashMap<>();
        boneX.put("name", BONE_X);
        boneX.put("parent", BONE_ROOT);
        boneX.put("pivot", List.of(0, 8, 0));

        Map<String, Object> boneY = new LinkedHashMap<>();
        boneY.put("name", BONE_Y);
        boneY.put("parent", BONE_X);
        boneY.put("pivot", List.of(0, 8, 0));

        Map<String, Object> boneZ = new LinkedHashMap<>();
        boneZ.put("name", BONE_Z);
        boneZ.put("parent", BONE_Y);
        boneZ.put("pivot", List.of(0, 8, 0));

        List<Map<String, Object>> bones;
        if (cubes != null) {
            // 3D path: java2bedrock chain + geo leaf at Java model-space centre
            // [0,8,0]. Bounds-centre put Valhalla flat meshes behind the FP
            // camera under large base rotations.
            Map<String, Object> boneGeo = new LinkedHashMap<>();
            boneGeo.put("name", BONE_GEO);
            boneGeo.put("parent", BONE_Z);
            boneGeo.put("pivot", List.of(0, 8, 0));
            boneGeo.put("cubes", cubes);
            bones = List.of(root, boneX, boneY, boneZ, boneGeo);
        } else {
            // No Java elements → the model is item/generated style. Bedrock's
            // texture_meshes extrudes the PNG into a voxel mesh exactly like
            // Java's own item renderer, which both looks correct from every
            // angle and keeps the base-pose constants valid (they were tuned
            // against this mesh orientation in java2bedrock).
            //
            // Bedrock builds that mesh at one unit per texture pixel, so its
            // size tracks the PNG's resolution rather than the item's. Java has
            // no such coupling: item/generated always occupies one block face
            // whether the sprite is 16px or 256px. A 64x64 sprite therefore
            // came out four times too large, with its centre four times too far
            // out — which is what made the 64px koujien tower over every 16px
            // item. Centre the pivot on the real texture and scale the bone
            // back to the 16px reference so any resolution lands at Java's
            // size. The mesh's one-pixel thickness scales with it, matching
            // Java's 1/16-block sheet more closely than a fixed unit would.
            float meshSize = textureWidth > 0 ? textureWidth : 16f;
            float meshScale = 16f / meshSize;
            Map<String, Object> textureMesh = new LinkedHashMap<>();
            textureMesh.put("texture", "default");
            textureMesh.put("position", List.of(0, 8, 0));
            textureMesh.put("rotation", List.of(90, 0, -180));
            textureMesh.put("local_pivot",
                List.of(meshSize / 2f, 0.5f, meshSize / 2f));
            boneZ.put("texture_meshes", List.of(textureMesh));
            if (meshScale != 1f) {
                boneZ.put("scale", List.of(meshScale, meshScale, meshScale));
            }
            bones = List.of(root, boneX, boneY, boneZ);
        }

        Map<String, Object> geometry = new LinkedHashMap<>();
        geometry.put("description", descriptor);
        geometry.put("bones", bones);

        // Per-face uv_rotation only exists from geometry format 1.21.0
        // (Microsoft "minecraft:geometry.v1.21.0" reference). Models that
        // don't emit it stay on 1.16.0 so nothing that renders today starts
        // depending on a newer format than it needs.
        String formatVersion = BedrockGeometryConverter.requiresUvRotationFormat(cubes)
            ? "1.21.0"
            : "1.16.0";

        return linkedMap(
            "format_version", formatVersion,
            "minecraft:geometry", List.of(geometry));
    }

    /**
     * Writes {@code visible_bounds_width} / {@code visible_bounds_height} /
     * {@code visible_bounds_offset} onto the geometry descriptor, growing the
     * java2bedrock defaults when the converted mesh needs more room.
     *
     * <p>Bedrock expresses these in blocks; cube coordinates are in model
     * units (16 per block). The largest scale the mesh is ever drawn at is the
     * biggest Java {@code display} scale across the hand slots multiplied by
     * the first-person root scale, so that product is what the box has to
     * contain.</p>
     */
    private static void applyVisibleBounds(
        Map<String, Object> descriptor,
        List<Map<String, Object>> cubes,
        JavaModelDisplay display,
        float rootScaleForBounds
    ) {
        float width = DEFAULT_VISIBLE_BOUNDS_WIDTH;
        float height = DEFAULT_VISIBLE_BOUNDS_HEIGHT;
        float offsetY = DEFAULT_VISIBLE_BOUNDS_OFFSET_Y;

        float[] bounds = BedrockGeometryConverter.computeBounds(cubes);
        if (bounds != null) {
            float scale = maxHandDisplayScale(display) * rootScaleForBounds;
            // Half-width has to cover the mesh on both sides of the bone, so
            // take the largest absolute extent rather than the span: the box
            // is centred on X/Z and the mesh is not.
            float halfExtent = Math.max(
                Math.max(Math.abs(bounds[0]), Math.abs(bounds[3])),
                Math.max(Math.abs(bounds[2]), Math.abs(bounds[5])));
            // The box has to enclose the mesh where the ANIMATION puts it, not
            // where the geometry declares it. Bedrock culls on this AABB in the
            // attached bone's space, and the hold animations translate the mesh
            // by the base pose (up to 15 units) plus the item's own display
            // translation (up to 26 on this pack) before it is drawn. Ignoring
            // those made the box a claim about a position the mesh never
            // occupies. Over-sizing only costs a little redundant draw work;
            // under-sizing makes the item vanish until an animation happens to
            // sweep it back inside, so this errs high deliberately.
            float animReach = maxAnimationOffset(display) * scale / 16f;
            float meshWidth = 2f * (halfExtent * scale / 16f + animReach);
            float meshHeight = (bounds[4] - bounds[1]) * scale / 16f + 2f * animReach;
            float meshCentreY = (bounds[1] + bounds[4]) / 2f * scale / 16f;

            width = Math.max(width, meshWidth * VISIBLE_BOUNDS_MARGIN);
            height = Math.max(height, meshHeight * VISIBLE_BOUNDS_MARGIN);
            // Keep the box tall enough to reach the default offset as well as
            // the mesh centre, so raising the centre never clips the bottom.
            offsetY = Math.max(offsetY, meshCentreY);
        }

        descriptor.put("visible_bounds_width", roundHundredths(width));
        descriptor.put("visible_bounds_height", roundHundredths(height));
        descriptor.put("visible_bounds_offset",
            List.of(0f, roundHundredths(offsetY), 0f));
    }

    /**
     * Largest uniform {@code display.*.scale} across the first- and
     * third-person hand slots, floored at 1 so a shrinking model never
     * shrinks the bounds below the mesh's own size.
     */
    /**
     * Largest single-axis translation any hold animation will apply, in Java
     * model units: the base pose plus the biggest display translation across
     * the hand slots. Used only to inflate the visible bounds.
     */
    private static float maxAnimationOffset(JavaModelDisplay display) {
        // Only the item's OWN display translation counts here. The fixed base
        // poses move the root bone identically for every item, so folding them
        // in would inflate all 87 boxes by a constant instead of singling out
        // the items that actually travel — and the java2bedrock defaults were
        // evidently sized with those base poses already in mind.
        float max = 0f;
        if (display != null) {
            for (boolean firstPerson : new boolean[]{true, false}) {
                for (boolean offHand : new boolean[]{true, false}) {
                    JavaModelDisplay.Transform t =
                        display.handTransformFor(firstPerson, offHand);
                    if (t != null) {
                        max = Math.max(max, maxAbsAxis(t.translation()));
                    }
                }
            }
        }
        return max;
    }

    private static float maxAbsAxis(float[] xyz) {
        return Math.max(Math.abs(xyz[0]),
            Math.max(Math.abs(xyz[1]), Math.abs(xyz[2])));
    }

    private static float maxHandDisplayScale(JavaModelDisplay display) {
        float max = 1f;
        if (display != null) {
            for (boolean firstPerson : new boolean[]{true, false}) {
                JavaModelDisplay.Transform t = display.handTransformFor(firstPerson);
                if (t != null) {
                    max = Math.max(max, maxAxis(t.scale()));
                }
            }
        }
        return max;
    }

    private static Map<String, Object> buildAnimationJson(
        String iconKey,
        JavaModelDisplay display,
        AttachableGenerationConfig config,
        boolean textureOnlyVanillaHold
    ) {
        String animPrefix = "animation." + NAMESPACE + "." + iconKey + ".";
        JavaModelDisplay.Transform hand3rd = orIdentity(display.handTransformFor(false, false));
        JavaModelDisplay.Transform hand1st = orIdentity(display.handTransformFor(true, false));
        // Off hand reads the model's own *_lefthand slots when present.
        JavaModelDisplay.Transform hand3rdOff = orIdentity(display.handTransformFor(false, true));
        JavaModelDisplay.Transform hand1stOff = orIdentity(display.handTransformFor(true, true));
        // Java applies its left-hand rule (negate translation X, negate
        // rotation Y and Z) to whatever transform the left hand resolves to —
        // the declared *_lefthand slot when present, otherwise the right-hand
        // one that ItemTransforms.Deserializer substitutes. It is never
        // skipped, so the off-hand flag below does not depend on which slot
        // supplied the values. See
        // BedrockGeometryConverter#applyJavaLeftHandRotation.

        // Flat / texture_meshes (vanilla sword + texture swap): MUST NOT reuse
        // the operator Valhalla 3D firstPersonBasePose — cubes and
        // texture_meshes sit in different local frames. Use flat FP root
        // (slightly below / closer than stock j2b) and keep handheld
        // translation. 3D meshes keep the operator pose.
        AttachableGenerationConfig.BasePose firstPersonPose = textureOnlyVanillaHold
            ? withHeightOffset(FLAT_FIRST_PERSON_POSE, config.firstPersonHeightOffset())
            : config.hasExplicitFirstPersonBasePose()
                ? config.firstPersonBasePose()
                : withHeightOffset(config.firstPersonBasePose(),
                    config.firstPersonHeightOffset());

        Map<String, Object> animations = new LinkedHashMap<>();
        if (!config.forceFirstPersonOnly()) {
            animations.put(animPrefix + "thirdperson_main_hand",
                buildHoldAnimation(hand3rd, false, false, firstPersonPose));
            animations.put(animPrefix + "thirdperson_off_hand",
                buildHoldAnimation(hand3rdOff, false, true, firstPersonPose));
        }
        animations.put(animPrefix + "firstperson_main_hand",
            buildHoldAnimation(hand1st, true, false, firstPersonPose));
        animations.put(animPrefix + "firstperson_off_hand",
            buildHoldAnimation(hand1stOff, true, true, firstPersonPose));
        if (display.head() != null) {
            animations.put(animPrefix + "head",
                buildHeadAnimation(display.head()));
            animations.put(animPrefix + "disable", buildDisableAnimation());
        }
        return linkedMap(
            "format_version", "1.8.0",
            "animations", animations);
    }

    private static JavaModelDisplay.Transform orIdentity(JavaModelDisplay.Transform t) {
        return t != null ? t : JavaModelDisplay.Transform.identity();
    }

    /**
     * FP root for texture-only / {@code texture_meshes} items.
     * Based on Kas-tle java2bedrock ({@code [4,10,4]/1.5}) with a nudge:
     * −X toward the camera; Y a bit below mid-screen; scale above stock j2b.
     */
    private static final AttachableGenerationConfig.BasePose FLAT_FIRST_PERSON_POSE =
        new AttachableGenerationConfig.BasePose(
            new float[]{90f, 60f, -40f},
            new float[]{0f, 15f, 4f},
            1.75f);

    /**
     * Returns {@code pose} with the operator's first-person height correction
     * added to Y. Applied to the built-in flat and 3D defaults only: an
     * explicit {@code firstPersonBasePose} is an absolute override and is
     * returned untouched, so tuning the offset can never fight a hand-authored
     * pose. See {@link AttachableGenerationConfig#firstPersonHeightOffset()}.
     */
    private static AttachableGenerationConfig.BasePose withHeightOffset(
            AttachableGenerationConfig.BasePose pose, float offset) {
        if (offset == 0f) {
            return pose;
        }
        float[] p = pose.position();
        return new AttachableGenerationConfig.BasePose(
            pose.rotation(),
            new float[]{p[0], p[1] + offset, p[2]},
            pose.scale());
    }

    /**
     * Builds one held-item animation: the root bone gets the fixed per-slot
     * base pose, the x bone gets X rotation + translation + scale, and the
     * y/z bones get their single rotation axis (java2bedrock decomposition).
     *
     * @param offHand {@code true} for the left/off hand, which applies
     *                vanilla {@code ItemTransform#apply}'s left-hand rule
     *                first: rotation Y and Z negate. Applies regardless of
     *                whether the values came from a {@code *_lefthand} slot or
     *                the right-hand fallback.
     */
    private static Map<String, Object> buildHoldAnimation(
        JavaModelDisplay.Transform transform,
        boolean firstPerson,
        boolean offHand,
        AttachableGenerationConfig.BasePose firstPersonPose
    ) {
        // Java's left-hand rule is one operation on one transform: rotation Y
        // and Z negate AND translation X negates. Both halves, or neither —
        // applying only the rotation half leaves the translation describing a
        // pose the rotation no longer matches.
        float[] javaRotation = offHand
            ? BedrockGeometryConverter.applyJavaLeftHandRotation(transform.rotation())
            : transform.rotation();
        float[] javaTranslation = offHand
            ? BedrockGeometryConverter.applyJavaLeftHandTranslation(transform.translation())
            : transform.translation();

        float[] rotation = BedrockGeometryConverter.convertRotation(javaRotation);
        // The root rotation has to be known before the translation is mapped:
        // the offset is expressed in the root's local frame, and the off hand's
        // root is mirrored (see below).
        float handSign = offHand ? -1f : 1f;
        float[] emittedRootRotation = firstPerson
            ? new float[]{firstPersonPose.rotation()[0],
                handSign * firstPersonPose.rotation()[1],
                handSign * firstPersonPose.rotation()[2]}
            : new float[]{THIRD_PERSON_BASE_ROTATION.get(0),
                THIRD_PERSON_BASE_ROTATION.get(1),
                THIRD_PERSON_BASE_ROTATION.get(2)};
        float[] position = BedrockGeometryConverter.convertTranslationInRootFrame(
            javaTranslation, emittedRootRotation, MIRROR_X, TRANSLATION_FRAME);
        float[] scale = BedrockGeometryConverter.convertScale(transform.scale());

        // Bedrock composes a child bone inside its parent's scale, so the
        // first-person root scale multiplies this translation as well as the
        // mesh. Java does not: ItemTransform#apply translates in the hand
        // frame and only then scales the model, so the offset is independent
        // of any size correction. GeyserMC's Rainbow mapper corroborates that
        // the frames are 1:1 in model units — it applies the Java display scale
        // directly with no root scale at all — which makes java2bedrock's
        // root scale a size correction for the mesh, not a change of units.
        // Dividing here cancels it back out for the offset only, so a weapon
        // with a large display translation (greataxe [0,4,-9], long spear
        // [-5.25,-7.25,-1]) lands where Java puts it instead of 1.5x further
        // out, which is what pushed those items out of the first-person view
        // and led to the translation being zeroed for every item.
        if (firstPerson) {
            float rootScale = firstPersonPose.scale();
            if (rootScale > 0f && rootScale != 1f) {
                // Rounded so the emitted JSON — and therefore the pack's
                // content hash — stays byte-stable across rebuilds.
                position = new float[]{
                    roundHundredths(position[0] / rootScale),
                    roundHundredths(position[1] / rootScale),
                    roundHundredths(position[2] / rootScale)
                };
            }
        }

        Map<String, Object> boneX = new LinkedHashMap<>();
        if (firstPerson && rotation[0] == 0f && rotation[1] == 0f && rotation[2] == 0f) {
            // java2bedrock quirk: a genuinely zero first-person rotation keeps
            // the animation channel inactive on some client versions; the
            // 0.1° epsilon forces it active without a visible pose change.
            boneX.put("rotation", List.of(0.1f, 0.1f, 0.1f));
        } else {
            boneX.put("rotation", List.of(rotation[0], 0f, 0f));
        }
        boneX.put("position", List.of(position[0], position[1], position[2]));
        boneX.put("scale", List.of(scale[0], scale[1], scale[2]));

        Map<String, Object> bones = new LinkedHashMap<>();
        bones.put(BONE_X, boneX);
        bones.put(BONE_Y, Map.of("rotation", List.of(0f, rotation[1], 0f)));
        bones.put(BONE_Z, Map.of("rotation", List.of(0f, 0f, rotation[2])));
        Map<String, Object> root = new LinkedHashMap<>();
        if (firstPerson) {
            // One base pose for every item, exactly as java2bedrock emits it.
            // The item-specific part of the first-person pose lives in the
            // Java display transform on the x/y/z bones — the previous
            // display-scale-dependent root nudges made the root item-specific
            // too, which double-counted the same information and could never
            // satisfy small and oversized weapons at the same time.
            float[] basePos = firstPersonPose.position();
            // The base pose maps Bedrock's first-person arm frame onto Java's
            // camera-space item frame. That mapping is handed, so the off hand
            // needs its mirror image — the same (x, -y, -z) rotation and -x
            // position rule Java applies to the transform itself.
            //
            // The third-person root gets away without this because its pose
            // ([90, 0, 0] / [0, 13, -3]) is already mirror-invariant: rotation
            // Y and Z and position X are all zero. The first-person pose
            // ([90, 60, -40] / [4, 10, 4]) is not, so feeding the right-arm
            // mapping to the left arm swings the item out of the viewport,
            // which is why third-person off hand rendered correctly while
            // first-person off hand showed nothing at all — for flat and 3D
            // items alike, since both use a non-symmetric first-person pose.
            root.put("rotation", List.of(
                emittedRootRotation[0], emittedRootRotation[1], emittedRootRotation[2]));
            root.put("position", List.of(
                handSign * basePos[0], basePos[1], basePos[2]));
            root.put("scale", firstPersonPose.scale());
        } else {
            root.put("rotation", THIRD_PERSON_BASE_ROTATION);
            root.put("position", THIRD_PERSON_BASE_POSITION);
        }
        bones.put(BONE_ROOT, root);

        Map<String, Object> animation = new LinkedHashMap<>();
        animation.put("loop", true);
        animation.put("bones", bones);
        return animation;
    }

    /**
     * First-person root scale used when sizing the geometry's visible bounds.
     * 3D meshes ride the (optionally operator-overridden) base pose; flat
     * texture-mesh items use their own fixed pose. Floored at 1 so the bounds
     * are never computed smaller than the mesh itself.
     */
    private static float rootScaleForBounds(
        AttachableGenerationConfig config, boolean useFullGeometry
    ) {
        float scale = useFullGeometry
            ? config.firstPersonBasePose().scale()
            : FLAT_FIRST_PERSON_POSE.scale();
        return Math.max(1f, scale);
    }

    private static float maxAxis(float[] xyz) {
        return Math.max(xyz[0], Math.max(xyz[1], xyz[2]));
    }

    private static float roundHundredths(float value) {
        return Math.round(value * 100f) / 100f;
    }

    /**
     * Third-person head-slot pose: Java renders head items at 62.5% scale.
     *
     * <p>The position is a plain X mirror here rather than the change of basis
     * the hand path runs
     * ({@link BedrockGeometryConverter#convertTranslationInRootFrame}), and that
     * is not an inconsistency between the two paths — it is the same rule with a
     * different root. The hand root declares a rotation ({@code [90, 0, 0]} in
     * third person), so the offset has to be re-expressed in that rotated frame;
     * the head root declares only a translation ({@link #HEAD_BASE_POSITION}),
     * so the change of basis collapses to the mirror alone. Substituting the
     * hand path's {@code M} here would put every hat below the head, because
     * {@code M} folds in the hand root's own 90° — see the note on that
     * method.</p>
     */
    private static Map<String, Object> buildHeadAnimation(
        JavaModelDisplay.Transform transform
    ) {
        float[] rotation = BedrockGeometryConverter.convertRotation(transform.rotation());
        float[] translation = transform.translation();
        float[] scale = BedrockGeometryConverter.convertScale(transform.scale());

        Map<String, Object> bones = new LinkedHashMap<>();
        Map<String, Object> boneX = new LinkedHashMap<>();
        boneX.put("rotation", List.of(rotation[0], 0f, 0f));
        boneX.put("position", List.of(
            -translation[0] * HEAD_SCALE,
            translation[1] * HEAD_SCALE,
            translation[2] * HEAD_SCALE));
        boneX.put("scale", List.of(
            scale[0] * HEAD_SCALE, scale[1] * HEAD_SCALE, scale[2] * HEAD_SCALE));
        bones.put(BONE_X, boneX);
        bones.put(BONE_Y, Map.of("rotation", List.of(0f, rotation[1], 0f)));
        bones.put(BONE_Z, Map.of("rotation", List.of(0f, 0f, rotation[2])));
        bones.put(BONE_ROOT, Map.of("position", HEAD_BASE_POSITION));

        Map<String, Object> animation = new LinkedHashMap<>();
        animation.put("loop", true);
        animation.put("bones", bones);
        return animation;
    }

    /** Hides the mesh (first-person head slot — the camera is inside the head). */
    private static Map<String, Object> buildDisableAnimation() {
        Map<String, Object> animation = new LinkedHashMap<>();
        animation.put("loop", true);
        animation.put("override_previous_animation", true);
        animation.put("bones", Map.of(BONE_ROOT, Map.of("scale", 0)));
        return animation;
    }

    /**
     * Tiny helper: builds a {@link LinkedHashMap} preserving declaration order
     * so the rendered JSON is stable from one build to the next. Required for
     * the patchVersion content-hash in {@code AutoBedrockPackBuilder} to remain
     * deterministic.
     */
    private static <V> Map<String, V> linkedMap(String k1, V v1, String k2, V v2) {
        Map<String, V> m = new LinkedHashMap<>();
        m.put(k1, v1);
        m.put(k2, v2);
        return m;
    }
}
