package com.geyserextra.paper.pack;

import java.util.Objects;

/**
 * Immutable value object capturing the {@code display} block of a Java edition
 * item model JSON.
 *
 * <p>Each slot ({@link #firstpersonRighthand()}, {@link #thirdpersonRighthand()},
 * {@link #gui()}, {@link #ground()}, {@link #head()}) is independently nullable:
 * a Java model only declares the slots it overrides; the rest inherit from the
 * parent model and ultimately from Minecraft's built-in {@code item/handheld} or
 * {@code item/generated}. Built-in vanilla defaults are not present in operator
 * packs, so a slot that resolves to {@code null} at the end of the parent chain
 * remains {@code null} here.</p>
 *
 * <p>Used by {@link BedrockAttachableWriter} to populate the {@code hold_first_person}
 * and {@code hold_third_person} animation channels of the generated attachable.</p>
 */
public record JavaModelDisplay(
    Transform firstpersonRighthand,
    Transform thirdpersonRighthand,
    Transform gui,
    Transform ground,
    Transform head
) {

    /**
     * Returns {@code true} when at least one held-item slot
     * (first-person right hand or third-person right hand) is non-null.
     * Used as the eligibility gate for writing an attachable: items
     * without any held-item transform fall through to vanilla rendering
     * rather than producing an attachable that would just duplicate the
     * vanilla in-hand pose.
     */
    public boolean hasAnyHandTransform() {
        return firstpersonRighthand != null || thirdpersonRighthand != null;
    }

    /**
     * Returns the most-specific available right-hand transform for the
     * given perspective, falling back to the other hand slot when the
     * requested one is missing so the attachable always renders something
     * even on a partially-defined display block.
     */
    public Transform handTransformFor(boolean firstPerson) {
        if (firstPerson) {
            return firstpersonRighthand != null ? firstpersonRighthand : thirdpersonRighthand;
        }
        return thirdpersonRighthand != null ? thirdpersonRighthand : firstpersonRighthand;
    }

    /**
     * Mojang-style display transform.
     *
     * <p>All three components default to identity ({@code [0,0,0]} for rotation,
     * {@code [0,0,0]} for translation, {@code [1,1,1]} for scale) when absent
     * from the JSON; the constructor enforces array length 3 so downstream
     * conversion code can index without bounds checks.</p>
     *
     * @param rotation    degrees around X, Y, Z axes (Java's right-handed system)
     * @param translation pixel offsets along X, Y, Z (1 pixel = 1/16 block)
     * @param scale       dimensionless multipliers along X, Y, Z
     */
    public record Transform(float[] rotation, float[] translation, float[] scale) {
        public Transform {
            Objects.requireNonNull(rotation, "rotation must not be null");
            Objects.requireNonNull(translation, "translation must not be null");
            Objects.requireNonNull(scale, "scale must not be null");
            if (rotation.length != 3 || translation.length != 3 || scale.length != 3) {
                throw new IllegalArgumentException(
                    "rotation/translation/scale must each have length 3");
            }
        }

        /** Identity transform: zero rotation/translation, unit scale. */
        public static Transform identity() {
            return new Transform(
                new float[]{0f, 0f, 0f},
                new float[]{0f, 0f, 0f},
                new float[]{1f, 1f, 1f});
        }
    }
}
