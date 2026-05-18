package com.geyserextra.paper.pack;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable value object capturing the {@code elements} array of a Java item
 * model JSON. Used by {@link BedrockGeometryConverter#convertElementsToCubes}
 * to render a 3D held-item appearance when the operator's model defines real
 * Blockbench geometry rather than the default 2D layered icon.
 *
 * <p>Phase 4: per-element {@code from} / {@code to} / {@code rotation}.</p>
 * <p>Phase 6: per-face UV via the {@link Face} record on each element.
 * When at least one face declares a UV region, the Bedrock converter emits
 * the per-face UV form (geometry 1.16.0+) so each cube face samples the
 * correct portion of the source texture, matching Java's rendering.</p>
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
     *
     * @param faces  per-face UV/texture metadata keyed by Java face name
     *               ({@code "north"}/{@code "south"}/{@code "east"}/{@code "west"}/
     *               {@code "up"}/{@code "down"}). Empty map means "no per-face
     *               UV declared" — the Bedrock converter falls back to the
     *               simple {@code uv: [0, 0]} cube-level form to avoid emitting
     *               an incomplete per-face structure.
     */
    public record Element(
        float[] from,
        float[] to,
        ElementRotation rotation,
        Map<String, Face> faces
    ) {
        public Element {
            Objects.requireNonNull(from, "from must not be null");
            Objects.requireNonNull(to, "to must not be null");
            Objects.requireNonNull(faces, "faces must not be null");
            if (from.length != 3 || to.length != 3) {
                throw new IllegalArgumentException("from/to must each have length 3");
            }
            faces = Map.copyOf(faces);
        }

        /**
         * Backward-compatible 3-arg constructor (pre-Phase-6 shape).
         * Delegates with {@code faces=Map.of()} so existing call sites
         * continue to compile and produce the simple-UV cube form.
         */
        public Element(float[] from, float[] to, ElementRotation rotation) {
            this(from, to, rotation, Map.of());
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

    /**
     * Per-face UV/texture/rotation data extracted from a Java item model.
     *
     * @param uv        {@code [u1, v1, u2, v2]} in Java's 0..16 abstract UV space,
     *                  where (u1, v1) is the top-left and (u2, v2) is the
     *                  bottom-right of the texture region. May be {@code null}
     *                  when the operator omitted the field — the Bedrock
     *                  converter defaults to {@code [0, 0, 16, 16]} (whole
     *                  texture) which matches Mojang's runtime default.
     * @param texture   Texture variable reference like {@code "#layer0"} or
     *                  {@code "#main"}. May be {@code null}; not currently
     *                  consumed by the Bedrock converter (single-texture
     *                  attachables only, see {@link BedrockAttachableWriter}
     *                  javadoc), but retained for future multi-texture support.
     * @param rotation  Texture rotation in degrees: {@code 0}, {@code 90},
     *                  {@code 180}, or {@code 270}. Bedrock per-face UV has
     *                  no native rotation field, so non-zero values are logged
     *                  as a known limitation at conversion time and the
     *                  texture appears unrotated on that face.
     */
    public record Face(float[] uv, String texture, int rotation) {
        public Face {
            if (uv != null && uv.length != 4) {
                throw new IllegalArgumentException("uv must have length 4 when present");
            }
            if (rotation != 0 && rotation != 90 && rotation != 180 && rotation != 270) {
                throw new IllegalArgumentException(
                    "rotation must be 0, 90, 180, or 270 (got " + rotation + ")");
            }
        }

        /** True when an explicit UV region is set; false uses {@code [0, 0, 16, 16]} default. */
        public boolean hasUv() {
            return uv != null;
        }
    }
}
