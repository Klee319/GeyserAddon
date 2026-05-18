package com.geyserextra.paper.pack;

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
 * <p><b>Phase 3 (this release):</b> only display-transform conversion is
 * implemented. Element-cube conversion for full 3D geometry will land in
 * Phase 4 once the transform path is verified against real models.</p>
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
}
