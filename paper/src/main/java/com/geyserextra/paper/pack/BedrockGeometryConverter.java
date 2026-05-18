package com.geyserextra.paper.pack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Pure functions that convert Java edition transform / geometry values into
 * the matching Bedrock edition representation.
 *
 * <p>This class is the single source of truth for axis-sign conventions
 * between the two engines. Java models use a right-handed coordinate system;
 * Bedrock entity geometry uses a left-handed one. The constants below encode
 * the empirically-determined axis flips so that "this is how to flip" lives
 * in exactly one place — when implementation revealed a sign was wrong, only
 * this file needs editing.</p>
 *
 * <p><b>Phase 3:</b> display-transform conversion (used by animations).</p>
 * <p><b>Phase 4:</b> {@link #convertElementsToCubes} converts Java
 * {@code elements} into Bedrock entity cubes for full 3D held-item geometry.</p>
 */
public final class BedrockGeometryConverter {

    /**
     * Java rotation in (x, y, z) degrees with right-handed convention →
     * Bedrock animation bone rotation in degrees, left-handed convention.
     * Empirically Bedrock's animation framework matches Java's pose when
     * Y and Z degrees are negated; X stays as-is.
     */
    private static final float ROT_X_SIGN = +1f;
    private static final float ROT_Y_SIGN = -1f;
    private static final float ROT_Z_SIGN = -1f;

    /**
     * Java translation in pixels (Y-up) → Bedrock animation bone position
     * in pixels (Y-up, Z reversed). Negating Z aligns the in-hand depth
     * direction between the two engines.
     */
    private static final float TRANS_X_SIGN = +1f;
    private static final float TRANS_Y_SIGN = +1f;
    private static final float TRANS_Z_SIGN = -1f;

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
     * Converts a Java {@code display.*.translation} array into Bedrock animation
     * bone {@code position} (pixel units in both systems). Returns a fresh array.
     */
    public static float[] convertTranslation(float[] javaTranslation) {
        if (javaTranslation == null || javaTranslation.length < 3) {
            return new float[]{0f, 0f, 0f};
        }
        return new float[]{
            TRANS_X_SIGN * javaTranslation[0],
            TRANS_Y_SIGN * javaTranslation[1],
            TRANS_Z_SIGN * javaTranslation[2]
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
    public static List<Map<String, Object>> convertElementsToCubes(JavaModelGeometry geometry) {
        if (geometry == null || !geometry.hasElements()) {
            return List.of();
        }
        List<Map<String, Object>> cubes = new ArrayList<>(geometry.elements().size());
        for (JavaModelGeometry.Element element : geometry.elements()) {
            if (element == null) continue;
            cubes.add(convertSingleElement(element));
        }
        return cubes;
    }

    private static Map<String, Object> convertSingleElement(JavaModelGeometry.Element element) {
        float[] from = element.from();
        float[] to = element.to();

        // Java cube corner (negative-Z) maps to Bedrock origin (negative-Z).
        // The Z reverse — taking (java.from.z - 8) rather than (8 - java.to.z)
        // — keeps the cube on the same side of the held-item bone that Java's
        // own renderer puts it on. We verified this empirically against
        // ValhallaMMO weapons; if a future model shows the cube on the wrong
        // side, only this offset and convertElementRotation below need touching.
        float originX = from[0] - 8f;
        float originY = from[1];
        float originZ = from[2] - 8f;

        // Sizes can theoretically be negative when an artist swaps from/to;
        // clamp to non-negative because Bedrock cubes reject negative dims.
        float sizeX = Math.max(0f, to[0] - from[0]);
        float sizeY = Math.max(0f, to[1] - from[1]);
        float sizeZ = Math.max(0f, to[2] - from[2]);

        Map<String, Object> cube = new LinkedHashMap<>();
        cube.put("origin", List.of(originX, originY, originZ));
        cube.put("size", List.of(sizeX, sizeY, sizeZ));
        cube.put("uv", List.of(0, 0));

        JavaModelGeometry.ElementRotation rot = element.rotation();
        if (rot != null) {
            float[] rotation = convertElementRotation(rot);
            float[] pivot = convertElementPivot(rot.origin());
            cube.put("rotation", List.of(rotation[0], rotation[1], rotation[2]));
            cube.put("pivot", List.of(pivot[0], pivot[1], pivot[2]));
        }
        return cube;
    }

    private static float[] convertElementRotation(JavaModelGeometry.ElementRotation rotation) {
        String axis = rotation.axis() != null ? rotation.axis().toLowerCase(Locale.ROOT) : "y";
        float angle = rotation.angle();
        return switch (axis) {
            case "x" -> new float[]{ROT_X_SIGN * angle, 0f, 0f};
            case "z" -> new float[]{0f, 0f, ROT_Z_SIGN * angle};
            default  -> new float[]{0f, ROT_Y_SIGN * angle, 0f}; // "y" or unknown axis falls back to Y
        };
    }

    private static float[] convertElementPivot(float[] javaOrigin) {
        if (javaOrigin == null || javaOrigin.length < 3) {
            return new float[]{0f, 0f, 0f};
        }
        return new float[]{
            javaOrigin[0] - 8f,
            javaOrigin[1],
            javaOrigin[2] - 8f
        };
    }
}
