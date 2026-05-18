package com.geyserextra.paper.pack;

import java.util.List;
import java.util.Objects;

/**
 * Immutable value object capturing the {@code elements} array of a Java item
 * model JSON. Used by {@link BedrockGeometryConverter#convertElementsToCubes}
 * to render a 3D held-item appearance when the operator's model defines real
 * Blockbench geometry rather than the default 2D layered icon.
 *
 * <p>Phase 4 scope: per-element {@code from} / {@code to} / {@code rotation}.
 * Per-face UV data is read but ignored at convert time — Bedrock's attachable
 * cube format accepts a single per-cube {@code uv} pair, so we trade fidelity
 * for predictability. Future phases may swap in the per-face UV form.</p>
 */
public record JavaModelGeometry(List<Element> elements) {

    public JavaModelGeometry {
        Objects.requireNonNull(elements, "elements must not be null");
        elements = List.copyOf(elements);
    }

    /** True when at least one element is present. */
    public boolean hasElements() {
        return !elements.isEmpty();
    }

    /**
     * One Blockbench cube. {@code from}/{@code to} are in Java's 0..16 model
     * coordinates; rotation may be {@code null} when the cube is axis-aligned.
     */
    public record Element(
        float[] from,
        float[] to,
        ElementRotation rotation
    ) {
        public Element {
            Objects.requireNonNull(from, "from must not be null");
            Objects.requireNonNull(to, "to must not be null");
            if (from.length != 3 || to.length != 3) {
                throw new IllegalArgumentException("from/to must each have length 3");
            }
        }
    }

    /**
     * Mojang-style per-element rotation. Angle is restricted to
     * {@code -45 / -22.5 / 0 / 22.5 / 45} in vanilla but operators
     * occasionally exceed those bounds via Blockbench plugins; we don't
     * enforce the limit because Bedrock's cube rotation field accepts any
     * float.
     *
     * @param origin pivot point in Java's 0..16 model coordinates
     * @param axis   {@code "x"}, {@code "y"}, or {@code "z"} (lowercased)
     * @param angle  rotation in degrees around the named axis
     */
    public record ElementRotation(float[] origin, String axis, float angle) {
        public ElementRotation {
            Objects.requireNonNull(origin, "origin must not be null");
            Objects.requireNonNull(axis, "axis must not be null");
            if (origin.length != 3) {
                throw new IllegalArgumentException("origin must have length 3");
            }
        }
    }
}
