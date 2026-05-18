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
 * <p><b>Phase 3 (this release):</b> always writes a flat-quad geometry
 * (single 16x16 quad facing the camera) and uses the Java {@code display}
 * transform on the {@code rightitem} bone's animation channels. Phase 4
 * will replace the flat quad with Java {@code elements}-derived cubes
 * when {@code mode == full} and the model has elements.</p>
 */
public final class BedrockAttachableWriter {

    /** Bedrock pack-internal namespace for our generated artifacts. */
    private static final String NAMESPACE = "geyserextra";

    /** Sub-folder under each artifact category — keeps generated files out of operator-authored content. */
    private static final String FOLDER = "geyserextra_auto";

    /** Bone name that the standard {@code controller.render.item_default} drives. */
    private static final String HELD_BONE = "rightitem";

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

        // full mode + non-empty elements → emit real 3D cubes with per-face UV.
        // offsets_only or empty elements → fall back to the flat-quad geometry.
        boolean useFullGeometry = AttachableGenerationConfig.MODE_FULL.equals(mode)
            && geometry != null
            && geometry.hasElements();

        Map<String, String> out = new LinkedHashMap<>();
        out.put(attachableEntryPath(iconKey),
                JsonUtil.toPrettyJson(buildAttachableJson(iconKey, textureRelativePath, config)));
        out.put(geometryEntryPath(iconKey),
                JsonUtil.toPrettyJson(buildGeometryJson(
                    iconKey, useFullGeometry ? geometry : null, safeTw, safeTh, logger)));
        out.put(animationEntryPath(iconKey),
                JsonUtil.toPrettyJson(buildAnimationJson(iconKey, display, config)));
        return out;
    }

    // ---------------------------------------------------------------------
    // Path helpers
    // ---------------------------------------------------------------------

    public static String attachableEntryPath(String iconKey) {
        return "attachables/" + FOLDER + "/" + iconKey + ".json";
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
        String iconKey, String textureRelativePath, AttachableGenerationConfig config
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

        Map<String, String> animations = new LinkedHashMap<>();
        animations.put("hold_first_person",
            "animation." + NAMESPACE + "." + iconKey + ".first_person");
        if (!config.forceFirstPersonOnly()) {
            animations.put("hold_third_person",
                "animation." + NAMESPACE + "." + iconKey + ".third_person");
        }
        description.put("animations", animations);

        List<Map<String, String>> scripts = new ArrayList<>();
        scripts.add(Map.of("hold_first_person", "context.is_first_person == 1.0"));
        if (!config.forceFirstPersonOnly()) {
            scripts.add(Map.of("hold_third_person", "context.is_first_person == 0.0"));
        }
        description.put("scripts", Map.of("animate", scripts));

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
        Logger logger
    ) {
        // Phase 3 baseline: a single 16x16 quad in the XY plane, hinged at
        // the bone pivot. Phase 4/6: when fullGeometry is non-null we use the
        // converter's element-derived cubes with per-face UV scaled to the
        // actual texture dimensions, giving Bedrock a Java-accurate 3D
        // representation of the model.
        Map<String, Object> descriptor = new LinkedHashMap<>();
        descriptor.put("identifier", "geometry." + NAMESPACE + "." + iconKey);
        descriptor.put("texture_width", textureWidth);
        descriptor.put("texture_height", textureHeight);
        descriptor.put("visible_bounds_width", 2);
        descriptor.put("visible_bounds_height", 2);
        descriptor.put("visible_bounds_offset", List.of(0, 0.5, 0));

        List<Map<String, Object>> cubes;
        if (fullGeometry != null && fullGeometry.hasElements()) {
            cubes = BedrockGeometryConverter.convertElementsToCubes(
                fullGeometry, textureWidth, textureHeight, logger);
        } else {
            // Flat-quad fallback (Phase 3 path). Use a single uv_size pair
            // matching the actual texture dims so non-16x16 PNGs are sampled
            // in full rather than cropped to the top-left 16x16 corner.
            Map<String, Object> flatQuad = new LinkedHashMap<>();
            flatQuad.put("origin", List.of(-8, 0, 0));
            flatQuad.put("size", List.of(16, 16, 0));
            if (textureWidth == 16 && textureHeight == 16) {
                // Keep the simple form for the vanilla case so the generated
                // JSON stays byte-identical to pre-Phase-6 for 16x16 icons.
                flatQuad.put("uv", List.of(0, 0));
            } else {
                // Per-face UV form lets us pin the sample region to the full
                // texture extent regardless of PNG resolution.
                Map<String, Object> faceUv = new LinkedHashMap<>();
                faceUv.put("uv", List.of(0, 0));
                faceUv.put("uv_size", List.of(textureWidth, textureHeight));
                // The flat quad only has one visible face (north). Set the
                // other faces to omit (Bedrock simply doesn't render them).
                Map<String, Object> uvMap = new LinkedHashMap<>();
                uvMap.put("north", faceUv);
                flatQuad.put("uv", uvMap);
            }
            cubes = List.of(flatQuad);
        }

        Map<String, Object> bone = new LinkedHashMap<>();
        bone.put("name", HELD_BONE);
        bone.put("pivot", List.of(0, 0, 0));
        bone.put("cubes", cubes);

        Map<String, Object> geometry = new LinkedHashMap<>();
        geometry.put("description", descriptor);
        geometry.put("bones", List.of(bone));

        return linkedMap(
            "format_version", "1.16.0",
            "minecraft:geometry", List.of(geometry));
    }

    private static Map<String, Object> buildAnimationJson(
        String iconKey, JavaModelDisplay display, AttachableGenerationConfig config
    ) {
        Map<String, Object> animations = new LinkedHashMap<>();
        animations.put(
            "animation." + NAMESPACE + "." + iconKey + ".first_person",
            buildHoldAnimation(display.handTransformFor(true)));
        if (!config.forceFirstPersonOnly()) {
            animations.put(
                "animation." + NAMESPACE + "." + iconKey + ".third_person",
                buildHoldAnimation(display.handTransformFor(false)));
        }
        return linkedMap(
            "format_version", "1.8.0",
            "animations", animations);
    }

    private static Map<String, Object> buildHoldAnimation(JavaModelDisplay.Transform transform) {
        JavaModelDisplay.Transform t = transform != null
            ? transform
            : JavaModelDisplay.Transform.identity();

        float[] rotation = BedrockGeometryConverter.convertRotation(t.rotation());
        float[] position = BedrockGeometryConverter.convertTranslation(t.translation());
        float[] scale = BedrockGeometryConverter.convertScale(t.scale());

        Map<String, Object> bone = new LinkedHashMap<>();
        bone.put("rotation", List.of(rotation[0], rotation[1], rotation[2]));
        bone.put("position", List.of(position[0], position[1], position[2]));
        bone.put("scale", List.of(scale[0], scale[1], scale[2]));

        Map<String, Object> animation = new LinkedHashMap<>();
        animation.put("loop", "hold_on_last_frame");
        animation.put("bones", Map.of(HELD_BONE, bone));
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
