package com.geyserextra.paper.pack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Pure functions that convert Java edition transform / geometry values into
 * the matching Bedrock edition representation.
 *
 * <p>This class is the single source of truth for axis-sign conventions
 * between the two engines. The conventions are ported verbatim from the
 * field-proven <a href="https://github.com/Kas-tle/java2bedrock.sh">java2bedrock</a>
 * converter (the standard Geyser companion tool): Bedrock's attachable frame
 * mirrors Java's X axis, so geometry is X-mirrored ({@code origin.x = 8 - to.x})
 * and display rotations flip sign on X and Y while Z stays as-is.</p>
 *
 * <p><b>Phase 3:</b> display-transform conversion (used by animations).</p>
 * <p><b>Phase 4:</b> {@link #convertElementsToCubes} converts Java
 * {@code elements} into Bedrock entity cubes for full 3D held-item geometry.</p>
 */
public final class BedrockGeometryConverter {

    /**
     * Java display rotation (x, y, z degrees) → Bedrock animation bone
     * rotation. java2bedrock convention: X and Y negate, Z keeps its sign
     * (the X-mirrored geometry flips the apparent handedness of the X and Y
     * axes but not Z).
     */
    private static final float ROT_X_SIGN = -1f;
    private static final float ROT_Y_SIGN = -1f;
    private static final float ROT_Z_SIGN = +1f;

    private BedrockGeometryConverter() {}

    /**
     * Converts a Java {@code display.*.rotation} array into Bedrock animation
     * bone {@code rotation} degrees. Returns a fresh array — callers can
     * mutate without affecting the input.
     */
    public static float[] convertRotation(float[] javaRotation) {
        if (javaRotation == null || javaRotation.length < 3) {
            return new float[]{0f, 0f, 0f};
        }
        return new float[]{
            ROT_X_SIGN * javaRotation[0],
            ROT_Y_SIGN * javaRotation[1],
            ROT_Z_SIGN * javaRotation[2]
        };
    }

    /**
     * Converts a Java {@code display.*.translation} array into Bedrock
     * animation bone {@code position} (pixel units in both systems).
     *
     * <p>java2bedrock sign matrix — X negates for the main hand (mirrored
     * frame) but not for the off hand (Java itself mirrors left-hand
     * rendering, so the two mirrors cancel); Z negates only in first person
     * where Bedrock's camera-space depth axis points the other way:</p>
     * <ul>
     *   <li>third-person main hand: {@code (-x, y, z)}</li>
     *   <li>third-person off hand: {@code (x, y, z)}</li>
     *   <li>first-person main hand: {@code (-x, y, -z)}</li>
     *   <li>first-person off hand: {@code (x, y, -z)}</li>
     * </ul>
     */
    public static float[] convertTranslation(
        float[] javaTranslation, boolean firstPerson, boolean offHand
    ) {
        if (javaTranslation == null || javaTranslation.length < 3) {
            return new float[]{0f, 0f, 0f};
        }
        float xSign = offHand ? +1f : -1f;
        float zSign = firstPerson ? -1f : +1f;
        return new float[]{
            xSign * javaTranslation[0],
            javaTranslation[1],
            zSign * javaTranslation[2]
        };
    }

    /**
     * Converts a Java {@code display.*.scale} array into Bedrock animation
     * bone {@code scale}. Scale is dimensionless and uses the same convention
     * in both engines, so this is a defensive copy with bounds normalisation.
     */
    public static float[] convertScale(float[] javaScale) {
        if (javaScale == null || javaScale.length < 3) {
            return new float[]{1f, 1f, 1f};
        }
        return new float[]{javaScale[0], javaScale[1], javaScale[2]};
    }

    // =========================================================================
    // Phase 4: element-cube conversion
    // =========================================================================

    /**
     * Converts a Java item model {@code elements} array into Bedrock entity
     * geometry cubes. Empty input → empty output, so callers can plug the
     * result straight into the {@code bones[0].cubes} array of a Bedrock
     * geometry JSON without further guards.
     *
     * <p>Coordinate mapping (item models, 0..16 range):</p>
     * <ul>
     *   <li>Bedrock cube origin (lower-X / lower-Y / lower-Z corner):
     *       {@code (java.from.x - 8, java.from.y, java.from.z - 8)}.
     *       The {@code -8} on X centres the model around the bone pivot;
     *       Z is shifted by {@code -8} so the cube sits in the same depth
     *       slice as Bedrock's standard held-item rendering.</li>
     *   <li>Bedrock cube size: {@code java.to - java.from} per axis; sizes
     *       are clamped to a non-negative minimum so a degenerate cube
     *       doesn't crash the Bedrock renderer.</li>
     *   <li>Element rotation: {@code axis} → matching slot in Bedrock's
     *       three-component rotation; Y and Z are sign-flipped to match
     *       the same handedness rule used by display transforms above.
     *       Pivot maps similarly to origin (X centred, Z shifted).</li>
     * </ul>
     *
     * <p>UV: this phase ignores per-face UV data. Each cube gets a single
     * {@code uv: [0, 0]} pair so the attachable texture is sampled from its
     * top-left corner; this matches the flat-quad fallback's behaviour for
     * items that lack elements and avoids the per-face UV format which has
     * inconsistent support across Bedrock client versions.</p>
     */
    /**
     * Phase 4 compatibility overload: produces cubes with the simple
     * {@code uv: [0, 0]} cube-level form, ignoring any per-face UV the
     * elements may carry. Used by call sites that don't yet route through
     * the texture-dimension-aware Phase 6 path.
     */
    public static List<Map<String, Object>> convertElementsToCubes(JavaModelGeometry geometry) {
        return convertElementsToCubes(geometry, 16, 16, null);
    }

    /**
     * Phase 6 entry point: produces cubes with per-face UV when the source
     * elements declare any face. UV coordinates from Java's 0..16 abstract
     * grid are scaled to Bedrock pixel space using {@code textureWidth} and
     * {@code textureHeight} (which must match the {@code texture_width} /
     * {@code texture_height} the geometry descriptor declares).
     *
     * <p>Empty input → empty output, so callers can plug the result straight
     * into the {@code bones[0].cubes} array of a Bedrock geometry JSON
     * without further guards.</p>
     *
     * <p>Coordinate mapping (item models, 0..16 range):</p>
     * <ul>
     *   <li>Bedrock cube origin (lower-X / lower-Y / lower-Z corner):
     *       {@code (java.from.x - 8, java.from.y, java.from.z - 8)}.
     *       The {@code -8} on X centres the model around the bone pivot;
     *       Z is shifted by {@code -8} so the cube sits in the same depth
     *       slice as Bedrock's standard held-item rendering.</li>
     *   <li>Bedrock cube size: {@code java.to - java.from} per axis;
     *       clamped non-negative.</li>
     *   <li>Element rotation: {@code axis} maps to matching Bedrock cube
     *       rotation slot; Y and Z signs are flipped to match the same
     *       handedness convention as display transforms.</li>
     *   <li>Per-face UV: {@code java.uv = [u1, v1, u2, v2]} (0..16 abstract)
     *       becomes Bedrock {@code uv: [u1·sx, v1·sy], uv_size: [(u2-u1)·sx, (v2-v1)·sy]}
     *       where {@code sx = textureWidth/16} and {@code sy = textureHeight/16}.
     *       Missing UV defaults to the whole 0..16 square (matches Mojang's
     *       runtime default for items).</li>
     * </ul>
     *
     * <p><b>Known limitations</b> (logged at FINE, not failure):</p>
     * <ul>
     *   <li>Java per-face texture rotation (0/90/180/270) has no native
     *       Bedrock equivalent — non-zero rotations produce a WARN-equivalent
     *       fine log and the texture appears unrotated on the affected face.</li>
     *   <li>Faces referencing different textures via {@code texture}
     *       ({@code #layer0} vs {@code #blade}) are all rendered with the
     *       attachable's single default texture. Multi-texture support
     *       requires Bedrock {@code material_instances} which is a separate
     *       phase. Per-face texture variable references are read but
     *       currently ignored at conversion time.</li>
     * </ul>
     */
    public static List<Map<String, Object>> convertElementsToCubes(
        JavaModelGeometry geometry,
        int textureWidth,
        int textureHeight,
        Logger logger
    ) {
        if (geometry == null || !geometry.hasElements()) {
            return List.of();
        }
        int tw = textureWidth > 0 ? textureWidth : 16;
        int th = textureHeight > 0 ? textureHeight : 16;
        List<Map<String, Object>> cubes = new ArrayList<>(geometry.elements().size());
        for (JavaModelGeometry.Element element : geometry.elements()) {
            if (element == null) continue;
            Map<String, Object> cube = convertSingleElement(element, tw, th, logger);
            // convertSingleElement returns null when the source element has no
            // usable faces — Mojang treats such elements as invisible, so
            // omitting the cube here is the only way to preserve that visual
            // semantic on the Bedrock side.
            if (cube != null) {
                cubes.add(cube);
            }
        }
        return cubes;
    }

    private static Map<String, Object> convertSingleElement(
        JavaModelGeometry.Element element,
        int textureWidth,
        int textureHeight,
        Logger logger
    ) {
        float[] from = element.from();
        float[] to = element.to();

        // Java's renderer accepts {@code from > to} on any axis and treats it
        // as a swap, so we normalise to (min, max) per axis here. Without the
        // normalisation, an artist mistake (or a model that intentionally
        // flips an axis) would clamp to a zero-size cube and render as an
        // invisible sliver. {@code Math.min} / {@code Math.max} preserves
        // Bedrock's "origin is the smallest-XYZ corner" invariant.
        float fx = Math.min(from[0], to[0]);
        float fy = Math.min(from[1], to[1]);
        float fz = Math.min(from[2], to[2]);
        float tx = Math.max(from[0], to[0]);
        float ty = Math.max(from[1], to[1]);
        float tz = Math.max(from[2], to[2]);

        // java2bedrock mapping: Bedrock's attachable frame mirrors Java's X
        // axis, so the cube origin (Bedrock's smallest-XYZ corner) takes the
        // mirrored LARGEST Java X corner: origin.x = -to.x + 8. Y is shared;
        // Z shifts by -8 to centre depth on the bone pivot.
        float originX = -tx + 8f;
        float originY = fy;
        float originZ = fz - 8f;

        float sizeX = tx - fx;
        float sizeY = ty - fy;
        float sizeZ = tz - fz;

        Map<String, JavaModelGeometry.Face> faces = element.faces();

        // Phase 6 (Codex-flagged correctness): Mojang requires a non-empty
        // {@code faces} object for a cube to be visible — an element with
        // {@code faces:{}} or no parseable face entries renders as invisible
        // in Java. Mirroring this, we omit the cube entirely (return null)
        // rather than falling back to a cube-level UV that would render all
        // six faces. The caller (convertElementsToCubes) filters nulls out
        // of the final list.
        if (faces == null || faces.isEmpty()) {
            if (logger != null) {
                logger.warning("[BedrockGeometry] element from " + java.util.Arrays.toString(from)
                    + " to " + java.util.Arrays.toString(to)
                    + " has no faces declared — skipping cube so it remains "
                    + "invisible (matches Java semantics). If you intended this "
                    + "cube to be visible, add a `faces` object to the Java model.");
            }
            return null;
        }

        Map<String, Object> faceUvs = buildPerFaceUvMap(faces, textureWidth, textureHeight, logger);
        if (faceUvs.isEmpty()) {
            // Every face was zero-area or otherwise unrenderable. Omit the
            // cube rather than promoting to cube-level UV — promoting would
            // make the cube fully visible and contradict the operator's
            // intent of having no usable faces.
            if (logger != null) {
                logger.warning("[BedrockGeometry] element from " + java.util.Arrays.toString(from)
                    + " to " + java.util.Arrays.toString(to)
                    + " produced no renderable Bedrock faces — skipping cube. "
                    + "Common causes: every UV had zero width or height, or "
                    + "the texture mapping was otherwise malformed.");
            }
            return null;
        }

        Map<String, Object> cube = new LinkedHashMap<>();
        cube.put("origin", List.of(originX, originY, originZ));
        cube.put("size", List.of(sizeX, sizeY, sizeZ));
        cube.put("uv", faceUvs);

        JavaModelGeometry.ElementRotation rot = element.rotation();
        if (rot != null) {
            float[] rotation = convertElementRotation(rot);
            float[] pivot = convertElementPivot(rot.origin());
            cube.put("rotation", List.of(rotation[0], rotation[1], rotation[2]));
            cube.put("pivot", List.of(pivot[0], pivot[1], pivot[2]));
        }
        return cube;
    }

    /**
     * Builds the {@code cube.uv} object in Bedrock geometry 1.16.0 per-face
     * form. Each entry has shape {@code {"uv": [u, v], "uv_size": [w, h]}}
     * in pixel space relative to the geometry descriptor's
     * {@code texture_width} / {@code texture_height}.
     *
     * <p>Faces missing from the input map are intentionally omitted from the
     * output — Bedrock interprets a missing face as "do not render this
     * face", which exactly matches Java's behaviour when a face is omitted
     * from the {@code faces} object.</p>
     */
    private static Map<String, Object> buildPerFaceUvMap(
        Map<String, JavaModelGeometry.Face> faces,
        int textureWidth,
        int textureHeight,
        Logger logger
    ) {
        Map<String, Object> out = new LinkedHashMap<>();
        // Iterate in stable order so the rendered JSON (and downstream
        // patchVersion hash) is deterministic.
        for (String faceName : List.of("north", "south", "east", "west", "up", "down")) {
            JavaModelGeometry.Face face = faces.get(faceName);
            if (face == null) {
                continue;
            }
            Map<String, Object> bedrockFace = convertFace(face, faceName, textureWidth, textureHeight, logger);
            if (bedrockFace != null) {
                out.put(faceName, bedrockFace);
            }
        }
        return out;
    }

    /**
     * Converts a single Java face into a Bedrock per-face UV entry.
     * Returns {@code null} when the result would be a zero-area UV region
     * (degenerate, would render as a single pixel artefact); callers omit
     * such faces so they simply don't render rather than producing visible
     * speckles.
     */
    private static Map<String, Object> convertFace(
        JavaModelGeometry.Face face,
        String faceName,
        int textureWidth,
        int textureHeight,
        Logger logger
    ) {
        // Phase 6 case C (hybrid rotation handling):
        //   - 0°   → standard uv/uv_size, identity mapping
        //   - 180° → uv=(u2,v2) with negative uv_size on both axes, which is
        //            point-symmetric to the unrotated form and visually
        //            indistinguishable from rotating the texture 180° in Java
        //   - 90°/270° → not expressible in Bedrock 1.16.0 per-face UV (would
        //            require swapping the texture's u/v axes relative to the
        //            face axes; Bedrock per-face uv has no rotation field).
        //            Render the face UNROTATED as the closest approximation —
        //            a hole in the model (the previous skip behaviour) is far
        //            more visible than a texture rotated 90° off, especially
        //            on thin weapon parts whose textures are near-uniform.
        //            Logged at FINE so it never spams the server console.
        int rotation = face.rotation();
        if (rotation != 0 && rotation != 180) {
            if (logger != null) {
                logger.fine("[BedrockGeometry] face " + faceName + " has Java rotation "
                    + rotation + "° which Bedrock 1.16.0 per-face UV cannot express "
                    + "(only 0°/180° supported via negative uv_size) — rendering the "
                    + "face with unrotated UV as an approximation. For an exact match, "
                    + "pre-rotate the texture region in the source PNG and set the "
                    + "model face rotation to 0.");
            }
            rotation = 0;
        }

        // Default UV when not specified: [0, 0, 16, 16] (whole texture).
        // This matches Mojang's runtime default for missing-UV faces on items.
        float u1, v1, u2, v2;
        if (face.hasUv()) {
            float[] uv = face.uv();
            u1 = uv[0];
            v1 = uv[1];
            u2 = uv[2];
            v2 = uv[3];
        } else {
            u1 = 0; v1 = 0; u2 = 16; v2 = 16;
        }

        // Scale Java's 0..16 abstract space to Bedrock pixel space.
        // For a 16x16 texture, scale = 1.0 (identity). For 32x32, scale = 2.0.
        float scaleX = textureWidth / 16f;
        float scaleY = textureHeight / 16f;

        // java2bedrock samples up/down faces point-mirrored (uv anchored at
        // the (u2,v2) corner with negative sizes): the X-mirrored geometry
        // flips how Bedrock orients top/bottom face UVs relative to Java.
        // A Java 180° face rotation is the same point-mirror, so the two
        // compose via XOR (180° on an up/down face cancels back to identity).
        boolean verticalFace = "up".equals(faceName) || "down".equals(faceName);
        boolean pointMirror = (rotation == 180) ^ verticalFace;

        float bedrockU;
        float bedrockV;
        float bedrockUSize;
        float bedrockVSize;
        if (pointMirror) {
            // Point-symmetric remap: the face's top-left corner samples from
            // what was the texture region's bottom-right corner, and uv_size
            // walks "backwards" via negative values. Bedrock honours negative
            // uv_size as a flip in that axis.
            bedrockU = u2 * scaleX;
            bedrockV = v2 * scaleY;
            bedrockUSize = -(u2 - u1) * scaleX;
            bedrockVSize = -(v2 - v1) * scaleY;
        } else {
            bedrockU = u1 * scaleX;
            bedrockV = v1 * scaleY;
            // uv_size may be negative when u1 > u2 or v1 > v2 — Bedrock
            // interprets that as a flipped texture on that face, matching
            // Java's u1>u2 / v1>v2 flipping semantics. We deliberately do
            // NOT clamp these to positive.
            bedrockUSize = (u2 - u1) * scaleX;
            bedrockVSize = (v2 - v1) * scaleY;
        }

        // Phase 6 (Codex-flagged): zero area on EITHER axis means the face
        // can't physically render — a UV strip of {@code N x 0} or {@code 0 x N}
        // is still degenerate. Catches both 0° and 180° degenerate cases
        // because 180°'s negative form preserves the absolute value.
        if (bedrockUSize == 0f || bedrockVSize == 0f) {
            return null;
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("uv", List.of(bedrockU, bedrockV));
        out.put("uv_size", List.of(bedrockUSize, bedrockVSize));
        return out;
    }

    private static float[] convertElementRotation(JavaModelGeometry.ElementRotation rotation) {
        String axis = rotation.axis() != null ? rotation.axis().toLowerCase(Locale.ROOT) : "y";
        float angle = rotation.angle();
        // Same sign matrix as display rotations (java2bedrock): X/Y negate
        // because the geometry frame is X-mirrored, Z keeps its sign.
        return switch (axis) {
            case "x" -> new float[]{ROT_X_SIGN * angle, 0f, 0f};
            case "z" -> new float[]{0f, 0f, ROT_Z_SIGN * angle};
            default  -> new float[]{0f, ROT_Y_SIGN * angle, 0f}; // "y" or unknown axis falls back to Y
        };
    }

    /**
     * Default bone pivot used when a model produces no renderable cubes.
     * Mirrors the historical java2bedrock fixed pivot so flat / degenerate
     * models keep their previous behaviour.
     */
    private static final float[] DEFAULT_PIVOT = {0f, 8f, 0f};

    /**
     * Computes the geometric centre of the axis-aligned bounding box that
     * encloses every converted cube — the pivot Java rotates a held-item
     * display transform around.
     *
     * <p>Ported from GeyserMC/Rainbow's {@code GeometryMapper}: the bone that
     * carries the cubes must pivot at {@code min + (max - min) / 2} (over the
     * cubes' {@code origin} and {@code origin + size}) so that display
     * rotations swing the model around its centre rather than a fixed point.
     * Using a fixed pivot makes an asymmetric model (e.g. a great-axe whose
     * bounds are not centred on the origin) rotate about the wrong location,
     * throwing off the first-person pose.</p>
     *
     * @param cubes converted Bedrock cubes (each with {@code origin} and
     *              {@code size} length-3 numeric lists); {@code null} / empty
     *              yields {@link #DEFAULT_PIVOT}
     * @return the bounds-centre pivot {@code [x, y, z]}
     */
    public static float[] computeBoundsCentrePivot(List<Map<String, Object>> cubes) {
        if (cubes == null || cubes.isEmpty()) {
            return DEFAULT_PIVOT.clone();
        }
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        boolean any = false;
        for (Map<String, Object> cube : cubes) {
            float[] origin = readFloat3(cube.get("origin"));
            float[] size = readFloat3(cube.get("size"));
            if (origin == null || size == null) {
                continue;
            }
            any = true;
            minX = Math.min(minX, origin[0]);
            minY = Math.min(minY, origin[1]);
            minZ = Math.min(minZ, origin[2]);
            maxX = Math.max(maxX, origin[0] + size[0]);
            maxY = Math.max(maxY, origin[1] + size[1]);
            maxZ = Math.max(maxZ, origin[2] + size[2]);
        }
        if (!any) {
            return DEFAULT_PIVOT.clone();
        }
        return new float[]{
            minX + (maxX - minX) / 2f,
            minY + (maxY - minY) / 2f,
            minZ + (maxZ - minZ) / 2f
        };
    }

    /**
     * Reads a length-3 numeric list (a cube {@code origin} / {@code size})
     * into a float array. Returns {@code null} when the value is missing or
     * not a 3-element numeric list so callers can skip malformed cubes.
     */
    private static float[] readFloat3(Object value) {
        if (!(value instanceof List<?> list) || list.size() < 3) {
            return null;
        }
        if (!(list.get(0) instanceof Number x)
            || !(list.get(1) instanceof Number y)
            || !(list.get(2) instanceof Number z)) {
            return null;
        }
        return new float[]{x.floatValue(), y.floatValue(), z.floatValue()};
    }

    private static float[] convertElementPivot(float[] javaOrigin) {
        if (javaOrigin == null || javaOrigin.length < 3) {
            return new float[]{0f, 0f, 0f};
        }
        // Pivot X mirrors like cube origins (java2bedrock: -origin.x + 8).
        return new float[]{
            -javaOrigin[0] + 8f,
            javaOrigin[1],
            javaOrigin[2] - 8f
        };
    }
}
