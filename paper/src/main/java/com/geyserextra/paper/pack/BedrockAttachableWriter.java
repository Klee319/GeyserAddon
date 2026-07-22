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
 * <p><b>Rendering scheme:</b> flat items (no Java {@code elements}) keep the
 * field-proven <a href="https://github.com/Kas-tle/java2bedrock.sh">java2bedrock</a>
 * four-bone chain {@code root → x → y → z} with {@code texture_meshes} on
 * {@code z}; the root binds to the player skeleton and receives a fixed
 * per-slot base pose while the Java display rotation is decomposed onto
 * {@code x}/{@code y}/{@code z}.</p>
 *
 * <p>3D models with elements use GeyserMC
 * <a href="https://github.com/GeyserMC/Rainbow">Rainbow</a>
 * ({@code GeometryMapper} + {@code AnimationMapper}) unless the operator sets
 * an explicit {@code firstPersonBasePose} (legacy escape hatch): a single
 * bone {@code geyserextra} binds to the skeleton, pivots at the model bounds
 * centre, holds the cubes, and receives both first- and third-person Rainbow
 * hold poses. No {@code x}/{@code y}/{@code z}/{@code geo} chain on that
 * path.</p>
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
     * Legacy leaf bone for the explicit-{@code firstPersonBasePose} escape hatch:
     * cubes live here (child of {@code z}) with a bounds-centre pivot while
     * hold animations still use the java2bedrock {@code x}/{@code y}/{@code z}
     * decomposition.
     */
    private static final String BONE_GEO = "geyserextra_geo";

    /**
     * Molang binding that attaches the root bone to whichever player bone
     * currently holds the item (main hand, off hand, or head). Verbatim from
     * java2bedrock — {@code q.item_slot_to_bone_name} does not handle the
     * head slot, hence the explicit ternary.
     */
    private static final String ROOT_BONE_BINDING =
        "c.item_slot == 'head' ? 'head' : q.item_slot_to_bone_name(c.item_slot)";

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
        return buildArtifacts(iconKey, display, geometry, textureRelativePath, 16, 16, config, null);
    }

    /**
     * Phase 6 entry point. Takes the actual PNG dimensions of the icon
     * texture so the geometry descriptor declares matching {@code texture_width}
     * and {@code texture_height}, and so per-face UVs scale correctly for
     * non-16x16 textures (the high-res operator-pack case).
     *
     * @param iconKey               sanitised Bedrock icon key (also the
     *                               {@code bedrock_identifier} suffix)
     * @param display                Java {@code display} block; may be {@code null}
     * @param geometry               Java {@code elements} block; may be {@code null}
     * @param textureRelativePath    existing {@code textures/items/<iconKey>} path
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
        if (config == null) {
            return Map.of();
        }
        String mode = config.mode();
        if (AttachableGenerationConfig.MODE_OFF.equals(mode)) {
            return Map.of();
        }
        // Mode is offsets_only or full. Both still require at least one
        // held-item transform — without it the attachable would just be a
        // duplicate of the vanilla in-hand rendering and not worth the bytes.
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
        boolean rainbowSingleBone = useFullGeometry
            && !config.hasExplicitFirstPersonBasePose();

        Map<String, String> out = new LinkedHashMap<>();
        out.put(attachableEntryPath(iconKey),
                JsonUtil.toPrettyJson(buildAttachableJson(iconKey, textureRelativePath, display, config)));
        out.put(geometryEntryPath(iconKey),
                JsonUtil.toPrettyJson(buildGeometryJson(
                    iconKey, useFullGeometry ? geometry : null, safeTw, safeTh,
                    rainbowSingleBone, logger)));
        out.put(animationEntryPath(iconKey),
                JsonUtil.toPrettyJson(buildAnimationJson(
                    iconKey, display, config, rainbowSingleBone)));
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
        if (iconKey == null || iconKey.isBlank()) {
            return Map.of();
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
            "default", "textures/entity/equipment/" + iconKey,
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
            attachableEntryPath(iconKey),
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
        boolean rainbowSingleBone,
        Logger logger
    ) {
        Map<String, Object> descriptor = new LinkedHashMap<>();
        descriptor.put("identifier", "geometry." + NAMESPACE + "." + iconKey);
        descriptor.put("texture_width", textureWidth);
        descriptor.put("texture_height", textureHeight);
        // Generous bounds (java2bedrock values) so oversized weapons are not
        // frustum-culled while swinging at the edge of the screen.
        descriptor.put("visible_bounds_width", 4);
        descriptor.put("visible_bounds_height", 4.5);
        descriptor.put("visible_bounds_offset", List.of(0, 0.75, 0));

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
        if (fullGeometry != null && fullGeometry.hasElements()) {
            List<Map<String, Object>> cubes = BedrockGeometryConverter.convertElementsToCubes(
                fullGeometry, textureWidth, textureHeight, logger);
            float[] pivot = BedrockGeometryConverter.computeBoundsCentrePivot(cubes);

            if (rainbowSingleBone) {
                // Rainbow GeometryMapper: one bound bone at bounds-centre pivot.
                root.put("pivot", List.of(pivot[0], pivot[1], pivot[2]));
                root.put("cubes", cubes);
                bones = List.of(root);
            } else {
                // Explicit firstPersonBasePose escape hatch: java2bedrock chain +
                // geo leaf at bounds centre (cubes only; hold anim uses x/y/z).
                Map<String, Object> boneGeo = new LinkedHashMap<>();
                boneGeo.put("name", BONE_GEO);
                boneGeo.put("parent", BONE_Z);
                boneGeo.put("pivot", List.of(pivot[0], pivot[1], pivot[2]));
                boneGeo.put("cubes", cubes);
                bones = List.of(root, boneX, boneY, boneZ, boneGeo);
            }
        } else {
            // No Java elements → the model is item/generated style. Bedrock's
            // texture_meshes extrudes the PNG into a voxel mesh exactly like
            // Java's own item renderer, which both looks correct from every
            // angle and keeps the base-pose constants valid (they were tuned
            // against this mesh orientation in java2bedrock).
            Map<String, Object> textureMesh = new LinkedHashMap<>();
            textureMesh.put("texture", "default");
            textureMesh.put("position", List.of(0, 8, 0));
            textureMesh.put("rotation", List.of(90, 0, -180));
            textureMesh.put("local_pivot", List.of(8, 0.5, 8));
            boneZ.put("texture_meshes", List.of(textureMesh));
            bones = List.of(root, boneX, boneY, boneZ);
        }

        Map<String, Object> geometry = new LinkedHashMap<>();
        geometry.put("description", descriptor);
        geometry.put("bones", bones);

        return linkedMap(
            "format_version", "1.16.0",
            "minecraft:geometry", List.of(geometry));
    }

    private static Map<String, Object> buildAnimationJson(
        String iconKey,
        JavaModelDisplay display,
        AttachableGenerationConfig config,
        boolean rainbowSingleBone
    ) {
        String animPrefix = "animation." + NAMESPACE + "." + iconKey + ".";
        JavaModelDisplay.Transform hand3rd = orIdentity(display.handTransformFor(false));
        JavaModelDisplay.Transform hand1st = orIdentity(display.handTransformFor(true));

        AttachableGenerationConfig.BasePose firstPersonPose = config.firstPersonBasePose();

        Map<String, Object> animations = new LinkedHashMap<>();
        if (!config.forceFirstPersonOnly()) {
            animations.put(animPrefix + "thirdperson_main_hand",
                buildHoldAnimation(hand3rd, false, false, firstPersonPose, rainbowSingleBone));
            // Java models rarely declare left-hand slots; vanilla mirrors the
            // right-hand transform, and the off-hand sign matrix in
            // convertTranslation performs exactly that mirror.
            animations.put(animPrefix + "thirdperson_off_hand",
                buildHoldAnimation(hand3rd, false, true, firstPersonPose, rainbowSingleBone));
        }
        animations.put(animPrefix + "firstperson_main_hand",
            buildHoldAnimation(hand1st, true, false, firstPersonPose, rainbowSingleBone));
        animations.put(animPrefix + "firstperson_off_hand",
            buildHoldAnimation(hand1st, true, true, firstPersonPose, rainbowSingleBone));
        if (display.head() != null) {
            animations.put(animPrefix + "head",
                buildHeadAnimation(display.head(), rainbowSingleBone));
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
     * Builds one held-item animation: the root bone gets the fixed per-slot
     * base pose, the x bone gets X rotation + translation + scale, and the
     * y/z bones get their single rotation axis (java2bedrock decomposition).
     *
     * <p><b>3D Rainbow path</b> ({@code rainbowSingleBone}): GeyserMC
     * {@code AnimationMapper} axis-permutation on {@link #BONE_ROOT} (the
     * same bone that binds to the skeleton and holds the cubes). Translations
     * are already in Bedrock pixel units in this codebase.</p>
     * <pre>
     *   First person:
     *     rotation = (-90 + javaRot.y, -javaRot.z, javaRot.x)
     *     position = (-javaTrans.y, 12.5 + javaTrans.z, javaTrans.x)
     *   Third person:
     *     rotation = (90, -javaRot.z, -javaRot.y)
     *     position = (-javaTrans.x, 12.5 + javaTrans.z, -javaTrans.y)
     *   scale = javaScale (per axis, unchanged) for both slots
     * </pre>
     * <p>Bedrock cannot address left/right hands separately in first person
     * (Rainbow limitation), so off-hand animations reuse the right-hand
     * values verbatim on this path (third-person off-hand included).</p>
     */
    private static Map<String, Object> buildHoldAnimation(
        JavaModelDisplay.Transform transform,
        boolean firstPerson,
        boolean offHand,
        AttachableGenerationConfig.BasePose firstPersonPose,
        boolean rainbowSingleBone
    ) {
        if (rainbowSingleBone) {
            float[] jr = transform.rotation();
            float[] jt = transform.translation();
            float[] js = transform.scale();

            Map<String, Object> rootBone = new LinkedHashMap<>();
            if (firstPerson) {
                rootBone.put("rotation", List.of(-90f + jr[1], -jr[2], jr[0]));
                rootBone.put("position", List.of(-jt[1], 12.5f + jt[2], jt[0]));
            } else {
                rootBone.put("rotation", List.of(90f, -jr[2], -jr[1]));
                rootBone.put("position", List.of(-jt[0], 12.5f + jt[2], -jt[1]));
            }
            rootBone.put("scale", List.of(js[0], js[1], js[2]));

            Map<String, Object> animation = new LinkedHashMap<>();
            animation.put("loop", true);
            animation.put("bones", Map.of(BONE_ROOT, rootBone));
            return animation;
        }

        float[] rotation = BedrockGeometryConverter.convertRotation(transform.rotation());
        float[] position = BedrockGeometryConverter.convertTranslation(
            transform.translation(), firstPerson, offHand);
        float[] scale = BedrockGeometryConverter.convertScale(transform.scale());

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
            float[] baseRot = firstPersonPose.rotation();
            float[] basePos = firstPersonPose.position();
            root.put("rotation", List.of(baseRot[0], baseRot[1], baseRot[2]));
            root.put("position", List.of(basePos[0], basePos[1], basePos[2]));
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

    /** Third-person head-slot pose: Java renders head items at 62.5% scale. */
    private static Map<String, Object> buildHeadAnimation(
        JavaModelDisplay.Transform transform,
        boolean rainbowSingleBone
    ) {
        float[] rotation = BedrockGeometryConverter.convertRotation(transform.rotation());
        float[] translation = transform.translation();
        float[] scale = BedrockGeometryConverter.convertScale(transform.scale());

        Map<String, Object> bones = new LinkedHashMap<>();
        if (rainbowSingleBone) {
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("rotation", List.of(rotation[0], rotation[1], rotation[2]));
            root.put("position", List.of(
                HEAD_BASE_POSITION.get(0) - translation[0] * HEAD_SCALE,
                HEAD_BASE_POSITION.get(1) + translation[1] * HEAD_SCALE,
                HEAD_BASE_POSITION.get(2) + translation[2] * HEAD_SCALE));
            root.put("scale", List.of(
                scale[0] * HEAD_SCALE, scale[1] * HEAD_SCALE, scale[2] * HEAD_SCALE));
            bones.put(BONE_ROOT, root);
        } else {
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
        }

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
